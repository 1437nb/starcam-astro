package com.starcam.astro.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.starcam.astro.astro.SolarSystemEphemeris.SolarBody
import com.starcam.astro.astro.TonightSky
import com.starcam.astro.ui.I18n
import com.starcam.astro.ui.theme.AppThemeMode
import com.starcam.astro.ui.theme.ThemeState
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 今日天象概览卡片（§0.143 Material 3 精修 + Canvas 真实月相晨昏线圆盘）：
 * 展示当晚月相几何圆盘、照度、观测建议与主要行星视窗。
 */
@Composable
fun TonightSkyCard(
    modifier: Modifier = Modifier,
    summary: TonightSky.TonightSkySummary = remember { TonightSky.compute() },
) {
    var expanded by remember { mutableStateOf(false) }
    val isEn = I18n.isEn
    val themeMode = ThemeState.mode
    val isNightRed = themeMode == AppThemeMode.NIGHT_RED
    val isPureWhite = themeMode == AppThemeMode.PURE_WHITE

    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(280, easing = FastOutSlowInEasing),
        label = "chevronRotation",
    )

    val elevation by animateDpAsState(
        targetValue = if (expanded) 3.dp else 1.dp,
        animationSpec = tween(240),
        label = "elevation",
    )

    Surface(
        onClick = { expanded = !expanded },
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = elevation,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            // 顶栏：标题 + 行星计数摘要 + 展开箭头
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = I18n.Home.tonightSky,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (expanded) {
                            if (isEn) "Collapse" else "收起详情"
                        } else {
                            if (isEn) "Details" else "展开星历"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier
                            .size(20.dp)
                            .rotate(chevronRotation),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // 核心月相行：28dp Canvas 月相晨昏线圆盘 + 月相名/照度 + 当晚观星建议
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MoonPhaseDisc(
                    illuminationPct = summary.moon.illuminationPct,
                    isWaxing = summary.moon.isWaxing,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "${if (isEn) summary.moon.phaseNameEn else summary.moon.phaseNameZh} · ${summary.moon.illuminationPct}%",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Text(
                                text = if (summary.moon.isWaxing) {
                                    if (isEn) "Waxing" else "渐盈"
                                } else {
                                    if (isEn) "Waning" else "渐亏"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = if (isEn) summary.recommendationEn else summary.recommendationZh,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 可见行星胶囊芯片（横向滚动，带行星色点与视星等）
            if (summary.visiblePlanets.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (planet in summary.visiblePlanets) {
                        val dotColor = planetAccentColor(planet.body, isNightRed, isPureWhite)
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .background(dotColor, CircleShape),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = if (isEn) planet.nameEn else planet.nameZh,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    text = "${String.format(Locale.ROOT, "%+.1f", planet.magnitude)}${if (isEn) "m" else "等"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                    }
                }
            }

            // 展开详情：月龄参数面板与各行星观测窗口行
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(
                    animationSpec = spring(
                        stiffness = Spring.StiffnessMediumLow,
                        dampingRatio = Spring.DampingRatioLowBouncy,
                    ),
                ) + fadeIn(tween(240, easing = FastOutSlowInEasing)),
                exit = shrinkVertically(
                    animationSpec = tween(220, easing = FastOutLinearInEasing),
                ) + fadeOut(tween(180)),
            ) {
                Column(modifier = Modifier.padding(top = 14.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(12.dp))

                    // 月龄与照度指标条
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "${I18n.Home.moonAge} ${String.format(Locale.ROOT, "%.1f", summary.moon.moonAgeDays)} ${if (isEn) "days" else "天"}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "${I18n.Home.moonIllumination} ${summary.moon.illuminationPct}% · " +
                                    if (summary.moon.isWaxing) {
                                        if (isEn) "Waxing" else "渐盈"
                                    } else {
                                        if (isEn) "Waning" else "渐亏"
                                    },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    if (summary.visiblePlanets.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = I18n.Home.visiblePlanets,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (p in summary.visiblePlanets) {
                                val dotColor = planetAccentColor(p.body, isNightRed, isPureWhite)
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .background(dotColor, CircleShape),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                text = if (isEn) p.nameEn else p.nameZh,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSurface,
                                            )
                                            Text(
                                                text = "${String.format(Locale.ROOT, "%+.1f", p.magnitude)}${if (isEn) " mag" else " 等"} · " +
                                                    "${if (isEn) "Elong." else "距角"} ${String.format(Locale.ROOT, "%.0f°", p.elongationDeg)}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            text = if (isEn) p.windowEn else p.windowZh,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.weight(1f),
                                            textAlign = TextAlign.End,
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = if (isEn) {
                                "Bright planets are currently near solar conjunction and below elongation threshold."
                            } else {
                                "近期主要亮行星距太阳较近，暂不在夜间理想观测距角内。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 真实球面投影月相圆盘（Canvas 几何绘制）：
 * - 按 `illuminationPct` 与 `isWaxing` 解算球面晨昏半椭圆曲线 `x_term = sign * (1 - 2f) * R * cos(θ)`；
 * - 夜视红模式下使用暗红地照与暖琥珀月面，不引入任何刺眼蓝白光；
 * - 纯白/深空蓝/纯黑模式下自适应高对比度银金月面与月海纹理暗影。
 */
@Composable
private fun MoonPhaseDisc(
    illuminationPct: Int,
    isWaxing: Boolean,
    modifier: Modifier = Modifier,
) {
    val mode = ThemeState.mode
    val isNightRed = mode == AppThemeMode.NIGHT_RED
    val isPureWhite = mode == AppThemeMode.PURE_WHITE
    val litPath = remember { Path() }

    val earthshineColor = when (mode) {
        AppThemeMode.NIGHT_RED -> Color(0xFF1F0808)
        AppThemeMode.PURE_WHITE -> Color(0xFF283248)
        AppThemeMode.PURE_BLACK -> Color(0xFF1C1D24)
        AppThemeMode.DEEP_SKY -> Color(0xFF182242)
    }
    val litColors = when {
        isNightRed -> listOf(Color(0xFFFFCC80), Color(0xFFFF9E40), Color(0xFFE65100))
        isPureWhite -> listOf(Color(0xFFFFF6D1), Color(0xFFF2CC68), Color(0xFFD9A938))
        else -> listOf(Color(0xFFFFFBEB), Color(0xFFF4DF99), Color(0xFFD7B657))
    }
    val mareTint = if (isNightRed) Color(0x308D2200) else Color(0x2452472C)
    val rimColor = when (mode) {
        AppThemeMode.NIGHT_RED -> Color(0x88FF8A80)
        AppThemeMode.PURE_WHITE -> Color(0x88535F70)
        AppThemeMode.PURE_BLACK -> Color(0x70A6A6A6)
        AppThemeMode.DEEP_SKY -> Color(0x758CA0D6)
    }

    Canvas(modifier = modifier) {
        val strokePx = 1.1.dp.toPx()
        val r = (size.minDimension - strokePx) * 0.5f
        if (r <= 0f) return@Canvas
        val center = Offset(size.width * 0.5f, size.height * 0.5f)
        val f = (illuminationPct / 100f).coerceIn(0f, 1f)

        // 1. 暗面基底（地球反照 Earthshine）
        drawCircle(
            color = earthshineColor,
            radius = r,
            center = center,
        )

        // 2. 受光月面与晨昏线（Terminator）几何裁剪
        if (f > 0.005f) {
            litPath.reset()
            if (f >= 0.995f) {
                litPath.addOval(Rect(center.x - r, center.y - r, center.x + r, center.y + r))
            } else {
                val sign = if (isWaxing) 1f else -1f
                val termFactor = 1f - 2f * f
                val steps = 36
                for (i in 0..steps) {
                    val theta = (-0.5 + i.toDouble() / steps) * PI
                    val x = center.x + sign * r * cos(theta).toFloat()
                    val y = center.y + r * sin(theta).toFloat()
                    if (i == 0) litPath.moveTo(x, y) else litPath.lineTo(x, y)
                }
                for (i in steps downTo 0) {
                    val theta = (-0.5 + i.toDouble() / steps) * PI
                    val x = center.x + sign * termFactor * r * cos(theta).toFloat()
                    val y = center.y + r * sin(theta).toFloat()
                    litPath.lineTo(x, y)
                }
                litPath.close()
            }

            clipPath(litPath) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = litColors,
                        center = Offset(center.x - r * 0.22f, center.y - r * 0.24f),
                        radius = r * 1.35f,
                    ),
                    radius = r,
                    center = center,
                )
                // 细腻月海浅影（仅在受光面显现，被晨昏线自然截断）
                drawCircle(
                    color = mareTint,
                    radius = r * 0.28f,
                    center = Offset(center.x - r * 0.24f, center.y - r * 0.18f),
                )
                drawCircle(
                    color = mareTint,
                    radius = r * 0.22f,
                    center = Offset(center.x + r * 0.22f, center.y - r * 0.08f),
                )
                drawCircle(
                    color = mareTint,
                    radius = r * 0.18f,
                    center = Offset(center.x + r * 0.06f, center.y + r * 0.28f),
                )
            }
        }

        // 3. 外缘精细轮廓环（保证新月与浅色主题下边界依然清晰）
        drawCircle(
            color = rimColor,
            radius = r,
            center = center,
            style = Stroke(width = strokePx),
        )
    }
}

private fun planetAccentColor(
    body: SolarBody,
    isNightRed: Boolean,
    isPureWhite: Boolean,
): Color {
    if (isNightRed) {
        return when (body) {
            SolarBody.VENUS -> Color(0xFFFFCC80)
            SolarBody.JUPITER -> Color(0xFFFFAB91)
            SolarBody.SATURN -> Color(0xFFFFB74D)
            SolarBody.MARS -> Color(0xFFFF5252)
            SolarBody.MERCURY -> Color(0xFFE57373)
            else -> Color(0xFFFF8A80)
        }
    }
    return when (body) {
        SolarBody.VENUS -> if (isPureWhite) Color(0xFFD49B1A) else Color(0xFFFFE599)
        SolarBody.JUPITER -> if (isPureWhite) Color(0xFFC27832) else Color(0xFFF4C28C)
        SolarBody.SATURN -> if (isPureWhite) Color(0xFFB38F2E) else Color(0xFFE6CF8B)
        SolarBody.MARS -> if (isPureWhite) Color(0xFFC94A38) else Color(0xFFFF8269)
        SolarBody.MERCURY -> if (isPureWhite) Color(0xFF5F6B7C) else Color(0xFFB8C4D8)
        else -> if (isPureWhite) Color(0xFF005AC1) else Color(0xFFE3C567)
    }
}
