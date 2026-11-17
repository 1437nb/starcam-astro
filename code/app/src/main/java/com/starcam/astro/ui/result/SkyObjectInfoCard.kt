package com.starcam.astro.ui.result

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import java.util.Locale

private val CardCloseIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "ObjectCardClose",
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

private val CardStarIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "ObjectCardStar",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 17.27f)
            lineTo(18.18f, 21f)
            lineTo(16.54f, 13.97f)
            lineTo(22f, 9.24f)
            lineTo(14.81f, 8.63f)
            lineTo(12f, 2f)
            lineTo(9.19f, 8.63f)
            lineTo(2f, 9.24f)
            lineTo(7.46f, 13.97f)
            lineTo(5.82f, 21f)
            close()
        }
    }.build()
}

private val CardDeepSkyIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "ObjectCardDeepSky",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 2f)
            lineTo(14.6f, 9.4f)
            lineTo(22f, 12f)
            lineTo(14.6f, 14.6f)
            lineTo(12f, 22f)
            lineTo(9.4f, 14.6f)
            lineTo(2f, 12f)
            lineTo(9.4f, 9.4f)
            close()
        }
    }.build()
}

private val CardPlanetIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "ObjectCardPlanet",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 6f)
            curveTo(8.69f, 6f, 6f, 8.69f, 6f, 12f)
            curveTo(6f, 15.31f, 8.69f, 18f, 12f, 18f)
            curveTo(15.31f, 18f, 18f, 15.31f, 18f, 12f)
            curveTo(18f, 8.69f, 15.31f, 6f, 12f, 6f)
            close()
            moveTo(21.5f, 8.2f)
            curveTo(20.9f, 6.8f, 18.2f, 6.7f, 14.5f, 7.8f)
            curveTo(15.2f, 8.2f, 15.8f, 8.7f, 16.3f, 9.3f)
            curveTo(18.6f, 8.7f, 20.1f, 8.8f, 20.3f, 9.3f)
            curveTo(20.6f, 10f, 17.5f, 12.1f, 12.6f, 14.2f)
            curveTo(7.7f, 16.3f, 3.9f, 17.2f, 3.6f, 16.5f)
            curveTo(3.4f, 16f, 4.5f, 14.9f, 6.4f, 13.6f)
            curveTo(6.1f, 12.9f, 6f, 12.2f, 6f, 11.4f)
            curveTo(3.3f, 13.3f, 1.9f, 15.2f, 2.4f, 16.5f)
            curveTo(3.2f, 18.3f, 8.2f, 17.7f, 13.5f, 15.4f)
            curveTo(18.8f, 13.1f, 22.3f, 9.9f, 21.5f, 8.2f)
            close()
        }
    }.build()
}

/** §0.47：被点中的天体引用（Messier、亮星或太阳系天体） */
sealed interface SkyObjectRef {
    data class MessierRef(val obj: MessierObject) : SkyObjectRef
    data class StarRef(val entry: StarEntry) : SkyObjectRef
    /** §0.58 太阳系天体（月亮/行星/太阳），携带该时刻的观测参数供卡片展示 */
    data class SolarRef(val pos: SolarSystemEphemeris.SolarPosition) : SkyObjectRef
}

