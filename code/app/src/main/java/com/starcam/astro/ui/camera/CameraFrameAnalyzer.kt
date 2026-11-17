package com.starcam.astro.ui.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.starcam.astro.astro.CatalogDomain
import com.starcam.astro.astro.LocalStarMatcher
import com.starcam.astro.astro.MessierCatalog
import com.starcam.astro.astro.SkyEphemeris
import com.starcam.astro.astro.StarChartOverlay
import com.starcam.astro.astro.StarNames
import com.starcam.astro.astro.WcsTransform
import com.starcam.astro.data.HistoryStore
import com.starcam.astro.data.SettingsRepository
import com.starcam.astro.data.SolveLogStore
import com.starcam.astro.ui.theme.LocaleState
import com.starcam.astro.util.DeviceOrientationTracker
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * §0.123 修复 P1-2：主摄物理传感器参数缓存，用于横竖屏切换时动态重算水平视场角。
 */
internal data class CameraSensorParams(
    val focals: FloatArray,
    val physWidth: Double,
    val physHeight: Double,
    val sensorRot: Int,
)

/**
 * 相机取景帧后台分析管线与透明叠加层合成（§0.142 模块化解耦）。
 */
internal fun createStarPreviewAnalyzer(
    context: Context,
    settings: SettingsRepository,
    orientationTracker: DeviceOrientationTracker,
    livePreviewFlag: AtomicBoolean,
    arFlag: AtomicBoolean,
    analyzeIntervalMs: Long,
    lastAnalyzeMs: AtomicLong,
    lastAnalyzeErrMs: AtomicLong,
    latestPointing: AtomicReference<DeviceOrientationTracker.DevicePointing>,
    mainHandler: Handler,
    onArCalibrated: (label: String, solvedFovDeg: Double, isHighConfidence: Boolean, deltaAzDeg: Double) -> Unit,
    onLiveOverlay: (overlay: Bitmap, label: String) -> Unit,
    onClearOverlay: () -> Unit,
): ImageAnalysis.Analyzer = ImageAnalysis.Analyzer { image ->
    val now = System.currentTimeMillis()
    if (!livePreviewFlag.get() || now - lastAnalyzeMs.get() < analyzeIntervalMs) {
        image.close()
        return@Analyzer
    }
    lastAnalyzeMs.set(now)
    var decoded: Bitmap? = null
    var oriented: Bitmap? = null
    try {
        decoded = bitmapFromYuvPlanes(image)
        val rot = image.imageInfo.rotationDegrees
        val source = decoded ?: return@Analyzer
        oriented = if (rot != 0) {
            val m = Matrix().apply { postRotate(rot.toFloat()) }
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, m, true)
        } else source
        val frame = oriented ?: return@Analyzer
        val w = frame.width
        val h = frame.height
        // Capture sensor pose and timestamp at frame arrival, before star detection/matching latency.
        val framePointing = latestPointing.get()
        val frameTimeSec = System.currentTimeMillis() / 1000L
        val hint = if (settings.sensorAssistedPointing) orientationTracker.createPointingHint() else null
        val stars = LocalStarMatcher.detectStars(frame, 60)
        // §0.77：预览路径关闭深域兜底
        val res = LocalStarMatcher.match(stars, w, h, hint, allowDeepRetry = false)
        if (res != null && res.solve.wcs != null) {
            val isEn = LocaleState.isEnglish
            val label = HistoryStore.nearestConstellation(
                res.solve.raDeg, res.solve.decDeg, isEn,
            )
            if (arFlag.get()) {
                val solvedFov = res.solve.fieldWidthDeg
                val latVal = framePointing.latDeg
                val lonVal = framePointing.lonDeg
                var deltaAz = 0.0
                if (latVal != null && lonVal != null && framePointing.hasOrientation) {
                    val jdNow = SkyEphemeris.unixSecondsToJd(frameTimeSec.toDouble())
                    val lstNow = SkyEphemeris.lstDeg(jdNow, lonVal)
                    val d2r = Math.PI / 180.0
                    val sl = sin(lstNow * d2r)
                    val cl = cos(lstNow * d2r)
                    val sp = sin(latVal * d2r)
                    val cp = cos(latVal * d2r)
                    val cd = cos(res.solve.decDeg * d2r)
                    val vx = cd * cos(res.solve.raDeg * d2r)
                    val vy = cd * sin(res.solve.raDeg * d2r)
                    val vz = sin(res.solve.decDeg * d2r)
                    val e = -sl * vx + cl * vy
                    val n = -sp * cl * vx - sp * sl * vy + cp * vz
                    var azSolve = Math.toDegrees(atan2(e, n))
                    if (azSolve < 0.0) azSolve += 360.0
                    deltaAz = azSolve - framePointing.azDeg
                    while (deltaAz > 180.0) deltaAz -= 360.0
                    while (deltaAz < -180.0) deltaAz += 360.0
                }
                val isHighConfidence = (res.inlierCount >= 18 &&
                    res.catalogDomain == CatalogDomain.SHALLOW) ||
                    ((res.solve.logodds ?: 0.0) >= 25.0)
                mainHandler.post {
                    onArCalibrated(label, solvedFov, isHighConfidence, deltaAz)
                }
            } else {
                val overlay = renderLiveOverlay(frame.width, frame.height, res.solve.wcs!!)
                if (overlay != null) {
                    mainHandler.post {
                        onLiveOverlay(overlay, label)
                    }
                }
            }
        } else {
            mainHandler.post {
                onClearOverlay()
            }
        }
    } catch (t: Throwable) {
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastAnalyzeErrMs.get() > 60_000L) {
            lastAnalyzeErrMs.set(nowMs)
            SolveLogStore.line(
                context,
                "AR 预览分析异常(${t.javaClass.simpleName}): ${t.message}",
            )
        }
    } finally {
        image.close()
        oriented?.takeIf { !it.isRecycled }?.recycle()
        decoded?.takeIf { it !== oriented && !it.isRecycled }?.recycle()
    }
}

