package com.starcam.astro.ui.camera

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.starcam.astro.astro.CameraMath
import com.starcam.astro.ui.AppIcons
import com.starcam.astro.ui.I18n
import com.starcam.astro.ui.theme.AppThemeMode
import com.starcam.astro.ui.theme.LocaleState
import com.starcam.astro.ui.theme.ThemeState
import com.starcam.astro.util.DeviceOrientationTracker
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private fun PathBuilder.addCircleNode(cx: Float, cy: Float, r: Float) {
    moveTo(cx + r, cy)
    arcTo(r, r, 0f, false, true, cx - r, cy)
    arcTo(r, r, 0f, false, true, cx + r, cy)
    close()
}

/** AR 实时星座星图矢量图标（连线 + 亮星节点 + 四芒主星） */
private val ArStarMapIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "ArStarMap",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.75f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(4.5f, 17.5f)
            lineTo(9.5f, 10.8f)
            lineTo(15.2f, 13.8f)
            lineTo(19.2f, 6.2f)
            moveTo(9.5f, 10.8f)
            lineTo(6.0f, 5.5f)
        }
        path(fill = SolidColor(Color.Black)) {
            addCircleNode(4.5f, 17.5f, 1.9f)
            addCircleNode(9.5f, 10.8f, 2.1f)
            addCircleNode(15.2f, 13.8f, 1.9f)
            addCircleNode(6.0f, 5.5f, 1.6f)
            // 右上四芒亮星
            moveTo(19.2f, 2.4f)
            lineTo(20.15f, 5.25f)
            lineTo(23.0f, 6.2f)
            lineTo(20.15f, 7.15f)
            lineTo(19.2f, 10.0f)
            lineTo(18.25f, 7.15f)
            lineTo(15.4f, 6.2f)
            lineTo(18.25f, 5.25f)
            close()
        }
    }.build()
}

/** 寻星十字准星矢量图标 */
private val TargetReticleIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "TargetReticle",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
        ) {
            addCircleNode(12f, 12f, 7.2f)
            moveTo(12f, 2.2f)
            lineTo(12f, 6.6f)
            moveTo(12f, 17.4f)
            lineTo(12f, 21.8f)
            moveTo(2.2f, 12f)
            lineTo(6.6f, 12f)
            moveTo(17.4f, 12f)
            lineTo(21.8f, 12f)
        }
        path(fill = SolidColor(Color.Black)) {
            addCircleNode(12f, 12f, 2.0f)
        }
    }.build()
}

/** 已锁定目标清除准星图标 */
private val TargetClearReticleIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "TargetClearReticle",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.75f,
            strokeLineCap = StrokeCap.Round,
        ) {
            addCircleNode(12f, 12f, 8.2f)
            moveTo(12f, 2.0f)
            lineTo(12f, 4.4f)
            moveTo(12f, 19.6f)
            lineTo(12f, 22.0f)
            moveTo(2.0f, 12f)
            lineTo(4.4f, 12f)
            moveTo(19.6f, 12f)
            lineTo(22.0f, 12f)
            moveTo(9.3f, 9.3f)
            lineTo(14.7f, 14.7f)
            moveTo(14.7f, 9.3f)
            lineTo(9.3f, 14.7f)
        }
    }.build()
}

/** 实时取景星点检测（四角取景框 + 星芒雷达）矢量图标 */
private val LiveStarDetectIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "LiveStarDetect",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(3.5f, 8.2f)
            lineTo(3.5f, 3.5f)
            lineTo(8.2f, 3.5f)
            moveTo(15.8f, 3.5f)
            lineTo(20.5f, 3.5f)
            lineTo(20.5f, 8.2f)
            moveTo(3.5f, 15.8f)
            lineTo(3.5f, 20.5f)
            lineTo(8.2f, 20.5f)
            moveTo(15.8f, 20.5f)
            lineTo(20.5f, 20.5f)
            lineTo(20.5f, 15.8f)
        }
        path(fill = SolidColor(Color.Black)) {
            moveTo(11.5f, 6.2f)
            lineTo(12.9f, 10.1f)
            lineTo(16.8f, 11.5f)
            lineTo(12.9f, 12.9f)
            lineTo(11.5f, 16.8f)
            lineTo(10.1f, 12.9f)
            lineTo(6.2f, 11.5f)
            lineTo(10.1f, 10.1f)
            close()
            addCircleNode(16.8f, 7.2f, 1.25f)
        }
    }.build()
}

/** 天球方位罗盘指针图标（替代原 🧭 emoji） */
private val CompassPointingIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "CompassPointing",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
        ) {
            addCircleNode(12f, 12f, 9.0f)
        }
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 5.2f)
            lineTo(14.5f, 12f)
            lineTo(12f, 18.8f)
            lineTo(9.5f, 12f)
            close()
        }
    }.build()
}