/** §0.47 / §0.143：天体科普卡片（照片底部与相机 AR 浮层共用，结构化元数据胶囊 + 暗适应安全） */
@Composable
internal fun ObjectInfoCard(ref: SkyObjectRef, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val isEn = LocaleState.isEnglish
    val night = ThemeState.mode == AppThemeMode.NIGHT_RED

    val rawAccent = when (ref) {
        is SkyObjectRef.MessierRef -> Color(MessierCatalog.typeColor(ref.obj.type))
        is SkyObjectRef.StarRef -> Color(0xFFFFE082)
        is SkyObjectRef.SolarRef -> Color(SolarSystemCatalog.color(ref.pos.body))
    }
    val accent = if (night) Color(0xFFFF8A80) else rawAccent
    val cardBg = if (night) Color(0xF2140404) else Color(0xF00B1528)
    val cardBorder = if (night) Color(0x886E2822) else accent.copy(alpha = 0.38f)
    val titleFg = if (night) Color(0xFFF5B8B0) else Color(0xFFF5F8FF)
    val bodyFg = if (night) Color(0xFFD99B93) else Color(0xFFE2E8F5)

    Surface(
        modifier = modifier,
        color = cardBg,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, cardBorder),
        shadowElevation = 10.dp,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            when (ref) {
                is SkyObjectRef.MessierRef -> {
                    val obj = ref.obj
                    val info = ObjectInfo.messier[obj.number]
                    val typeText = ObjectInfo.typeLabel(obj.type, isEn)

                    ObjectCardHeader(
                        icon = CardDeepSkyIcon,
                        title = obj.label(isEn),
                        subtitle = "M${obj.number} · $typeText",
                        accent = accent,
                        titleFg = titleFg,
                        night = night,
                        onDismiss = onDismiss,
                    )

                    Spacer(Modifier.height(10.dp))

                    val pills = buildList {
                        add(I18n.InfoCard.typeLabel to typeText)
                        info?.let {
                            add(I18n.InfoCard.magLabel to "${"%.1f".format(Locale.ROOT, it.mag)}${if (isEn) "m" else "等"}")
                            add(I18n.InfoCard.distLabel to if (isEn) it.distLyEn else it.distLyZh)
                        }
                    }
                    MetadataPillRow(pills = pills, accent = accent, titleFg = titleFg, night = night)

                    info?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = if (isEn) it.descEn else it.descZh,
                            color = bodyFg,
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

                    ObjectCardHeader(
                        icon = CardStarIcon,
                        title = if (displayName.isNotEmpty()) displayName else "HIP ${entry.hip}",
                        subtitle = if (displayName.isNotEmpty() && entry.hip > 0) "HIP ${entry.hip}" else conName,
                        accent = accent,
                        titleFg = titleFg,
                        night = night,
                        onDismiss = onDismiss,
                    )

                    Spacer(Modifier.height(10.dp))

                    val pills = buildList {
                        add(I18n.InfoCard.magLabel to "${"%.1f".format(Locale.ROOT, entry.mag)}${if (isEn) "m" else "等"}")
                        add(I18n.InfoCard.constellationLabel to conName)
                        info?.let {
                            add(I18n.InfoCard.distLabel to if (isEn) it.distLyEn else it.distLyZh)
                        }
                    }
                    MetadataPillRow(pills = pills, accent = accent, titleFg = titleFg, night = night)

                    info?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = if (isEn) it.descEn else it.descZh,
                            color = bodyFg,
                            style = MaterialTheme.typography.bodySmall,
                            lineHeight = 19.sp,
                        )
                    }
                }

                is SkyObjectRef.SolarRef -> {
                    val pos = ref.pos
                    val body = pos.body
                    val info = SolarSystemCatalog.info[body]

                    ObjectCardHeader(
                        icon = CardPlanetIcon,
                        title = SolarSystemCatalog.name(body, isEn),
                        subtitle = SolarSystemCatalog.typeLabel(body, isEn),
                        accent = accent,
                        titleFg = titleFg,
                        night = night,
                        onDismiss = onDismiss,
                    )

                    Spacer(Modifier.height(10.dp))

                    val pills = buildList {
                        add(I18n.InfoCard.magLabel to "${"%.1f".format(Locale.ROOT, pos.magnitude)}${if (isEn) "m" else "等"}")
                        add(I18n.InfoCard.elongationLabel to "${"%.0f".format(Locale.ROOT, pos.elongationDeg)}°")
                        if (body != SolarSystemEphemeris.SolarBody.SUN) {
                            add(I18n.InfoCard.phaseLabel to "${"%.0f".format(Locale.ROOT, pos.phase * 100)}%")
                        }
                        info?.let {
                            add(I18n.InfoCard.distLabel to if (isEn) it.distEn else it.distZh)
                        }
                        if (pos.angularDiameterDeg * 60.0 >= 0.1) {
                            add(I18n.InfoCard.diameterLabel to "${"%.1f".format(Locale.ROOT, pos.angularDiameterDeg * 60)}′")
                        }
                    }
                    MetadataPillRow(pills = pills, accent = accent, titleFg = titleFg, night = night)

                    info?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = if (isEn) it.descEn else it.descZh,
                            color = bodyFg,
                            style = MaterialTheme.typography.bodySmall,
                            lineHeight = 19.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ObjectCardHeader(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: Color,
    titleFg: Color,
    night: Boolean,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = accent.copy(alpha = if (night) 0.16f else 0.20f),
            border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
        ) {
            Box(
                modifier = Modifier.size(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(17.dp),
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = titleFg,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    color = accent.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Surface(
            onClick = onDismiss,
            shape = CircleShape,
            color = if (night) Color(0xFF280909) else Color(0x22FFFFFF),
        ) {
            Box(
                modifier = Modifier.size(28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = CardCloseIcon,
                    contentDescription = I18n.InfoCard.close(),
                    tint = titleFg.copy(alpha = 0.78f),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun MetadataPillRow(
    pills: List<Pair<String, String>>,
    accent: Color,
    titleFg: Color,
    night: Boolean,
) {
    val pillBg = if (night) Color(0xFF220808) else Color(0x1CFFFFFF)
    val pillBorder = if (night) Color(0x555A2622) else Color(0x26FFFFFF)
    val labelFg = if (night) Color(0xFFC88F88) else Color(0xFFAAB8D4)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        pills.forEachIndexed { idx, (label, value) ->
            Surface(
                shape = MaterialTheme.shapes.small,
                color = pillBg,
                border = BorderStroke(
                    1.dp,
                    if (idx == 0) accent.copy(alpha = 0.42f) else pillBorder,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text(
                        text = label,
                        color = labelFg,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        maxLines = 1,
                    )
                    Text(
                        text = value,
                        color = if (idx == 0) accent else titleFg,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