/**
 * §0.42 / §0.123：实时预览认星叠加层——把星座连线和星座名画到透明背景上（P3-4 零帧拷贝）。
 */
internal fun renderLiveOverlay(w: Int, h: Int, wcs: WcsTransform): Bitmap? {
    val out = try {
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    } catch (_: Throwable) {
        return null
    }
    val canvas = AndroidCanvas(out)
    val linePaint = Paint().apply {
        color = 0xCCFFC24B.toInt()
        style = Paint.Style.STROKE
        strokeWidth = maxOf(2f, w / 400f)
        pathEffect = DashPathEffect(floatArrayOf(14f, 9f), 0f)
        isAntiAlias = true
    }
    val labelPaint = Paint().apply {
        color = 0xFFFFFFFF.toInt()
        textSize = maxOf(22f, w / 30f)
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
        setShadowLayer(5f, 0f, 0f, 0xAA000000.toInt())
    }
    val isEn = LocaleState.isEnglish
    val stars = StarChartOverlay.projectStars(wcs, w, h)
    for (line in StarChartOverlay.projectLines(stars)) {
        canvas.drawLine(line.a.x, line.a.y, line.b.x, line.b.y, linePaint)
    }
    val namePaint = Paint().apply {
        color = 0xFFF7EDCB.toInt()
        textSize = maxOf(16f, w / 45f)
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
        setShadowLayer(4f, 1f, 1f, 0xFF000000.toInt())
    }
    for (s in stars) {
        if (!s.visible || s.entry.mag > 3.2) continue
        val nm = StarNames.displayName(s.entry.hip, s.entry.name, isEn)
        if (nm.isEmpty()) continue
        canvas.drawText(nm, s.x + 6f, s.y - 6f, namePaint)
    }
    val ringPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = maxOf(2f, w / 700f)
        isAntiAlias = true
    }
    val messierPaint = Paint().apply {
        color = 0xFFF7EDCB.toInt()
        textSize = maxOf(15f, w / 48f)
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
        setShadowLayer(4f, 1f, 1f, 0xFF000000.toInt())
    }
    for (m in StarChartOverlay.projectMessier(wcs, w, h)) {
        if (!m.visible) continue
        ringPaint.color = MessierCatalog.typeColor(m.obj.type)
        canvas.drawCircle(m.x, m.y, maxOf(6f, w / 160f), ringPaint)
        canvas.drawText(m.obj.label(isEn), m.x + 8f, m.y - 6f, messierPaint)
    }
    for (label in StarChartOverlay.constellationLabels(stars, isEn)) {
        canvas.drawText(label.text, label.x, label.y, labelPaint)
    }
    return out
}

/**
 * §0.42/§0.66：把 ImageAnalysis 的 YUV_420_888 帧的 Y 平面装成灰度 Bitmap。
 */
internal fun bitmapFromYuvPlanes(proxy: ImageProxy): Bitmap? {
    val w = proxy.width
    val h = proxy.height
    val planes = proxy.planes
    if (planes.isEmpty()) return null
    val yPlane = planes[0]
    if (yPlane.pixelStride != 1) return null
    val buf = yPlane.buffer
    val rowStride = yPlane.rowStride
    if (buf.capacity() < rowStride * (h - 1) + w) return null

    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(w * h)
    var idx = 0
    for (row in 0 until h) {
        buf.position(row * rowStride)
        for (col in 0 until w) {
            val y = buf.get().toInt() and 0xFF
            pixels[idx++] = (0xFF shl 24) or (y shl 16) or (y shl 8) or y
        }
    }
    bmp.setPixels(pixels, 0, w, 0, 0, w, h)
    return bmp
}
