package com.starcam.astro.ui.result

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.widget.Toast
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.starcam.astro.astro.AstroTips
import com.starcam.astro.astro.DemoSolver
import com.starcam.astro.astro.OverlayRenderer
import com.starcam.astro.astro.PlateSolveException
import com.starcam.astro.astro.SkyRegion
import com.starcam.astro.astro.SolveDiagnostics
import com.starcam.astro.astro.SolveEngine
import com.starcam.astro.astro.SolveResult
import com.starcam.astro.astro.StarChartOverlay
import com.starcam.astro.astro.StarNames
import com.starcam.astro.astro.StarSolver
import com.starcam.astro.astro.WcsTransform
import com.starcam.astro.data.HistoryStore
import com.starcam.astro.data.SettingsRepository
import com.starcam.astro.data.StatsStore
import com.starcam.astro.ui.theme.AppThemeMode
import com.starcam.astro.ui.theme.ThemeState
import com.starcam.astro.util.ImageUtils
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 识别结果页状态 */
private sealed interface ResultUiState {
    data class Loading(val bitmap: Bitmap? = null, val message: String) : ResultUiState
    data class Success(
        val solve: SolveResult,
        val bitmap: Bitmap,
        val stars: List<StarChartOverlay.Star2D>,
        val lines: List<StarChartOverlay.Line2D>,
        val labels: List<StarChartOverlay.Label2D>,
        /** §0.43c：梅西耶深空天体标记（投影到画面内的可见项） */
        val messier: List<StarChartOverlay.Messier2D>,
        val isDemo: Boolean,
        /** 使用的识别引擎；null 表示演示模式 */
        val engine: SolveEngine?,
        /** 引擎详情（索引档位/内点数/任务号等） */
        val engineDetail: String?,
    ) : ResultUiState
    data class Error(
        val message: String,
        /** 失败可视化诊断（§0.33）：照片 + 检出星点标注；null 为无法诊断的失败 */
        val bitmap: Bitmap? = null,
        val diagnostics: SolveDiagnostics? = null,
    ) : ResultUiState
}