@Immutable
private data class HudPalette(
    val inactiveBg: Color,
    val inactiveBorder: Color,
    val inactiveContent: Color,
    val activeBg: Color,
    val activeBorder: Color,
    val activeContent: Color,
    val levelColor: Color,
    val ringGuide: Color,
    val bannerBg: Color,
    val bannerBorder: Color,
    val focusBannerBg: Color,
    val focusBannerBorder: Color,
    val skyHudBg: Color,
    val skyHudBorder: Color,
    val skyHudText: Color,
    val skyHudAccent: Color,
    val groundHudBg: Color,
    val groundHudBorder: Color,
    val groundHudText: Color,
)

@Composable
private fun rememberHudPalette(): HudPalette {
    val nightRed = ThemeState.mode == AppThemeMode.NIGHT_RED
    return remember(nightRed) {
        if (nightRed) {
            HudPalette(
                inactiveBg = Color(0xB3120404),
                inactiveBorder = Color(0x4DFF5252),
                inactiveContent = Color(0xFFE89B93),
                activeBg = Color(0xD93B0E0E),
                activeBorder = Color(0xFFFF5252),
                activeContent = Color(0xFFFFB74D),
                levelColor = Color(0xFFFFB74D),
                ringGuide = Color(0x4DFF5252),
                bannerBg = Color(0xE0180505),
                bannerBorder = Color(0x55FF5252),
                focusBannerBg = Color(0xE0290909),
                focusBannerBorder = Color(0x88FF6E40),
                skyHudBg = Color(0xBF120404),
                skyHudBorder = Color(0x4DFF5252),
                skyHudText = Color(0xFFF0A8A0),
                skyHudAccent = Color(0xFFFFB74D),
                groundHudBg = Color(0xD92E0808),
                groundHudBorder = Color(0x88FF6E40),
                groundHudText = Color(0xFFFFB4A8),
            )
        } else {
            HudPalette(
                inactiveBg = Color(0x99080D1A),
                inactiveBorder = Color(0x38FFFFFF),
                inactiveContent = Color(0xF2F4F7FF),
                activeBg = Color(0xCC2A2210),
                activeBorder = Color(0xFFE3C567),
                activeContent = Color(0xFFFFDF85),
                levelColor = Color(0xFF69F0AE),
                ringGuide = Color(0x40FFFFFF),
                bannerBg = Color(0xCC080D1A),
                bannerBorder = Color(0x38FFFFFF),
                focusBannerBg = Color(0xD9121C3E),
                focusBannerBorder = Color(0x667FB3E8),
                skyHudBg = Color(0xAD080D1A),
                skyHudBorder = Color(0x38FFFFFF),
                skyHudText = Color(0xFFE2E8F8),
                skyHudAccent = Color(0xFFE3C567),
                groundHudBg = Color(0xBF4A1010),
                groundHudBorder = Color(0x77F87171),
                groundHudText = Color(0xFFFECACA),
            )
        }
    }
}

