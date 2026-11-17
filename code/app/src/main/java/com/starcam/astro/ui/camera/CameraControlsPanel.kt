package com.starcam.astro.ui.camera

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.starcam.astro.astro.CameraMath
import com.starcam.astro.ui.I18n
import com.starcam.astro.ui.theme.AppThemeMode
import com.starcam.astro.ui.theme.LocaleState
import com.starcam.astro.ui.theme.ThemeState
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

private enum class ActiveControlDrawer {
    NONE,
    AR_FOV,
    EXPOSURE,
}

private data class AstroProPreset(
    val zhTitle: String,
    val enTitle: String,
    val rawIso: Int,
    val rawSec: Double,
)

private val ASTRO_SCENE_PRESETS = listOf(
    AstroProPreset("广角银河", "Wide Milky Way", rawIso = 1600, rawSec = 8.0),
    AstroProPreset("暗夜深空", "Dark Sky", rawIso = 3200, rawSec = 15.0),
    AstroProPreset("手持夜拍", "Handheld", rawIso = 3200, rawSec = 0.5),
)

private fun nearestShutterStopIndex(targetSec: Double, exposureRange: LongRange?): Int {
    val stops = CameraMath.shutterStopsSec
    if (stops.isEmpty()) return 0
    val clampedNanos = CameraMath.clampExposureNanos(CameraMath.secToNanos(targetSec), exposureRange)
    val clampedSec = (clampedNanos / 1_000_000_000.0).coerceAtLeast(1e-6)
    var bestIdx = 0
    var bestDist = Double.MAX_VALUE
    for (i in stops.indices) {
        val s = stops[i].coerceAtLeast(1e-6)
        val dist = abs(ln(s) - ln(clampedSec))
        if (dist < bestDist) {
            bestDist = dist
            bestIdx = i
        }
    }
    return bestIdx
}

private fun compactShutterText(sec: Double): String = when {
    abs(sec - 0.5) < 1e-3 -> "0.5s"
    else -> CameraMath.shutterLabel(sec).replace(" ", "")
}

@Immutable
private data class ControlsPalette(
    val chipBg: Color,
    val chipBorder: Color,
    val chipText: Color,
    val chipMutedText: Color,
    val chipActiveBg: Color,
    val chipActiveBorder: Color,
    val chipActiveText: Color,
    val cardBg: Color,
    val cardBorder: Color,
    val segmentTrackBg: Color,
    val segmentSelectedBg: Color,
    val segmentSelectedText: Color,
    val badgeAutoBg: Color,
    val badgeAutoText: Color,
    val warnBg: Color,
    val warnBorder: Color,
    val warnText: Color,
    val sliderAccent: Color,
    val sliderTrackInactive: Color,
)

@Composable
private fun rememberControlsPalette(): ControlsPalette {
    val nightRed = ThemeState.mode == AppThemeMode.NIGHT_RED
    return remember(nightRed) {
        if (nightRed) {
            ControlsPalette(
                chipBg = Color(0xB3120404),
                chipBorder = Color(0x4DFF5252),
                chipText = Color(0xFFE89B93),
                chipMutedText = Color(0xFFB5726B),
                chipActiveBg = Color(0xD93B0E0E),
                chipActiveBorder = Color(0xFFFF5252),
                chipActiveText = Color(0xFFFFB74D),
                cardBg = Color(0xEB130404),
                cardBorder = Color(0x66FF5252),
                segmentTrackBg = Color(0xFF210707),
                segmentSelectedBg = Color(0xFF4C1313),
                segmentSelectedText = Color(0xFFFFB74D),
                badgeAutoBg = Color(0x38FFB74D),
                badgeAutoText = Color(0xFFFFB74D),
                warnBg = Color(0x33FF5252),
                warnBorder = Color(0x66FF6E40),
                warnText = Color(0xFFFFB74D),
                sliderAccent = Color(0xFFFF5252),
                sliderTrackInactive = Color(0xFF3D1414),
            )
        } else {
            ControlsPalette(
                chipBg = Color(0x99080D1A),
                chipBorder = Color(0x38FFFFFF),
                chipText = Color(0xF2F4F7FF),
                chipMutedText = Color(0xFFAAB4CE),
                chipActiveBg = Color(0xCC2A2210),
                chipActiveBorder = Color(0xFFE3C567),
                chipActiveText = Color(0xFFFFDF85),
                cardBg = Color(0xE0090F20),
                cardBorder = Color(0x42FFFFFF),
                segmentTrackBg = Color(0xFF131B36),
                segmentSelectedBg = Color(0xFF362D16),
                segmentSelectedText = Color(0xFFFFDF85),
                badgeAutoBg = Color(0x2E69F0AE),
                badgeAutoText = Color(0xFF9BF6C4),
                warnBg = Color(0x2EFFC24B),
                warnBorder = Color(0x66FFC24B),
                warnText = Color(0xFFFFE082),
                sliderAccent = Color(0xFFE3C567),
                sliderTrackInactive = Color(0xFF283254),
            )
        }
    }
}

