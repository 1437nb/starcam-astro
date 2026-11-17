package com.starcam.astro.ui.result

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.starcam.astro.astro.LayerFlags
import com.starcam.astro.ui.I18n
import com.starcam.astro.ui.theme.AppThemeMode
import com.starcam.astro.ui.theme.LocaleState
import com.starcam.astro.ui.theme.ThemeState
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ViewerCloseIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "ViewerClose",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(19f, 6.41f)
            lineTo(17.59f, 5f)
            lineTo(12f, 10.59f)
            lineTo(6.41f, 5f)
            lineTo(5f, 6.41f)
            lineTo(10.59f, 12f)
            lineTo(5f, 17.59f)
            lineTo(6.41f, 19f)
            lineTo(12f, 13.41f)
            lineTo(17.59f, 19f)
            lineTo(19f, 17.59f)
            lineTo(13.41f, 12f)
            close()
        }
    }.build()
}

private val ViewerLayersIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "ViewerLayers",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(11.99f, 2f)
            lineTo(2f, 7f)
            lineTo(12f, 12f)
            lineTo(22f, 7f)
            lineTo(11.99f, 2f)
            close()
            moveTo(12f, 14.91f)
            lineTo(3.63f, 10.73f)
            lineTo(2f, 12f)
            lineTo(12f, 17f)
            lineTo(22f, 12f)
            lineTo(20.37f, 10.73f)
            lineTo(12f, 14.91f)
            close()
            moveTo(12f, 18.91f)
            lineTo(3.63f, 14.73f)
            lineTo(2f, 16f)
            lineTo(12f, 21f)
            lineTo(22f, 16f)
            lineTo(20.37f, 14.73f)
            lineTo(12f, 18.91f)
            close()
        }
    }.build()
}

/**
 * 全屏图片查看器（§0.34，§0.37 增加原图/标注切换，§0.47 增加图层开关面板，§0.143 HUD 精修）：
 * 捏合缩放 1~5x、单指拖拽平移、双击放大 3x/还原；顶部半透明胶囊工具栏集成关闭、
 * 实时缩放倍率（点击平滑复位 1.0×）、分段「标注 / 原图」切换与带后台重绘微加载态的图层面板。
 *
 * §0.48：传入 [hitTest] 时支持在放大图内轻点天体（梅西耶/亮星）弹出科普卡片。
 * 回调参数为位图像素坐标与"屏幕像素/位图像素"比（供容差换算），可在任意缩放级别命中。
 */
