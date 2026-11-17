package com.starcam.astro.ui.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.starcam.astro.astro.CameraMath
import com.starcam.astro.ui.AppIcons
import com.starcam.astro.ui.I18n
import com.starcam.astro.ui.theme.LocaleState
import com.starcam.astro.util.DeviceOrientationTracker
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 相机顶部工具条、状态横幅、传感器天球朝向 HUD 与电子滚转水平仪（§0.142 模块化解耦）。
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
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, start = 8.dp, end = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = I18n.back,
                tint = Color.White,
            )
        }
        Row {
            IconButton(onClick = onToggleAr) {
                Text(
                    "🌌",
                    fontSize = 18.sp,
                    color = if (arEnabled) MaterialTheme.colorScheme.primary else Color.White,
                )
            }
            if (arEnabled && sensorAssistedPointing) {
                IconButton(onClick = onArTargetAction) {
                    Text(
                        if (hasArTarget) "✕🎯" else "🎯",
                        fontSize = 16.sp,
                        color = if (hasArTarget) MaterialTheme.colorScheme.primary else Color.White,
                    )
                }
            }
            IconButton(onClick = onToggleLivePreview) {
                Text(
                    if (livePreview) "🛰️" else "🔭",
                    fontSize = 18.sp,
                    color = if (livePreview) MaterialTheme.colorScheme.primary else Color.White,
                )
            }
            IconButton(onClick = onToggleFlash) {
                Icon(
                    AppIcons.FlashOn,
                    contentDescription = I18n.Camera.flashDesc,
                    tint = if (flashOn) MaterialTheme.colorScheme.primary else Color.White,
                )
            }
            IconButton(onClick = onToggleNightEv) {
                Icon(
                    AppIcons.Night,
                    contentDescription = I18n.Camera.nightModeDesc,
                    tint = if (nightEvActive) MaterialTheme.colorScheme.primary else Color.White,
                )
            }
            IconButton(onClick = onSwitchCamera) {
                Icon(
                    AppIcons.CameraSwitch,
                    contentDescription = I18n.Camera.switchCameraDesc,
                    tint = Color.White,
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
    message?.let {
        Surface(
            color = Color(0xCC000000),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 64.dp),
        ) {
            Text(
                it,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
    if (focusHint) {
        Surface(
            color = Color(0xCC1A237E),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 64.dp),
        ) {
            Text(
                I18n.Camera.focusHint,
                color = Color.White,
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
    val isSky = pointingState.altDeg > 0.0
    Surface(
        color = if (isSky) Color(0xAA111827) else Color(0xAA7F1D1D),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = if (topBannerVisible) 116.dp else 64.dp),
    ) {
        val dir = when (((pointingState.azDeg + 22.5) % 360 / 45).toInt()) {
            0 -> if (isEn) "N" else "北"
            1 -> if (isEn) "NE" else "东北"
            2 -> if (isEn) "E" else "东"
            3 -> if (isEn) "SE" else "东南"
            4 -> if (isEn) "S" else "南"
            5 -> if (isEn) "SW" else "西南"
            6 -> if (isEn) "W" else "西"
            else -> if (isEn) "NW" else "西北"
        }
        val text = if (pointingState.raDeg != null && pointingState.decDeg != null) {
            val raH = (pointingState.raDeg ?: 0.0) / 15.0
            val raM = ((raH - raH.toInt()) * 60).toInt()
            val dec = pointingState.decDeg ?: 0.0
            if (isEn) {
                "🧭 Az %.0f°(%s) · Alt %.0f° · Hint RA %02dh%02dm Dec %+.0f°".format(
                    pointingState.azDeg, dir, pointingState.altDeg, raH.toInt(), raM, dec,
                )
            } else {
                "🧭 方位 %.0f°(%s) · 仰角 %.0f° · 预估 RA %02dh%02dm Dec %+.0f°".format(
                    pointingState.azDeg, dir, pointingState.altDeg, raH.toInt(), raM, dec,
                )
            }
        } else {
            if (isEn) {
                "🧭 Az %.0f°(%s) · Alt %.0f°".format(pointingState.azDeg, dir, pointingState.altDeg)
            } else {
                "🧭 方位 %.0f°(%s) · 仰角 %.0f°".format(pointingState.azDeg, dir, pointingState.altDeg)
            }
        }
        Text(
            text,
            color = if (isSky) Color(0xFFE0E7FF) else Color(0xFFFECACA),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
internal fun BoxScope.CameraRollLevelIndicator(
    pointingState: DeviceOrientationTracker.DevicePointing,
    topBannerVisible: Boolean,
) {
    val roll = CameraMath.rollDeg(pointingState.right, pointingState.up)
    val level = abs(roll) < 1.0
    Surface(
        color = Color(0xAA111827),
        shape = CircleShape,
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(start = 12.dp)
            .padding(top = if (topBannerVisible) 200.dp else 148.dp)
            .size(58.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2f - 6.dp.toPx()
                val c = Offset(size.width / 2f, size.height / 2f)
                drawCircle(
                    color = Color.White.copy(alpha = 0.25f),
                    radius = r,
                    center = c,
                    style = Stroke(width = 1.5.dp.toPx()),
                )
                val lineLen = r * 1.25f
                val rad = Math.toRadians(roll)
                val dx = cos(rad).toFloat() * lineLen
                val dy = sin(rad).toFloat() * lineLen
                drawLine(
                    color = if (level) Color(0xFF69F0AE) else Color.White.copy(alpha = 0.9f),
                    start = Offset(c.x - dx, c.y - dy),
                    end = Offset(c.x + dx, c.y + dy),
                    strokeWidth = 2.dp.toPx(),
                )
            }
            Text(
                "${"%.0f".format(roll)}°",
                color = if (level) Color(0xFF69F0AE) else Color.White,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 14.dp),
            )
        }
    }
}