/**
 * 相机底部单手折叠快捷条与半透明玻璃参数抽屉（AR FOV 标定 + Camera2 Pro 星空场景预设/手动 ISO/快门 + EV 补偿）（§0.143）。
 */
@Composable
internal fun BoxScope.CameraExposureControls(
    showArFovSlider: Boolean,
    arFovDeg: Float,
    fovAutoCalibrated: Boolean,
    onArFovChange: (Float) -> Unit,
    onArFovChangeFinished: () -> Unit,
    isoRangeState: IntRange?,
    exposureRangeState: LongRange?,
    proMode: Boolean,
    proIso: Int,
    proShutterIdx: Int,
    onToggleProMode: () -> Unit,
    onProIsoChange: (Int) -> Unit,
    onProShutterChange: (Int) -> Unit,
    evIndex: Int,
    maxEvIndex: Int,
    onEvChange: (Int) -> Unit,
) {
    val isEn = LocaleState.isEnglish
    val palette = rememberControlsPalette()
    var activeDrawer by remember { mutableStateOf(ActiveControlDrawer.NONE) }

    LaunchedEffect(showArFovSlider) {
        if (!showArFovSlider && activeDrawer == ActiveControlDrawer.AR_FOV) {
            activeDrawer = ActiveControlDrawer.NONE
        }
    }

    val proSupported = isoRangeState != null && exposureRangeState != null &&
        exposureRangeState.first <= 33_333_333L && exposureRangeState.last >= 1_000_000_000L
    val effectivePro = proMode && proSupported
    val stops = CameraMath.shutterStopsSec
    val currentSec = stops[proShutterIdx.coerceIn(0, stops.lastIndex)]
    val clampedProIso = CameraMath.clampIso(proIso, isoRangeState)

    val sliderColors = SliderDefaults.colors(
        thumbColor = palette.sliderAccent,
        activeTrackColor = palette.sliderAccent,
        inactiveTrackColor = palette.sliderTrackInactive,
        activeTickColor = palette.cardBg,
        inactiveTickColor = palette.chipMutedText.copy(alpha = 0.45f),
    )
    val monoLabelStyle = MaterialTheme.typography.labelMedium.copy(
        fontFamily = FontFamily.Monospace,
        fontFeatureSettings = "tnum",
    )

    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 132.dp)
            .fillMaxWidth()
            .padding(horizontal = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 单一折叠控制面板（点击下方快捷胶囊平滑展开/收起，不遮挡取景画面）
        AnimatedVisibility(
            visible = activeDrawer != ActiveControlDrawer.NONE,
            enter = fadeIn(tween(180)) + expandVertically(expandFrom = Alignment.Bottom, animationSpec = tween(200)),
            exit = fadeOut(tween(140)) + shrinkVertically(shrinkTowards = Alignment.Bottom, animationSpec = tween(170)),
        ) {
            Surface(
                color = palette.cardBg,
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(1.dp, palette.cardBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
            ) {
                when (activeDrawer) {
                    ActiveControlDrawer.AR_FOV -> {
                        ArFovDrawerContent(
                            isEn = isEn,
                            arFovDeg = arFovDeg,
                            fovAutoCalibrated = fovAutoCalibrated,
                            palette = palette,
                            sliderColors = sliderColors,
                            onArFovChange = onArFovChange,
                            onArFovChangeFinished = onArFovChangeFinished,
                            onCollapse = { activeDrawer = ActiveControlDrawer.NONE },
                        )
                    }
                    ActiveControlDrawer.EXPOSURE -> {
                        ExposureProDrawerContent(
                            isEn = isEn,
                            proSupported = proSupported,
                            effectivePro = effectivePro,
                            proIso = proIso,
                            clampedProIso = clampedProIso,
                            proShutterIdx = proShutterIdx,
                            currentSec = currentSec,
                            isoRangeState = isoRangeState,
                            exposureRangeState = exposureRangeState,
                            evIndex = evIndex,
                            maxEvIndex = maxEvIndex,
                            palette = palette,
                            sliderColors = sliderColors,
                            onToggleProMode = onToggleProMode,
                            onProIsoChange = onProIsoChange,
                            onProShutterChange = onProShutterChange,
                            onEvChange = onEvChange,
                            onCollapse = { activeDrawer = ActiveControlDrawer.NONE },
                        )
                    }
                    ActiveControlDrawer.NONE -> Unit
                }
            }
        }

        // 收起状态下夜景 EV > 0 的轻量三脚架稳拍提示条（点按可展开曝光面板）
        AnimatedVisibility(
            visible = activeDrawer == ActiveControlDrawer.NONE && !effectivePro && evIndex > 0,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(120)),
        ) {
            Surface(
                onClick = { activeDrawer = ActiveControlDrawer.EXPOSURE },
                color = palette.warnBg,
                shape = RoundedCornerShape(50),
                border = BorderStroke(1.dp, palette.warnBorder),
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                Text(
                    text = if (isEn) {
                        "Steady phone / tripod recommended · Tap to adjust"
                    } else {
                        "夜景提亮已开启：建议靠稳或用三脚架 · 点此精调"
                    },
                    color = palette.warnText,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                )
            }
        }

        // 底部单手快捷参数胶囊栏（Quick-Chip Bar）
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showArFovSlider) {
                val fovOpen = activeDrawer == ActiveControlDrawer.AR_FOV
                val fovChipText = if (fovAutoCalibrated) {
                    String.format(
                        Locale.ROOT,
                        if (isEn) "AR %.0f° · Auto" else "AR %.0f° · 自动",
                        arFovDeg,
                    )
                } else {
                    String.format(Locale.ROOT, "AR %.0f°", arFovDeg)
                }
                QuickParamChip(
                    text = fovChipText,
                    expanded = fovOpen,
                    highlighted = fovOpen || fovAutoCalibrated,
                    palette = palette,
                    textStyle = monoLabelStyle,
                    onClick = {
                        activeDrawer = if (fovOpen) ActiveControlDrawer.NONE else ActiveControlDrawer.AR_FOV
                    },
                )
            }

            val expOpen = activeDrawer == ActiveControlDrawer.EXPOSURE
            val expChipText = if (effectivePro) {
                "PRO · ISO $clampedProIso · ${compactShutterText(currentSec)}"
            } else {
                when {
                    evIndex == 0 -> "EV ±0"
                    evIndex > 0 -> "EV +$evIndex"
                    else -> "EV $evIndex"
                }
            }
            QuickParamChip(
                text = expChipText,
                expanded = expOpen,
                highlighted = expOpen || effectivePro || evIndex != 0,
                palette = palette,
                textStyle = monoLabelStyle,
                onClick = {
                    activeDrawer = if (expOpen) ActiveControlDrawer.NONE else ActiveControlDrawer.EXPOSURE
                },
            )
        }
    }
}

