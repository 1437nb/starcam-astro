package com.starcam.astro.ui.home

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.starcam.astro.util.ImageUtils
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PhotoImportState(
    val running: Boolean = false,
    val completed: Int = 0,
    val total: Int = 0,
    val failed: Int = 0,
    val finished: Boolean = false,
    val paths: List<String> = emptyList(),
    val overLimit: Boolean = false,
)

/** Import survives configuration changes; content-provider reads never run in the picker callback. */
class PhotoImportViewModel(application: Application) : AndroidViewModel(application) {
    var state by mutableStateOf(PhotoImportState())
        private set
    private var task: Job? = null

    fun importPhotos(uris: List<Uri>) {
        if (uris.isEmpty() || state.running) return
        if (uris.size > 9) {
            state = PhotoImportState(finished = true, overLimit = true)
            return
        }
        val inputs = uris.toList()
        state = PhotoImportState(running = true, total = inputs.size)
        task = viewModelScope.launch {
            val ownedFiles = ArrayList<File>()
            var delivered = false
            try {
                val batchId = UUID.randomUUID().toString()
                for ((index, uri) in inputs.withIndex()) {
                    val file = withContext(Dispatchers.IO) {
                        val jobContext = currentCoroutineContext()
                        val prefix = "import_${batchId}_$index"
                        val imported = ImageUtils.copyUriToCacheKeepingName(getApplication(), uri, prefix) { jobContext.ensureActive() }
                        imported?.also { ownedFiles.add(it) }
                    }
                    state = state.copy(
                        completed = index + 1,
                        failed = state.failed + if (file == null) 1 else 0,
                    )
                }
                state = state.copy(running = false, finished = true, paths = ownedFiles.map { it.absolutePath })
                delivered = true
            } catch (e: CancellationException) {
                throw e
            } finally {
                if (!delivered) {
                    // Only this import's uniquely named cache files are owned by this task.
                    withContext(NonCancellable + Dispatchers.IO) { ownedFiles.forEach { it.delete() } }
                }
            }
        }
    }

    fun cancel() {
        task?.cancel()
        state = PhotoImportState()
    }

    fun consumeResult() { state = PhotoImportState() }
}
