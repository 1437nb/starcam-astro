package com.starcam.astro.ui.camera

import android.graphics.Bitmap
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.starcam.astro.astro.ArSkyProjector
import com.starcam.astro.astro.CameraMath
import com.starcam.astro.astro.LayerFlags
import com.starcam.astro.astro.LayeredRenderer
import com.starcam.astro.astro.SkyEphemeris
import com.starcam.astro.astro.SolarSystemCatalog
import com.starcam.astro.astro.SolarSystemEphemeris
import com.starcam.astro.ui.I18n
import com.starcam.astro.ui.result.SkyObjectRef
import com.starcam.astro.ui.theme.AppThemeMode
import com.starcam.astro.ui.theme.LocaleState
import com.starcam.astro.ui.theme.ThemeState
import com.starcam.astro.util.DeviceOrientationTracker
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.tan
import kotlinx.coroutines.isActive

/** §0.54b：找星导航画笔（一次性创建，Canvas 逐帧复用，避免每帧新建 Paint） */
internal class NavPaints(
    val ring: Paint,
    val text: Paint,
    val arrow: Paint,
)

/**
 * §0.58：AR 太阳系天体列表的帧内缓存。
 * 逐帧只做一次引用比较，不构造任何新对象（严格保持 AR 叠加层零每帧分配）。
 */
internal class SolarFrameCache {
    var src: List<SolarSystemEphemeris.SolarPosition>? = null
    var list: List<SolarSystemEphemeris.SolarPosition> = emptyList()
}

/**
 * §0.54b / §0.58：AR 点击命中测试（显示矩形坐标）。太阳系天体优先，其次梅西耶，再次亮星。
 */
internal fun arHitTest(
    arSky: ArSkyProjector.ProjectedSky,
    rx: Float,
    ry: Float,
    messierTolPx: Float,
    starTolPx: Float,
): SkyObjectRef? {
    var best: SkyObjectRef? = null
    var solarD = messierTolPx * 1.6f
    for (s in arSky.solar) {
        val d = hypot((s.x - rx).toDouble(), (s.y - ry).toDouble()).toFloat()
        if (d < solarD) {
            solarD = d
            best = SkyObjectRef.SolarRef(s.pos)
        }
    }
    if (best != null) return best
    var bestD = messierTolPx
    for (m in arSky.messier) {
        val d = hypot((m.x - rx).toDouble(), (m.y - ry).toDouble()).toFloat()
        if (d < bestD) {
            bestD = d
            best = SkyObjectRef.MessierRef(m.obj)
        }
    }
    if (best != null) return best
    var starD = starTolPx
    for (s in arSky.stars) {
        if (s.entry.mag > 3.2) continue
        val d = hypot((s.x - rx).toDouble(), (s.y - ry).toDouble()).toFloat()
        if (d < starD) {
            starD = d
            best = SkyObjectRef.StarRef(s.entry)
        }
    }
    return best
}

/**
 * §0.49：AR 实时星图叠加层（~60fps 跟手重绘，所有 Paint 与数组在 remember 中一次性分配，零每帧分配）。
 */
