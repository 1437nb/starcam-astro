package com.starcam.astro.ui.result

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.starcam.astro.astro.SolveEngine
import com.starcam.astro.ui.I18n
import com.starcam.astro.ui.theme.LocaleState
import com.starcam.astro.ui.theme.onSuccessContainer
import com.starcam.astro.ui.theme.successContainer
import java.util.Locale
import kotlin.math.abs

private val CopyVectorIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "InfoCopy",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(16f, 1f)
            horizontalLineTo(4f)
            curveTo(2.9f, 1f, 2f, 1.9f, 2f, 3f)
            verticalLineTo(17f)
            horizontalLineTo(4f)
            verticalLineTo(3f)
            horizontalLineTo(16f)
            verticalLineTo(1f)
            close()
            moveTo(19f, 5f)
            horizontalLineTo(8f)
            curveTo(6.9f, 5f, 6f, 5.9f, 6f, 7f)
            verticalLineTo(21f)
            curveTo(6f, 22.1f, 6.9f, 23f, 8f, 23f)
            horizontalLineTo(19f)
            curveTo(20.1f, 23f, 21f, 22.1f, 21f, 21f)
            verticalLineTo(7f)
            curveTo(21f, 5.9f, 20.1f, 5f, 19f, 5f)
            close()
            moveTo(19f, 21f)
            horizontalLineTo(8f)
            verticalLineTo(7f)
            horizontalLineTo(19f)
            verticalLineTo(21f)
            close()
        }
    }.build()
}

private val WarningVectorIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "InfoWarning",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(1f, 21f)
            horizontalLineTo(23f)
            lineTo(12f, 2f)
            lineTo(1f, 21f)
            close()
            moveTo(13f, 18f)
            horizontalLineTo(11f)
            verticalLineTo(16f)
            horizontalLineTo(13f)
            verticalLineTo(18f)
            close()
            moveTo(13f, 14f)
            horizontalLineTo(11f)
            verticalLineTo(10f)
            horizontalLineTo(13f)
            verticalLineTo(14f)
            close()
        }
    }.build()
}

private val SparkleStarIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "InfoSparkle",
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

