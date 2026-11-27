package com.starcam.astro.ui.result

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.starcam.astro.astro.AstroTips
import com.starcam.astro.astro.LayerFlags
import com.starcam.astro.astro.SkyRegion
import com.starcam.astro.astro.SolveDiagnostics
import com.starcam.astro.astro.SolveEngine
import com.starcam.astro.astro.SolvePhase
import com.starcam.astro.astro.SolveResult
import com.starcam.astro.astro.StarChartOverlay
import com.starcam.astro.data.SettingsRepository
import com.starcam.astro.ui.I18n
import com.starcam.astro.ui.theme.LocaleState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 识别结果页状态（放大查看器命中测试复用 Success 数据） */
sealed interface ResultUiState {
    data class Loading(
        val bitmap: Bitmap? = null,
        val message: String,
        val phase: com.starcam.astro.astro.SolvePhase = com.starcam.astro.astro.SolvePhase.PREPARING,
        val startedAtNanos: Long = System.nanoTime(),
        val budgetSec: Int? = null,
    ) : ResultUiState
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
         * §0.90 本次识别耗时（毫秒，与 §0.40 打点计时的口径一致：只计 StarSolver.solve
         * 本身，不含照片解码）。≤0 表示无耗时可言（演示模式），信息面板据此不显示该行。
         */
        val elapsedMs: Long = 0,
        /**
         * §0.58 太阳系天体标记（月亮/行星/太阳）。
         * 需要拍摄时间，以及位置（EXIF GPS，或 §0.59 的当前定位兜底）；
         * 两者缺一都无法确定拍摄瞬间的行星位置，宁可不标也不能标错。
         */
        val solar: List<StarChartOverlay.Solar2D> = emptyList(),
        /**
         * §0.59 位置来源为「当前定位兜底」时为真——照片没有 EXIF GPS，
         * 改用设备当前定位代替。位置未必等于拍摄地（异地或久前的旧照片会偏），
         * 信息面板据实提示用户；天体标注的可信度同样受此影响。
         */
        val locationIsFallback: Boolean = false,
        /** 原始输入文件，供按原图尺寸重新解码导出。 */
        val exportSourcePath: String? = null,
        val solveUsesUprightPixels: Boolean? = false,
    ) : ResultUiState
    data class Error(
        val message: String,
        /** 失败可视化诊断（§0.33）：照片 + 检出星点标注；null 为无法诊断的失败 */
        val bitmap: Bitmap? = null,
        val diagnostics: SolveDiagnostics? = null,
        /** §0.90 本次识别耗时（毫秒）；≤0 表示求解根本没开始（读图失败等），不显示 */
        val elapsedMs: Long = 0,
    ) : ResultUiState
}

/**
 * §0.94 成功页的重活产物：在 IO 线程算好后再回主线程赋 `state`，
 * 使 [ResultScreen] 的主线程只做一次状态写入。
 */
internal data class PreparedSuccess(
    val stars: List<StarChartOverlay.Star2D>,
    val lines: List<StarChartOverlay.Line2D>,
    val labels: List<StarChartOverlay.Label2D>,
    val messier: List<StarChartOverlay.Messier2D>,
    val solar: List<StarChartOverlay.Solar2D>,
    val locationIsFallback: Boolean,
)

