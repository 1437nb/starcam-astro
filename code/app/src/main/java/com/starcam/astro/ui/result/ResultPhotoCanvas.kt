package com.starcam.astro.ui.result

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.starcam.astro.astro.LayerFlags
import com.starcam.astro.astro.LayeredRenderer
import com.starcam.astro.ui.I18n
import com.starcam.astro.ui.theme.AppThemeMode
import com.starcam.astro.ui.theme.LocaleState
import com.starcam.astro.ui.theme.ThemeState
import kotlin.math.min

/** §0.120 图层快捷开关栏（识别成功后直接切换星星名字、星座连线等标注） */
@Composable
internal fun LayerFilterChips(
    flags: LayerFlags,
    onFlagsChange: (LayerFlags) -> Unit,
    showPlanets: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = flags.starNames,
            onClick = { onFlagsChange(flags.copy(starNames = !flags.starNames)) },
            label = { Text(I18n.Layers.starNames) },
            leadingIcon = if (flags.starNames) {
                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
            } else null,
        )
        FilterChip(
            selected = flags.lines,
            onClick = { onFlagsChange(flags.copy(lines = !flags.lines)) },
            label = { Text(I18n.Layers.lines) },
            leadingIcon = if (flags.lines) {
                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
            } else null,
        )
        FilterChip(
            selected = flags.constellationNames,
            onClick = { onFlagsChange(flags.copy(constellationNames = !flags.constellationNames)) },
            label = { Text(I18n.Layers.constellationNames) },
            leadingIcon = if (flags.constellationNames) {
                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
            } else null,
        )
        FilterChip(
            selected = flags.messier,
            onClick = { onFlagsChange(flags.copy(messier = !flags.messier)) },
            label = { Text(I18n.Layers.messier) },
            leadingIcon = if (flags.messier) {
                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
            } else null,
        )
        if (showPlanets) {
            FilterChip(
                selected = flags.planets,
                onClick = { onFlagsChange(flags.copy(planets = !flags.planets)) },
                label = { Text(I18n.Layers.planets) },
                leadingIcon = if (flags.planets) {
                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                } else null,
            )
        }
    }
}

