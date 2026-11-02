package com.starcam.astro.ui.batch

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.starcam.astro.astro.SolveToken
import com.starcam.astro.astro.StarSolver
import com.starcam.astro.data.HistoryStore
import com.starcam.astro.data.SettingsRepository
import com.starcam.astro.ui.I18n
import com.starcam.astro.ui.theme.LocaleState
import com.starcam.astro.util.ImageUtils
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 批量结果只保留轻量元数据，逐张查看时复用单张结果页。旋转屏幕不重复求解。 */
class BatchViewModel(application: Application) : AndroidViewModel(application) {
    var outcomes by mutableStateOf<List<BatchOutcome?>>(emptyList())
        private set
    var processing by mutableStateOf<Set<Int>>(emptySet())
        private set
    var finished by mutableStateOf(false)
        private set
    var counts by mutableStateOf(BatchCounts(0, 0, 0))
        private set

    private var job: Job? = null
    private var started = false
    private val token = SolveToken()

    fun start(paths: List<String>, settings: SettingsRepository) {
        if (started) return
        started = true
        outcomes = List(paths.size) { null }
        val context = getApplication<Application>()
        job = viewModelScope.launch {
            val queue = paths.withIndex().toMutableList()
            val tally = BatchTally()
            val lowRam = (context.getSystemService(android.content.Context.ACTIVITY_SERVICE)
                as? android.app.ActivityManager)?.isLowRamDevice == true
            val workers = (if (lowRam) 1 else 2).coerceAtMost(paths.size)
            coroutineScope {
                repeat(workers) {
                    launch {
                        while (isActive && queue.isNotEmpty()) {
                            // 出队和状态写入均在主线程，IO 处理可并发。
                            val (index, path) = queue.removeAt(0)
                            processing = processing + index
                            val outcome = withContext(Dispatchers.IO) { processOne(path, settings) }
                            outcomes = outcomes.toMutableList().also { it[index] = outcome }
                            processing = processing - index
                            tally.record(outcome)
                            counts = tally.snapshot()
                        }
                    }
                }
            }
            finished = true
        }
    }

    private suspend fun processOne(path: String, settings: SettingsRepository): BatchOutcome {
        val context = getApplication<Application>()
        var bitmap: Bitmap? = null
        var onlineBitmap: Bitmap? = null
        try {
            val display = ImageUtils.decodeSampledBitmap(path, 2200)
                ?: return BatchOutcome.ProcessingFailed(I18n.Result.cannotReadImage)
            bitmap = display
            val result = StarSolver.solve(context, display, path, settings, token = token) { }
                ?: return BatchOutcome.SolveFailed(I18n.Batch.solveFailed)
            onlineBitmap = result.displayBitmap
            kotlin.coroutines.coroutineContext.ensureActive()
            token.check()
            if (result.solve.wcs == null) return BatchOutcome.ProcessingFailed(I18n.Result.missingWcs)

            // 在线 WCS 对应上传时旋转后的像素。保存无损显示副本，重新打开不能直接套原图。
            // 文件在应用私有 cache 中，不进入系统相册；原路径保留 EXIF 拍摄时间和位置。
            val displayPath = result.displayBitmap?.let { online ->
                val file = File.createTempFile("batch-display-", ".png", context.cacheDir)
                file.outputStream().use { stream ->
                    check(online.compress(Bitmap.CompressFormat.PNG, 100, stream))
                }
                file.absolutePath
            }
            val engineDetail = listOf(result.engine.label(LocaleState.isEnglish), result.detail)
                .filter { it.isNotBlank() }.joinToString(" · ")
            try {
                HistoryStore.save(
                    context,
                    HistoryStore.Entry.fromSolve(
                        timestamp = System.currentTimeMillis(),
                        imagePath = path,
                        solve = result.solve,
                        engine = result.engine.labelZh,
                        constellation = HistoryStore.nearestConstellationZh(
                            result.solve.raDeg, result.solve.decDeg,
                        ),
                    ),
                )
            } catch (_: Exception) {
                // 历史写入失败不丢弃本次批量结果，仍可单张查看和导出。
            }
            return BatchOutcome.Solved(result.solve, engineDetail, displayPath)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return BatchOutcome.ProcessingFailed(I18n.Batch.processingFailed(e.message))
        } finally {
            onlineBitmap?.takeIf { it !== bitmap && !it.isRecycled }?.recycle()
            bitmap?.takeUnless { it.isRecycled }?.recycle()
        }
    }

    fun cancel() {
        token.cancel()
        job?.cancel()
    }

    override fun onCleared() {
        cancel()
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    BatchViewModel(application) as T
            }
    }
}