/**
 * 识别结果页：展示照片 + 星座连线/星名叠加，以及天区信息。
 * [demoRegion] 非空时走离线演示流程（不联网）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(
    imagePath: String,
    demoRegion: SkyRegion?,
    cachedSolve: SolveResult? = null,
    cachedEngine: String? = null,
    cachedDisplayPath: String? = null,
    settings: SettingsRepository,
    onBack: () -> Unit,
    onRetake: () -> Unit,
    onSolveCompleted: (ResultUiState.Success) -> Unit = {},
    viewModel: ResultViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        key = "$imagePath-${cachedSolve != null}-${demoRegion?.toString()}",
        factory = ResultViewModel.factory(LocalContext.current.applicationContext as android.app.Application),
    ),
) {
    // §0.123 修复 P1-3：系统返回拦截并取消在途任务，离开界面时释放位图与清理后台
    androidx.activity.compose.BackHandler {
        viewModel.cancel()
        onBack()
    }

    val activity = LocalContext.current as? android.app.Activity
    androidx.compose.runtime.DisposableEffect(imagePath, demoRegion, cachedSolve, cachedDisplayPath) {
        onDispose {
            if (activity?.isChangingConfigurations != true) {
                viewModel.onLeaveScreen()
            }
        }
    }

    LaunchedEffect(imagePath, demoRegion, cachedSolve, cachedDisplayPath) {
        viewModel.initiateSolve(
            imagePath = imagePath,
            demoRegion = demoRegion,
            cachedSolve = cachedSolve,
            cachedEngine = cachedEngine,
            cachedDisplayPath = cachedDisplayPath,
            settings = settings,
        )
    }

    val state = viewModel.state
    var hasRetried by remember(imagePath, demoRegion, cachedSolve, cachedDisplayPath) { mutableStateOf(false) }
    LaunchedEffect(state) {
        val success = state as? ResultUiState.Success
        if (success != null && !success.isDemo && (cachedSolve == null || hasRetried)) onSolveCompleted(success)
    }
    val detectedStars = viewModel.detectedStars
    val detectedStarTotal = viewModel.detectedStarTotal

    // 识别等待时的天文冷知识（§0.33.4）：每张照片换一条，支持中英双语
    val isEn = com.starcam.astro.ui.theme.LocaleState.isEnglish
    val tip = remember(imagePath, demoRegion, isEn) { AstroTips.random(isEnglish = isEn) }
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

    Box {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(com.starcam.astro.ui.I18n.Result.title) },
                    navigationIcon = {
                        IconButton(onClick = {
                            viewModel.cancel()
                            onBack()
                        }) {
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
                    state = s,
                    tip = tip,
                    stars = detectedStars,
                    starTotal = detectedStarTotal,
                    onCancel = {
                        viewModel.cancel()
                        onBack()
                    },
                    modifier = Modifier.padding(padding),
                )
                is ResultUiState.Success -> SuccessContent(
                    state = s,
                    modifier = Modifier.padding(padding),
                    layerFlags = layerFlags,
                    onFlagsChange = { layerFlags = it },
                    onRetry = {
                        hasRetried = true
                        viewModel.retry(imagePath, demoRegion, settings)
                    },
                    onRetake = onRetake,
                    onBack = onBack,
                    onOpenViewer = { orig, anno ->
                        viewerRender = { f -> renderAnnotatedBitmap(s, flags = f) }
                        viewerHit = { bx, by, pxPerBmp ->
                            hitTestObjectAt(
                                s, bx, by,
                                messierTolScreenPx / pxPerBmp, starTolScreenPx / pxPerBmp,
                                flags = layerFlags,
                            )
                        }
                        viewer = Triple(orig, anno, com.starcam.astro.ui.I18n.Result.viewerTitleSuccess)
                    },
                )
                is ResultUiState.Error -> ErrorContent(
                    message = s.message,
                    bitmap = s.bitmap,
                    diagnostics = s.diagnostics,
                    elapsedMs = s.elapsedMs,
                    modifier = Modifier.padding(padding),
                    onRetry = {
                        hasRetried = true
                        viewModel.retry(imagePath, demoRegion, settings)
                    },
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
                onClose = {
                    viewer = null
                    viewerRender = null
                    viewerHit = null
                },
                renderAnnotated = viewerRender,
                initialFlags = layerFlags,
                onFlagsChange = { layerFlags = it },
                hitTest = viewerHit,
            )
        }
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
    // §0.78：全分辨率标注图合成要移出主线程（见下面 onTap 的说明）
    val scope = rememberCoroutineScope()
    var openingViewer by remember { mutableStateOf(false) }
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
            // §0.78：原来在主线程**同步**合成 2200px 全分辨率位图（≈14MB + 一遍全星表
            // 投影），点开大图时明显卡顿；而同一个操作在 SaveToGalleryButton 里却走了 IO。
            onTap = {
                if (!openingViewer) {
                    openingViewer = true
                    scope.launch {
                        try {
                            val anno = withContext(Dispatchers.IO) {
                                renderAnnotatedBitmap(state, flags = layerFlags)
                            }
                            onOpenViewer(state.bitmap, anno)
                        } finally {
                            openingViewer = false
                        }
                    }
                }
            },
        )
        Spacer(Modifier.height(10.dp))
        // §0.120：图层快捷开关（识别完毕后直接切换星星名字、连线、星座名等标注）
        LayerFilterChips(
            flags = layerFlags,
            onFlagsChange = onFlagsChange,
            showPlanets = state.solar.isNotEmpty(),
        )
        Spacer(Modifier.height(10.dp))
        PhotoExportActions(state, layerFlags) { flags, english ->
            renderAnnotatedBitmap(state, isEnglish = english, flags = flags)
        }
        Spacer(Modifier.height(12.dp))
        InfoPanel(state, Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilledTonalButton(
                onClick = onRetry,
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp),
            ) {
                Text(com.starcam.astro.ui.I18n.Result.reSolve)
            }
            OutlinedButton(
                onClick = onRetake,
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp),
            ) {
                Text(com.starcam.astro.ui.I18n.Result.retake)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

