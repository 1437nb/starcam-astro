package com.starcam.astro.ui.camera

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.starcam.astro.astro.Constellations
import com.starcam.astro.astro.MessierCatalog
import com.starcam.astro.astro.MessierObject
import com.starcam.astro.astro.ObjectInfo
import com.starcam.astro.astro.SolarSystemCatalog
import com.starcam.astro.astro.SolarSystemEphemeris
import com.starcam.astro.astro.StarCatalogData
import com.starcam.astro.astro.StarEntry
import com.starcam.astro.astro.StarNames
import com.starcam.astro.astro.StarNamesEn
import com.starcam.astro.ui.theme.AppThemeMode
import com.starcam.astro.ui.theme.LocaleState
import com.starcam.astro.ui.theme.ThemeState
import java.util.Locale

/**
 * §0.54b / §0.143 AR 找星导航目标（太阳系天体、梅西耶深空天体或亮星）。
 * 标签在构造期预生成，保证 AR 60fps 渲染路径零每帧字符串分配。
 */
sealed interface ArTarget {
    data class Solar(val pos: SolarSystemEphemeris.SolarPosition) : ArTarget {
        internal val labelZh: String = SolarSystemCatalog.name(pos.body, isEnglish = false)
        internal val labelEn: String = SolarSystemCatalog.name(pos.body, isEnglish = true)
    }

    data class Messier(val obj: MessierObject) : ArTarget

    data class Star(val entry: StarEntry) : ArTarget {
        internal val labelZh: String =
            StarNames.displayName(entry.hip, entry.name, isEnglish = false).ifEmpty { "HIP ${entry.hip}" }
        internal val labelEn: String =
            StarNames.displayName(entry.hip, entry.name, isEnglish = true).ifEmpty { "HIP ${entry.hip}" }
    }

    val raDeg: Double
        get() = when (this) {
            is Solar -> pos.raDeg
            is Messier -> obj.ra
            is Star -> entry.ra
        }

    val decDeg: Double
        get() = when (this) {
            is Solar -> pos.decDeg
            is Messier -> obj.dec
            is Star -> entry.dec
        }

    fun label(isEnglish: Boolean): String = when (this) {
        is Solar -> if (isEnglish) labelEn else labelZh
        is Messier -> obj.label(isEnglish)
        is Star -> if (isEnglish) labelEn else labelZh
    }
}

private enum class TargetCategory {
    ALL,
    SOLAR,
    MESSIER,
    STAR,
}

private data class PickerItem(
    val key: String,
    val category: TargetCategory,
    val target: ArTarget,
    val title: String,
    val subtitle: String,
    val glyph: String,
    val rawColorArgb: Int,
    val searchBlob: String,
)

/** 梅西耶天体 → IAU 所属星座缩写（用于星座检索与副标题展示） */
private val MESSIER_CONSTELLATIONS: Map<Int, String> = mapOf(
    3 to "CVn", 4 to "Sco", 5 to "Ser", 6 to "Sco", 7 to "Sco",
    8 to "Sgr", 11 to "Sct", 13 to "Her", 15 to "Peg", 16 to "Ser",
    17 to "Sgr", 20 to "Sgr", 27 to "Vul", 31 to "And", 32 to "And",
    33 to "Tri", 35 to "Gem", 36 to "Aur", 37 to "Aur", 38 to "Aur",
    41 to "CMa", 42 to "Ori", 43 to "Ori", 44 to "Cnc", 45 to "Tau",
    51 to "CVn", 53 to "Com", 57 to "Lyr", 63 to "CVn", 64 to "Com",
    65 to "Leo", 66 to "Leo", 67 to "Cnc", 71 to "Sge", 80 to "Sco",
    81 to "UMa", 82 to "UMa", 92 to "Her", 96 to "Leo", 97 to "UMa",
    101 to "UMa", 104 to "Vir", 108 to "UMa", 109 to "UMa",
)

/** 亮星常用俗名（补充传统星官名，支持搜索如“北极星”“牛郎”“天狼星”“北斗”） */
private val POPULAR_STAR_ALIASES_ZH: Map<Int, String> = mapOf(
    32349 to "天狼星",
    11767 to "北极星",
    91262 to "织女星 夏夜大三角",
    97649 to "牛郎星 夏夜大三角",
    100453 to "天津四 夏夜大三角",
    69673 to "大角星",
    71683 to "南门二 比邻星",
    27989 to "参宿四 冬季大三角",
    37279 to "南河三 冬季大三角",
    54061 to "天枢 北斗七星",
    53910 to "天璇 北斗七星",
    58001 to "天玑 北斗七星",
    59774 to "天权 北斗七星",
    62956 to "玉衡 北斗七星",
    65378 to "开阳 北斗七星",
    67301 to "摇光 北斗七星",
)