@Composable
fun ZoomableImageViewer(
    original: Bitmap,
    annotated: Bitmap,
    title: String,
    onClose: () -> Unit,
    renderAnnotated: ((LayerFlags) -> Bitmap)? = null,
    initialFlags: LayerFlags = LayerFlags.ALL,
    onFlagsChange: ((LayerFlags) -> Unit)? = null,
    hitTest: ((x: Float, y: Float, screenPxPerBitmapPx: Float) -> SkyObjectRef?)? = null,
) {
    var showAnnotated by remember { mutableStateOf(true) }
    // §0.47 / §0.120：图层开关与当前标注位图（绑定 initialFlags 与 annotated，开关变化时与调用方同步）
    var flags by remember(initialFlags) { mutableStateOf(initialFlags) }
    var annotatedBitmap by remember(annotated) { mutableStateOf(annotated) }
    val scope = rememberCoroutineScope()
    val currentFlagsChange by rememberUpdatedState(onFlagsChange)
    val currentRender by rememberUpdatedState(renderAnnotated)
    val currentHitTest by rememberUpdatedState(hitTest)
    val night = ThemeState.mode == AppThemeMode.NIGHT_RED
    val isEn = LocaleState.isEnglish

    /**
     * §0.78 / §0.120 / §0.123：图层切换的重渲染统一入口，双向同步图层状态。
     * 采用 Conflated / Last-Write-Wins 调度循环（修复 P2-3），杜绝快速切换时渲染丢失与画面勾选不一致。
     */
    var isRendering by remember { mutableStateOf(false) }
    var pendingFlags by remember { mutableStateOf<LayerFlags?>(null) }
    val rerender: (LayerFlags) -> Unit = { f ->
        flags = f
        currentFlagsChange?.invoke(f)
        pendingFlags = f
        val render = currentRender
        if (render != null && !isRendering) {
            isRendering = true
            scope.launch {
                try {
                    while (true) {
                        val targetFlags = pendingFlags ?: break
                        pendingFlags = null
                        val newBmp = withContext(Dispatchers.IO) { render.invoke(targetFlags) }
                        annotatedBitmap = newBmp
                    }
                } finally {
                    isRendering = false
                }
            }
        }
    }
    var showLayerPanel by remember { mutableStateOf(false) }
    // §0.48：点击命中的天体 → 底部科普卡片
    var cardObject by remember { mutableStateOf<SkyObjectRef?>(null) }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val shown = if (showAnnotated) annotatedBitmap else original

    val resetZoomSmoothly: () -> Unit = {
        val startScale = scale
        val startOffset = offset
        if (startScale > 1.01f || startOffset != Offset.Zero) {
            scope.launch {
                animate(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                ) { fraction, _ ->
                    scale = startScale + (1f - startScale) * fraction
                    offset = Offset(
                        startOffset.x * (1f - fraction),
                        startOffset.y * (1f - fraction),
                    )
                }
                scale = 1f
                offset = Offset.Zero
            }
        }
    }

    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(1f, 5f)
        scale = newScale
        // 平移限制在图片边缘内（放大后允许拖动查看）
        if (boxSize.width > 0) {
            val maxX = boxSize.width * (newScale - 1f) / 2f
            val maxY = boxSize.height * (newScale - 1f) / 2f
            // 拖动速度 ×2（§0.38）：放大后滑动更跟手；1 倍时偏移被钳制为 0，无副作用
            offset = Offset(
                (offset.x + panChange.x * 2f).coerceIn(-maxX, maxX),
                (offset.y + panChange.y * 2f).coerceIn(-maxY, maxY),
            )
        }
    }

    val hudBg = if (night) Color(0xD9160505) else Color(0xCC091022)
    val hudBorder = if (night) Color(0x665A2622) else Color(0x36FFFFFF)
    val hudFg = if (night) Color(0xFFF2ACA4) else Color(0xFFF2F5FC)
    val hudMuted = if (night) Color(0xFFC88F88) else Color(0xFFB4C0DC)
    val hudAccent = if (night) Color(0xFFFF6E6E) else Color(0xFF7FC4FF)
    val hudActiveBg = if (night) Color(0xFF3E1111) else Color(0xFF1D3A64)
    val hudActiveBorder = if (night) Color(0xAAFF7A70) else Color(0x9982BAFF)

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            Image(
                bitmap = shown.asImageBitmap(),
                contentDescription = title,
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { boxSize = it }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
                    .transformable(transformableState)
                    .pointerInput(currentHitTest) {
                        detectTapGestures(
                            onTap = { pos ->
                                if (showLayerPanel) {
                                    showLayerPanel = false
                                    return@detectTapGestures
                                }
                                // §0.48：轻点天体 → 换算位图坐标做命中测试（graphicsLayer 的
                                // 视觉缩放/平移不改变本地坐标，pointerInput 收到的即本地坐标）
                                val ht = currentHitTest
                                if (ht != null && boxSize.width > 0) {
                                    val bw = original.width.toFloat()
                                    val bh = original.height.toFloat()
                                    val fit = minOf(
                                        boxSize.width / bw, boxSize.height / bh,
                                    )
                                    if (fit > 0f) {
                                        val bx = (pos.x - (boxSize.width - bw * fit) / 2f) / fit
                                        val by = (pos.y - (boxSize.height - bh * fit) / 2f) / fit
                                        val hit = ht(bx, by, fit * scale)
                                        if (hit != null) cardObject = hit
                                    }
                                }
                            },
                            onDoubleTap = { pos ->
                                if (scale > 1.01f) {
                                    resetZoomSmoothly()
                                } else {
                                    val newScale = 3f
                                    scale = newScale
                                    // 以点击点为中心放大：把点击点移到画面中心
                                    val maxX = boxSize.width * (newScale - 1f) / 2f
                                    val maxY = boxSize.height * (newScale - 1f) / 2f
                                    offset = Offset(
                                        ((boxSize.width / 2f - pos.x) * (newScale - 1f))
                                            .coerceIn(-maxX, maxX),
                                        ((boxSize.height / 2f - pos.y) * (newScale - 1f))
                                            .coerceIn(-maxY, maxY),
                                    )
                                }
                            },
                        )
                    },
                contentScale = ContentScale.Fit,
            )

            // 顶部悬浮半透明胶囊工具栏（§0.143）
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 第一行：关闭按钮 + 标题 + 实时缩放胶囊（>1.01× 时可点击平滑复位）
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = CircleShape,
                    color = hudBg,
                    border = BorderStroke(1.dp, hudBorder),
                    shadowElevation = 6.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Surface(
                            onClick = onClose,
                            shape = CircleShape,
                            color = if (night) Color(0xFF280909) else Color(0x22FFFFFF),
                        ) {
                            Box(
                                modifier = Modifier.size(32.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = ViewerCloseIcon,
                                    contentDescription = I18n.InfoCard.close(),
                                    tint = hudFg,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }

                        Text(
                            text = title,
                            color = hudFg,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )

                        val canResetZoom = scale > 1.01f
                        val zoomText = "%.1f×".format(Locale.ROOT, scale)
                        Surface(
                            shape = CircleShape,
                            color = if (canResetZoom) hudActiveBg else Color.Transparent,
                            border = BorderStroke(
                                1.dp,
                                if (canResetZoom) hudActiveBorder else hudBorder,
                            ),
                            modifier = if (canResetZoom) {
                                Modifier
                                    .clip(CircleShape)
                                    .clickable(onClick = resetZoomSmoothly)
                            } else {
                                Modifier
                            },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = zoomText,
                                    color = if (canResetZoom) hudFg else hudMuted,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                if (canResetZoom) {
                                    Text(
                                        text = if (isEn) "Reset" else "复位",
                                        color = hudAccent,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                        }
                    }
                }

                // 第二行：分段「标注 / 原图」切换胶囊 + 图层面板按钮（含 IO 重绘微加载态）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = hudBg,
                        border = BorderStroke(1.dp, hudBorder),
                        shadowElevation = 4.dp,
                    ) {
                        Row(
                            modifier = Modifier.padding(3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ViewerModeSegment(
                                label = I18n.annotation,
                                selected = showAnnotated,
                                activeBg = hudActiveBg,
                                activeFg = hudFg,
                                inactiveFg = hudMuted,
                                onClick = { showAnnotated = true },
                            )
                            ViewerModeSegment(
                                label = I18n.original,
                                selected = !showAnnotated,
                                activeBg = hudActiveBg,
                                activeFg = hudFg,
                                inactiveFg = hudMuted,
                                onClick = { showAnnotated = false },
                            )
                        }
                    }

                    if (renderAnnotated != null) {
                        Surface(
                            onClick = { showLayerPanel = !showLayerPanel },
                            shape = CircleShape,
                            color = if (showLayerPanel) hudActiveBg else hudBg,
                            border = BorderStroke(
                                1.dp,
                                if (showLayerPanel) hudActiveBorder else hudBorder,
                            ),
                            shadowElevation = 4.dp,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                if (isRendering) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 1.8.dp,
                                        color = hudAccent,
                                    )
                                } else {
                                    Icon(
                                        imageVector = ViewerLayersIcon,
                                        contentDescription = I18n.Layers.panelTitle,
                                        tint = hudFg,
                                        modifier = Modifier.size(15.dp),
                                    )
                                }
                                Text(
                                    text = I18n.Layers.panelTitle,
                                    color = hudFg,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }

                // §0.47 / §0.143：图层开关面板（右上悬浮，预设胶囊 + 5 项矢量勾选行）
                AnimatedVisibility(
                    visible = showLayerPanel && renderAnnotated != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    ViewerLayerPanel(
                        flags = flags,
                        isRendering = isRendering,
                        night = night,
                        isEn = isEn,
                        onChange = rerender,
                    )
                }
            }

            // 底部手势提示胶囊（卡片未弹出时展示）
            AnimatedVisibility(
                visible = cardObject == null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 18.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = hudBg.copy(alpha = 0.72f),
                    border = BorderStroke(1.dp, hudBorder),
                ) {
                    Text(
                        text = I18n.Viewer.gestureHint,
                        color = hudMuted,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    )
                }
            }

            // §0.48：天体科普卡片（放大图底部浮层）
            AnimatedVisibility(
                visible = cardObject != null,
                enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                cardObject?.let { ref ->
                    ObjectInfoCard(
                        ref = ref,
                        onDismiss = { cardObject = null },
                    )
                }
            }
        }
    }
}