@Composable
internal fun BoxScope.CameraArOverlay(
    orientationTracker: DeviceOrientationTracker,
    pointingState: DeviceOrientationTracker.DevicePointing,
    arSky: ArSkyProjector.ProjectedSky,
    arFovDeg: Float,
    arCalibrated: Boolean,
    arAzimuthOffsetDeg: AtomicReference<Double>,
    arTarget: ArTarget?,
    isPortrait: Boolean,
    topBannerVisible: Boolean,
) {
    val arPaints = remember { LayeredRenderer.RenderPaints() }
    val effRight = remember { FloatArray(3) }
    val effUp = remember { FloatArray(3) }
    val effAxis = remember { FloatArray(3) }
    val solarFrame = remember { SolarFrameCache() }
    val arTpos = remember { FloatArray(3) }
    val horizonSeg = remember { FloatArray(4) }
    var arTick by remember { mutableStateOf(0) }

    val navPaintSp = with(LocalDensity.current) { 1.sp.toPx() }
    val navPaints = remember(navPaintSp) {
        NavPaints(
            ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2.2f * navPaintSp
                color = 0xFF69F0AE.toInt()
            },
            text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF69F0AE.toInt()
                textSize = 12f * navPaintSp
                typeface = Typeface.DEFAULT_BOLD
            },
            arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 3f * navPaintSp
                strokeCap = Paint.Cap.ROUND
                color = 0xFF69F0AE.toInt()
            },
        )
    }
    val horizonPaint = remember(navPaintSp) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x66A7F3D0.toInt()
            strokeWidth = 1.5f * navPaintSp
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(10f * navPaintSp, 8f * navPaintSp), 0f)
        }
    }
    val cardinalDotPaint = remember(navPaintSp) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xBBA7F3D0.toInt()
            style = Paint.Style.FILL
        }
    }
    val cardinalMajorTextPaint = remember(navPaintSp) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xEEA7F3D0.toInt()
            textSize = 12f * navPaintSp
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(3f * navPaintSp, 1f, 1f, 0xFF000000.toInt())
        }
    }
    val cardinalMinorTextPaint = remember(navPaintSp) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x88A7F3D0.toInt()
            textSize = 9.5f * navPaintSp
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(2f * navPaintSp, 1f, 1f, 0xFF000000.toInt())
        }
    }

    LaunchedEffect(Unit) {
        while (isActive) {
            withFrameNanos { }
            arTick++
        }
    }

    val isEn = LocaleState.isEnglish
    val night = ThemeState.mode == AppThemeMode.NIGHT_RED
    val pxPerSp = with(LocalDensity.current) { 1.sp.toPx() }

    Canvas(modifier = Modifier.fillMaxSize()) {
        @Suppress("UNUSED_EXPRESSION")
        arTick
        val p = orientationTracker.predictedPointing()
        val canDraw = p.hasOrientation && p.latDeg != null && p.lonDeg != null
        if (canDraw) {
            val fitRect = CameraMath.computeFitCenterRect(
                size.width, size.height, isPortrait = isPortrait,
            )
            val rectL = fitRect.left
            val rectT = fitRect.top
            val rectW = fitRect.width
            val rectH = fitRect.height

            val lon = p.lonDeg ?: 0.0
            val lat = p.latDeg ?: 0.0
            val jd = SkyEphemeris.unixSecondsToJd((System.currentTimeMillis() / 1000).toDouble())
            val lst = SkyEphemeris.lstDeg(jd, lon)

            System.arraycopy(p.right, 0, effRight, 0, 3)
            System.arraycopy(p.up, 0, effUp, 0, 3)
            System.arraycopy(p.axis, 0, effAxis, 0, 3)
            val calOffset = arAzimuthOffsetDeg.get()
            if (arCalibrated && abs(calOffset) > 1e-4) {
                CameraMath.rotateBasisAroundUp(effRight, effUp, effAxis, calOffset)
            }

            val allSolar = SolarSystemEphemeris.cachedTopocentric(
                System.currentTimeMillis() / 1000, lat, lon,
            )
            if (solarFrame.src !== allSolar) {
                solarFrame.src = allSolar
                solarFrame.list = allSolar.filter {
                    it.body == SolarSystemEphemeris.SolarBody.SUN ||
                        it.body in SolarSystemCatalog.defaultAnnotated
                }
            }

            ArSkyProjector.project(
                right = effRight, up = effUp, axis = effAxis,
                lstDeg = lst, latDeg = lat,
                fovDeg = arFovDeg.toDouble(),
                widthPx = rectW, heightPx = rectH,
                correctionVec = null,
                out = arSky,
                solarPositions = solarFrame.list,
                isEnglish = isEn,
            )
            val nc = drawContext.canvas.nativeCanvas
            nc.save()
            nc.clipRect(rectL, rectT, rectL + rectW, rectT + rectH)
            nc.translate(rectL, rectT)
            LayeredRenderer.draw(
                nc,
                arSky.toOverlayScene(),
                LayerFlags.ALL,
                isEnglish = isEn,
                density = pxPerSp,
                night = night,
                drawWidth = rectW,
                drawHeight = rectH,
                dimBelowHorizon = true,
                degPerPx = (arFovDeg / rectW).toFloat(),
                paints = arPaints,
            )
            val f = (rectW / 2f) / tan(Math.toRadians(arFovDeg / 2.0)).toFloat()
            val halfW = rectW / 2f
            val halfH = rectH / 2f
            if (CameraMath.computeHorizonSegmentInto(
                    effRight[2], effUp[2], effAxis[2], f, rectW, rectH, horizonSeg,
                )
            ) {
                nc.drawLine(horizonSeg[0], horizonSeg[1], horizonSeg[2], horizonSeg[3], horizonPaint)
            }

            for (i in 0 until 8) {
                val azDeg = i * 45.0
                val isMajor = i % 2 == 0
                val rad = Math.toRadians(azDeg)
                val e = sin(rad).toFloat()
                val n = cos(rad).toFloat()
                val camX = e * effRight[0] + n * effRight[1]
                val camY = e * effUp[0] + n * effUp[1]
                val camZ = e * effAxis[0] + n * effAxis[1]
                if (camZ > 0.08f) {
                    val sx = halfW + f * camX / camZ
                    val sy = halfH - f * camY / camZ
                    if (sx in 10f..(rectW - 10f) && sy in 10f..(rectH - 10f)) {
                        nc.drawCircle(sx, sy, (if (isMajor) 2.5f else 1.8f) * pxPerSp, cardinalDotPaint)
                        val cLabel = I18n.Ar.cardinalLabel(i, isEn)
                        nc.drawText(
                            cLabel,
                            sx + 5f * pxPerSp,
                            sy - 5f * pxPerSp,
                            if (isMajor) cardinalMajorTextPaint else cardinalMinorTextPaint,
                        )
                    }
                }
            }
            arTarget?.let { target ->
                val label = target.label(isEn)
                val visible = ArSkyProjector.projectPoint(
                    target.raDeg, target.decDeg, effRight, effUp, effAxis,
                    lst, lat, arFovDeg.toDouble(), rectW, rectH, null,
                    arTpos,
                )
                if (visible) {
                    val sx = arTpos[0]
                    val sy = arTpos[1]
                    val onScreen = sx in -24f..rectW + 24f && sy in -24f..rectH + 24f
                    if (onScreen) {
                        val pulse = (arTick % 60) / 60f
                        navPaints.ring.strokeWidth = 2.2f * pxPerSp
                        nc.drawCircle(sx, sy, (16f + 4f * pulse) * pxPerSp, navPaints.ring)
                        navPaints.text.textSize = 12f * pxPerSp
                        nc.drawText(
                            "$label · ${I18n.Ar.found}",
                            sx + 14f * pxPerSp, sy - 10f * pxPerSp, navPaints.text,
                        )
                    } else {
                        val dx = sx - rectW / 2f
                        val dy = sy - rectH / 2f
                        val len = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                        if (len > 1f) {
                            val ux = dx / len
                            val uy = dy / len
                            val dist = 10f * pxPerSp
                            val ax = rectW / 2f + ux * dist
                            val ay = rectH / 2f + uy * dist
                            val arrowLen = 22f * pxPerSp
                            navPaints.arrow.strokeWidth = 3f * pxPerSp
                            nc.drawLine(ax, ay, ax + ux * arrowLen, ay + uy * arrowLen, navPaints.arrow)
                            val angDeg = Math.toDegrees(atan((len / f).toDouble()))
                            val note = I18n.Ar.offAngle.format(Locale.ROOT, angDeg)
                            navPaints.text.textSize = 12f * pxPerSp
                            nc.drawText(
                                label, ax + ux * 16f * pxPerSp - 40f * pxPerSp,
                                ay + uy * 16f * pxPerSp - 8f * pxPerSp, navPaints.text,
                            )
                            nc.drawText(
                                note, ax + ux * 16f * pxPerSp - 40f * pxPerSp,
                                ay + uy * 16f * pxPerSp + 10f * pxPerSp, navPaints.text,
                            )
                        }
                    }
                } else {
                    navPaints.text.textSize = 12f * pxPerSp
                    nc.drawText(
                        "↻ $label", rectW / 2f - 60f * pxPerSp, rectH / 2f - 60f * pxPerSp,
                        navPaints.text,
                    )
                }
            }
            nc.restore()
        }
    }

    val hint = when {
        !pointingState.hasOrientation -> I18n.Ar.needOrientation
        pointingState.latDeg == null -> I18n.Ar.needLocation
        else -> null
    }
    hint?.let {
        Text(
            it,
            color = Color.White.copy(alpha = 0.8f),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .align(Alignment.Center)
                .background(Color.Black.copy(alpha = 0.45f), MaterialTheme.shapes.medium)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
    if (arCalibrated) {
        Text(
            I18n.Ar.calibratedHint,
            color = Color(0xFFB9F6CA),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 12.dp)
                .padding(top = if (topBannerVisible) 156.dp else 104.dp)
                .background(Color.Black.copy(alpha = 0.4f), MaterialTheme.shapes.small)
                .padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
}

/**
 * §0.42 / §0.123：实时认星叠加层（FIT_CENTER 几何对齐 + 透明底）。
 */
@Composable
internal fun BoxScope.CameraLivePreviewOverlay(
    overlay: Bitmap,
    previewLabel: String,
) {
    val imgBmp = remember(overlay) { overlay.asImageBitmap() }
    Canvas(modifier = Modifier.fillMaxSize()) {
        val fitRect = CameraMath.computeFitCenterRect(
            size.width, size.height, overlay.width, overlay.height,
        )
        drawImage(
            image = imgBmp,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(overlay.width, overlay.height),
            dstOffset = IntOffset(
                round(fitRect.left).toInt(),
                round(fitRect.top).toInt(),
            ),
            dstSize = IntSize(
                round(fitRect.width).toInt(),
                round(fitRect.height).toInt(),
            ),
        )
    }
    if (previewLabel.isNotEmpty()) {
        Surface(
            color = Color(0xCC1A237E),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 64.dp),
        ) {
            Text(
                I18n.Camera.identifiedLabel(previewLabel),
                color = Color.White,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * §0.41 / §0.54b：点击天体命中科普卡片 + 点击画面对焦金色收缩框。
 */
@Composable
internal fun CameraTapFocusLayer(
    arOn: Boolean,
    isPortrait: Boolean,
    arSky: ArSkyProjector.ProjectedSky,
    focusPoint: Offset?,
    onSelectArCard: (SkyObjectRef) -> Unit,
    onFocusAt: (Float, Float) -> Unit,
) {
    val arMessierTolPx = with(LocalDensity.current) { 28.dp.toPx() }
    val arStarTolPx = with(LocalDensity.current) { 32.dp.toPx() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(arOn, isPortrait) {
                detectTapGestures { pos ->
                    if (arOn) {
                        val fitRect = CameraMath.computeFitCenterRect(
                            size.width.toFloat(), size.height.toFloat(), isPortrait = isPortrait,
                        )
                        val rx = pos.x - fitRect.left
                        val ry = pos.y - fitRect.top
                        if (rx in 0f..fitRect.width && ry in 0f..fitRect.height) {
                            val hit = arHitTest(arSky, rx, ry, arMessierTolPx, arStarTolPx)
                            if (hit != null) {
                                onSelectArCard(hit)
                                return@detectTapGestures
                            }
                        }
                    }
                    onFocusAt(pos.x / size.width, pos.y / size.height)
                }
            },
    ) {
        focusPoint?.let { p ->
            val focusScale by animateFloatAsState(
                targetValue = 1.0f,
                animationSpec = spring(
                    stiffness = Spring.StiffnessMediumLow,
                    dampingRatio = Spring.DampingRatioLowBouncy,
                ),
                label = "focusAnimScale",
            )
            Canvas(Modifier.fillMaxSize()) {
                val w = 56.dp.toPx() * focusScale
                val cx = p.x * size.width
                val cy = p.y * size.height
                val l = w / 2
                val stroke = 2.dp.toPx()
                val c = Color(0xFFFFC24B)
                val corners = listOf(
                    listOf(Offset(cx - l, cy - l), Offset(cx - l + l * 0.35f, cy - l)),
                    listOf(Offset(cx + l - l * 0.35f, cy - l), Offset(cx + l, cy - l)),
                    listOf(Offset(cx - l, cy + l), Offset(cx - l + l * 0.35f, cy + l)),
                    listOf(Offset(cx + l - l * 0.35f, cy + l), Offset(cx + l, cy + l)),
                    listOf(Offset(cx - l, cy - l), Offset(cx - l, cy - l + l * 0.35f)),
                    listOf(Offset(cx + l, cy - l), Offset(cx + l, cy - l + l * 0.35f)),
                    listOf(Offset(cx - l, cy + l - l * 0.35f), Offset(cx - l, cy + l)),
                    listOf(Offset(cx + l, cy + l - l * 0.35f), Offset(cx + l, cy + l)),
                )
                for ((a, b) in corners) {
                    drawLine(color = c, start = a, end = b, strokeWidth = stroke)
                }
            }
        }
    }
}
