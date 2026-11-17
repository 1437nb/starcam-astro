package com.starcam.astro.ui.result

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.starcam.astro.astro.LocalStarMatcher
import com.starcam.astro.astro.SolveDiagnostics
import com.starcam.astro.data.SolveLogStore
import com.starcam.astro.ui.I18n
import com.starcam.astro.ui.theme.LocaleState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (state.bitmap != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .aspectRatio(state.bitmap.width.toFloat() / state.bitmap.height.toFloat())
                    .clip(MaterialTheme.shapes.large)
                    .background(Color.Black),
            ) {
                Image(
                    bitmap = state.bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
                Canvas(Modifier.fillMaxSize()) {
                    // 1. Radar scanning beam across night sky photo
                    val scanY = sweepProgress * size.height
                    val bandH = 50.dp.toPx()
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color(0x227FB3E8),
                                Color(0x667FB3E8),
                            ),
                            startY = (scanY - bandH).coerceAtLeast(0f),
                            endY = scanY,
                        ),
                        topLeft = Offset(0f, (scanY - bandH).coerceAtLeast(0f)),
                        size = Size(size.width, bandH.coerceAtMost(scanY)),
                    )
                    drawLine(
                        color = Color(0xFFB5D8FF).copy(alpha = 0.75f),
                        start = Offset(0f, scanY),
                        end = Offset(size.width, scanY),
                        strokeWidth = 1.5.dp.toPx(),
                    )

                    // 2. Detected candidate stars with celestial breathing glow
                    if (stars.isNotEmpty()) {
                        for (s in stars) {
                            val center = Offset(s.x * size.width, s.y * size.height)
                            val baseR = (3.dp.toPx() + 6.dp.toPx() * s.brightness01).coerceAtLeast(3.dp.toPx())
                            drawCircle(
                                color = Color(0xFFFFC24B).copy(alpha = starGlowAlpha * 0.35f),
                                radius = baseR * 1.6f,
                                center = center,
                            )
                            drawCircle(
                                color = Color(0xFFFFD580).copy(alpha = starGlowAlpha),
                                radius = baseR,
                                center = center,
                                style = Stroke(width = 1.4.dp.toPx()),
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(
            state.phase.label(LocaleState.isEnglish),
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        state.budgetSec?.let { budget ->
            val isEn = LocaleState.isEnglish
            Text(
                if (isEn) "Elapsed ${elapsedSec}s · Budget ${budget}s" else "已用 ${elapsedSec}秒 · 总预算 ${budget}秒",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            state.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
        if (stars.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                I18n.Result.starsDetectedMatching(
                    if (starTotal > 0) starTotal else stars.size,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(12.dp))
        // 天文冷知识（§0.33.4）：等待时随机展示一条
        Text(
            "🔭 $tip",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Text(I18n.cancel)
        }
    }
}

/**
 * 失败页（§0.33 可视化诊断）：照片上圈出 App 检出的全部星点，
 * 用"看得见"代替猜测——星太少/够多但没匹配上，给不同的下一步建议。
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
    // §0.81：全分辨率诊断图合成要移出主线程（见下方 clickable）
    val scope = rememberCoroutineScope()
    val isEn = LocaleState.isEnglish
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (bitmap != null && diagnostics != null && diagnostics.stars.isNotEmpty()) {
            // 星点标注图：红圈 = App 检测到的星点，圈大小随亮度；点击放大（§0.34）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat())
                    .clip(MaterialTheme.shapes.large)
                    .background(Color.Black)
                    .clickable {
                        // §0.81：renderDiagnosticBitmap 会新建全分辨率位图（2200px 级
                        // ≈14MB）并跑一遍全星表投影 —— 原先在点击回调（主线程）里同步执行。
                        scope.launch {
                            val anno = withContext(Dispatchers.IO) {
                                renderDiagnosticBitmap(bitmap, diagnostics)
                            }
                            onOpenViewer(bitmap, anno)
                        }
                    },
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = if (isEn) "Star detection diagnostic" else "星点检测标注图",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                Canvas(Modifier.fillMaxSize()) {
                    val circleColor = Color(0xFFFFC24B)
                    for (s in diagnostics.stars) {
                        drawCircle(
                            color = circleColor,
                            radius = (2.2f + 3.6f * s.brightness01).dp.toPx(),
                            center = Offset(s.x * size.width, s.y * size.height),
                            style = Stroke(width = 1.4.dp.toPx()),
                        )
                    }
                }
                // 点击放大提示
                Text(
                    I18n.tapToZoom,
                    color = Color.White.copy(alpha = 0.65f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.35f))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                I18n.Result.diagLegend(diagnostics.starCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text("😕", fontSize = 44.sp)
        }

        if (diagnostics != null) {
            Spacer(Modifier.height(16.dp))
            Text(
                diagnostics.verdictTitle(isEn),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                diagnostics.verdictDetail(isEn),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (diagnostics.enginesText(isEn).isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    diagnostics.enginesText(isEn),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 原引导文案（API Key / GPS 提示）降级为补充说明
            Spacer(Modifier.height(10.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            // §0.70 技术细节（可折叠）：把引擎尝试轨迹与内部统计摊开。
            // 用户反馈「识别不了」时，复制这段就能让开发者定位 —— 比截图可靠。
            SolveLogDetails(diagnostics)
        } else {
            Spacer(Modifier.height(12.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        // §0.90 识别耗时：失败页同样给出本次尝试花了多久（求解前失败时为空，不显示）
        val solveTimeText = I18n.Result.solveTimeLine(elapsedMs)
        if (solveTimeText.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                solveTimeText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onRetry) { Text(I18n.retry) }
            OutlinedButton(onClick = onBack) { Text(I18n.Result.backHome) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * §0.70 识别失败的技术细节（可折叠）。
 *
 * 为什么要有它：用户报「识别不了」时，截图只能看到「星点充足但未匹配」这类
 * 笼统结论，无法定位。这里把**引擎尝试轨迹**与**自研引擎内部统计**
 * （投票轮/打分轮）摊开，并可一键复制 —— 开发者据此就能判断是提星、
 * 投票、尺度还是星表域的问题。
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
                fun rate(best: Int?, inFrame: Int?): String =
                    if (best != null && inFrame != null && inFrame > 0) {
                        val r = best.toFloat() / inFrame
                        "%.2f%s".format(r, if (r >= 0.20f) " passed" else " below threshold")
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
                // §0.86 逐轮显示：§0.75 之后一次求解最多跑两遍（浅域 + 深域重试），
                // 而调试字段是单槽、后写的深域会覆盖浅域。实测 5031 浅域打分轮
                // 16/30 可解、深域只有 7/715 的伪解 —— 只显示后者会把死因判错，
                // 所以两轮分开列，并各自标出对齐率是否过了 20% 门槛。
                fun rate(best: Int?, inFrame: Int?): String =
                    if (best != null && inFrame != null && inFrame > 0) {
                        val r = best.toFloat() / inFrame
                        "%.2f%s".format(r, if (r >= 0.20f) " 过门" else " 未过门")
                    } else "-"
                val shallowVote = LocalStarMatcher.debugShallowVoteStats
                if (shallowVote != null) {
                    appendLine("── 浅域（mag≤4.0）──")
                    appendLine("  投票轮：$shallowVote")
                    appendLine("  打分轮：${LocalStarMatcher.debugShallowScoredStats ?: "-"}")
                    appendLine(
                        "  最优对齐 ${LocalStarMatcher.debugShallowScoredBest ?: 0} 颗" +
                            " / 画面内 ${LocalStarMatcher.debugShallowScoredInFrame ?: 0} 颗" +
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
    Spacer(Modifier.height(14.dp))
    TextButton(onClick = { expanded = !expanded }) {
        Text(
            if (isEn) {
                if (expanded) "▾ Hide Technical Details" else "▸ View Technical Details (can copy for feedback)"
            } else {
                if (expanded) "▾ 隐藏技术细节" else "▸ 查看技术细节（反馈问题时可复制）"
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (expanded) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
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
                        },
                        label = { Text(if (isEn) "Copy Log Path" else "复制日志路径") },
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (isEn) {
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
