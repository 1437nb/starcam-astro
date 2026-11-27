package com.starcam.astro.ui.result

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.starcam.astro.astro.LocalStarMatcher
import com.starcam.astro.astro.SolveDiagnostics
import com.starcam.astro.data.SolveLogStore
import com.starcam.astro.ui.I18n
import com.starcam.astro.ui.theme.AppThemeMode
import com.starcam.astro.ui.theme.LocaleState
import com.starcam.astro.ui.theme.ThemeState
import java.util.Locale
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val DiagWarningIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "DiagWarning",
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

private val DiagRefreshIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "DiagRefresh",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(17.65f, 6.35f)
            curveTo(16.2f, 4.9f, 14.21f, 4f, 12f, 4f)
            curveTo(7.58f, 4f, 4.01f, 7.58f, 4.01f, 12f)
            curveTo(4.01f, 16.42f, 7.58f, 20f, 12f, 20f)
            curveTo(15.73f, 20f, 18.84f, 17.45f, 19.73f, 14f)
            horizontalLineTo(17.65f)
            curveTo(16.83f, 16.33f, 14.61f, 18f, 12f, 18f)
            curveTo(8.69f, 18f, 6f, 15.31f, 6f, 12f)
            curveTo(6f, 8.69f, 8.69f, 6f, 12f, 6f)
            curveTo(13.66f, 6f, 15.14f, 6.69f, 16.22f, 7.78f)
            lineTo(13f, 11f)
            horizontalLineTo(20f)
            verticalLineTo(4f)
            lineTo(17.65f, 6.35f)
            close()
        }
    }.build()
}

private val DiagZoomIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "DiagZoom",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(7f, 14f)
            horizontalLineTo(5f)
            verticalLineTo(19f)
            horizontalLineTo(10f)
            verticalLineTo(17f)
            horizontalLineTo(7f)
            verticalLineTo(14f)
            close()
            moveTo(5f, 10f)
            horizontalLineTo(7f)
            verticalLineTo(7f)
            horizontalLineTo(10f)
            verticalLineTo(5f)
            horizontalLineTo(5f)
            verticalLineTo(10f)
            close()
            moveTo(17f, 17f)
            horizontalLineTo(14f)
            verticalLineTo(19f)
            horizontalLineTo(19f)
            verticalLineTo(14f)
            horizontalLineTo(17f)
            verticalLineTo(17f)
            close()
            moveTo(14f, 5f)
            verticalLineTo(7f)
            horizontalLineTo(17f)
            verticalLineTo(10f)
            horizontalLineTo(19f)
            verticalLineTo(5f)
            horizontalLineTo(14f)
            close()
        }
    }.build()
}

