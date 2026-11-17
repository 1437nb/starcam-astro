package com.starcam.astro.ui.result

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.starcam.astro.astro.SolveEngine
import com.starcam.astro.ui.I18n
import com.starcam.astro.ui.theme.LocaleState
import java.util.Locale
import kotlin.math.abs

/** 识别信息面板 */
@Composable
internal fun InfoPanel(state: ResultUiState.Success, modifier: Modifier = Modifier) {
    val solve = state.solve
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            val isEn = LocaleState.isEnglish
            Text(
                when {
                    state.isDemo -> I18n.Result.titleDemo
                    state.engine == SolveEngine.ASTROMETRY_NATIVE -> I18n.Result.titleNative
                    state.engine == SolveEngine.LOCAL_MATCHER -> I18n.Result.titleLocal
                    state.engine == SolveEngine.ONLINE_NOVA -> I18n.Result.titleOnline
                    else -> I18n.Result.titleDefault
                },
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
            )
            if (state.engine != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    state.engineDetail ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // §0.59：照片没有 EXIF GPS，识别与天体标注都用的是设备当前定位——必须如实告知
            if (state.locationIsFallback) {
                Spacer(Modifier.height(4.dp))
                Text(
                    I18n.Result.locationFallback,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(10.dp))
            state.engine?.let {
                InfoRow(I18n.Result.labelEngine, it.label(isEn))
            }
            InfoRow(I18n.Result.labelCenter, "RA ${formatRa(solve.raDeg)}    Dec ${formatDec(solve.decDeg)}")
            InfoRow(I18n.Result.labelFov, "${"%.1f".format(Locale.ROOT, solve.fieldWidthDeg)}° × ${"%.1f".format(Locale.ROOT, solve.fieldHeightDeg)}°")
            // §0.90 识别耗时（演示模式无耗时可言，为空则不显示该行）
            I18n.Result.formatSolveTime(state.elapsedMs)
                .takeIf { it.isNotEmpty() }
                ?.let { InfoRow(I18n.Result.labelSolveTime, it) }
            InfoRow(I18n.Result.labelPixScale, "${"%.1f".format(Locale.ROOT, solve.pixScaleArcsec)}${if (isEn) "″/px" else "″/像素"}")
            InfoRow(
                I18n.Result.labelOrientation,
                "${"%.1f".format(Locale.ROOT, solve.orientationDeg)}°${if (isEn) " (parity ${solve.parity})" else "（parity ${solve.parity}）"}",
            )
            solve.subId?.let { InfoRow(I18n.Result.labelTaskId, "#$it") }
            Spacer(Modifier.height(8.dp))
            if (state.labels.isNotEmpty()) {
                Text(I18n.Result.constellationsInField, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                ) {
                    state.labels.forEach { label ->
                        AssistChip(onClick = {}, label = { Text(label.text) })
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatRa(raDeg: Double): String {
    var v = ((raDeg % 360.0) + 360.0) % 360.0
    val hh = (v / 15.0).toInt()
    v = (v - hh * 15.0) * 4.0 // 余量转为分钟（度→分钟：*4）
    val mm = v.toInt()
    val ss = ((v - mm) * 60.0).toInt()
    return "%02dh %02dm %02ds".format(hh, mm, ss)
}

private fun formatDec(decDeg: Double): String {
    val sign = if (decDeg < 0) "-" else "+"
    val a = abs(decDeg)
    val d = a.toInt()
    val m = ((a - d) * 60.0).toInt()
    val s = (((a - d) * 60.0 - m) * 60.0).toInt()
    return "%s%02d° %02d′ %02d″".format(sign, d, m, s)
}