@Composable
private fun QuickParamChip(
    text: String,
    expanded: Boolean,
    highlighted: Boolean,
    palette: ControlsPalette,
    textStyle: androidx.compose.ui.text.TextStyle,
    onClick: () -> Unit,
) {
    val bg = if (expanded) palette.chipActiveBg else palette.chipBg
    val border = when {
        expanded -> palette.chipActiveBorder
        highlighted -> palette.chipActiveBorder.copy(alpha = 0.65f)
        else -> palette.chipBorder
    }
    val contentCol = if (highlighted) palette.chipActiveText else palette.chipText

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = bg,
        border = BorderStroke(if (expanded) 1.25.dp else 1.dp, border),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        ) {
            Text(
                text = text,
                color = contentCol,
                style = textStyle,
                fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                contentDescription = null,
                tint = contentCol.copy(alpha = 0.85f),
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

@Composable
private fun ArFovDrawerContent(
    isEn: Boolean,
    arFovDeg: Float,
    fovAutoCalibrated: Boolean,
    palette: ControlsPalette,
    sliderColors: androidx.compose.material3.SliderColors,
    onArFovChange: (Float) -> Unit,
    onArFovChangeFinished: () -> Unit,
    onCollapse: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = if (isEn) "AR Field of View" else "AR 视场角",
                color = palette.chipText,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = String.format(Locale.ROOT, "%.1f°", arFovDeg),
                color = palette.chipActiveText,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontFamily = FontFamily.Monospace,
                    fontFeatureSettings = "tnum",
                ),
                fontWeight = FontWeight.Bold,
            )
            if (fovAutoCalibrated) {
                Spacer(Modifier.width(8.dp))
                Surface(
                    color = palette.badgeAutoBg,
                    shape = RoundedCornerShape(50),
                ) {
                    Text(
                        text = if (isEn) "Auto" else "自动标定",
                        color = palette.badgeAutoText,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            val isDefault62 = abs(arFovDeg - 62f) < 0.2f
            Surface(
                onClick = {
                    onArFovChange(62f)
                    onArFovChangeFinished()
                },
                shape = RoundedCornerShape(50),
                color = if (isDefault62) palette.chipBg else palette.chipActiveBg,
                border = BorderStroke(
                    1.dp,
                    if (isDefault62) palette.chipBorder else palette.chipActiveBorder.copy(alpha = 0.8f),
                ),
            ) {
                Text(
                    text = if (isEn) "Reset 62°" else "重置 62°",
                    color = if (isDefault62) palette.chipMutedText else palette.chipActiveText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.width(6.dp))
            CollapseCardButton(isEn = isEn, palette = palette, onClick = onCollapse)
        }

        Spacer(Modifier.height(4.dp))
        Slider(
            value = arFovDeg.coerceIn(40f, 90f),
            onValueChange = onArFovChange,
            onValueChangeFinished = onArFovChangeFinished,
            valueRange = 40f..90f,
            colors = sliderColors,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (isEn) "40° Tele" else "40° 中长焦",
                color = palette.chipMutedText,
                fontSize = 10.sp,
            )
            Text(
                text = if (isEn) "62° Main" else "62° 标准主摄",
                color = palette.chipMutedText,
                fontSize = 10.sp,
            )
            Text(
                text = if (isEn) "90° Ultra-wide" else "90° 超广角",
                color = palette.chipMutedText,
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun ExposureProDrawerContent(
    isEn: Boolean,
    proSupported: Boolean,
    effectivePro: Boolean,
    proIso: Int,
    clampedProIso: Int,
    proShutterIdx: Int,
    currentSec: Double,
    isoRangeState: IntRange?,
    exposureRangeState: LongRange?,
    evIndex: Int,
    maxEvIndex: Int,
    palette: ControlsPalette,
    sliderColors: androidx.compose.material3.SliderColors,
    onToggleProMode: () -> Unit,
    onProIsoChange: (Int) -> Unit,
    onProShutterChange: (Int) -> Unit,
    onEvChange: (Int) -> Unit,
    onCollapse: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
        // 顶部栏：自动 EV / Pro 手动 分段切换 + 收起按钮
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (proSupported) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(50))
                        .background(palette.segmentTrackBg)
                        .padding(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SegmentOption(
                        label = if (isEn) "Auto EV" else "自动 EV",
                        selected = !effectivePro,
                        palette = palette,
                        modifier = Modifier.weight(1f),
                        onClick = { if (effectivePro) onToggleProMode() },
                    )
                    SegmentOption(
                        label = if (isEn) "Pro Manual" else "Pro 手动",
                        selected = effectivePro,
                        palette = palette,
                        modifier = Modifier.weight(1f),
                        onClick = { if (!effectivePro) onToggleProMode() },
                    )
                }
            } else {
                Text(
                    text = if (isEn) "Auto Exposure (EV)" else "自动曝光补偿 (EV)",
                    color = palette.chipText,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.width(8.dp))
            CollapseCardButton(isEn = isEn, palette = palette, onClick = onCollapse)
        }

        Spacer(Modifier.height(10.dp))

        if (effectivePro) {
            val stops = CameraMath.shutterStopsSec
            // 3 个一键星空场景预设（安全夹紧到设备 ISO 与快门范围）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ASTRO_SCENE_PRESETS.forEach { preset ->
                    val presetIso = CameraMath.clampIso(preset.rawIso, isoRangeState)
                    val presetShutterIdx = nearestShutterStopIndex(preset.rawSec, exposureRangeState)
                    val presetSec = stops[presetShutterIdx.coerceIn(0, stops.lastIndex)]
                    val isSelected = clampedProIso == presetIso &&
                        proShutterIdx.coerceIn(0, stops.lastIndex) == presetShutterIdx
                    Surface(
                        onClick = {
                            onProIsoChange(presetIso)
                            onProShutterChange(presetShutterIdx)
                        },
                        shape = MaterialTheme.shapes.medium,
                        color = if (isSelected) palette.chipActiveBg else palette.segmentTrackBg,
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) palette.chipActiveBorder else palette.chipBorder,
                        ),
                        modifier = Modifier.weight(1f),
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 7.dp),
                        ) {
                            Text(
                                text = if (isEn) preset.enTitle else preset.zhTitle,
                                color = if (isSelected) palette.chipActiveText else palette.chipText,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "ISO $presetIso · ${compactShutterText(presetSec)}",
                                color = if (isSelected) palette.chipActiveText.copy(alpha = 0.9f) else palette.chipMutedText,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // ISO 滑块
            val minIso = isoRangeState?.first ?: 100
            val maxIso = (isoRangeState?.last ?: 6400).coerceAtLeast(minIso + 100)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = I18n.Pro.isoLabel,
                    color = palette.chipMutedText,
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    text = "ISO $clampedProIso",
                    color = palette.chipActiveText,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontFeatureSettings = "tnum",
                    ),
                    fontWeight = FontWeight.Bold,
                )
            }
            Slider(
                value = proIso.coerceIn(minIso, maxIso).toFloat(),
                onValueChange = {
                    val stepped = ((it.roundToInt() + 50) / 100) * 100
                    onProIsoChange(CameraMath.clampIso(stepped.coerceAtLeast(minIso), isoRangeState))
                },
                valueRange = minIso.toFloat()..maxIso.toFloat(),
                colors = sliderColors,
            )

            // 快门时长滑块
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = I18n.Pro.shutterLabel,
                    color = palette.chipMutedText,
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    text = CameraMath.shutterLabel(currentSec),
                    color = palette.chipActiveText,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontFeatureSettings = "tnum",
                    ),
                    fontWeight = FontWeight.Bold,
                )
            }
            Slider(
                value = proShutterIdx.coerceIn(0, stops.lastIndex).toFloat(),
                onValueChange = { onProShutterChange(it.roundToInt()) },
                valueRange = 0f..(stops.lastIndex.toFloat()),
                steps = (stops.size - 2).coerceAtLeast(0),
                colors = sliderColors,
            )

            if (currentSec > 1.0) {
                Surface(
                    color = palette.warnBg,
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(1.dp, palette.warnBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                ) {
                    Text(
                        text = I18n.Pro.longExposureWarn,
                        color = palette.warnText,
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        } else {
            // 自动 EV 曝光补偿面板
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (isEn) "Exposure Compensation" else "曝光补偿档位",
                    color = palette.chipMutedText,
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (evIndex == 0) "EV ±0" else I18n.Camera.evLabel(evIndex),
                    color = palette.chipActiveText,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontFeatureSettings = "tnum",
                    ),
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                if (evIndex != 0) {
                    Surface(
                        onClick = { onEvChange(0) },
                        shape = RoundedCornerShape(50),
                        color = palette.chipActiveBg,
                        border = BorderStroke(1.dp, palette.chipActiveBorder.copy(alpha = 0.8f)),
                    ) {
                        Text(
                            text = if (isEn) "Reset ±0" else "重置 ±0",
                            color = palette.chipActiveText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                        )
                    }
                }
            }

            if (maxEvIndex > 0) {
                Slider(
                    value = evIndex.coerceIn(-maxEvIndex, maxEvIndex).toFloat(),
                    onValueChange = { onEvChange(it.roundToInt()) },
                    valueRange = -maxEvIndex.toFloat()..maxEvIndex.toFloat(),
                    steps = (maxEvIndex * 2 - 1).coerceAtLeast(0),
                    colors = sliderColors,
                )
            } else {
                Text(
                    text = if (isEn) "EV adjustment unavailable on current lens" else "当前镜头不支持 EV 档位调节",
                    color = palette.chipMutedText,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            if (evIndex > 0) {
                Surface(
                    color = palette.warnBg,
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.dp, palette.warnBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                ) {
                    Text(
                        text = I18n.Camera.nightGuidance,
                        color = palette.warnText,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SegmentOption(
    label: String,
    selected: Boolean,
    palette: ControlsPalette,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) palette.segmentSelectedBg else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 10.dp),
    ) {
        Text(
            text = label,
            color = if (selected) palette.segmentSelectedText else palette.chipMutedText,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CollapseCardButton(
    isEn: Boolean,
    palette: ControlsPalette,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = palette.chipBg,
        border = BorderStroke(1.dp, palette.chipBorder),
        modifier = Modifier.size(30.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = if (isEn) "Collapse panel" else "收起面板",
                tint = palette.chipText,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
internal fun BoxScope.CameraShutterButton(
    isCapturing: Boolean,
    onTakePhoto: () -> Unit,
) {
    val nightRed = ThemeState.mode == AppThemeMode.NIGHT_RED
    val shutterScale by animateFloatAsState(
        targetValue = if (isCapturing) 0.86f else 1.0f,
        animationSpec = spring(
            stiffness = Spring.StiffnessMedium,
            dampingRatio = Spring.DampingRatioMediumBouncy,
        ),
        label = "shutterScale",
    )

    val pulseColor = if (nightRed) Color(0xFFFF6E40) else Color(0xFFFFC24B)
    val outerBg = if (nightRed) Color(0xD9240707) else Color(0x4D080D1A)
    val outerRing = if (nightRed) Color(0xFFE8524A) else Color(0xFFF4F7FF)
    val innerFill = when {
        nightRed && isCapturing -> Color(0xFFFF8A65).copy(alpha = 0.72f)
        nightRed -> Color(0xFF9E2824)
        isCapturing -> Color(0xFFFFC24B).copy(alpha = 0.85f)
        else -> Color(0xFFF4F7FF)
    }
    val innerBorder = if (nightRed) Color(0x66FFB74D) else Color(0x26000000)

    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 46.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (isCapturing) {
            val infinitePulse = rememberInfiniteTransition(label = "shutterRing")
            val ringAlpha by infinitePulse.animateFloat(
                initialValue = 0.35f,
                targetValue = 0.90f,
                animationSpec = infiniteRepeatable(
                    animation = tween(700, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "ringAlpha",
            )
            Surface(
                shape = CircleShape,
                color = Color.Transparent,
                modifier = Modifier.size(86.dp),
                border = BorderStroke(2.dp, pulseColor.copy(alpha = ringAlpha)),
            ) {}
        }
        Surface(
            onClick = { if (!isCapturing) onTakePhoto() },
            shape = CircleShape,
            color = outerBg,
            border = BorderStroke(3.dp, outerRing),
            modifier = Modifier
                .size(72.dp)
                .scale(shutterScale),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Surface(
                    shape = CircleShape,
                    color = innerFill,
                    modifier = Modifier.size(56.dp),
                    border = BorderStroke(1.5.dp, innerBorder),
                ) {}
            }
        }
    }
}
