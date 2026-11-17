package com.starcam.astro.ui.result

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
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

private val LayersVectorIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "ResultLayers",
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

private val CloseVectorIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "ResultClose",
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

private val CompareVectorIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "ResultCompare",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(10f, 3f)
            horizontalLineTo(5f)
            curveTo(3.9f, 3f, 3f, 3.9f, 3f, 5f)
            verticalLineTo(19f)
            curveTo(3f, 20.1f, 3.9f, 21f, 5f, 21f)
            horizontalLineTo(10f)
            verticalLineTo(23f)
            horizontalLineTo(12f)
            verticalLineTo(1f)
            horizontalLineTo(10f)
            verticalLineTo(3f)
            close()
            moveTo(10f, 18f)
            horizontalLineTo(5f)
            lineTo(10f, 12f)
            verticalLineTo(18f)
            close()
            moveTo(19f, 3f)
            horizontalLineTo(14f)
            verticalLineTo(5f)
            horizontalLineTo(19f)
            verticalLineTo(18f)
            lineTo(14f, 12f)
            verticalLineTo(21f)
            horizontalLineTo(19f)
            curveTo(20.1f, 21f, 21f, 20.1f, 21f, 19f)
            verticalLineTo(5f)
            curveTo(21f, 3.9f, 20.1f, 3f, 19f, 3f)
            close()
        }
    }.build()
}

private val ZoomVectorIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "ResultZoomFullscreen",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(7f, 14f)
            horizontalLineTo(5f)
            verticalLineTo(19f)
            horizontalLineTo(10f)
            verticalLineTo(17f)
            horizontalLineTo(7f)
            verticalLineTo(14f)
            close()
            moveTo(5f, 10f)
            horizontalLineTo(7f)
            verticalLineTo(7f)
            horizontalLineTo(10f)
            verticalLineTo(5f)
            horizontalLineTo(5f)
            verticalLineTo(10f)
            close()
            moveTo(17f, 17f)
            horizontalLineTo(14f)
            verticalLineTo(19f)
            horizontalLineTo(19f)
            verticalLineTo(14f)
            horizontalLineTo(17f)
            verticalLineTo(17f)
            close()
            moveTo(14f, 5f)
            verticalLineTo(7f)
            horizontalLineTo(17f)
            verticalLineTo(10f)
            horizontalLineTo(19f)
            verticalLineTo(5f)
            horizontalLineTo(14f)
            close()
        }
    }.build()
}