@Composable
private fun CameraTopCapsule(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean,
    palette: HudPalette,
    onClick: () -> Unit,
    label: String? = null,
) {
    val containerColor = if (active) palette.activeBg else palette.inactiveBg
    val borderColor = if (active) palette.activeBorder else palette.inactiveBorder
    val contentColor = if (active) palette.activeContent else palette.inactiveContent
    val shape = if (label == null) CircleShape else RoundedCornerShape(50)
    // 使用 clip + clickable 而非 Surface(onClick=...)，避免 Material 3 minimumInteractiveComponentSize
    // 把 36dp 胶囊强行撑成 48dp 导致窄屏下右上角按钮被挤出屏幕或彼此紧贴重合。
    Surface(
        shape = shape,
        color = containerColor,
        border = BorderStroke(if (active) 1.2.dp else 1.dp, borderColor),
        modifier = (if (label == null) {
            Modifier.size(36.dp)
        } else {
            Modifier
                .height(36.dp)
                .defaultMinSize(minWidth = 36.dp)
        })
            .clip(shape)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        if (label == null) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = contentColor,
                    modifier = Modifier.size(17.dp),
                )
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = contentColor,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    text = label,
                    color = contentColor,
                    fontSize = 11.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 相机顶部工具条、状态横幅、传感器天球朝向 HUD 与电子滚转水平仪（§0.142 模块化解耦，§0.143 胶囊化与夜视红精修）。
 */
@Composable
internal fun CameraTopBar(
    arEnabled: Boolean,
    sensorAssistedPointing: Boolean,
    hasArTarget: Boolean,
    livePreview: Boolean,
    flashOn: Boolean,
    nightEvActive: Boolean,
    onBack: () -> Unit,
    onToggleAr: () -> Unit,
    onArTargetAction: () -> Unit,
    onToggleLivePreview: () -> Unit,
    onToggleFlash: () -> Unit,
    onToggleNightEv: () -> Unit,
    onSwitchCamera: () -> Unit,
    arTargetLabel: String? = null,
) {
    val isEn = LocaleState.isEnglish
    val palette = rememberHudPalette()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, start = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CameraTopCapsule(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = I18n.back,
            active = false,
            palette = palette,
            onClick = onBack,
        )
        Spacer(Modifier.width(6.dp))
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CameraTopCapsule(
                    icon = ArStarMapIcon,
                    contentDescription = I18n.Ar.toggleDesc,
                    active = arEnabled,
                    palette = palette,
                    onClick = onToggleAr,
                    label = "AR",
                )
                if (arEnabled && sensorAssistedPointing) {
                    val shortTarget = arTargetLabel?.trim()?.let {
                        if (it.length > 6) "${it.take(5)}…" else it
                    }
                    val targetPillLabel = if (hasArTarget) {
                        shortTarget ?: if (isEn) "Clear" else "清除"
                    } else {
                        if (isEn) "Find" else "找星"
                    }
                    CameraTopCapsule(
                        icon = if (hasArTarget) TargetClearReticleIcon else TargetReticleIcon,
                        contentDescription = if (hasArTarget) I18n.Ar.clearTarget else I18n.Ar.targetBtn,
                        active = hasArTarget,
                        palette = palette,
                        onClick = onArTargetAction,
                        label = targetPillLabel,
                    )
                }
                CameraTopCapsule(
                    icon = LiveStarDetectIcon,
                    contentDescription = I18n.Camera.liveOverlayDesc,
                    active = livePreview,
                    palette = palette,
                    onClick = onToggleLivePreview,
                    label = if (isEn) "Live" else "认星",
                )
                CameraTopCapsule(
                    icon = AppIcons.FlashOn,
                    contentDescription = I18n.Camera.flashDesc,
                    active = flashOn,
                    palette = palette,
                    onClick = onToggleFlash,
                )
                CameraTopCapsule(
                    icon = AppIcons.Night,
                    contentDescription = I18n.Camera.nightModeDesc,
                    active = nightEvActive,
                    palette = palette,
                    onClick = onToggleNightEv,
                )
                CameraTopCapsule(
                    icon = AppIcons.CameraSwitch,
                    contentDescription = I18n.Camera.switchCameraDesc,
                    active = false,
                    palette = palette,
                    onClick = onSwitchCamera,
                )
            }
        }
    }
}