/**
 * 识别结果页：展示照片 + 星座连线/星名叠加，以及天区信息。
 * [demoRegion] 非空时走离线演示流程（不联网）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(
    imagePath: String,
    demoRegion: SkyRegion?,
    settings: SettingsRepository,
    onBack: () -> Unit,
    onRetake: () -> Unit,
) {
    val context = LocalContext.current
    var state by remember { mutableStateOf<ResultUiState>(ResultUiState.Loading(null, "准备中…")) }
    var attempt by remember { mutableIntStateOf(0) }
    // 识别等待时的天文冷知识（§0.33.4）：每张照片/每次重试换一条
    val tip = remember(imagePath, demoRegion, attempt) { AstroTips.random() }
    // 全屏缩放查看器（§0.34/§0.37）：成功页/失败页点击图片打开
    // Triple(原图, 标注图, 标题)，查看器内可切换原图/标注
    var viewer by remember { mutableStateOf<Triple<Bitmap, Bitmap, String>?>(null) }
    // 加载阶段实时预览：本地提星完成后立即回调（§0.36）
    var detectedStars by remember { mutableStateOf<List<SolveDiagnostics.DiagStar>>(emptyList()) }

    // 识别成功 → 投影星表并切换到成功页；失败返回 false
    fun showSuccess(
        solve: SolveResult,
        bitmap: Bitmap,
        engine: SolveEngine?,
        engineDetail: String?,
        isDemo: Boolean,
    ): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        val rawWcs = solve.wcs ?: run {
            state = ResultUiState.Error("识别结果缺少坐标系信息，请重试")
            return false
        }
        // 若求解尺寸与显示尺寸不一致，做等比换算
        val wcs = if (solve.imageWidth == w && solve.imageHeight == h) {
            rawWcs
        } else {
            scaleWcs(rawWcs, solve.imageWidth, solve.imageHeight, w, h)
        }
        val stars = StarChartOverlay.projectStars(wcs, w, h)
        val lines = StarChartOverlay.projectLines(stars)
        val labels = StarChartOverlay.constellationLabels(stars)
        val messier = StarChartOverlay.projectMessier(wcs, w, h)
        state = ResultUiState.Success(solve, bitmap, stars, lines, labels, messier, isDemo, engine, engineDetail)
        return true
    }

    LaunchedEffect(imagePath, demoRegion, attempt) {
        state = ResultUiState.Loading(null, "准备照片…")
        detectedStars = emptyList()
        try {
            if (demoRegion != null) {
                // 离线演示：内置天区合成的模拟星空照片，不与真实求解链路交互
                val display = withContext(Dispatchers.IO) {
                    ImageUtils.decodeSampledBitmap(imagePath, 2200)
                }
                if (display == null) {
                    state = ResultUiState.Error("无法读取照片文件")
                    return@LaunchedEffect
                }
                state = ResultUiState.Loading(display, "正在生成模拟星空…")
                delay(800)
                val solve = DemoSolver.solveFor(demoRegion)
                if (!showSuccess(solve, display, null, null, isDemo = true)) return@LaunchedEffect
            } else {
                // 真实照片：三层引擎调度（官方 astrometry 本地 → 内置星表宽场 → 在线 nova）
                val display = withContext(Dispatchers.IO) {
                    ImageUtils.decodeSampledBitmap(imagePath, 2200)
                }
                if (display == null) {
                    state = ResultUiState.Error("无法读取照片文件")
                    return@LaunchedEffect
                }
                state = ResultUiState.Loading(display, "准备识别…")
                var diag: SolveDiagnostics? = null
                val t0 = System.currentTimeMillis() // 识别打点计时（§0.40）
                val result = StarSolver.solve(
                    context, display, imagePath, settings,
                    onProgress = { msg -> state = ResultUiState.Loading(display, msg) },
                    onDiagnostics = { diag = it },
                    onStarsDetected = { detectedStars = it },
                )
                val elapsedMs = System.currentTimeMillis() - t0
                if (result == null) {
                    StatsStore.record(context, ok = false, engine = "无", ms = elapsedMs)
                    val message = if (!settings.hasApiKey) {
                        "本地识别未能匹配星图。\n\n" +
                            "可到「设置」中填写 astrometry.net 免费 API Key 启用在线识别\n" +
                            "（https://nova.astrometry.net/api_help 注册即可获得）。"
                    } else {
                        "本地与在线引擎均未能匹配星图。\n" +
                            "可尝试重新拍摄（避免过曝或抖动），或在「设置」中调整识别引擎模式。"
                    } + "\n💡 拍摄时开启定位可缩小搜索天区、提高成功率。"
                    state = ResultUiState.Error(message, display, diag)
                    return@LaunchedEffect
                }
                if (!showSuccess(
                        result.solve,
                        result.displayBitmap,
                        result.engine,
                        result.detail,
                        isDemo = false,
                    )
                ) return@LaunchedEffect
                // 识别打点（§0.40）：成功记录引擎与耗时
                StatsStore.record(
                    context,
                    ok = true,
                    engine = result.engine?.name ?: "?",
                    ms = elapsedMs,
                )
                // 写入识别历史（成功后）：时间/路径/天区/引擎/最近星座
                try {
                    HistoryStore.save(
                        context,
                        HistoryStore.Entry(
                            timestamp = System.currentTimeMillis(),
                            imagePath = imagePath,
                            raDeg = result.solve.raDeg,
                            decDeg = result.solve.decDeg,
                            fovDeg = result.solve.fieldWidthDeg,
                            engine = result.detail ?: (result.engine?.name ?: ""),
                            constellation = HistoryStore.nearestConstellationZh(
                                result.solve.raDeg, result.solve.decDeg,
                            ),
                        ),
                    )
                } catch (_: Exception) {
                }
            }
        } catch (e: PlateSolveException) {
            state = ResultUiState.Error(e.message ?: "识别失败")
        } catch (e: Exception) {
            state = ResultUiState.Error("识别失败：${e.message ?: "未知错误"}")
        }
    }

    Box {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("识别结果") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            },
        ) { padding ->
            when (val s = state) {
                is ResultUiState.Loading -> LoadingContent(
                    s, tip, detectedStars, Modifier.padding(padding),
                )
                is ResultUiState.Success -> SuccessContent(
                    state = s,
                    modifier = Modifier.padding(padding),
                    onRetry = { attempt++ },
                    onRetake = onRetake,
                    onBack = onBack,
                    onOpenViewer = { orig, anno ->
                        viewer = Triple(orig, anno, "星空与星座连线（双指缩放查看）")
                    },
                )
                is ResultUiState.Error -> ErrorContent(
                    message = s.message,
                    bitmap = s.bitmap,
                    diagnostics = s.diagnostics,
                    modifier = Modifier.padding(padding),
                    onRetry = { attempt++ },
                    onBack = onBack,
                    onOpenViewer = { orig, anno ->
                        viewer = Triple(orig, anno, "星点检测标注（双指缩放查看）")
                    },
                )
            }
        }
        viewer?.let { (orig, anno, title) ->
            ZoomableImageViewer(orig, anno, title, onClose = { viewer = null })
        }
    }
}

@Composable
private fun LoadingContent(
    state: ResultUiState.Loading,
    tip: String,
    stars: List<SolveDiagnostics.DiagStar>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        state.bitmap?.let { bitmap ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat())
                    .background(Color.Black, RoundedCornerShape(16.dp)),
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "星空照片",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                )
                // 加载阶段实时星点预览（§0.36）：提星完成后立即画上金色圈
                if (stars.isNotEmpty()) {
                    Canvas(Modifier.fillMaxSize()) {
                        val circleColor = Color(0xFFFFC24B)
                        for (s in stars) {
                            drawCircle(
                                color = circleColor,
                                radius = (2.2f + 3.6f * s.brightness01).dp.toPx(),
                                center = Offset(s.x * size.width, s.y * size.height),
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
            state.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
        if (stars.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                "已检测到 ${stars.size} 颗星点，正在匹配星表…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(12.dp))
        // 天文冷知识（§0.33.4）：等待时随机展示一条
        Text(
            "🔭 $tip",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}

/**
 * 失败页（§0.33 可视化诊断）：照片上圈出 App 检出的全部星点，
 * 用"看得见"代替猜测——星太少/够多但没匹配上，给不同的下一步建议。
 */