/** §0.120 / §0.143 图层快捷开关栏（预设快捷切换 + 圆润色调筛选胶囊） */
@Composable
internal fun LayerFilterChips(
    flags: LayerFlags,
    onFlagsChange: (LayerFlags) -> Unit,
    showPlanets: Boolean,
    modifier: Modifier = Modifier,
) {
    val isEn = LocaleState.isEnglish
    val allSelected = if (showPlanets) {
        flags.allEnabled
    } else {
        flags.lines && flags.starNames && flags.constellationNames && flags.messier
    }
    val linesOnlySelected = flags == LayerFlags.LINES_ONLY

    val presetChipColors = FilterChipDefaults.filterChipColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
    )
    val layerChipColors = FilterChipDefaults.filterChipColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
        selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
        selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = allSelected,
            onClick = { onFlagsChange(LayerFlags.ALL) },
            shape = CircleShape,
            colors = presetChipColors,
            label = {
                Text(
                    if (isEn) "All" else "全选",
                    fontWeight = if (allSelected) FontWeight.SemiBold else FontWeight.Normal,
                )
            },
            leadingIcon = if (allSelected) {
                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(15.dp)) }
            } else null,
        )
        FilterChip(
            selected = linesOnlySelected,
            onClick = {
                onFlagsChange(if (linesOnlySelected) LayerFlags.ALL else LayerFlags.LINES_ONLY)
            },
            shape = CircleShape,
            colors = presetChipColors,
            label = {
                Text(
                    if (isEn) "Lines Only" else "仅连线",
                    fontWeight = if (linesOnlySelected) FontWeight.SemiBold else FontWeight.Normal,
                )
            },
            leadingIcon = if (linesOnlySelected) {
                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(15.dp)) }
            } else null,
        )

        Box(
            modifier = Modifier
                .width(1.dp)
                .height(20.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )

        FilterChip(
            selected = flags.lines,
            onClick = { onFlagsChange(flags.copy(lines = !flags.lines)) },
            shape = CircleShape,
            colors = layerChipColors,
            label = { Text(I18n.Layers.lines) },
            leadingIcon = if (flags.lines) {
                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
            } else null,
        )
        FilterChip(
            selected = flags.starNames,
            onClick = { onFlagsChange(flags.copy(starNames = !flags.starNames)) },
            shape = CircleShape,
            colors = layerChipColors,
            label = { Text(I18n.Layers.starNames) },
            leadingIcon = if (flags.starNames) {
                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
            } else null,
        )
        FilterChip(
            selected = flags.constellationNames,
            onClick = { onFlagsChange(flags.copy(constellationNames = !flags.constellationNames)) },
            shape = CircleShape,
            colors = layerChipColors,
            label = { Text(I18n.Layers.constellationNames) },
            leadingIcon = if (flags.constellationNames) {
                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
            } else null,
        )
        FilterChip(
            selected = flags.messier,
            onClick = { onFlagsChange(flags.copy(messier = !flags.messier)) },
            shape = CircleShape,
            colors = layerChipColors,
            label = { Text(I18n.Layers.messier) },
            leadingIcon = if (flags.messier) {
                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
            } else null,
        )
        if (showPlanets) {
            FilterChip(
                selected = flags.planets,
                onClick = { onFlagsChange(flags.copy(planets = !flags.planets)) },
                shape = CircleShape,
                colors = layerChipColors,
                label = { Text(I18n.Layers.planets) },
                leadingIcon = if (flags.planets) {
                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                } else null,
            )
        }
    }
}

