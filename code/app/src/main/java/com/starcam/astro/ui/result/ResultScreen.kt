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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.input.pointer.pointerInput
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
import com.starcam.astro.astro.LayerFlags
import com.starcam.astro.astro.LayeredRenderer
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

/** 识别结果页状态（internal：放大查看器命中测试复用 Success 数据） */
internal sealed interface ResultUiState {
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
        /**
         * §0.58 太阳系天体标记（月亮/行星/太阳）。
         * 仅在 EXIF 同时具备拍摄时间与 GPS 时非空——缺任一项都无法确定
         * 拍摄瞬间的行星位置，宁可不标也不能标错。
         */
        val solar: List<StarChartOverlay.Solar2D> = emptyList(),
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
    var state by remember { mutableStateOf<ResultUiState>(ResultUiState.Loading(null, com.starcam.astro.ui.I18n.Result.preparing)) }
    var attempt by remember { mutableIntStateOf(0) }
    // 识别等待时的天文冷知识（§0.33.4）：每张照片/每次重试换一条
    val tip = remember(imagePath, demoRegion, attempt) { AstroTips.random() }
    // 全屏缩放查看器（§0.34/§0.37）：成功页/失败页点击图片打开
    // Triple(原图, 标注图, 标题)，查看器内可切换原图/标注
    var viewer by remember { mutableStateOf<Triple<Bitmap, Bitmap, String>?>(null) }
    // §0.47：图层开关与查看器重渲染回调（预览/保存/查看三处渲染保持一致）
    var layerFlags by remember { mutableStateOf(LayerFlags.ALL) }
    var viewerRender by remember { mutableStateOf<((LayerFlags) -> Bitmap)?>(null) }
    // §0.48：放大查看器内点击天体 → 科普卡片（位图坐标 + 屏幕像素/位图像素比）
    var viewerHit by remember { mutableStateOf<((Float, Float, Float) -> SkyObjectRef?)?>(null) }
    val messierTolScreenPx = with(LocalDensity.current) { 28.dp.toPx() }
    val starTolScreenPx = with(LocalDensity.current) { 32.dp.toPx() }
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
            state = ResultUiState.Error(com.starcam.astro.ui.I18n.Result.missingWcs)
            return false
        }
        // 若求解尺寸与显示尺寸不一致，做等比换算
        val wcs = if (solve.imageWidth == w && solve.imageHeight == h) {
            rawWcs
        } else {
            rawWcs.rescaledFor(solve.imageWidth, solve.imageHeight, w, h)
        }
        val isEn = com.starcam.astro.ui.theme.LocaleState.isEnglish
        val stars = StarChartOverlay.projectStars(wcs, w, h)
        val lines = StarChartOverlay.projectLines(stars)
        val labels = StarChartOverlay.constellationLabels(stars, isEn)
        val messier = StarChartOverlay.projectMessier(wcs, w, h)
        // §0.58 太阳系天体（月亮/行星）：位置随时刻变化，需 EXIF 的拍摄时间 + GPS。
        // 缺任一项即返回空表（宁可不标也不能标错）；演示模式为合成天区，同样跳过。
        val solar = if (isDemo) {
            emptyList()
        } else {
            runCatching {
                StarChartOverlay.projectSolarSystem(
                    wcs, w, h,
                    com.starcam.astro.astro.ExifPriorsReader.solarSystemForPhoto(imagePath),
                )
            }.getOrDefault(emptyList())
        }
        state = ResultUiState.Success(
            solve, bitmap, stars, lines, labels, messier, isDemo, engine, engineDetail, solar,
        )
        return true
    }

    LaunchedEffect(imagePath, demoRegion, attempt) {
        state = ResultUiState.Loading(null, com.starcam.astro.ui.I18n.Result.preparingPhoto)
        detectedStars = emptyList()
        try {
            if (demoRegion != null) {
                // 离线演示：内置天区合成的模拟星空照片，不与真实求解链路交互
                val display = withContext(Dispatchers.IO) {
                    ImageUtils.decodeSampledBitmap(imagePath, 2200)
                }
                if (display == null) {
                    state = ResultUiState.Error(com.starcam.astro.ui.I18n.Result.cannotReadImage)
                    return@LaunchedEffect
                }
                state = ResultUiState.Loading(display, com.starcam.astro.ui.I18n.Result.generatingDemo)
                delay(800)
                val solve = DemoSolver.solveFor(demoRegion)
                if (!showSuccess(solve, display, null, null, isDemo = true)) return@LaunchedEffect
            } else {
                // 真实照片：三层引擎调度（官方 astrometry 本地 → 内置星表宽场 → 在线 nova）
                val display = withContext(Dispatchers.IO) {
                    ImageUtils.decodeSampledBitmap(imagePath, 2200)
                }
                if (display == null) {
                    state = ResultUiState.Error(com.starcam.astro.ui.I18n.Result.cannotReadImage)
                    return@LaunchedEffect
                }
                state = ResultUiState.Loading(display, com.starcam.astro.ui.I18n.Result.preparingSolve)
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
                        com.starcam.astro.ui.I18n.Result.solveFailedNoKey
                    } else {
                        com.starcam.astro.ui.I18n.Result.solveFailedWithKey
                    }
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
                            constellation = HistoryStore.nearestConstellation(
                                result.solve.raDeg, result.solve.decDeg,
                                com.starcam.astro.ui.theme.LocaleState.isEnglish,
                            ),
                        ),
                    )
                } catch (_: Exception) {
                }
            }
        } catch (e: PlateSolveException) {
            state = ResultUiState.Error(e.message ?: com.starcam.astro.ui.I18n.Result.solveFailed)
        } catch (e: Exception) {
            state = ResultUiState.Error("${com.starcam.astro.ui.I18n.Result.solveFailed}：${e.message ?: ""}")
        }
    }

    Box {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(com.starcam.astro.ui.I18n.Result.title) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = com.starcam.astro.ui.I18n.back)
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
                    layerFlags = layerFlags,
                    onFlagsChange = { layerFlags = it },
                    onRetry = { attempt++ },
                    onRetake = onRetake,
                    onBack = onBack,
                    onOpenViewer = { orig, anno ->
                        viewerRender = { f -> renderAnnotatedBitmap(s, flags = f) }
                        viewerHit = { bx, by, pxPerBmp ->
                            hitTestObjectAt(
                                s, bx, by,
                                messierTolScreenPx / pxPerBmp, starTolScreenPx / pxPerBmp,
                            )
                        }
                        viewer = Triple(orig, anno, com.starcam.astro.ui.I18n.Result.viewerTitleSuccess)
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
                        viewerRender = null
                        viewerHit = null
                        viewer = Triple(orig, anno, com.starcam.astro.ui.I18n.Result.viewerTitleDiag)
                    },
                )
            }
        }
        viewer?.let { (orig, anno, title) ->
            ZoomableImageViewer(
                orig, anno, title,
                onClose = { viewer = null },
                renderAnnotated = viewerRender,
                initialFlags = layerFlags,
                hitTest = viewerHit,
            )
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
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black),
            ) {
                Image(
                    bitmap = state.bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
                if (stars.isNotEmpty()) {
                    Canvas(Modifier.fillMaxSize()) {
                        for (s in stars) {
                            drawCircle(
                                color = Color(0xFFFFC24B).copy(alpha = 0.8f),
                                radius = (3.dp.toPx() + 6.dp.toPx() * s.brightness01).coerceAtLeast(3.dp.toPx()),
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
                com.starcam.astro.ui.I18n.Result.starsDetectedMatching(stars.size),
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
                com.starcam.astro.ui.I18n.Result.diagLegend(diagnostics.starCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text("😕", fontSize = 44.sp)
        }

        val isEn = com.starcam.astro.ui.theme.LocaleState.isEnglish
        if (diagnostics != null) {
            Spacer(Modifier.height(16.dp))
            Text(
                diagnostics.verdictTitle(isEn),
                fontSize = 19.sp,
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
            Button(onClick = onRetry) { Text(com.starcam.astro.ui.I18n.retry) }
            OutlinedButton(onClick = onBack) { Text(com.starcam.astro.ui.I18n.Result.backHome) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SuccessContent(
    state: ResultUiState.Success,
    modifier: Modifier = Modifier,
    layerFlags: LayerFlags = LayerFlags.ALL,
    onFlagsChange: (LayerFlags) -> Unit = {},
    onRetry: () -> Unit,
    onRetake: () -> Unit,
    onBack: () -> Unit,
    onOpenViewer: (Bitmap, Bitmap) -> Unit,
) {
    // §0.47：图层开关由 ResultScreen 持有（查看器共享同一开关状态）
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        StarPhotoOverlay(
            state = state,
            flags = layerFlags,
            onFlagsChange = onFlagsChange,
            onTap = { onOpenViewer(state.bitmap, renderAnnotatedBitmap(state, flags = layerFlags)) },
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            SaveToGalleryButton(state, layerFlags)
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
            ) { Text(com.starcam.astro.ui.I18n.Result.reSolve) }
            OutlinedButton(onClick = onRetake, modifier = Modifier.weight(1f)) { Text(com.starcam.astro.ui.I18n.Result.retake) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** 保存到相册：把照片 + 星座标注合成图存入本地（Android 10+ 直接入相册；§0.47 遵循图层开关） */
@Composable
private fun SaveToGalleryButton(state: ResultUiState.Success, flags: LayerFlags = LayerFlags.ALL) {
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
                        val annotated = renderAnnotatedBitmap(state, flags = flags)
                        try {
                            ImageUtils.saveBitmapToGallery(
                                context,
                                annotated,
                                "StarCam_${System.currentTimeMillis()}.jpg",
                            )
                        } finally {
                            annotated.recycle()
                        }
                    }.getOrNull()
                }
                saving = false
                Toast.makeText(
                    context,
                    if (location != null) com.starcam.astro.ui.I18n.Result.savedToast(location) else com.starcam.astro.ui.I18n.Result.saveFailed,
                    Toast.LENGTH_LONG,
                ).show()
            }
        },
        enabled = !saving,
    ) { Text(if (saving) com.starcam.astro.ui.I18n.Result.saving else com.starcam.astro.ui.I18n.Result.saveToGallery) }
}

/** 将识别结果（照片 + 星座连线 + 星名标注）渲染为位图，供保存/分享（支持双语与图层开关 §0.47） */
private fun renderAnnotatedBitmap(
    state: ResultUiState.Success,
    isEnglish: Boolean = com.starcam.astro.ui.theme.LocaleState.isEnglish,
    flags: LayerFlags = LayerFlags.ALL,
): Bitmap {
    val src = state.bitmap
    val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    canvas.drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
    val scene = LayeredRenderer.OverlayScene(
        src.width, src.height, state.stars, state.lines, state.labels, state.messier, state.solar,
    )
    LayeredRenderer.draw(
        canvas, scene, flags, isEnglish, density = 2.0f,
        degPerPx = plateScaleDegPerPx(state),
    )
    return out
}

/**
 * §0.58 板比例（天球度 / 图像像素）：由求解视场与位图宽度推得。
 * 供渲染器把日月画成真实视直径；求解视场异常时返回 null（退化为固定标记）。
 */
internal fun plateScaleDegPerPx(state: ResultUiState.Success): Float? {
    val w = state.bitmap.width
    val fov = state.solve.fieldWidthDeg
    if (w <= 0 || fov <= 0.0 || !fov.isFinite()) return null
    return (fov / w).toFloat()
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

/** 照片 + 星座叠加画布（点击放大查看 §0.34；图层开关/按住原图/点击天体科普 §0.47） */
@Composable
private fun StarPhotoOverlay(
    state: ResultUiState.Success,
    flags: LayerFlags,
    onFlagsChange: (LayerFlags) -> Unit,
    onTap: () -> Unit,
) {
    val bitmap = state.bitmap
    val w = bitmap.width
    val h = bitmap.height
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    // §0.47：按住看原图（pointerInput 捕获按下/抬起，避免与点击放大冲突）
    var holdingOriginal by remember { mutableStateOf(false) }
    // §0.47：点击天体 → 科普卡片（Messier 优先，其次亮星）
    var cardObject by remember { mutableStateOf<SkyObjectRef?>(null) }
    var showPanel by remember { mutableStateOf(false) }
    // 夜视红主题下用低亮度红系标注，不破坏暗适应（§0.36）
    val night = ThemeState.mode == AppThemeMode.NIGHT_RED
    val isEn = com.starcam.astro.ui.theme.LocaleState.isEnglish
    // 命中容差（屏幕像素）：v1.5.32 曾误用图像像素（≈4dp）导致几乎点不中
    val messierTolPx = with(LocalDensity.current) { 28.dp.toPx() }
    val starTolPx = with(LocalDensity.current) { 32.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(480.dp)
            .onSizeChanged { boxSize = it }
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { _ ->
                        holdingOriginal = true
                        try {
                            awaitRelease()
                        } finally {
                            holdingOriginal = false
                        }
                    },
                    onTap = { pos ->
                        // §0.47：命中测试——把点击点换算到图像坐标（letterbox 偏移 + fit-scale），
                        // 容差按屏幕像素换算成位图像素；未命中则放大查看
                        val scale = min(boxSize.width.toFloat() / w, boxSize.height.toFloat() / h)
                        if (scale > 0f) {
                            val imgX = (pos.x - (boxSize.width - w * scale) / 2f) / scale
                            val imgY = (pos.y - (boxSize.height - h * scale) / 2f) / scale
                            val hit = hitTestObjectAt(
                                state, imgX, imgY,
                                messierTolPx / scale, starTolPx / scale,
                            )
                            if (hit != null) {
                                cardObject = hit
                            } else {
                                onTap()
                            }
                        }
                    },
                )
            },
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
            // 渲染密度：1sp 对应的像素数（含系统字体缩放），保证预览字号与导出图观感一致
            val pxPerSp = with(LocalDensity.current) { 1.sp.toPx() }

            // §0.47：按住时显示纯净原图，松开恢复标注；叠加层按 flags 逐层绘制
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "星空照片（识别结果叠加）",
                modifier = imageModifier,
                contentScale = androidx.compose.ui.layout.ContentScale.FillBounds,
            )
            if (!holdingOriginal) {
                val scene = LayeredRenderer.OverlayScene(
                    w, h, state.stars, state.lines, state.labels, state.messier, state.solar,
                )
                Canvas(modifier = imageModifier) {
                    // 显式传 DrawScope.size（组合件绘制区）：nativeCanvas.width 是整块
                    // 窗口画布，直接用会导致 x/y 缩放比不一致、星座严重变形（v1.5.33）
                    LayeredRenderer.draw(
                        drawContext.canvas.nativeCanvas,
                        scene, flags, isEn, density = pxPerSp, night = night,
                        drawWidth = size.width.toFloat(),
                        drawHeight = size.height.toFloat(),
                        degPerPx = plateScaleDegPerPx(state),
                    )
                }
            }
        }
        // 图层控制按钮（§0.47，支持双语）：右侧悬浮
        Text(
            if (showPanel) "✕" else "≡",
            fontSize = 18.sp,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 10.dp, end = 10.dp)
                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                .clickable { showPanel = !showPanel }
                .padding(horizontal = 10.dp, vertical = 2.dp),
        )
        if (showPanel) {
            LayerPanel(
                flags = flags,
                onChange = onFlagsChange,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 48.dp, end = 10.dp),
            )
        }
        // 按住看原图提示（§0.47）
        Text(
            if (holdingOriginal) com.starcam.astro.ui.I18n.Layers.releaseToRestore else com.starcam.astro.ui.I18n.Layers.holdOriginal,
            fontSize = 11.sp,
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
                .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                .padding(horizontal = 6.dp, vertical = 3.dp),
        )
        // 点击放大提示（§0.34，支持双语）
        Text(
            if (isEn) "🔍 Tap to zoom · Tap markers for info" else "🔍 点击放大 · 轻点标记看详情",
            color = Color.White.copy(alpha = 0.65f),
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp)
                .background(Color.Black.copy(alpha = 0.35f))
                .padding(horizontal = 6.dp, vertical = 3.dp),
        )

        // §0.47：天体科普卡片（照片底部滑出的浮层，直接可见）
        cardObject?.let { ref ->
            ObjectInfoCard(
                ref = ref,
                onDismiss = { cardObject = null },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
            )
        }
    }
}