@Composable
private fun ErrorContent(
    message: String,
    bitmap: Bitmap?,
    diagnostics: SolveDiagnostics?,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onOpenViewer: (Bitmap, Bitmap) -> Unit,
) {
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
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black)
                    .clickable {
                        onOpenViewer(bitmap, renderDiagnosticBitmap(bitmap, diagnostics))
                    },
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "星点检测标注图",
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
                    "🔍 点击图片放大查看",
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.35f))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "◯ 金色圈 = App 检测到的 ${diagnostics.starCount} 个星点",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text("😕", fontSize = 44.sp)
        }

        if (diagnostics != null) {
            Spacer(Modifier.height(16.dp))
            Text(
                diagnostics.verdictTitle(),
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                diagnostics.verdictDetail(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (diagnostics.enginesText().isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    diagnostics.enginesText(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
            // 原引导文案（API Key / GPS 提示）降级为补充说明
            Spacer(Modifier.height(10.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
            )
        } else {
            Spacer(Modifier.height(12.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onRetry) { Text("重试") }
            OutlinedButton(onClick = onBack) { Text("返回主页") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SuccessContent(
    state: ResultUiState.Success,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit,
    onRetake: () -> Unit,
    onBack: () -> Unit,
    onOpenViewer: (Bitmap, Bitmap) -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        StarPhotoOverlay(
            state = state,
            onTap = { onOpenViewer(state.bitmap, renderAnnotatedBitmap(state)) },
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            SaveToGalleryButton(state)
        }
        Spacer(Modifier.height(8.dp))
        InfoPanel(state, Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onRetry,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) { Text("重新识别") }
            OutlinedButton(onClick = onRetake, modifier = Modifier.weight(1f)) { Text("重拍一张") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** 保存到相册：把照片 + 星座标注合成图存入本地（Android 10+ 直接入相册） */
@Composable
private fun SaveToGalleryButton(state: ResultUiState.Success) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }

    TextButton(
        onClick = {
            if (saving) return@TextButton
            saving = true
            scope.launch {
                val location = withContext(Dispatchers.IO) {
                    runCatching {
                        val annotated = renderAnnotatedBitmap(state)
                        try {
                            ImageUtils.saveBitmapToGallery(
                                context,
                                annotated,
                                "星空识星_${System.currentTimeMillis()}.jpg",
                            )
                        } finally {
                            annotated.recycle()
                        }
                    }.getOrNull()
                }
                saving = false
                Toast.makeText(
                    context,
                    if (location != null) "已保存到本地：$location" else "保存失败，请检查存储空间",
                    Toast.LENGTH_LONG,
                ).show()
            }
        },
        enabled = !saving,
    ) { Text(if (saving) "保存中…" else "💾 保存到相册") }
}

/** 将识别结果（照片 + 星座连线 + 星名标注）渲染为位图，供保存/分享 */
private fun renderAnnotatedBitmap(state: ResultUiState.Success): Bitmap {    val src = state.bitmap
    val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    canvas.drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))

    val density = 2.0f // 按原图分辨率绘制（Compose 画布上的等效绘制密度）
    val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x8C7FD0FF.toInt() // alpha 0.55：细线半透明，不抢照片
        strokeWidth = 1.2f * density
        style = Paint.Style.STROKE
    }
    // 星座连线：两端按星等留空（星星不被线覆盖，与屏幕预览一致，§0.36.5）
    for (line in state.lines) {
        val seg = OverlayRenderer.shrink(
            line.a.x, line.a.y, line.a.entry.mag,
            line.b.x, line.b.y, line.b.entry.mag,
        ) ?: continue
        canvas.drawLine(seg[0], seg[1], seg[2], seg[3], linePaint)
    }
    // 星名（亮星且有名，中文优先——与屏幕预览一致，§0.36.5）
    val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF7EDCB.toInt()
        textSize = 12f * density
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(4f * density, 1f, 1f, 0xFF000000.toInt())
    }
    for (s in state.stars) {
        if (!s.visible || s.entry.mag > 3.2) continue
        val name = StarNames.displayName(s.entry.hip, s.entry.name)
        if (name.isEmpty()) continue
        canvas.drawText(name, s.x + 7f, s.y - 7f, namePaint)
    }
    // 星座名称标签（半透明，小字号；§0.37 与预览一致调小调淡）
    val conPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x5CBBD8FF.toInt()
        textSize = 11f * density
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(4f * density, 1f, 1f, 0xFF000000.toInt())
    }
    for (l in state.labels) {
        canvas.drawText(l.text, l.x, l.y, conPaint)
    }
    return out
}

/**
 * 失败诊断合成图（§0.34）：照片 + 金色星点圈，供放大查看/查看器使用。
 * 与加载页/失败页的动态标注一致：圈大小随星点亮度。
 */
private fun renderDiagnosticBitmap(src: Bitmap, diag: SolveDiagnostics): Bitmap {
    val longEdge = maxOf(src.width, src.height)
    val viewScale = 800f / longEdge
    val outW = (src.width * viewScale).toInt().coerceAtLeast(1)
    val outH = (src.height * viewScale).toInt().coerceAtLeast(1)
    val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    canvas.drawBitmap(src, null, Rect(0, 0, outW, outH), Paint(Paint.FILTER_BITMAP_FLAG))
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFFFC24B.toInt()
        strokeWidth = 2.5f
    }
    for (s in diag.stars) {
        canvas.drawCircle(s.x * outW, s.y * outH, (3f + 7f * s.brightness01).coerceAtLeast(3f), paint)
    }
    return out
}

/** 照片 + 星座叠加画布（点击放大查看 §0.34；对比原图与夜视红配色 §0.36） */
@Composable
private fun StarPhotoOverlay(state: ResultUiState.Success, onTap: () -> Unit) {
    val bitmap = state.bitmap
    val w = bitmap.width
    val h = bitmap.height
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    // 对比原图开关（§0.36）：标注层可一键隐藏
    var showOverlay by remember { mutableStateOf(true) }
    // 夜视红主题下用低亮度红系标注，不破坏暗适应（§0.36）
    val night = ThemeState.mode == AppThemeMode.NIGHT_RED
    val lineColor = if (night) Color(0x66FF8A80) else Color(0xFF7FD0FF).copy(alpha = 0.55f)
    val nameColor = if (night) 0xFFE8A8A0.toInt() else 0xFFF7EDCB.toInt()
    // 星座名：减小并降低不透明度（§0.37）
    val conColor = if (night) 0x55E8A8A0 else 0x5CBBD8FF.toInt()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(480.dp)
            .onSizeChanged { boxSize = it }
            .background(Color.Black)
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        if (boxSize.width > 0 && boxSize.height > 0) {
            val scale = min(
                boxSize.width.toFloat() / w,
                boxSize.height.toFloat() / h,
            )
            // boxSize 是像素（onSizeChanged），dispW/H 亦为像素值：
            // 必须经 Density 把像素精确转回 dp（直接 Modifier.size(像素值.dp) 会把
            // 像素当 dp 再乘 density，预览放大 density 倍、星座线与星星错位——实测 bug）
            val dispW = with(LocalDensity.current) { (w * scale).toInt().toDp() }
            val dispH = with(LocalDensity.current) { (h * scale).toInt().toDp() }
            val imageModifier = Modifier.size(dispW, dispH)

            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "星空照片（识别结果叠加）",
                modifier = imageModifier,
                contentScale = androidx.compose.ui.layout.ContentScale.FillBounds,
            )
            if (showOverlay) {
                Canvas(modifier = imageModifier) {
                    // 用画布实际尺寸（DrawScope.size，px）计算比例，
                    // 与 modifier 尺寸单位自洽，避免 px/dp 混用错位
                    val sx = size.width.toFloat() / w
                    val sy = size.height.toFloat() / h

                    // 星座连线（细线半透明；两端按星等留空，星星不被线覆盖）
                    for (line in state.lines) {
                        val seg = com.starcam.astro.astro.OverlayRenderer.shrink(
                            line.a.x, line.a.y, line.a.entry.mag,
                            line.b.x, line.b.y, line.b.entry.mag,
                        ) ?: continue
                        drawLine(
                            color = lineColor,
                            start = Offset(seg[0] * sx, seg[1] * sy),
                            end = Offset(seg[2] * sx, seg[3] * sy),
                            strokeWidth = 1.2.dp.toPx(),
                        )
                    }

                    // 星名（亮星且有名）
                    val namePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = nameColor
                        textSize = 12.sp.toPx()
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        setShadowLayer(4f, 1f, 1f, 0xFF000000.toInt())
                    }
                    for (s in state.stars) {
                        if (!s.visible || s.entry.mag > 3.2) continue
                        val name = com.starcam.astro.astro.StarNames.displayName(s.entry.hip, s.entry.name)
                        if (name.isEmpty()) continue
                        drawContext.canvas.nativeCanvas.drawText(
                            name, s.x * sx + 7f, s.y * sy - 7f, namePaint,
                        )
                    }

                    // 星座名称标签（半透明，小字号；§0.37 调小调淡）
                    val conPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = conColor
                        textSize = 11.sp.toPx()
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        setShadowLayer(4f, 1f, 1f, 0xFF000000.toInt())
                    }
                    for (l in state.labels) {
                        drawContext.canvas.nativeCanvas.drawText(l.text, l.x * sx, l.y * sy, conPaint)
                    }

                    // §0.43c：梅西耶深空天体标注（小圆圈 + 中文名，颜色按类型）
                    val messierPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        style = android.graphics.Paint.Style.STROKE
                        strokeWidth = 1.4.dp.toPx()
                    }
                    val messierText = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = 0xFFF7EDCB.toInt()
                        textSize = 10.sp.toPx()
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        setShadowLayer(3f, 1f, 1f, 0xFF000000.toInt())
                    }
                    for (m in state.messier) {
                        if (!m.visible) continue
                        val cx = m.x * sx
                        val cy = m.y * sy
                        messierPaint.color = com.starcam.astro.astro.MessierCatalog.typeColor(m.obj.type)
                        drawContext.canvas.nativeCanvas.drawCircle(
                            cx, cy, 7.dp.toPx(), messierPaint,
                        )
                        drawContext.canvas.nativeCanvas.drawText(
                            "M${m.obj.number} ${m.obj.zh}", cx + 10.dp.toPx(), cy - 6.dp.toPx(), messierText,
                        )
                    }
                }
            }
        }
        // 对比原图开关（§0.36）
        Text(
            if (showOverlay) "标注" else "原图",
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.85f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp)
                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                .clickable { showOverlay = !showOverlay }
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )
        // 点击放大提示（§0.34）
        Text(
            "🔍 点击图片放大查看",
            color = Color.White.copy(alpha = 0.65f),
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp)
                .background(Color.Black.copy(alpha = 0.35f))
                .padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
}