/** 识别遥测信息面板（§0.143：引擎状态头 + 定位回退横幅 + 2×2 遥测卡片网格 + 星座胶囊条） */
@Composable
internal fun InfoPanel(state: ResultUiState.Success, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val solve = state.solve
    val isEn = LocaleState.isEnglish

    val raStr = formatRa(solve.raDeg)
    val decStr = formatDec(solve.decDeg)
    val copyableCoords = "RA $raStr, Dec $decStr"

    val rawTitle = when {
        state.isDemo -> I18n.Result.titleDemo
        state.engine == SolveEngine.ASTROMETRY_NATIVE -> I18n.Result.titleNative
        state.engine == SolveEngine.LOCAL_MATCHER -> I18n.Result.titleLocal
        state.engine == SolveEngine.ONLINE_NOVA -> I18n.Result.titleOnline
        else -> I18n.Result.titleDefault
    }
    // 剥离前导 emoji 以便配合统一的成功圆章图标显示
    val cleanTitle = rawTitle
        .removePrefix("✨ ")
        .removePrefix("🔭 ")
        .removePrefix("🗺 ")
        .removePrefix("✅ ")
        .trim()

    val subtitleParts = buildList {
        state.engineDetail?.takeIf { it.isNotBlank() }?.let { add(it) }
        solve.subId?.let { add("${I18n.Result.labelTaskId} #$it") }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            // 1. 顶部引擎状态与置信度/参数详情头
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.successContainer,
                ) {
                    Box(
                        modifier = Modifier.size(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSuccessContainer,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = cleanTitle,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (subtitleParts.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = subtitleParts.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                val badgeLabel = when {
                    state.isDemo -> if (isEn) "Offline Demo" else "离线演示"
                    state.engine != null -> state.engine.label(isEn)
                    else -> null
                }
                badgeLabel?.let { tag ->
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            text = tag,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            // §0.59：照片没有 EXIF GPS，识别与天体标注都用的是设备当前定位——必须如实告知
            if (state.locationIsFallback) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.45f),
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = WarningVectorIcon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = I18n.Result.locationFallback.removePrefix("⚠ ").trim(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // 2. 2×2 遥测数据网格（surfaceContainerHigh 迷你卡片）
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // 左上：天球中心 RA / Dec + 一键复制坐标胶囊
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = if (isEn) "Center (J2000)" else "天球中心",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                Surface(
                                    onClick = {
                                        val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clip.setPrimaryClip(
                                            ClipData.newPlainText("StarCam J2000", copyableCoords),
                                        )
                                        Toast.makeText(
                                            context,
                                            if (isEn) "Copied: $copyableCoords" else "已复制坐标：$copyableCoords",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    },
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                                    ) {
                                        Icon(
                                            imageVector = CopyVectorIcon,
                                            contentDescription = if (isEn) "Copy coordinates" else "复制坐标",
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.size(11.dp),
                                        )
                                        Text(
                                            text = if (isEn) "Copy" else "复制",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = "RA  $raStr",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                )
                                Text(
                                    text = "Dec $decStr",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                )
                            }
                        }
                    }

                    // 右上：视场大小 FOV
                    val fovClass = when {
                        solve.fieldWidthDeg >= 35.0 -> if (isEn) "Wide-field sky" else "广角星野"
                        solve.fieldWidthDeg >= 8.0 -> if (isEn) "Medium field" else "中视场天区"
                        else -> if (isEn) "Narrow deep-sky" else "窄场深空"
                    }
                    TelemetryMiniCard(
                        label = I18n.Result.labelFov,
                        value = "${"%.1f".format(Locale.ROOT, solve.fieldWidthDeg)}° × ${"%.1f".format(Locale.ROOT, solve.fieldHeightDeg)}°",
                        caption = fovClass,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // 左下：像素比例尺 Scale
                    TelemetryMiniCard(
                        label = I18n.Result.labelPixScale,
                        value = "${"%.1f".format(Locale.ROOT, solve.pixScaleArcsec)}${if (isEn) "″/px" else "″/像素"}",
                        caption = "${state.bitmap.width} × ${state.bitmap.height} px",
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )

                    // 右下：旋转角 / 识别耗时
                    val solveTimeStr = I18n.Result.formatSolveTime(state.elapsedMs)
                    val rotCaption = if (solveTimeStr.isNotEmpty()) {
                        "$solveTimeStr · parity ${solve.parity}"
                    } else {
                        if (isEn) "parity ${solve.parity}" else "奇偶性 parity ${solve.parity}"
                    }
                    TelemetryMiniCard(
                        label = if (solveTimeStr.isNotEmpty()) {
                            if (isEn) "Rotation · Time" else "旋转角 · 耗时"
                        } else {
                            I18n.Result.labelOrientation
                        },
                        value = "${"%.1f".format(Locale.ROOT, solve.orientationDeg)}°",
                        caption = rotCaption,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            }

            // 3. 视场内星座胶囊横排
            if (state.labels.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = I18n.Result.constellationsInField.trimEnd(':', '：'),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "${state.labels.size}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                ) {
                    state.labels.forEach { label ->
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant,
                            ),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Icon(
                                    imageVector = SparkleStarIcon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(12.dp),
                                )
                                Text(
                                    text = label.text,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TelemetryMiniCard(
    label: String,
    value: String,
    caption: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun formatRa(raDeg: Double): String {
    var v = ((raDeg % 360.0) + 360.0) % 360.0
    val hh = (v / 15.0).toInt()
    v = (v - hh * 15.0) * 4.0 // 余量转为分钟（度→分钟：*4）
    val mm = v.toInt()
    val ss = ((v - mm) * 60.0).toInt()
    return "%02dh %02dm %02ds".format(Locale.ROOT, hh, mm, ss)
}

private fun formatDec(decDeg: Double): String {
    val sign = if (decDeg < 0) "-" else "+"
    val a = abs(decDeg)
    val d = a.toInt()
    val m = ((a - d) * 60.0).toInt()
    val s = (((a - d) * 60.0 - m) * 60.0).toInt()
    return "%s%02d° %02d′ %02d″".format(Locale.ROOT, sign, d, m, s)
}

