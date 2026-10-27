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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.starcam.astro.astro.TonightSky
import com.starcam.astro.ui.I18n
import java.util.Locale

/**
 * 今日天象概览卡片（Material 3 风格 + 弹性微动效）：展示当晚月相、照度、观测建议与主要行星视窗。
 */
@Composable
fun TonightSkyCard(
    modifier: Modifier = Modifier,
    summary: TonightSky.TonightSkySummary = remember { TonightSky.compute() },
) {
    var expanded by remember { mutableStateOf(false) }
    val isEn = I18n.isEn

    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "chevronRotation",
    )

    val elevation by animateDpAsState(
        targetValue = if (expanded) 3.dp else 1.dp,
        animationSpec = tween(250),
        label = "elevation",
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = elevation,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            // 顶栏：标题 + 月相速览 + 旋转箭头
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "🌌 ${I18n.Home.tonightSky}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${summary.moon.symbol} ${if (isEn) summary.moon.phaseNameEn else summary.moon.phaseNameZh} · ${summary.moon.illuminationPct}%",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
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

            Spacer(Modifier.height(8.dp))

            // 观星指引建议
            Text(
                text = "${summary.recommendationIcon} ${if (isEn) summary.recommendationEn else summary.recommendationZh}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 可见行星胶囊标签（横向滚动，单行不换行，杜绝小屏幕或英文模式下末尾挤压变形）
            if (summary.visiblePlanets.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (planet in summary.visiblePlanets) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ) {
                            Text(
                                text = "${if (isEn) planet.nameEn else planet.nameZh} ${String.format(Locale.ROOT, "%+.1f", planet.magnitude)}m",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }

            // 展开详情：月龄参数与各行星观测视窗（平滑弹性展开/收起）
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(
                    animationSpec = spring(
                        stiffness = Spring.StiffnessMediumLow,
                        dampingRatio = Spring.DampingRatioLowBouncy,
                    ),
                ) + fadeIn(tween(250, easing = FastOutSlowInEasing)),
                exit = shrinkVertically(
                    animationSpec = tween(220, easing = FastOutLinearInEasing),
                ) + fadeOut(tween(180)),
            ) {
                Column(modifier = Modifier.padding(top = 14.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(Modifier.height(12.dp))

                    Text(
                        text = "🌙 ${if (isEn) summary.moon.phaseNameEn else summary.moon.phaseNameZh}${if (isEn) ": " else "："}" +
                            "${I18n.Home.moonAge} ${String.format(Locale.ROOT, "%.1f", summary.moon.moonAgeDays)} ${if (isEn) "days" else "天"}" +
                            " · ${I18n.Home.moonIllumination} ${summary.moon.illuminationPct}%" +
                            " · ${if (summary.moon.isWaxing) (if (isEn) "Waxing" else "渐盈") else (if (isEn) "Waning" else "渐亏")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    Spacer(Modifier.height(8.dp))

                    for (p in summary.visiblePlanets) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "🪐 ${if (isEn) p.nameEn else p.nameZh}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.widthIn(min = if (isEn) 84.dp else 64.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "${if (isEn) p.windowEn else p.windowZh} (${String.format(Locale.ROOT, "%.0f°", p.elongationDeg)})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.End,
                            )
                        }
                    }
                }
            }
        }
    }
}