/** 照片 + 星座叠加画布（点击放大查看 §0.34；图层开关/按住原图/点击天体科普 §0.47） */
@Composable
internal fun StarPhotoOverlay(
    state: ResultUiState.Success,
    flags: LayerFlags,
    onFlagsChange: (LayerFlags) -> Unit,
    onTap: () -> Unit,
) {
    val currentOnTap by rememberUpdatedState(onTap)
    val currentFlags by rememberUpdatedState(flags)
    val bitmap = state.bitmap
    val w = bitmap.width
    val h = bitmap.height
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    // §0.47：按住看原图（pointerInput 捕获按下/抬起，避免与点击放大冲突）
    var holdingOriginal by remember { mutableStateOf(false) }
    // §0.47：点击天体 → 科普卡片（Messier 优先，其次亮星）
    var cardObject by remember { mutableStateOf<SkyObjectRef?>(null) }
    var showPanel by remember { mutableStateOf(false) }
    // 夜视红主题下用低亮度红系标注，不破坏暗适应（§0.36）
    val night = ThemeState.mode == AppThemeMode.NIGHT_RED
    val isEn = LocaleState.isEnglish
    // 命中容差（屏幕像素）：v1.5.32 曾误用图像像素（≈4dp）导致几乎点不中
    val messierTolPx = with(LocalDensity.current) { 28.dp.toPx() }
    val starTolPx = with(LocalDensity.current) { 32.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(480.dp)
            .onSizeChanged { boxSize = it }
            .background(Color.Black)
            .pointerInput(state, flags) {
                detectTapGestures(
                    onPress = { _ ->
                        holdingOriginal = true
                        try {
                            awaitRelease()
                        } finally {
                            holdingOriginal = false
                        }
                    },
                    onTap = { pos ->
                        // §0.47：命中测试——把点击点换算到图像坐标（letterbox 偏移 + fit-scale），
                        // 容差按屏幕像素换算成位图像素；未命中则放大查看
                        val scale = min(boxSize.width.toFloat() / w, boxSize.height.toFloat() / h)
                        if (scale > 0f) {
                            val imgX = (pos.x - (boxSize.width - w * scale) / 2f) / scale
                            val imgY = (pos.y - (boxSize.height - h * scale) / 2f) / scale
                            val hit = hitTestObjectAt(
                                state, imgX, imgY,
                                messierTolPx / scale, starTolPx / scale,
                                flags = currentFlags,
                            )
                            if (hit != null) {
                                cardObject = hit
                            } else {
                                currentOnTap()
                            }
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (boxSize.width > 0 && boxSize.height > 0) {
            val scale = min(
                boxSize.width.toFloat() / w,
                boxSize.height.toFloat() / h,
            )
            // boxSize 是像素（onSizeChanged），dispW/H 亦为像素值：
            // 必须经 Density 把像素精确转回 dp（直接 Modifier.size(像素值.dp) 会把
            // 像素当 dp 再乘 density，预览放大 density 倍、星座线与星星错位——实测 bug）
            val dispW = with(LocalDensity.current) { (w * scale).toInt().toDp() }
            val dispH = with(LocalDensity.current) { (h * scale).toInt().toDp() }
            val imageModifier = Modifier.size(dispW, dispH)
            // 渲染密度：1sp 对应的像素数（含系统字体缩放），保证预览字号与导出图观感一致
            val pxPerSp = with(LocalDensity.current) { 1.sp.toPx() }

            // §0.47：按住时显示纯净原图，松开恢复标注；叠加层按 flags 逐层绘制
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = if (isEn) "Star photo (solved overlay)" else "星空照片（识别结果叠加）",
                modifier = imageModifier,
                contentScale = ContentScale.FillBounds,
            )
            if (!holdingOriginal) {
                val scene = LayeredRenderer.OverlayScene(
                    w, h, state.stars, state.lines, state.labels, state.messier, state.solar,
                )
                Canvas(modifier = imageModifier) {
                    // 显式传 DrawScope.size（组合件绘制区）：nativeCanvas.width 是整块
                    // 窗口画布，直接用会导致 x/y 缩放比不一致、星座严重变形（v1.5.33）
                    LayeredRenderer.draw(
                        drawContext.canvas.nativeCanvas,
                        scene, flags, isEn, density = pxPerSp, night = night,
                        drawWidth = size.width.toFloat(),
                        drawHeight = size.height.toFloat(),
                        degPerPx = plateScaleDegPerPx(state) ?: Float.NaN,
                    )
                }
            }
        }
        // 图层控制按钮（§0.47，支持双语）：右侧悬浮
        Text(
            if (showPanel) "✕" else "≡",
            fontSize = 18.sp,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 10.dp, end = 10.dp)
                .background(Color.Black.copy(alpha = 0.45f), MaterialTheme.shapes.small)
                .clickable { showPanel = !showPanel }
                .padding(horizontal = 10.dp, vertical = 2.dp),
        )
        AnimatedVisibility(
            visible = showPanel,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 48.dp, end = 10.dp),
        ) {
            LayerPanel(
                flags = flags,
                onChange = onFlagsChange,
            )
        }
        // 按住看原图提示（§0.47）
        Text(
            if (holdingOriginal) I18n.Layers.releaseToRestore else I18n.Layers.holdOriginal,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
                .background(Color.Black.copy(alpha = 0.35f), MaterialTheme.shapes.small)
                .padding(horizontal = 6.dp, vertical = 3.dp),
        )
        // 点击放大提示（§0.34，支持双语）
        Text(
            if (isEn) "🔍 Tap to zoom · Tap markers for info" else "🔍 点击放大 · 轻点标记看详情",
            color = Color.White.copy(alpha = 0.65f),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp)
                .background(Color.Black.copy(alpha = 0.35f))
                .padding(horizontal = 6.dp, vertical = 3.dp),
        )

        // §0.47：天体科普卡片（照片底部滑出的浮层，直接可见）
        AnimatedVisibility(
            visible = cardObject != null,
            enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
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

/** §0.47：图层控制面板（星座连线/星名/星座名/梅西耶/月亮与行星 5 个独立开关） */
@Composable
private fun LayerPanel(
    flags: LayerFlags,
    onChange: (LayerFlags) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.72f),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                I18n.Layers.panelTitle,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            LayerToggle(I18n.Layers.lines, flags.lines) { onChange(flags.copy(lines = it)) }
            LayerToggle(I18n.Layers.starNames, flags.starNames) { onChange(flags.copy(starNames = it)) }
            LayerToggle(I18n.Layers.constellationNames, flags.constellationNames) { onChange(flags.copy(constellationNames = it)) }
            LayerToggle(I18n.Layers.messier, flags.messier) { onChange(flags.copy(messier = it)) }
            LayerToggle(I18n.Layers.planets, flags.planets) { onChange(flags.copy(planets = it)) }
        }
    }
}

@Composable
private fun LayerToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clickable { onChange(!checked) }
            .padding(vertical = 3.dp),
    ) {
        Text(
            if (checked) "☑" else "☐",
            color = if (checked) Color(0xFF7FD0FF) else Color.White.copy(alpha = 0.55f),
            fontSize = 15.sp,
        )
        Spacer(Modifier.width(6.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.bodySmall)
    }
}
