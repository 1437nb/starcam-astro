package com.starcam.astro.ui.result

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.starcam.astro.astro.DemoSolver
import com.starcam.astro.astro.ExifPriorsReader
import com.starcam.astro.astro.SkyRegion
import com.starcam.astro.astro.SolveDiagnostics
import com.starcam.astro.astro.SolveEngine
import com.starcam.astro.astro.SolveResult
import com.starcam.astro.astro.SolveToken
import com.starcam.astro.astro.StarChartOverlay
import com.starcam.astro.astro.StarSolver
import com.starcam.astro.data.HistoryStore
import com.starcam.astro.data.SettingsRepository
import com.starcam.astro.data.StatsStore
import com.starcam.astro.ui.I18n
import com.starcam.astro.ui.theme.LocaleState
import com.starcam.astro.util.ImageUtils
import com.starcam.astro.util.LocationHelper
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 识别结果页 ViewModel（§0.114 架构重构）。
 *
 * 职责：
 * 1. 管理求解生命周期（后台求解、取消、重试），与 Composable 生命周期解耦；
 * 2. 屏幕旋转或配置变更时持久保持求解状态与已解出结果，避免重复 Platesolving；
 * 3. 页面退出销毁（[onCleared]）时即时熔断后台计算，避免空转与锁竞争。
 */
class ResultViewModel(application: Application) : AndroidViewModel(application) {

    var state by mutableStateOf<ResultUiState>(ResultUiState.Loading(null, I18n.Result.preparing))
        private set

    var detectedStars by mutableStateOf<List<SolveDiagnostics.DiagStar>>(emptyList())
        private set

    var detectedStarTotal by mutableIntStateOf(0)
        private set

    private var solveJob: Job? = null
    private var solveToken: SolveToken? = null

    private var lastInitiatedKey: String? = null

    /**
     * 触发或恢复求解任务。
     * 若当前已有同参数求解在运行或已完成，则安全保持，不重复触发。
     */
    fun initiateSolve(
        imagePath: String,
        demoRegion: SkyRegion?,
        cachedSolve: SolveResult?,
        cachedEngine: String?,
        settings: SettingsRepository,
    ) {
        val key = "$imagePath-${demoRegion?.name}-${cachedSolve != null}"
        if (key == lastInitiatedKey && solveJob != null && solveJob?.isActive == true) {
            return
        }
        if (key == lastInitiatedKey && state is ResultUiState.Success) {
            return
        }
        lastInitiatedKey = key
        startSolve(imagePath, demoRegion, cachedSolve, cachedEngine, settings)
    }

    private fun startSolve(
        imagePath: String,
        demoRegion: SkyRegion?,
        cachedSolve: SolveResult?,
        cachedEngine: String?,
        settings: SettingsRepository,
    ) {
        solveToken?.cancel()
        solveJob?.cancel()

        val token = SolveToken()
        solveToken = token

        solveJob = viewModelScope.launch {
            state = ResultUiState.Loading(null, I18n.Result.preparingPhoto)
            detectedStars = emptyList()
            detectedStarTotal = 0

            try {
                if (cachedSolve != null) {
                    val display = withContext(Dispatchers.IO) {
                        ImageUtils.decodeSampledBitmap(imagePath, 2200)
                    }
                    if (display == null) {
                        state = ResultUiState.Error(I18n.Result.cannotReadImage)
                        return@launch
                    }
                    showSuccess(
                        imagePath = imagePath,
                        solve = cachedSolve,
                        bitmap = display,
                        engine = null,
                        engineDetail = cachedEngine,
                        isDemo = false,
                        elapsedMs = 0L,
                        recordHistory = false,
                    )
                    return@launch
                }

                if (demoRegion != null) {
                    val display = withContext(Dispatchers.IO) {
                        ImageUtils.decodeSampledBitmap(imagePath, 2200)
                    }
                    if (display == null) {
                        state = ResultUiState.Error(I18n.Result.cannotReadImage)
                        return@launch
                    }
                    state = ResultUiState.Loading(display, I18n.Result.generatingDemo)
                    delay(800)
                    val solve = DemoSolver.solveFor(demoRegion)
                    showSuccess(
                        imagePath = imagePath,
                        solve = solve,
                        bitmap = display,
                        engine = null,
                        engineDetail = null,
                        isDemo = true,
                        elapsedMs = 0L,
                        recordHistory = false,
                    )
                    return@launch
                }

                // 真实照片求解流程
                val display = withContext(Dispatchers.IO) {
                    ImageUtils.decodeSampledBitmap(imagePath, 2200)
                }
                if (display == null) {
                    state = ResultUiState.Error(I18n.Result.cannotReadImage)
                    return@launch
                }
                state = ResultUiState.Loading(display, I18n.Result.preparingSolve)
                var diag: SolveDiagnostics? = null
                val t0 = System.currentTimeMillis()

                val result = StarSolver.solve(
                    context = getApplication(),
                    display = display,
                    imagePath = imagePath,
                    settings = settings,
                    token = token,
                    onProgress = { msg -> state = ResultUiState.Loading(display, msg) },
                    onDiagnostics = { diag = it },
                    onStarsDetected = { list, total ->
                        detectedStars = list
                        detectedStarTotal = total
                    },
                )
                val elapsedMs = System.currentTimeMillis() - t0

                if (result == null) {
                    withContext(Dispatchers.IO) {
                        StatsStore.record(getApplication(), ok = false, engine = "无", ms = elapsedMs)
                    }
                    val message = if (!settings.hasApiKey) {
                        I18n.Result.solveFailedNoKey
                    } else {
                        I18n.Result.solveFailedWithKey
                    }
                    state = ResultUiState.Error(message, display, diag, elapsedMs)
                    return@launch
                }

                showSuccess(
                    imagePath = imagePath,
                    solve = result.solve,
                    bitmap = result.displayBitmap ?: display,
                    engine = result.engine,
                    engineDetail = result.detail,
                    isDemo = false,
                    elapsedMs = elapsedMs,
                    recordHistory = true,
                )
            } catch (e: CancellationException) {
                // 取消时不弹出错误对话框，静默中止
            } catch (e: Throwable) {
                state = ResultUiState.Error("求解异常：${e.message}")
            }
        }
    }