@Composable
internal fun LoadingContent(
    state: ResultUiState.Loading,
    tip: String,
    stars: List<SolveDiagnostics.DiagStar>,
    starTotal: Int,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var elapsedSec by remember(state.startedAtNanos) { mutableStateOf(0L) }
    LaunchedEffect(state.startedAtNanos) {
        while (true) {
            elapsedSec = ((System.nanoTime() - state.startedAtNanos) / 1_000_000_000L).coerceAtLeast(0L)
            delay(1000)
        }
    }
    val night = ThemeState.mode == AppThemeMode.NIGHT_RED
    val isEn = LocaleState.isEnglish
    val infiniteTransition = rememberInfiniteTransition(label = "solvingMotion")
    val sweepProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweepProgress",
    )
    val starGlowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.40f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "starGlowAlpha",
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (state.bitmap != null) {
            val bw = state.bitmap.width.coerceAtLeast(1)
            val bh = state.bitmap.height.coerceAtLeast(1)
            val clampedAspect = (bw.toFloat() / bh.toFloat()).coerceIn(0.75f, 1.6f)
            var previewBoxSize by remember { mutableStateOf(IntSize.Zero) }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 320.dp)
                    .aspectRatio(clampedAspect)
                    .clip(MaterialTheme.shapes.large)
                    .background(Color.Black)
                    .onSizeChanged { previewBoxSize = it },
                contentAlignment = Alignment.Center,
            ) {
                if (previewBoxSize.width > 0 && previewBoxSize.height > 0) {
                    val fit = min(
                        previewBoxSize.width.toFloat() / bw,
                        previewBoxSize.height.toFloat() / bh,
                    )
                    val dispW = with(LocalDensity.current) { (bw * fit).toInt().toDp() }
                    val dispH = with(LocalDensity.current) { (bh * fit).toInt().toDp() }
                    val fitMod = Modifier.size(dispW, dispH)

                    Image(
                        bitmap = state.bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = fitMod,
                        contentScale = ContentScale.FillBounds,
                    )
                    Canvas(fitMod) {
                        val scanY = sweepProgress * size.height
                        val bandH = 50.dp.toPx()
                        val beamColors = if (night) {
                            listOf(Color.Transparent, Color(0x22FF5252), Color(0x66FF5252))
                        } else {
                            listOf(Color.Transparent, Color(0x227FB3E8), Color(0x667FB3E8))
                        }
                        val beamLineColor = if (night) {
                            Color(0xFFFF7A70).copy(alpha = 0.75f)
                        } else {
                            Color(0xFFB5D8FF).copy(alpha = 0.75f)
                        }
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = beamColors,
                                startY = (scanY - bandH).coerceAtLeast(0f),
                                endY = scanY,
                            ),
                            topLeft = Offset(0f, (scanY - bandH).coerceAtLeast(0f)),
                            size = Size(size.width, bandH.coerceAtMost(scanY)),
                        )
                        drawLine(
                            color = beamLineColor,
                            start = Offset(0f, scanY),
                            end = Offset(size.width, scanY),
                            strokeWidth = 1.5.dp.toPx(),
                        )

                        if (stars.isNotEmpty()) {
                            val glowColor = if (night) Color(0xFFFF6E6E) else Color(0xFFFFC24B)
                            val ringColor = if (night) Color(0xFFFF9E94) else Color(0xFFFFD580)
                            for (s in stars) {
                                val center = Offset(s.x * size.width, s.y * size.height)
                                val baseR = (3.dp.toPx() + 6.dp.toPx() * s.brightness01).coerceAtLeast(3.dp.toPx())
                                drawCircle(
                                    color = glowColor.copy(alpha = starGlowAlpha * 0.35f),
                                    radius = baseR * 1.6f,
                                    center = center,
                                )
                                drawCircle(
                                    color = ringColor.copy(alpha = starGlowAlpha),
                                    radius = baseR,
                                    center = center,
                                    style = Stroke(width = 1.4.dp.toPx()),
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.large,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.5.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = state.phase.label(isEn),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                state.budgetSec?.let { budget ->
                    Spacer(Modifier.height(10.dp))
                    val progress = if (budget > 0) {
                        (elapsedSec.toFloat() / budget.toFloat()).coerceIn(0f, 1f)
                    } else 0f
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (isEn) "Elapsed ${elapsedSec}s · Budget ${budget}s" else "已用 ${elapsedSec}秒 · 总预算 ${budget}秒",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                if (stars.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            text = I18n.Result.starsDetectedMatching(
                                if (starTotal > 0) starTotal else stars.size,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        // 天文冷知识（§0.33.4）：等待时随机展示一条
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(
                text = tip,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }

        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.height(44.dp),
        ) {
            Text(I18n.cancel)
        }
    }
}

/**
 * 失败页（§0.33 可视化诊断 + §0.143 结构化诊断卡片与重试主次分层）：
 * 照片上圈出 App 检出的全部星点，以结构化遥测胶囊与诊断建议卡呈现根因。
 */
@Composable
internal fun ErrorContent(
    message: String,
    bitmap: Bitmap?,
    diagnostics: SolveDiagnostics?,
    elapsedMs: Long,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onOpenViewer: (Bitmap, Bitmap) -> Unit,
) {
    // §0.81：全分辨率诊断图合成要移出主线程
    val scope = rememberCoroutineScope()
    val isEn = LocaleState.isEnglish
    val night = ThemeState.mode == AppThemeMode.NIGHT_RED
    var openingDiagViewer by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (bitmap != null && diagnostics != null && diagnostics.stars.isNotEmpty()) {
            val bw = bitmap.width.coerceAtLeast(1)
            val bh = bitmap.height.coerceAtLeast(1)
            val clampedAspect = (bw.toFloat() / bh.toFloat()).coerceIn(0.75f, 1.6f)
            var diagBoxSize by remember { mutableStateOf(IntSize.Zero) }
            val hudBg = if (night) Color(0xC81A0505) else Color(0xB8091022)
            val hudBorder = if (night) Color(0x665A2622) else Color(0x38FFFFFF)
            val hudFg = if (night) Color(0xFFF2ACA4) else Color(0xFFF2F5FC)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 210.dp, max = 350.dp)
                    .aspectRatio(clampedAspect)
                    .clip(MaterialTheme.shapes.large)
                    .background(Color.Black)
                    .onSizeChanged { diagBoxSize = it }
                    .clickable(enabled = !openingDiagViewer) {
                        openingDiagViewer = true
                        scope.launch {
                            try {
                                val anno = withContext(Dispatchers.IO) {
                                    renderDiagnosticBitmap(bitmap, diagnostics)
                                }
                                onOpenViewer(bitmap, anno)
                            } finally {
                                openingDiagViewer = false
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (diagBoxSize.width > 0 && diagBoxSize.height > 0) {
                    val fit = min(
                        diagBoxSize.width.toFloat() / bw,
                        diagBoxSize.height.toFloat() / bh,
                    )
                    val dispW = with(LocalDensity.current) { (bw * fit).toInt().toDp() }
                    val dispH = with(LocalDensity.current) { (bh * fit).toInt().toDp() }
                    val fitMod = Modifier.size(dispW, dispH)

                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = if (isEn) "Star detection diagnostic" else "星点检测标注图",
                        modifier = fitMod,
                        contentScale = ContentScale.FillBounds,
                    )
                    Canvas(fitMod) {
                        val circleColor = if (night) Color(0xFFFF7A70) else Color(0xFFFFC24B)
                        for (s in diagnostics.stars) {
                            drawCircle(
                                color = circleColor,
                                radius = (2.2f + 3.6f * s.brightness01).dp.toPx(),
                                center = Offset(s.x * size.width, s.y * size.height),
                                style = Stroke(width = 1.4.dp.toPx()),
                            )
                        }
                    }
                }

                // 左上角：检星图例胶囊
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp),
                    shape = CircleShape,
                    color = hudBg,
                    border = BorderStroke(1.dp, hudBorder),
                ) {
                    Text(
                        text = I18n.Result.diagLegend(diagnostics.starCount),
                        color = hudFg,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }

                // 右下角：点击放大胶囊
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(10.dp),
                    shape = CircleShape,
                    color = hudBg,
                    border = BorderStroke(1.dp, hudBorder),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(
                            imageVector = DiagZoomIcon,
                            contentDescription = null,
                            tint = hudFg,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = if (isEn) "Zoom" else "点击放大",
                            color = hudFg,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        // 结构化诊断摘要卡片
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.large,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.padding(16.dp)) {
                val isSevere = diagnostics == null || diagnostics.verdict == SolveDiagnostics.Verdict.TOO_FEW
                val badgeContainer = if (isSevere) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.tertiaryContainer
                }
                val badgeTint = if (isSevere) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onTertiaryContainer
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = badgeContainer,
                    ) {
                        Box(
                            modifier = Modifier.size(36.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = DiagWarningIcon,
                                contentDescription = null,
                                tint = badgeTint,
                                modifier = Modifier.size(19.dp),
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = diagnostics?.verdictTitle(isEn) ?: I18n.Result.solveFailed,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = if (isEn) "Diagnostic Summary" else "识别失败原因与排查建议",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                val solveTimeVal = I18n.Result.formatSolveTime(elapsedMs)
                if (diagnostics != null || solveTimeVal.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        diagnostics?.let { diag ->
                            if (!diag.isOnlineOnlyFailure) {
                                DiagMetricPill(
                                    label = if (isEn) "Detected Stars" else "检出星点",
                                    value = if (isEn) "${diag.starCount}" else "${diag.starCount} 颗",
                                )
                            }
                            if (diag.enginesTried.isNotEmpty()) {
                                DiagMetricPill(
                                    label = if (isEn) "Engines Tried" else "已尝试引擎",
                                    value = diag.enginesTried.joinToString(" → "),
                                )
                            }
                        }
                        if (solveTimeVal.isNotEmpty()) {
                            DiagMetricPill(
                                label = I18n.Result.labelSolveTime,
                                value = solveTimeVal,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                if (diagnostics != null) {
                    Text(
                        text = diagnostics.verdictDetail(isEn),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp),
                        )
                    }

                    // §0.70 技术细节（可折叠）
                    SolveLogDetails(diagnostics)
                } else {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        // 主次分明的行动按钮：主按钮「重试」，次按钮「返回主页」
        Button(
            onClick = onRetry,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Icon(
                imageVector = DiagRefreshIcon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = I18n.retry,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp),
        ) {
            Text(I18n.Result.backHome)
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun DiagMetricPill(
    label: String,
    value: String,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * §0.70 识别失败的技术细节（可折叠）。
 *
 * 完整的失败现场（含匹配器实际吃到的像素）落在应用日志目录，
 * 见 [SolveLogStore]，设置页可导出。
 */
@Composable
private fun SolveLogDetails(diagnostics: SolveDiagnostics?) {
    val ctx = LocalContext.current
    val isEn = LocaleState.isEnglish
    var expanded by remember { mutableStateOf(false) }
    val detail = remember(diagnostics, isEn) {
        buildString {
            if (isEn) {
                appendLine("Stars detected: ${diagnostics?.starCount ?: 0}")
                appendLine("Engine trace: ${diagnostics?.enginesTried?.joinToString(" → ") ?: "-"}")
                diagnostics?.onlineFailureReason?.takeIf { it.isNotBlank() }?.let {
                    appendLine("Online error: $it")
                }
                fun rate(best: Int?, inFrame: Int?): String =
                    if (best != null && inFrame != null && inFrame > 0) {
                        val r = best.toFloat() / inFrame
                        "%.2f%s".format(Locale.ROOT, r, if (r >= 0.20f) " passed" else " below threshold")
                    } else "-"
                val shallowVote = LocalStarMatcher.debugShallowVoteStats
                if (shallowVote != null) {
                    appendLine("── Shallow Catalog (mag≤4.0) ──")
                    appendLine("  Voting: $shallowVote")
                    appendLine("  Scoring: ${LocalStarMatcher.debugShallowScoredStats ?: "-"}")
                    appendLine(
                        "  Best aligned: ${LocalStarMatcher.debugShallowScoredBest ?: 0}" +
                            " / in-frame ${LocalStarMatcher.debugShallowScoredInFrame ?: 0}" +
                            " (alignment rate ${rate(LocalStarMatcher.debugShallowScoredBest, LocalStarMatcher.debugShallowScoredInFrame)})",
                    )
                }
                appendLine("── Deep Catalog Retry (mag≤6.5) ──")
                appendLine("  Voting: ${LocalStarMatcher.debugVoteStats ?: "-"}")
                appendLine("  Scoring: ${LocalStarMatcher.debugScoredStats ?: "-"}")
                appendLine(
                    "  Best aligned: ${LocalStarMatcher.debugScoredBest ?: 0}" +
                        " / in-frame ${LocalStarMatcher.debugScoredInFrame ?: 0}" +
                        " (alignment rate ${rate(LocalStarMatcher.debugScoredBest, LocalStarMatcher.debugScoredInFrame)})",
                )
            } else {
                appendLine("检星 ${diagnostics?.starCount ?: 0} 颗")
                appendLine("引擎轨迹：${diagnostics?.enginesTried?.joinToString(" → ") ?: "-"}")
                diagnostics?.onlineFailureReason?.takeIf { it.isNotBlank() }?.let {
                    appendLine("在线失败原因：$it")
                }
                fun rate(best: Int?, inFrame: Int?): String =
                    if (best != null && inFrame != null && inFrame > 0) {
                        val r = best.toFloat() / inFrame
                        "%.2f%s".format(Locale.ROOT, r, if (r >= 0.20f) " 过门" else " 未过门")
                    } else "-"
                val shallowVote = LocalStarMatcher.debugShallowVoteStats
                if (shallowVote != null) {
                    appendLine("── 浅域（mag≤4.0）──")
                    appendLine("  投票轮：$shallowVote")
                    appendLine("  打分轮：${LocalStarMatcher.debugShallowScoredStats ?: "-"}")
                    appendLine(
                        "  最优对齐 ${LocalStarMatcher.debugShallowScoredBest ?: 0} 颗" +
                            " / 画面内 ${LocalStarMatcher.debugShallowScoredInFrame ?: 0}" +
                            "（对齐率 ${rate(LocalStarMatcher.debugShallowScoredBest, LocalStarMatcher.debugShallowScoredInFrame)}）",
                    )
                }
                appendLine("── 深域重试（mag≤6.5）──")
                appendLine("  投票轮：${LocalStarMatcher.debugVoteStats ?: "-"}")
                appendLine("  打分轮：${LocalStarMatcher.debugScoredStats ?: "-"}")
                appendLine(
                    "  最优对齐 ${LocalStarMatcher.debugScoredBest ?: 0} 颗" +
                        " / 画面内 ${LocalStarMatcher.debugScoredInFrame ?: 0} 颗" +
                        "（对齐率 ${rate(LocalStarMatcher.debugScoredBest, LocalStarMatcher.debugScoredInFrame)}）",
                )
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    TextButton(
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = if (isEn) {
                if (expanded) "▾ Hide Technical Details" else "▸ View Technical Details (copy for feedback)"
            } else {
                if (expanded) "▾ 隐藏技术细节" else "▸ 查看技术细节（反馈问题时可复制）"
            },
            style = MaterialTheme.typography.labelLarge,
        )
    }

    AnimatedVisibility(
        visible = expanded,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = {
                            val clip = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clip.setPrimaryClip(
                                ClipData.newPlainText(
                                    if (isEn) "StarCam Diagnostics" else "StarCam 诊断",
                                    detail,
                                ),
                            )
                            Toast.makeText(
                                ctx,
                                if (isEn) "Diagnostics copied" else "已复制技术诊断细节",
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                        label = { Text(if (isEn) "Copy" else "复制") },
                    )
                    AssistChip(
                        onClick = {
                            val dir = SolveLogStore.logDir(ctx)
                            val clip = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clip.setPrimaryClip(
                                ClipData.newPlainText(
                                    if (isEn) "StarCam Log Path" else "StarCam 日志路径",
                                    dir.absolutePath,
                                ),
                            )
                            Toast.makeText(
                                ctx,
                                if (isEn) "Log path copied" else "已复制日志目录路径",
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                        label = { Text(if (isEn) "Copy Log Path" else "复制日志路径") },
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (isEn) {
                        "Full diagnostic dump (including matcher input pixels) is saved in the app log directory and can be exported in Settings → Recognition Log."
                    } else {
                        "完整现场（含匹配器输入的像素）已存于应用日志目录，可在「设置 → 识别日志」导出。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