/** 照片 + 星座叠加画布（点击放大查看 §0.34；图层开关/按住原图/点击天体科普 §0.47；自适应视口与浮层精修 §0.143） */
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
    val clampedAspect = (w.coerceAtLeast(1).toFloat() / h.coerceAtLeast(1).toFloat()).coerceIn(0.75f, 1.6f)
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

    val hudFg = if (night) Color(0xFFF2ACA4) else Color(0xFFF2F5FC)
    val hudBg = if (night) Color(0xC81A0505) else Color(0xB8091022)
    val hudActiveBg = if (night) Color(0xE63B1010) else Color(0xE01A365D)
    val hudBorder = if (night) Color(0x665A2622) else Color(0x38FFFFFF)
    val hudActiveBorder = if (night) Color(0xB3FF6E6E) else Color(0x9982BAFF)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 260.dp, max = 480.dp)
            .aspectRatio(clampedAspect)
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
                        // 容差按屏幕像素换算成位图像素；未命中则收起面板或放大查看
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
                                showPanel = false
                                cardObject = hit
                            } else if (showPanel) {
                                showPanel = false
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

        // 右上角：磨砂半透明图层胶囊按钮（§0.47 / §0.143）
        Surface(
            onClick = { showPanel = !showPanel },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 10.dp, end = 10.dp),
            shape = CircleShape,
            color = if (showPanel) hudActiveBg else hudBg,
            border = BorderStroke(1.dp, if (showPanel) hudActiveBorder else hudBorder),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = if (showPanel) CloseVectorIcon else LayersVectorIcon,
                    contentDescription = I18n.Layers.panelTitle,
                    tint = hudFg,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = I18n.Layers.panelTitle,
                    color = hudFg,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

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
                night = night,
                isEn = isEn,
            )
        }

        // 左下角：按住对比原图状态胶囊（按下照片任意处即时高亮反馈）
        AnimatedVisibility(
            visible = cardObject == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp),
        ) {
            val holdContainer by animateColorAsState(
                targetValue = if (holdingOriginal) hudActiveBg else hudBg,
                label = "holdOriginalBg",
            )
            val holdBorderColor by animateColorAsState(
                targetValue = if (holdingOriginal) hudActiveBorder else hudBorder,
                label = "holdOriginalBorder",
            )
            Surface(
                shape = CircleShape,
                color = holdContainer,
                border = BorderStroke(1.dp, holdBorderColor),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        imageVector = CompareVectorIcon,
                        contentDescription = null,
                        tint = if (holdingOriginal) {
                            if (night) Color(0xFFFFB74D) else Color(0xFF9CD0FF)
                        } else {
                            hudFg.copy(alpha = 0.85f)
                        },
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = if (holdingOriginal) {
                            I18n.Layers.releaseToRestore
                        } else {
                            if (isEn) "Hold Original" else "按住对比原图"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (holdingOriginal) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (holdingOriginal) hudFg else hudFg.copy(alpha = 0.88f),
                    )
                }
            }
        }

        // 右下角：一键全屏放大胶囊按钮（§0.34 / §0.143）
        AnimatedVisibility(
            visible = cardObject == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(10.dp),
        ) {
            Surface(
                onClick = { currentOnTap() },
                shape = CircleShape,
                color = hudBg,
                border = BorderStroke(1.dp, hudBorder),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        imageVector = ZoomVectorIcon,
                        contentDescription = null,
                        tint = hudFg,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = if (isEn) "Zoom" else "全屏放大",
                        color = hudFg,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        // §0.47：天体科普卡片（照片底部滑出的浮层，直接可见）
        AnimatedVisibility(
            visible = cardObject != null,
            enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
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

/** §0.47 / §0.143：图层控制浮层面板（顶部预设快捷胶囊 + 5 项矢量勾选行） */
@Composable
private fun LayerPanel(
    flags: LayerFlags,
    onChange: (LayerFlags) -> Unit,
    night: Boolean,
    isEn: Boolean,
    modifier: Modifier = Modifier,
) {
    val panelBg = if (night) Color(0xEB150404) else Color(0xEB0A1224)
    val panelBorder = if (night) Color(0x735A2622) else Color(0x38FFFFFF)
    val textPrimary = if (night) Color(0xFFF2ACA4) else Color(0xFFF2F5FC)
    val textMuted = if (night) Color(0xFFC88F88) else Color(0xFFB4C0DC)
    val accent = if (night) Color(0xFFFF6E6E) else Color(0xFF7FC4FF)
    val enabledCount = listOf(
        flags.lines,
        flags.starNames,
        flags.constellationNames,
        flags.messier,
        flags.planets,
    ).count { it }

    Surface(
        modifier = modifier.widthIn(min = 204.dp, max = 248.dp),
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
                Text(
                    "$enabledCount / 5",
                    color = textMuted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            Spacer(Modifier.height(8.dp))

            // 顶部快捷预设胶囊：全部显示 / 纯净连线
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LayerPresetPill(
                    label = if (isEn) "All" else "全部显示",
                    selected = flags.allEnabled,
                    night = night,
                    onClick = { onChange(LayerFlags.ALL) },
                    modifier = Modifier.weight(1f),
                )
                LayerPresetPill(
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

            LayerToggle(I18n.Layers.lines, flags.lines, textPrimary, textMuted, accent, night) {
                onChange(flags.copy(lines = it))
            }
            LayerToggle(I18n.Layers.starNames, flags.starNames, textPrimary, textMuted, accent, night) {
                onChange(flags.copy(starNames = it))
            }
            LayerToggle(I18n.Layers.constellationNames, flags.constellationNames, textPrimary, textMuted, accent, night) {
                onChange(flags.copy(constellationNames = it))
            }
            LayerToggle(I18n.Layers.messier, flags.messier, textPrimary, textMuted, accent, night) {
                onChange(flags.copy(messier = it))
            }
            LayerToggle(I18n.Layers.planets, flags.planets, textPrimary, textMuted, accent, night) {
                onChange(flags.copy(planets = it))
            }
        }
    }
}

@Composable
private fun LayerPresetPill(
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
private fun LayerToggle(
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
                .background(
                    if (checked) accent else Color.Transparent,
                )
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