@Composable
internal fun BoxScope.CameraStatusBanners(
    message: String?,
    focusHint: Boolean,
) {
    val palette = rememberHudPalette()
    message?.let {
        Surface(
            color = palette.bannerBg,
            shape = RoundedCornerShape(50),
            border = BorderStroke(1.dp, palette.bannerBorder),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 60.dp, start = 16.dp, end = 16.dp),
        ) {
            Text(
                text = it,
                color = palette.inactiveContent,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
    if (focusHint && message == null) {
        Surface(
            color = palette.focusBannerBg,
            shape = RoundedCornerShape(50),
            border = BorderStroke(1.dp, palette.focusBannerBorder),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 60.dp, start = 16.dp, end = 16.dp),
        ) {
            Text(
                text = I18n.Camera.focusHint,
                color = palette.inactiveContent,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
internal fun BoxScope.CameraPointingHud(
    pointingState: DeviceOrientationTracker.DevicePointing,
    topBannerVisible: Boolean,
) {
    val isEn = LocaleState.isEnglish
    val palette = rememberHudPalette()
    val isSky = pointingState.altDeg > 0.0
    val monoStyle = MaterialTheme.typography.labelSmall.copy(
        fontFamily = FontFamily.Monospace,
        fontFeatureSettings = "tnum",
        fontWeight = FontWeight.Medium,
    )

    val azNorm = (((pointingState.azDeg % 360.0) + 360.0) % 360.0)
    val azInt = azNorm.roundToInt() % 360
    val altInt = pointingState.altDeg.roundToInt().coerceIn(-90, 90)
    val dir = when (((azNorm + 22.5) % 360.0 / 45.0).toInt()) {
        0 -> if (isEn) "N" else "北"
        1 -> if (isEn) "NE" else "东北"
        2 -> if (isEn) "E" else "东"
        3 -> if (isEn) "SE" else "东南"
        4 -> if (isEn) "S" else "南"
        5 -> if (isEn) "SW" else "西南"
        6 -> if (isEn) "W" else "西"
        else -> if (isEn) "NW" else "西北"
    }

    val containerBg = if (isSky) palette.skyHudBg else palette.groundHudBg
    val borderCol = if (isSky) palette.skyHudBorder else palette.groundHudBorder
    val textCol = if (isSky) palette.skyHudText else palette.groundHudText
    val accentCol = if (isSky) palette.skyHudAccent else palette.groundHudText

    Surface(
        color = containerBg,
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, borderCol),
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = if (topBannerVisible) 108.dp else 60.dp, start = 12.dp, end = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Icon(
                imageVector = CompassPointingIcon,
                contentDescription = null,
                tint = accentCol,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = String.format(Locale.ROOT, "%s %03d°", if (isEn) "Az" else "方位", azInt),
                color = textCol,
                style = monoStyle,
                maxLines = 1,
                softWrap = false,
            )
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .width(if (isEn) 22.dp else 28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = dir,
                    color = accentCol,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            val rightTelemetry = if (pointingState.raDeg != null && pointingState.decDeg != null) {
                val raDegNorm = (((pointingState.raDeg ?: 0.0) % 360.0) + 360.0) % 360.0
                val totalRaMin = (raDegNorm / 15.0 * 60.0).roundToInt() % (24 * 60)
                val raH = totalRaMin / 60
                val raM = totalRaMin % 60
                val decInt = (pointingState.decDeg ?: 0.0).roundToInt().coerceIn(-90, 90)
                if (isEn) {
                    String.format(
                        Locale.ROOT,
                        "· Alt %+03d° · RA %02dh%02dm Dec %+03d°",
                        altInt, raH, raM, decInt,
                    )
                } else {
                    String.format(
                        Locale.ROOT,
                        "· 仰 %+03d° · RA %02dh%02dm Dec %+03d°",
                        altInt, raH, raM, decInt,
                    )
                }
            } else {
                if (isEn) {
                    String.format(Locale.ROOT, "· Alt %+03d°", altInt)
                } else {
                    String.format(Locale.ROOT, "· 仰 %+03d°", altInt)
                }
            }
            Text(
                text = rightTelemetry,
                color = textCol,
                style = monoStyle,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
internal fun BoxScope.CameraRollLevelIndicator(
    pointingState: DeviceOrientationTracker.DevicePointing,
    topBannerVisible: Boolean,
) {
    val palette = rememberHudPalette()
    val roll = CameraMath.rollDeg(pointingState.right, pointingState.up)
    val level = abs(roll) < 1.0
    val indicatorColor = if (level) palette.levelColor else palette.inactiveContent
    val borderColor = if (level) palette.levelColor.copy(alpha = 0.72f) else palette.inactiveBorder

    Surface(
        color = palette.inactiveBg,
        shape = CircleShape,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(start = 12.dp)
            .padding(top = if (topBannerVisible) 196.dp else 144.dp)
            .size(58.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2f - 6.dp.toPx()
                val c = Offset(size.width / 2f, size.height / 2f)
                drawCircle(
                    color = palette.ringGuide,
                    radius = r,
                    center = c,
                    style = Stroke(width = 1.4.dp.toPx()),
                )
                // 左右固定水平参考短刻度
                val refTick = 3.dp.toPx()
                drawLine(
                    color = palette.ringGuide,
                    start = Offset(c.x - r - refTick * 0.5f, c.y),
                    end = Offset(c.x - r + refTick, c.y),
                    strokeWidth = 1.3.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = palette.ringGuide,
                    start = Offset(c.x + r - refTick, c.y),
                    end = Offset(c.x + r + refTick * 0.5f, c.y),
                    strokeWidth = 1.3.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                // 滚转线
                val lineLen = r * 1.15f
                val rad = Math.toRadians(roll)
                val dx = cos(rad).toFloat() * lineLen
                val dy = sin(rad).toFloat() * lineLen
                drawLine(
                    color = indicatorColor,
                    start = Offset(c.x - dx, c.y - dy),
                    end = Offset(c.x + dx, c.y + dy),
                    strokeWidth = if (level) 2.2.dp.toPx() else 1.8.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                // |roll| < 1.0° 水平合焦中心刻度线与天顶刻度
                if (level) {
                    val centerTick = 4.5.dp.toPx()
                    drawLine(
                        color = palette.levelColor,
                        start = Offset(c.x, c.y - centerTick),
                        end = Offset(c.x, c.y + centerTick),
                        strokeWidth = 1.8.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = palette.levelColor,
                        start = Offset(c.x, c.y - r),
                        end = Offset(c.x, c.y - r + 4.dp.toPx()),
                        strokeWidth = 1.8.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                    drawCircle(
                        color = palette.levelColor,
                        radius = 2.dp.toPx(),
                        center = c,
                    )
                }
            }
            val rollInt = roll.roundToInt()
            val rollText = if (rollInt == 0) "0°" else String.format(Locale.ROOT, "%+d°", rollInt)
            Text(
                text = rollText,
                color = indicatorColor,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontFeatureSettings = "tnum",
                ),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}
