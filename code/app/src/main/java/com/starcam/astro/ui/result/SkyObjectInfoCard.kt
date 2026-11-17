package com.starcam.astro.ui.result

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import com.starcam.astro.astro.Constellations
import com.starcam.astro.astro.MessierCatalog
import com.starcam.astro.astro.MessierObject
import com.starcam.astro.astro.ObjectInfo
import com.starcam.astro.astro.SolarSystemCatalog
import com.starcam.astro.astro.SolarSystemEphemeris
import com.starcam.astro.astro.StarEntry
import com.starcam.astro.astro.StarNames
import com.starcam.astro.ui.I18n
import com.starcam.astro.ui.theme.AppThemeMode
import com.starcam.astro.ui.theme.LocaleState
import com.starcam.astro.ui.theme.ThemeState

/** §0.47：被点中的天体引用（Messier 或亮星） */
sealed interface SkyObjectRef {
    data class MessierRef(val obj: MessierObject) : SkyObjectRef
    data class StarRef(val entry: StarEntry) : SkyObjectRef
    /** §0.58 太阳系天体（月亮/行星/太阳），携带该时刻的观测参数供卡片展示 */
    data class SolarRef(val pos: SolarSystemEphemeris.SolarPosition) : SkyObjectRef
}

/** §0.47：天体科普卡片（照片底部浮层，中英双语；Messier 与亮星两种；放大查看器亦复用） */
@Composable
internal fun ObjectInfoCard(ref: SkyObjectRef, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val isEn = LocaleState.isEnglish
    val night = ThemeState.mode == AppThemeMode.NIGHT_RED
    Surface(
        modifier = modifier,
        color = if (night) Color(0xF01A0505) else Color(0xF00D1B2A),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            when (ref) {
                is SkyObjectRef.MessierRef -> {
                    val obj = ref.obj
                    val info = ObjectInfo.messier[obj.number]
                    val typeColor = Color(MessierCatalog.typeColor(obj.type))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("✦ ", color = typeColor, fontSize = 18.sp)
                        Text(
                            obj.label(isEn),
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "✕",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 16.sp,
                            modifier = Modifier.clickable(onClick = onDismiss).padding(4.dp),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        buildString {
                            append(I18n.InfoCard.typeLabel); append(": ")
                            append(ObjectInfo.typeLabel(obj.type, isEn))
                            info?.let {
                                append("   ·   ")
                                append(I18n.InfoCard.magLabel); append(": ")
                                append("${"%.1f".format(it.mag)}${if (isEn) "m" else "等"}")
                            }
                        },
                        color = typeColor,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    info?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            buildString {
                                append(I18n.InfoCard.distLabel); append(": ")
                                append(if (isEn) it.distLyEn else it.distLyZh)
                            },
                            color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (isEn) it.descEn else it.descZh,
                            color = Color.White.copy(alpha = 0.92f),
                            style = MaterialTheme.typography.bodySmall,
                            lineHeight = 19.sp,
                        )
                    }
                }
                is SkyObjectRef.StarRef -> {
                    val entry = ref.entry
                    val displayName = StarNames.displayName(entry.hip, entry.name, isEn)
                    val conName = Constellations.name(entry.con, isEn)
                    val info = ObjectInfo.stars[entry.hip]
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("★ ", color = Color(0xFFFFE082), fontSize = 18.sp)
                        Text(
                            if (displayName.isNotEmpty()) displayName else "HIP ${entry.hip}",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "✕",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 16.sp,
                            modifier = Modifier.clickable(onClick = onDismiss).padding(4.dp),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        buildString {
                            append(I18n.InfoCard.magLabel); append(": ")
                            append("${"%.1f".format(entry.mag)}${if (isEn) "m" else "等"}")
                            append("   ·   ")
                            append(I18n.InfoCard.constellationLabel); append(": ")
                            append(conName)
                        },
                        color = Color(0xFFFFE082),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                    info?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            buildString {
                                append(I18n.InfoCard.distLabel); append(": ")
                                append(if (isEn) it.distLyEn else it.distLyZh)
                            },
                            color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (isEn) it.descEn else it.descZh,
                            color = Color.White.copy(alpha = 0.92f),
                            style = MaterialTheme.typography.bodySmall,
                            lineHeight = 19.sp,
                        )
                    }
                }
                is SkyObjectRef.SolarRef -> {
                    val pos = ref.pos
                    val body = pos.body
                    val bodyColor = Color(SolarSystemCatalog.color(body))
                    val info = SolarSystemCatalog.info[body]
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("● ", color = bodyColor, fontSize = 16.sp)
                        Text(
                            SolarSystemCatalog.name(body, isEn),
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "✕",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 16.sp,
                            modifier = Modifier.clickable(onClick = onDismiss).padding(4.dp),
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        SolarSystemCatalog.typeLabel(body, isEn),
                        color = bodyColor,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        buildString {
                            append(I18n.InfoCard.magLabel); append(": ")
                            append("${"%.1f".format(pos.magnitude)}${if (isEn) "m" else "等"}")
                            append("   ·   ")
                            append(I18n.InfoCard.elongationLabel); append(": ")
                            append("${"%.0f".format(pos.elongationDeg)}°")
                            // 太阳无相位概念，仅日月之外显示被照亮比例
                            if (body != SolarSystemEphemeris.SolarBody.SUN) {
                                append("   ·   ")
                                append(I18n.InfoCard.phaseLabel); append(": ")
                                append("${"%.0f".format(pos.phase * 100)}%")
                            }
                        },
                        color = bodyColor,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                    info?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            buildString {
                                append(I18n.InfoCard.distLabel); append(": ")
                                append(if (isEn) it.distEn else it.distZh)
                                append("   ·   ")
                                append(I18n.InfoCard.diameterLabel); append(": ")
                                append("${"%.1f".format(pos.angularDiameterDeg * 60)}′")
                            },
                            color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (isEn) it.descEn else it.descZh,
                            color = Color.White.copy(alpha = 0.92f),
                            style = MaterialTheme.typography.bodySmall,
                            lineHeight = 19.sp,
                        )
                    }
                }
            }
        }
    }
}