/** §0.47：图层控制面板（星座连线/星名/星座名/梅西耶/月亮与行星 5 个独立开关） */
@Composable
private fun LayerPanel(
    flags: LayerFlags,
    onChange: (LayerFlags) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.72f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                com.starcam.astro.ui.I18n.Layers.panelTitle,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            LayerToggle(com.starcam.astro.ui.I18n.Layers.lines, flags.lines) { onChange(flags.copy(lines = it)) }
            LayerToggle(com.starcam.astro.ui.I18n.Layers.starNames, flags.starNames) { onChange(flags.copy(starNames = it)) }
            LayerToggle(com.starcam.astro.ui.I18n.Layers.constellationNames, flags.constellationNames) { onChange(flags.copy(constellationNames = it)) }
            LayerToggle(com.starcam.astro.ui.I18n.Layers.messier, flags.messier) { onChange(flags.copy(messier = it)) }
            LayerToggle(com.starcam.astro.ui.I18n.Layers.planets, flags.planets) { onChange(flags.copy(planets = it)) }
        }
    }
}

@Composable
private fun LayerToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clickable { onChange(!checked) }
            .padding(vertical = 3.dp),
    ) {
        Text(
            if (checked) "☑" else "☐",
            color = if (checked) Color(0xFF7FD0FF) else Color.White.copy(alpha = 0.55f),
            fontSize = 15.sp,
        )
        Spacer(Modifier.width(6.dp))
        Text(label, color = Color.White, fontSize = 12.sp)
    }
}

