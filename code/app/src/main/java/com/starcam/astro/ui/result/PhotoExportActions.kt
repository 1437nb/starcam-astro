package com.starcam.astro.ui.result

import android.widget.Toast
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.starcam.astro.astro.LayerFlags
import com.starcam.astro.ui.I18n
import com.starcam.astro.ui.theme.LocaleState
import com.starcam.astro.util.PhotoExportException
import com.starcam.astro.util.PhotoExportFailure
import com.starcam.astro.util.PhotoExportInput
import com.starcam.astro.util.PhotoExporter
import com.starcam.astro.util.PhotoSourceInfo
import com.starcam.astro.util.PhotoSourceKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun PhotoExportActions(
    state: ResultUiState.Success,
    layerFlags: LayerFlags,
    renderPreview: (LayerFlags, Boolean) -> android.graphics.Bitmap,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedFlags by remember(state) { mutableStateOf<LayerFlags?>(null) }
    var source by remember(state) { mutableStateOf<PhotoSourceInfo?>(null) }
    var inspecting by remember(state) { mutableStateOf(false) }
    var sourceError by remember(state) { mutableStateOf<String?>(null) }
    var saveError by remember(state) { mutableStateOf<String?>(null) }
    var saving by remember(state) { mutableStateOf(false) }

    LaunchedEffect(selectedFlags) {
        if (selectedFlags != null) {
            source = null
            sourceError = null
            inspecting = true
            try {
                source = PhotoExporter.inspect(context, state.exportSourcePath.orEmpty())
                if (state.solveUsesUprightPixels == null) sourceError = I18n.Export.unknownBasis
            } catch (e: CancellationException) {
                throw e
            } catch (e: PhotoExportException) {
                sourceError = exportFailureMessage(e.reason)
            } catch (_: OutOfMemoryError) {
                sourceError = I18n.Export.memoryLimit
            } catch (_: Exception) {
                sourceError = I18n.Export.cannotRead
            } finally {
                inspecting = false
            }
        }
    }

    fun save(original: Boolean) {
        val flags = selectedFlags ?: return
        val english = LocaleState.isEnglish
        selectedFlags = null
        saving = true
        saveError = null
        scope.launch {
            try {
                val input = PhotoExportInput(
                    state.exportSourcePath.orEmpty(), state.solve, state.solveUsesUprightPixels,
                    state.solar.map { it.pos }, state.bitmap.width, state.bitmap.height,
                )
                val saved = PhotoExporter.save(context, input, flags, english, original) { renderPreview(flags, english) }
                Toast.makeText(context, I18n.Result.savedToast("${saved.location} (${saved.width}×${saved.height})"), Toast.LENGTH_LONG).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: PhotoExportException) {
                saveError = exportFailureMessage(e.reason)
            } catch (_: OutOfMemoryError) {
                saveError = I18n.Export.memoryLimit
            } catch (_: Exception) {
                saveError = I18n.Result.saveFailed
            } finally {
                saving = false
            }
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { selectedFlags = LayerFlags.LINES_ONLY }, enabled = !saving,
                modifier = Modifier.weight(1f),
            ) { Text(I18n.Result.saveLinesOnly) }
            Button(
                onClick = { selectedFlags = layerFlags }, enabled = !saving,
                modifier = Modifier.weight(1f),
            ) { Text(I18n.Result.saveAnnotated) }
        }
        if (saving) Text(I18n.Export.saving, style = MaterialTheme.typography.bodySmall)
        saveError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }

    if (selectedFlags != null) {
        AlertDialog(
            onDismissRequest = { selectedFlags = null },
            title = { Text(I18n.Export.title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(I18n.Export.description)
                    OutlinedButton(onClick = { save(false) }, modifier = Modifier.fillMaxWidth()) {
                        Text(I18n.Export.preview(state.bitmap.width, state.bitmap.height))
                    }
                    if (inspecting) CircularProgressIndicator()
                    val info = source
                    if (info != null) {
                        Button(
                            onClick = { save(true) }, enabled = sourceError == null && !inspecting,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(I18n.Export.original(info.outputWidth, info.outputHeight, info.kind == PhotoSourceKind.RAW_PREVIEW)) }
                        Text(
                            when (info.kind) {
                                PhotoSourceKind.RAW_PREVIEW -> I18n.Export.rawNote
                                PhotoSourceKind.FITS -> I18n.Export.fitsNote
                                else -> I18n.Export.originalNote
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    sourceError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = { selectedFlags = null }) { Text(I18n.cancel) } },
        )
    }
}

private fun exportFailureMessage(reason: PhotoExportFailure): String = when (reason) {
    PhotoExportFailure.SOURCE_MISSING -> I18n.Export.sourceMissing
    PhotoExportFailure.CANNOT_READ -> I18n.Export.cannotRead
    PhotoExportFailure.MEMORY_LIMIT -> I18n.Export.memoryLimit
    PhotoExportFailure.UNKNOWN_BASIS -> I18n.Export.unknownBasis
    PhotoExportFailure.SAVE_FAILED -> I18n.Result.saveFailed
}