private val SOLAR_DISPLAY_ORDER: List<SolarSystemEphemeris.SolarBody> = listOf(
    SolarSystemEphemeris.SolarBody.MOON,
    SolarSystemEphemeris.SolarBody.VENUS,
    SolarSystemEphemeris.SolarBody.JUPITER,
    SolarSystemEphemeris.SolarBody.SATURN,
    SolarSystemEphemeris.SolarBody.MARS,
    SolarSystemEphemeris.SolarBody.MERCURY,
    SolarSystemEphemeris.SolarBody.URANUS,
    SolarSystemEphemeris.SolarBody.NEPTUNE,
    SolarSystemEphemeris.SolarBody.SUN,
)

/** §0.54b / §0.143 可搜索分类找星目标选择弹窗（太阳系 + 梅西耶深空 + 亮星，中英双语） */
@Composable
fun ArTargetPickerDialog(
    onPick: (ArTarget) -> Unit,
    onDismiss: () -> Unit,
    latDeg: Double? = null,
    lonDeg: Double? = null,
) {
    val isEn = LocaleState.isEnglish
    val nightRed = ThemeState.mode == AppThemeMode.NIGHT_RED
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(TargetCategory.ALL) }

    val solarPositions = remember(latDeg, lonDeg) {
        val nowSec = System.currentTimeMillis() / 1000L
        val raw = if (latDeg != null && lonDeg != null) {
            SolarSystemEphemeris.cachedTopocentric(nowSec, latDeg, lonDeg)
        } else {
            SolarSystemEphemeris.geocentricPositions(SolarSystemEphemeris.unixSecondsToJd(nowSec))
        }
        val byBody = raw.associateBy { it.body }
        SOLAR_DISPLAY_ORDER.mapNotNull { byBody[it] }
    }

    val allItems = remember(isEn, solarPositions) {
        val list = ArrayList<PickerItem>(160)

        // 1. 太阳系天体
        for (pos in solarPositions) {
            val body = pos.body
            val zhName = SolarSystemCatalog.name(body, false)
            val enName = SolarSystemCatalog.name(body, true)
            val typeLabel = SolarSystemCatalog.typeLabel(body, isEn)
            val magStr = String.format(Locale.ROOT, "%+.1f%s", pos.magnitude, if (isEn) "m" else "等")
            val phasePart = if (body != SolarSystemEphemeris.SolarBody.SUN) {
                val pct = (pos.phase * 100.0).toInt()
                if (isEn) " · Illum $pct%" else " · 照亮 $pct%"
            } else {
                ""
            }
            val alias = when (body) {
                SolarSystemEphemeris.SolarBody.MOON -> "月球 月亮 moon lunar"
                SolarSystemEphemeris.SolarBody.SUN -> "太阳 日 sun solar"
                SolarSystemEphemeris.SolarBody.VENUS -> "启明星 长庚星 太白"
                SolarSystemEphemeris.SolarBody.JUPITER -> "岁星"
                SolarSystemEphemeris.SolarBody.MARS -> "荧惑"
                SolarSystemEphemeris.SolarBody.SATURN -> "镇星"
                SolarSystemEphemeris.SolarBody.MERCURY -> "辰星"
                else -> ""
            }
            list.add(
                PickerItem(
                    key = "S_${body.name}",
                    category = TargetCategory.SOLAR,
                    target = ArTarget.Solar(pos),
                    title = if (isEn) enName else zhName,
                    subtitle = "$typeLabel · $magStr$phasePart",
                    glyph = "●",
                    rawColorArgb = SolarSystemCatalog.color(body),
                    searchBlob = "$zhName $enName ${SolarSystemCatalog.typeLabel(body, false)} ${SolarSystemCatalog.typeLabel(body, true)} 太阳系 solar $alias"
                        .lowercase(Locale.ROOT),
                )
            )
        }

        // 2. 梅西耶深空天体
        val messierSorted = MessierCatalog.byNumber.values.sortedBy { it.number }
        for (obj in messierSorted) {
            val conAbbr = MESSIER_CONSTELLATIONS[obj.number] ?: ""
            val conZh = if (conAbbr.isNotEmpty()) Constellations.zhName(conAbbr) else ""
            val conEn = if (conAbbr.isNotEmpty()) Constellations.enName(conAbbr) else ""
            val conDisplay = if (isEn) conEn else conZh
            val typeDisplay = ObjectInfo.typeLabel(obj.type, isEn)
            val mag = ObjectInfo.messier[obj.number]?.mag
            val magPart = if (mag != null) {
                String.format(Locale.ROOT, " · %.1f%s", mag, if (isEn) "m" else "等")
            } else {
                ""
            }
            val conPart = if (conDisplay.isNotEmpty()) " · $conDisplay" else ""
            val messierAliases = when (obj.number) {
                45 -> "七姊妹 昴宿 pleiades"
                44 -> "鬼星团 积尸气"
                42 -> "猎户大星云"
                31 -> "仙女星系"
                17 -> "天鹅星云"
                16 -> "创生之柱"
                else -> ""
            }
            list.add(
                PickerItem(
                    key = "M_${obj.number}",
                    category = TargetCategory.MESSIER,
                    target = ArTarget.Messier(obj),
                    title = obj.label(isEn),
                    subtitle = "$typeDisplay$conPart$magPart",
                    glyph = "✦",
                    rawColorArgb = MessierCatalog.typeColor(obj.type),
                    searchBlob = "m${obj.number} m ${obj.number} ${obj.number} ${obj.zh} ${obj.en} ${obj.labelZh} ${obj.labelEn} ${ObjectInfo.typeLabel(obj.type, false)} ${ObjectInfo.typeLabel(obj.type, true)} $conAbbr $conZh $conEn 梅西耶 messier $messierAliases"
                        .lowercase(Locale.ROOT),
                )
            )
        }

        // 3. 著名亮星（按视星等由亮到暗排序，一等导航星置顶）
        val starHipSet = LinkedHashSet<Int>().apply {
            addAll(ObjectInfo.stars.keys)
            addAll(StarNamesEn.names.keys)
        }
        val starEntries = starHipSet.mapNotNull { hip ->
            val idx = StarCatalogData.indexOfHip(hip)
            if (idx >= 0) StarCatalogData.stars[idx] else null
        }.sortedBy { it.mag }

        for (entry in starEntries) {
            val zhName = StarNames.displayName(entry.hip, entry.name, isEnglish = false)
            val enName = StarNames.displayName(entry.hip, entry.name, isEnglish = true)
            val bayer = StarNames.bayer[entry.hip] ?: ""
            val conZh = Constellations.zhName(entry.con)
            val conEn = Constellations.enName(entry.con)
            val aliasZh = POPULAR_STAR_ALIASES_ZH[entry.hip] ?: ""
            val primaryZh = aliasZh.substringBefore(' ').takeIf {
                it.isNotEmpty() && !zhName.contains(it)
            }
            val displayTitle = if (isEn) {
                enName.ifEmpty { "HIP ${entry.hip}" }
            } else {
                val base = zhName.ifEmpty { "HIP ${entry.hip}" }
                if (primaryZh != null) "$base ($primaryZh)" else base
            }
            val conDisplay = if (isEn) conEn else conZh
            val bayerPart = if (bayer.isNotEmpty() && bayer != displayTitle) " · $bayer" else ""
            val magPart = String.format(Locale.ROOT, " · %.1f%s", entry.mag, if (isEn) "m" else "等")
            list.add(
                PickerItem(
                    key = "H_${entry.hip}",
                    category = TargetCategory.STAR,
                    target = ArTarget.Star(entry),
                    title = displayTitle,
                    subtitle = "$conDisplay$bayerPart$magPart",
                    glyph = "★",
                    rawColorArgb = 0xFFFFE082.toInt(),
                    searchBlob = "$zhName $enName $bayer ${entry.name} hip${entry.hip} hip ${entry.hip} ${entry.con} $conZh $conEn $aliasZh"
                        .lowercase(Locale.ROOT),
                )
            )
        }

        list
    }

    val filteredItems = remember(allItems, selectedCategory, searchQuery) {
        val tokens = searchQuery.trim()
            .lowercase(Locale.ROOT)
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
        allItems.filter { item ->
            val catOk = selectedCategory == TargetCategory.ALL || item.category == selectedCategory
            val queryOk = tokens.isEmpty() || tokens.all { tok -> item.searchBlob.contains(tok) }
            catOk && queryOk
        }
    }

    val solarFiltered = remember(filteredItems) {
        filteredItems.filter { it.category == TargetCategory.SOLAR }
    }
    val messierFiltered = remember(filteredItems) {
        filteredItems.filter { it.category == TargetCategory.MESSIER }
    }
    val starFiltered = remember(filteredItems) {
        filteredItems.filter { it.category == TargetCategory.STAR }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = true),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 6.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(top = 16.dp, bottom = 10.dp)) {
                // 标题与结果计数
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                ) {
                    Text(
                        text = if (isEn) "Find Celestial Target" else "选择找星导航目标",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            text = "${filteredItems.size}",
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                // 搜索框：支持中英名称、Mxx 编号、星座缩写/全称
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingIcon = if (searchQuery.isNotEmpty()) {
                        {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = if (isEn) "Clear search" else "清空搜索",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        null
                    },
                    placeholder = {
                        Text(
                            text = if (isEn) {
                                "Name, M-code, or constellation (M42, Orion…)"
                            } else {
                                "搜名称 / M编号 / 星座（如 M42、木星、猎户）"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )

                // 横向分类 FilterChips（全部 / 太阳系 / 梅西耶 / 亮星）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val categories = listOf(
                        TargetCategory.ALL to (if (isEn) "All" else "全部"),
                        TargetCategory.SOLAR to (if (isEn) "Solar System" else "太阳系"),
                        TargetCategory.MESSIER to (if (isEn) "Messier" else "梅西耶"),
                        TargetCategory.STAR to (if (isEn) "Bright Stars" else "亮星"),
                    )
                    categories.forEach { (cat, label) ->
                        val selected = selectedCategory == cat
                        FilterChip(
                            selected = selected,
                            onClick = { selectedCategory = cat },
                            leadingIcon = if (selected) {
                                {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            } else {
                                null
                            },
                            label = {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            },
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                if (filteredItems.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .padding(horizontal = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = if (isEn) "No matching celestial objects" else "未找到匹配的天体",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = if (isEn) {
                                    "Try \"M31\", \"Jupiter\", \"Polaris\", or \"Orion\""
                                } else {
                                    "可试试搜索「M31」「木星」「北极星」或「猎户座」"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 240.dp, max = 360.dp),
                    ) {
                        if (solarFiltered.isNotEmpty()) {
                            item(key = "hdr_solar") {
                                SectionHeader(if (isEn) "Solar System" else "太阳系")
                            }
                            items(solarFiltered, key = { it.key }) { item ->
                                TargetRow(
                                    item = item,
                                    nightRed = nightRed,
                                    onClick = { onPick(item.target) },
                                )
                            }
                        }
                        if (messierFiltered.isNotEmpty()) {
                            item(key = "hdr_messier") {
                                SectionHeader(if (isEn) "Messier Objects" else "梅西耶深空天体")
                            }
                            items(messierFiltered, key = { it.key }) { item ->
                                TargetRow(
                                    item = item,
                                    nightRed = nightRed,
                                    onClick = { onPick(item.target) },
                                )
                            }
                        }
                        if (starFiltered.isNotEmpty()) {
                            item(key = "hdr_star") {
                                SectionHeader(if (isEn) "Bright Stars" else "导航亮星")
                            }
                            items(starFiltered, key = { it.key }) { item ->
                                TargetRow(
                                    item = item,
                                    nightRed = nightRed,
                                    onClick = { onPick(item.target) },
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = if (isEn) "Close" else "关闭",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun TargetRow(
    item: PickerItem,
    nightRed: Boolean,
    onClick: () -> Unit,
) {
    // 深色夜空圆章承托星点色彩，确保在 PURE_WHITE 浅色主题与 NIGHT_RED 暗适应模式下均清晰柔和
    val discBg = if (nightRed) Color(0xFF240707) else Color(0xFF101733)
    val discBorder = if (nightRed) Color(0x55FF5252) else Color(0x387FB3E8)
    val glyphColor = if (nightRed) Color(0xFFFF9E80) else Color(item.rawColorArgb)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(30.dp)
                .background(discBg, CircleShape)
                .border(1.dp, discBorder, CircleShape),
        ) {
            Text(
                text = item.glyph,
                color = glyphColor,
                fontSize = 13.sp,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
            modifier = Modifier.size(18.dp),
        )
    }
}