/** §0.47：被点中的天体引用（Messier 或亮星） */
sealed interface SkyObjectRef {
    data class MessierRef(val obj: com.starcam.astro.astro.MessierObject) : SkyObjectRef
    data class StarRef(val entry: com.starcam.astro.astro.StarEntry) : SkyObjectRef
    /** §0.58 太阳系天体（月亮/行星/太阳），携带该时刻的观测参数供卡片展示 */
    data class SolarRef(val pos: com.starcam.astro.astro.SolarSystemEphemeris.SolarPosition) : SkyObjectRef
}

/**
 * §0.47：天体命中测试（图像坐标系）。
 * [imgX]/[imgY] 为位图像素坐标；容差亦为位图像素，调用方按当前缩放换算：
 * 位图容差 = 屏幕容差px ÷ (屏幕px ÷ 位图px)。
 */
internal fun hitTestObjectAt(
    state: ResultUiState.Success,
    imgX: Float,
    imgY: Float,
    messierTolImgPx: Float,
    starTolImgPx: Float,
): SkyObjectRef? {
    // §0.58 太阳系天体优先级最高：日月行星是全画面最醒目的目标，
    // 且常与深空天体近邻（如月亮掠过毕星团），必须先命中
    var solarRef: SkyObjectRef? = null
    var solarDist = messierTolImgPx * 1.6f
    for (s in state.solar) {
        if (!s.visible) continue
        val d = kotlin.math.hypot((s.x - imgX).toDouble(), (s.y - imgY).toDouble()).toFloat()
        if (d < solarDist) {
            solarDist = d
            solarRef = SkyObjectRef.SolarRef(s.pos)
        }
    }
    if (solarRef != null) return solarRef

    // 梅西耶
    var best: SkyObjectRef? = null
    var bestDist = messierTolImgPx
    for (m in state.messier) {
        if (!m.visible) continue
        val d = kotlin.math.hypot((m.x - imgX).toDouble(), (m.y - imgY).toDouble()).toFloat()
        if (d < bestDist) {
            bestDist = d
            best = SkyObjectRef.MessierRef(m.obj)
        }
    }
    if (best != null) return best

    // 亮星：只挑有标注价值的亮星（mag ≤ 3.2，与星名图层同域）
    var starDist = starTolImgPx
    var starRef: SkyObjectRef? = null
    for (s in state.stars) {
        if (!s.visible || s.entry.mag > 3.2) continue
        val d = kotlin.math.hypot((s.x - imgX).toDouble(), (s.y - imgY).toDouble()).toFloat()
        if (d < starDist) {
            starDist = d
            starRef = SkyObjectRef.StarRef(s.entry)
        }
    }
    return starRef
}