/** 识别信息面板 */
@Composable
private fun InfoPanel(state: ResultUiState.Success, modifier: Modifier = Modifier) {
    val solve = state.solve
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                when {
                    state.isDemo -> "✨ 离线演示 · 识别成功"
                    state.engine == SolveEngine.ASTROMETRY_NATIVE -> "🔭 astrometry.net 官方引擎 · 识别成功"
                    state.engine == SolveEngine.LOCAL_MATCHER -> "🗺 本地星表匹配 · 识别成功"
                    state.engine == SolveEngine.ONLINE_NOVA -> "✅ 在线识别成功"
                    else -> "识别成功"
                },
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
            )
            if (state.engine != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    state.engineDetail ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            Spacer(Modifier.height(10.dp))
            state.engine?.let {
                InfoRow("识别引擎", it.label)
            }
            InfoRow("中心坐标", "RA ${formatRa(solve.raDeg)}    Dec ${formatDec(solve.decDeg)}")
            InfoRow("视场大小", "${"%.1f".format(solve.fieldWidthDeg)}° × ${"%.1f".format(solve.fieldHeightDeg)}°")
            InfoRow("像素比例尺", "${"%.1f".format(solve.pixScaleArcsec)}″/像素")
            InfoRow("方向角", "${"%.1f".format(solve.orientationDeg)}°（parity ${solve.parity}）")
            solve.subId?.let { InfoRow("任务编号", "#$it") }
            Spacer(Modifier.height(8.dp))
            if (state.labels.isNotEmpty()) {
                Text("画面中的星座：", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    state.labels.forEach { label ->
                        AssistChip(onClick = {}, label = { Text(label.text) })
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 把 WCS 从求解图尺寸换算到显示位图尺寸 */
private fun scaleWcs(wcs: WcsTransform, sw: Int, sh: Int, w: Int, h: Int): WcsTransform {
    val sx = w.toDouble() / sw
    val sy = h.toDouble() / sh
    return WcsTransform(
        crpix1 = (wcs.crpix1 - 0.5) * sx + 0.5,
        crpix2 = (wcs.crpix2 - 0.5) * sy + 0.5,
        crval1 = wcs.crval1,
        crval2 = wcs.crval2,
        cd11 = wcs.cd11 / sx,
        cd12 = wcs.cd12 / sx,
        cd21 = wcs.cd21 / sy,
        cd22 = wcs.cd22 / sy,
    )
}

private fun formatRa(raDeg: Double): String {
    var v = ((raDeg % 360.0) + 360.0) % 360.0
    val hh = (v / 15.0).toInt()
    v = (v - hh * 15.0) * 4.0 // 余量转为分钟（度→分钟：*4）
    val mm = v.toInt()
    val ss = ((v - mm) * 60.0).toInt()
    return "%02dh %02dm %02ds".format(hh, mm, ss)
}

private fun formatDec(decDeg: Double): String {
    val sign = if (decDeg < 0) "-" else "+"
    val a = kotlin.math.abs(decDeg)
    val d = a.toInt()
    val m = ((a - d) * 60.0).toInt()
    val s = (((a - d) * 60.0 - m) * 60.0).toInt()
    return "%s%02d° %02d′ %02d″".format(sign, d, m, s)
}
