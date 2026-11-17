package com.starcam.astro.ui.camera

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.starcam.astro.astro.CameraMath
import com.starcam.astro.ui.I18n
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 相机底部曝光与视场控件面板（AR FOV 滑块 + Camera2 Pro 手动 ISO/快门 + EV 补偿 + 快门按钮）（§0.142 模块化解耦）。
 */
@Composable
internal fun BoxScope.CameraExposureControls(
    showArFovSlider: Boolean,
    arFovDeg: Float,
    fovAutoCalibrated: Boolean,
    onArFovChange: (Float) -> Unit,
    onArFovChangeFinished: () -> Unit,
    isoRangeState: IntRange?,
    exposureRangeState: LongRange?,
    proMode: Boolean,
    proIso: Int,
    proShutterIdx: Int,
    onToggleProMode: () -> Unit,
    onProIsoChange: (Int) -> Unit,
    onProShutterChange: (Int) -> Unit,
    evIndex: Int,
    maxEvIndex: Int,
    onEvChange: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 128.dp)
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showArFovSlider) {
            Text(
                if (fovAutoCalibrated) {
                    I18n.Ar.fovAuto.format(Locale.ROOT, arFovDeg)
                } else {
                    I18n.Ar.fovLabel.format(Locale.ROOT, arFovDeg)
                },
                color = if (fovAutoCalibrated) Color(0xFFB9F6CA) else Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
            Slider(
                value = arFovDeg,
                onValueChange = onArFovChange,
                onValueChangeFinished = onArFovChangeFinished,
                valueRange = 40f..90f,
            )
        }

        val proSupported = isoRangeState != null && exposureRangeState != null &&
            exposureRangeState.first <= 33_333_333L && exposureRangeState.last >= 1_000_000_000L
        if (proSupported) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleProMode)
                    .padding(vertical = 2.dp),
            ) {
                Text(
                    if (proMode) I18n.Pro.proOn else I18n.Pro.proOff,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (proMode) MaterialTheme.colorScheme.primary else Color.White,
                )
            }
            if (proMode) {
                val stops = CameraMath.shutterStopsSec
                val sec = stops[proShutterIdx.coerceIn(0, stops.lastIndex)]
                Text(
                    "ISO ${CameraMath.clampIso(proIso, isoRangeState)} · ${CameraMath.shutterLabel(sec)}",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    I18n.Pro.isoLabel,
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall,
                )
                Slider(
                    value = proIso.toFloat(),
                    onValueChange = {
                        onProIsoChange(CameraMath.clampIso((it.roundToInt() / 100) * 100, isoRangeState))
                    },
                    valueRange = (isoRangeState?.first ?: 100).toFloat()..
                        (isoRangeState?.last ?: 6400).toFloat(),
                )
                Text(
                    I18n.Pro.shutterLabel,
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall,
                )
                Slider(
                    value = proShutterIdx.toFloat(),
                    onValueChange = { onProShutterChange(it.roundToInt()) },
                    valueRange = 0f..(stops.lastIndex.toFloat()),
                    steps = stops.size - 2,
                )
                if (sec > 1.0) {
                    Text(
                        I18n.Pro.longExposureWarn,
                        color = Color(0xFFFFE082),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        }

        if (!proMode) {
            Text(
                I18n.Camera.evLabel(evIndex),
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (maxEvIndex > 0) {
                Slider(
                    value = evIndex.toFloat(),
                    onValueChange = { onEvChange(it.roundToInt()) },
                    valueRange = -maxEvIndex.toFloat()..maxEvIndex.toFloat(),
                    steps = (maxEvIndex * 2 - 1).coerceAtLeast(0),
                )
            }
        }

        if (evIndex > 0) {
            Surface(
                color = Color(0xCC1A237E),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Text(
                    I18n.Camera.nightGuidance,
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
internal fun BoxScope.CameraShutterButton(
    isCapturing: Boolean,
    onTakePhoto: () -> Unit,
) {
    val shutterScale by animateFloatAsState(
        targetValue = if (isCapturing) 0.86f else 1.0f,
        animationSpec = spring(
            stiffness = Spring.StiffnessMedium,
            dampingRatio = Spring.DampingRatioMediumBouncy,
        ),
        label = "shutterScale",
    )
    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (isCapturing) {
            val infinitePulse = rememberInfiniteTransition(label = "shutterRing")
            val ringAlpha by infinitePulse.animateFloat(
                initialValue = 0.35f,
                targetValue = 0.90f,
                animationSpec = infiniteRepeatable(
                    animation = tween(700, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "ringAlpha",
            )
            Surface(
                shape = CircleShape,
                color = Color.Transparent,
                modifier = Modifier.size(86.dp),
                border = BorderStroke(2.dp, Color(0xFFFFC24B).copy(alpha = ringAlpha)),
            ) {}
        }
        Surface(
            onClick = { if (!isCapturing) onTakePhoto() },
            shape = CircleShape,
            color = Color.White,
            modifier = Modifier
                .size(72.dp)
                .scale(shutterScale),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Surface(
                    shape = CircleShape,
                    color = if (isCapturing) Color(0xFFFFC24B).copy(alpha = 0.65f) else Color.Transparent,
                    modifier = Modifier.size(58.dp),
                    border = BorderStroke(4.dp, Color.Black.copy(alpha = 0.3f)),
                ) {}
            }
        }
    }
}