@Composable
private fun ViewerModeSegment(
    label: String,
    selected: Boolean,
    activeBg: Color,
    activeFg: Color,
    inactiveFg: Color,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) activeBg else Color.Transparent,
    ) {
        Text(
            text = label,
            color = if (selected) activeFg else inactiveFg,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun ViewerLayerPanel(
    flags: LayerFlags,
    isRendering: Boolean,
    night: Boolean,
    isEn: Boolean,
    onChange: (LayerFlags) -> Unit,
) {
    val panelBg = if (night) Color(0xEB150404) else Color(0xEB0A1224)
    val panelBorder = if (night) Color(0x735A2622) else Color(0x38FFFFFF)
    val textPrimary = if (night) Color(0xFFF2ACA4) else Color(0xFFF2F5FC)
    val textMuted = if (night) Color(0xFFC88F88) else Color(0xFFB4C0DC)
    val accent = if (night) Color(0xFFFF6E6E) else Color(0xFF7FC4FF)

    Surface(
        modifier = Modifier.widthIn(min = 204.dp, max = 252.dp),
        color = panelBg,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, panelBorder),
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    I18n.Layers.panelTitle,
                    color = textPrimary,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
                if (isRendering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(13.dp),
                        strokeWidth = 1.6.dp,
                        color = accent,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ViewerPresetPill(
                    label = if (isEn) "All" else "全部显示",
                    selected = flags.allEnabled,
                    night = night,
                    onClick = { onChange(LayerFlags.ALL) },
                    modifier = Modifier.weight(1f),
                )
                ViewerPresetPill(
                    label = if (isEn) "Lines Only" else "纯净连线",
                    selected = flags == LayerFlags.LINES_ONLY,
                    night = night,
                    onClick = { onChange(LayerFlags.LINES_ONLY) },
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = if (night) Color(0x385A2622) else Color(0x24FFFFFF))
            Spacer(Modifier.height(4.dp))

            ViewerLayerToggle(I18n.Layers.lines, flags.lines, textPrimary, textMuted, accent, night) {
                onChange(flags.copy(lines = it))
            }
            ViewerLayerToggle(I18n.Layers.starNames, flags.starNames, textPrimary, textMuted, accent, night) {
                onChange(flags.copy(starNames = it))
            }
            ViewerLayerToggle(I18n.Layers.constellationNames, flags.constellationNames, textPrimary, textMuted, accent, night) {
                onChange(flags.copy(constellationNames = it))
            }
            ViewerLayerToggle(I18n.Layers.messier, flags.messier, textPrimary, textMuted, accent, night) {
                onChange(flags.copy(messier = it))
            }
            ViewerLayerToggle(I18n.Layers.planets, flags.planets, textPrimary, textMuted, accent, night) {
                onChange(flags.copy(planets = it))
            }
        }
    }
}

@Composable
private fun ViewerPresetPill(
    label: String,
    selected: Boolean,
    night: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = when {
        selected && night -> Color(0xFF441414)
        selected -> Color(0xFF1F3D66)
        night -> Color(0xFF220808)
        else -> Color(0x1FFFFFFF)
    }
    val border = when {
        selected && night -> Color(0xAAFF7A70)
        selected -> Color(0xAA82BAFF)
        night -> Color(0x445A2622)
        else -> Color(0x26FFFFFF)
    }
    val fg = when {
        selected && night -> Color(0xFFFFC4BC)
        selected -> Color(0xFFD9ECFF)
        night -> Color(0xFFD69A92)
        else -> Color(0xFFD0DAEE)
    }
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = CircleShape,
        color = bg,
        border = BorderStroke(1.dp, border),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = fg,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ViewerLayerToggle(
    label: String,
    checked: Boolean,
    textPrimary: Color,
    textMuted: Color,
    accent: Color,
    night: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val boxShape = RoundedCornerShape(5.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable { onChange(!checked) }
            .padding(horizontal = 4.dp, vertical = 5.dp),
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(boxShape)
                .background(if (checked) accent else Color.Transparent)
                .border(
                    width = 1.4.dp,
                    color = if (checked) accent else textMuted.copy(alpha = 0.65f),
                    shape = boxShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = if (night) Color(0xFF280606) else Color(0xFF06162A),
                    modifier = Modifier.size(13.dp),
                )
            }
        }
        Spacer(Modifier.width(9.dp))
        Text(
            text = label,
            color = if (checked) textPrimary else textMuted,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (checked) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