    private suspend fun showSuccess(
        imagePath: String,
        solve: SolveResult,
        bitmap: Bitmap,
        engine: SolveEngine?,
        engineDetail: String?,
        isDemo: Boolean,
        elapsedMs: Long = 0,
        recordHistory: Boolean = false,
    ) {
        val w = bitmap.width
        val h = bitmap.height
        val rawWcs = solve.wcs ?: run {
            state = ResultUiState.Error(I18n.Result.missingWcs)
            return
        }

        val isEn = LocaleState.isEnglish
        val context = getApplication<Application>()

        val prepared = withContext(Dispatchers.IO) {
            val wcs = if (solve.imageWidth == w && solve.imageHeight == h) {
                rawWcs
            } else {
                rawWcs.rescaledFor(solve.imageWidth, solve.imageHeight, w, h)
            }
            val stars = StarChartOverlay.projectStars(wcs, w, h)
            val lines = StarChartOverlay.projectLines(stars)
            val labels = StarChartOverlay.constellationLabels(stars, isEn)
            val messier = StarChartOverlay.projectMessier(wcs, w, h)

            val needFallback = ExifPriorsReader.needsLocationFallback(imagePath)
            val fallbackLoc = if (needFallback) {
                runCatching { LocationHelper(context).getBestLocation() }.getOrNull()
            } else null

            val solar = if (isDemo) {
                emptyList()
            } else {
                runCatching {
                    StarChartOverlay.projectSolarSystem(
                        wcs, w, h,
                        ExifPriorsReader.solarSystemForPhoto(
                            imagePath,
                            fallbackLoc?.let { it.latitude to it.longitude },
                        ),
                    )
                }.getOrDefault(emptyList())
            }

            if (recordHistory) {
                try {
                    StatsStore.record(context, ok = true, engine = engine?.labelZh ?: "演示", ms = elapsedMs)
                } catch (_: Exception) {}

                try {
                    HistoryStore.save(
                        context,
                        HistoryStore.Entry.fromSolve(
                            timestamp = System.currentTimeMillis(),
                            imagePath = imagePath,
                            solve = solve,
                            engine = engine?.labelZh ?: "演示",
                            constellation = HistoryStore.nearestConstellationZh(solve.raDeg, solve.decDeg),
                        ),
                    )
                } catch (_: Exception) {}
            }

            PreparedSuccess(stars, lines, labels, messier, solar, fallbackLoc != null)
        }

        state = ResultUiState.Success(
            solve = solve,
            bitmap = bitmap,
            stars = prepared.stars,
            lines = prepared.lines,
            labels = prepared.labels,
            messier = prepared.messier,
            isDemo = isDemo,
            engine = engine,
            engineDetail = engineDetail,
            elapsedMs = elapsedMs,
            solar = prepared.solar,
            locationIsFallback = prepared.locationIsFallback,
        )
    }

    /** 取消当前后台求解任务并释放令牌 */
    fun cancel() {
        solveToken?.cancel()
        solveJob?.cancel()
    }

    /** 重试求解 */
    fun retry(imagePath: String, demoRegion: SkyRegion?, settings: SettingsRepository) {
        startSolve(
            imagePath = imagePath,
            demoRegion = demoRegion,
            cachedSolve = null,
            cachedEngine = null,
            settings = settings,
        )
    }

    override fun onCleared() {
        super.onCleared()
        solveToken?.cancel()
        solveJob?.cancel()
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ResultViewModel(application) as T
                }
            }
    }
}
