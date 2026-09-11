package com.starcam.astro.ui.camera

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.starcam.astro.astro.MessierCatalog
import com.starcam.astro.astro.MessierObject
import com.starcam.astro.astro.ObjectInfo
import com.starcam.astro.astro.StarCatalogData
import com.starcam.astro.astro.StarEntry
import com.starcam.astro.astro.StarNames
import com.starcam.astro.ui.theme.LocaleState

/**
 * §0.54b AR 找星导航目标（梅西耶天体或亮星）。
 */
sealed interface ArTarget {
    data class Messier(val obj: MessierObject) : ArTarget
    data class Star(val entry: StarEntry) : ArTarget

    val raDeg: Double
        get() = when (this) {
            is Messier -> obj.ra
            is Star -> entry.ra
        }
    val decDeg: Double
        get() = when (this) {
            is Messier -> obj.dec
            is Star -> entry.dec
        }
    fun label(isEnglish: Boolean): String = when (this) {
        is Messier -> obj.label(isEnglish)
        is Star -> {
            val n = StarNames.displayName(entry.hip, entry.name, isEnglish)
            if (n.isNotEmpty()) n else "HIP ${entry.hip}"
        }
    }
}

/** §0.54b 找星目标选择弹窗（梅西耶 44 + 科普亮星，双语） */
@Composable
fun ArTargetPickerDialog(
    onPick: (ArTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    val isEn = LocaleState.isEnglish
    val messierList = MessierCatalog.byNumber.values.sortedBy { it.number }
    val starHips = ObjectInfo.stars.keys.sorted()
    val starEntries = starHips.mapNotNull { hip ->
        val idx = StarCatalogData.indexOfHip(hip)
        if (idx >= 0) StarCatalogData.stars[idx] else null
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = true),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(vertical = 16.dp)) {
                Text(
                    if (isEn) "🎯 Find a target" else "🎯 选择找星目标",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                LazyColumn(Modifier.height(420.dp)) {
                    item {
                        SectionHeader(if (isEn) "Messier Objects" else "梅西耶天体")
                    }
                    items(messierList, key = { "M${it.number}" }) { obj ->
                        TargetRow(
                            text = obj.label(isEn),
                            color = MessierCatalog.typeColor(obj.type),
                            onClick = { onPick(ArTarget.Messier(obj)) },
                        )
                    }
                    item {
                        SectionHeader(if (isEn) "Bright Stars" else "亮星")
                    }
                    items(starEntries, key = { "H${it.hip}" }) { entry ->
                        TargetRow(
                            text = StarNames.displayName(entry.hip, entry.name, isEn),
                            color = 0xFFFFE082.toInt(),
                            onClick = { onPick(ArTarget.Star(entry)) },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (isEn) "✕  Close" else "✕  关闭",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
    )
}

@Composable
private fun TargetRow(text: String, color: Int, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 9.dp),
    ) {
        Text("✦ ", color = Color(color), fontSize = 15.sp)
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