/** §0.47：天体科普卡片（照片底部浮层，中英双语；Messier 与亮星两种；放大查看器亦复用） */
@Composable
internal fun ObjectInfoCard(ref: SkyObjectRef, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val isEn = com.starcam.astro.ui.theme.LocaleState.isEnglish
    val night = ThemeState.mode == AppThemeMode.NIGHT_RED
    Surface(
        modifier = modifier,
        color = if (night) Color(0xF01A0505) else Color(0xF00D1B2A),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            when (ref) {
                is SkyObjectRef.MessierRef -> {
                    val obj = ref.obj
                    val info = com.starcam.astro.astro.ObjectInfo.messier[obj.number]
                    val typeColor = Color(com.starcam.astro.astro.MessierCatalog.typeColor(obj.type))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("✦ ", color = typeColor, fontSize = 18.sp)
                        Text(
                            obj.label(isEn),
                            color = Color.White,
                            fontSize = 17.sp,
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
                            append(com.starcam.astro.ui.I18n.InfoCard.typeLabel); append(": ")
                            append(com.starcam.astro.astro.ObjectInfo.typeLabel(obj.type, isEn))
                            info?.let {
                                append("   ·   ")
                                append(com.starcam.astro.ui.I18n.InfoCard.magLabel); append(": ")
                                append("m${"%.1f".format(it.mag)}")
                            }
                        },
                        color = typeColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    info?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            buildString {
                                append(com.starcam.astro.ui.I18n.InfoCard.distLabel); append(": ")
                                append(if (isEn) it.distLyEn else it.distLyZh)
                            },
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 13.sp,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (isEn) it.descEn else it.descZh,
                            color = Color.White.copy(alpha = 0.92f),
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                        )
                    }
                }
                is SkyObjectRef.StarRef -> {
                    val entry = ref.entry
                    val displayName = StarNames.displayName(entry.hip, entry.name, isEn)
                    val conName = com.starcam.astro.astro.Constellations.name(entry.con, isEn)
                    val info = com.starcam.astro.astro.ObjectInfo.stars[entry.hip]
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("★ ", color = Color(0xFFFFE082), fontSize = 18.sp)
                        Text(
                            if (displayName.isNotEmpty()) displayName else "HIP ${entry.hip}",
                            color = Color.White,
                            fontSize = 17.sp,
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
                            append(com.starcam.astro.ui.I18n.InfoCard.magLabel); append(": ")
                            append("m${"%.1f".format(entry.mag)}")
                            append("   ·   ")
                            append(com.starcam.astro.ui.I18n.InfoCard.constellationLabel); append(": ")
                            append(conName)
                        },
                        color = Color(0xFFFFE082),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    info?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            buildString {
                                append(com.starcam.astro.ui.I18n.InfoCard.distLabel); append(": ")
                                append(if (isEn) it.distLyEn else it.distLyZh)
                            },
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 13.sp,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (isEn) it.descEn else it.descZh,
                            color = Color.White.copy(alpha = 0.92f),
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                        )
                    }
                }
                is SkyObjectRef.SolarRef -> {
                    val pos = ref.pos
                    val body = pos.body
                    val bodyColor = Color(com.starcam.astro.astro.SolarSystemCatalog.color(body))
                    val info = com.starcam.astro.astro.SolarSystemCatalog.info[body]
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("● ", color = bodyColor, fontSize = 16.sp)
                        Text(
                            com.starcam.astro.astro.SolarSystemCatalog.name(body, isEn),
                            color = Color.White,
                            fontSize = 17.sp,
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
                        com.starcam.astro.astro.SolarSystemCatalog.typeLabel(body, isEn),
                        color = bodyColor,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        buildString {
                            append(com.starcam.astro.ui.I18n.InfoCard.magLabel); append(": ")
                            append("m${"%.1f".format(pos.magnitude)}")
                            append("   ·   ")
                            append(com.starcam.astro.ui.I18n.InfoCard.elongationLabel); append(": ")
                            append("${"%.0f".format(pos.elongationDeg)}°")
                            // 太阳无相位概念，仅日月之外显示被照亮比例
                            if (body != com.starcam.astro.astro.SolarSystemEphemeris.SolarBody.SUN) {
                                append("   ·   ")
                                append(com.starcam.astro.ui.I18n.InfoCard.phaseLabel); append(": ")
                                append("${"%.0f".format(pos.phase * 100)}%")
                            }
                        },
                        color = bodyColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    info?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            buildString {
                                append(com.starcam.astro.ui.I18n.InfoCard.distLabel); append(": ")
                                append(if (isEn) it.distEn else it.distZh)
                                append("   ·   ")
                                append(com.starcam.astro.ui.I18n.InfoCard.diameterLabel); append(": ")
                                append("${"%.1f".format(pos.angularDiameterDeg * 60)}′")
                            },
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 13.sp,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (isEn) it.descEn else it.descZh,
                            color = Color.White.copy(alpha = 0.92f),
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                        )
                    }
                }
            }
        }
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
            val isEn = com.starcam.astro.ui.theme.LocaleState.isEnglish
            Text(
                when {
                    state.isDemo -> com.starcam.astro.ui.I18n.Result.titleDemo
                    state.engine == SolveEngine.ASTROMETRY_NATIVE -> com.starcam.astro.ui.I18n.Result.titleNative
                    state.engine == SolveEngine.LOCAL_MATCHER -> com.starcam.astro.ui.I18n.Result.titleLocal
                    state.engine == SolveEngine.ONLINE_NOVA -> com.starcam.astro.ui.I18n.Result.titleOnline
                    else -> com.starcam.astro.ui.I18n.Result.titleDefault
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
                InfoRow(com.starcam.astro.ui.I18n.Result.labelEngine, it.label(isEn))
            }
            InfoRow(com.starcam.astro.ui.I18n.Result.labelCenter, "RA ${formatRa(solve.raDeg)}    Dec ${formatDec(solve.decDeg)}")
            InfoRow(com.starcam.astro.ui.I18n.Result.labelFov, "${"%.1f".format(solve.fieldWidthDeg)}° × ${"%.1f".format(solve.fieldHeightDeg)}°")
            InfoRow(com.starcam.astro.ui.I18n.Result.labelPixScale, "${"%.1f".format(solve.pixScaleArcsec)}${if (isEn) "″/px" else "″/像素"}")
            InfoRow(com.starcam.astro.ui.I18n.Result.labelOrientation, "${"%.1f".format(solve.orientationDeg)}°（parity ${solve.parity}）")
            solve.subId?.let { InfoRow(com.starcam.astro.ui.I18n.Result.labelTaskId, "#$it") }
            Spacer(Modifier.height(8.dp))
            if (state.labels.isNotEmpty()) {
                Text(com.starcam.astro.ui.I18n.Result.constellationsInField, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
