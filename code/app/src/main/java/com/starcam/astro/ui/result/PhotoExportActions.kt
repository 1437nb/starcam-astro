package com.starcam.astro.ui.result

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.starcam.astro.astro.LayerFlags
import com.starcam.astro.ui.I18n
import com.starcam.astro.ui.theme.LocaleState
import com.starcam.astro.util.ImageMemoryLimitException
import com.starcam.astro.util.PhotoExportException
import com.starcam.astro.util.PhotoExportFailure
import com.starcam.astro.util.PhotoExportInput
import com.starcam.astro.util.PhotoExporter
import com.starcam.astro.util.PhotoSourceInfo
import com.starcam.astro.util.PhotoSourceKind
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ExportBusyMode { SAVING, SHARING }

private val ExportSaveIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "ExportSave",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(19f, 12f)
            verticalLineTo(19f)
            horizontalLineTo(5f)
            verticalLineTo(12f)
            horizontalLineTo(3f)
            verticalLineTo(19f)
            curveTo(3f, 20.1f, 3.9f, 21f, 5f, 21f)
            horizontalLineTo(19f)
            curveTo(20.1f, 21f, 21f, 20.1f, 21f, 19f)
            verticalLineTo(12f)
            horizontalLineTo(19f)
            close()
            moveTo(13f, 12.67f)
            lineTo(15.59f, 10.09f)
            lineTo(17f, 11.5f)
            lineTo(12f, 16.5f)
            lineTo(7f, 11.5f)
            lineTo(8.41f, 10.09f)
            lineTo(11f, 12.67f)
            verticalLineTo(3f)
            horizontalLineTo(13f)
            verticalLineTo(12.67f)
            close()
        }
    }.build()
}

private val ExportLinesIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "ExportConstellationLines",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(19f, 3f)
            curveTo(17.9f, 3f, 17f, 3.9f, 17f, 5f)
            curveTo(17f, 5.24f, 17.05f, 5.47f, 17.13f, 5.68f)
            lineTo(13.56f, 9.25f)
            curveTo(13.09f, 9.09f, 12.56f, 9f, 12f, 9f)
            curveTo(11.44f, 9f, 10.91f, 9.09f, 10.44f, 9.25f)
            lineTo(7.87f, 6.68f)
            curveTo(7.95f, 6.47f, 8f, 6.24f, 8f, 6f)
            curveTo(8f, 4.9f, 7.1f, 4f, 6f, 4f)
            curveTo(4.9f, 4f, 4f, 4.9f, 4f, 6f)
            curveTo(4f, 7.1f, 4.9f, 8f, 6f, 8f)
            curveTo(6.24f, 8f, 6.47f, 7.95f, 6.68f, 7.87f)
            lineTo(9.25f, 10.44f)
            curveTo(9.09f, 10.91f, 9f, 11.44f, 9f, 12f)
            curveTo(9f, 12.62f, 9.19f, 13.19f, 9.5f, 13.67f)
            lineTo(6.32f, 16.85f)
            curveTo(6.06f, 16.75f, 5.79f, 16.7f, 5.5f, 16.7f)
            curveTo(4.12f, 16.7f, 3f, 17.82f, 3f, 19.2f)
            curveTo(3f, 20.58f, 4.12f, 21.7f, 5.5f, 21.7f)
            curveTo(6.88f, 21.7f, 8f, 20.58f, 8f, 19.2f)
            curveTo(8f, 18.91f, 7.95f, 18.64f, 7.85f, 18.38f)
            lineTo(11.03f, 15.2f)
            curveTo(11.34f, 15.3f, 11.66f, 15.35f, 12f, 15.35f)
            curveTo(13.85f, 15.35f, 15.35f, 13.85f, 15.35f, 12f)
            curveTo(15.35f, 11.44f, 15.21f, 10.91f, 14.97f, 10.45f)
            lineTo(18.54f, 6.88f)
            curveTo(18.69f, 6.95f, 18.84f, 7f, 19f, 7f)
            curveTo(20.1f, 7f, 21f, 6.1f, 21f, 5f)
            curveTo(21f, 3.9f, 20.1f, 3f, 19f, 3f)
            close()
        }
    }.build()
}

private val ExportShareIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "ExportShare",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(18f, 16.08f)
            curveTo(17.24f, 16.08f, 16.56f, 16.38f, 16.04f, 16.85f)
            lineTo(8.91f, 12.7f)
            curveTo(8.96f, 12.47f, 9f, 12.24f, 9f, 12f)
            curveTo(9f, 11.76f, 8.96f, 11.53f, 8.91f, 11.3f)
            lineTo(15.96f, 7.19f)
            curveTo(16.5f, 7.69f, 17.21f, 8f, 18f, 8f)
            curveTo(19.66f, 8f, 21f, 6.66f, 21f, 5f)
            curveTo(21f, 3.34f, 19.66f, 2f, 18f, 2f)
            curveTo(16.34f, 2f, 15f, 3.34f, 15f, 5f)
            curveTo(15f, 5.24f, 15.04f, 5.47f, 15.09f, 5.7f)
            lineTo(8.04f, 9.81f)
            curveTo(7.5f, 9.31f, 6.79f, 9f, 6f, 9f)
            curveTo(4.34f, 9f, 3f, 10.34f, 3f, 12f)
            curveTo(3f, 13.66f, 4.34f, 15f, 6f, 15f)
            curveTo(6.79f, 15f, 7.5f, 14.69f, 8.04f, 14.19f)
            lineTo(15.16f, 18.35f)
            curveTo(15.11f, 18.56f, 15.08f, 18.78f, 15.08f, 19f)
            curveTo(15.08f, 20.61f, 16.39f, 21.92f, 18f, 21.92f)
            curveTo(19.61f, 21.92f, 20.92f, 20.61f, 20.92f, 19f)
            curveTo(20.92f, 17.39f, 19.61f, 16.08f, 18f, 16.08f)
            close()
        }
    }.build()
}

@Composable
internal fun PhotoExportActions(
    state: ResultUiState.Success,
    layerFlags: LayerFlags,
    renderPreview: (LayerFlags, Boolean) -> Bitmap,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isEn = LocaleState.isEnglish

    val hasSourceFile = !state.exportSourcePath.isNullOrBlank()
    var exportOriginal by remember(state) { mutableStateOf(false) }
    var source by remember(state) { mutableStateOf<PhotoSourceInfo?>(null) }
    var inspecting by remember(state) { mutableStateOf(false) }
    var sourceError by remember(state) { mutableStateOf<String?>(null) }
    var saveError by remember(state) { mutableStateOf<String?>(null) }
    var busyMode by remember(state) { mutableStateOf<ExportBusyMode?>(null) }

    LaunchedEffect(state.exportSourcePath, state.solveUsesUprightPixels) {
        source = null
        sourceError = null
        if (!hasSourceFile) {
            exportOriginal = false
            return@LaunchedEffect
        }
        inspecting = true
        try {
            source = PhotoExporter.inspect(context, state.exportSourcePath.orEmpty())
            if (state.solveUsesUprightPixels == null) {
                sourceError = I18n.Export.unknownBasis
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PhotoExportException) {
            sourceError = exportFailureMessage(e.reason)
        } catch (_: ImageMemoryLimitException) {
            sourceError = I18n.Export.memoryLimit
        } catch (_: OutOfMemoryError) {
            sourceError = I18n.Export.memoryLimit
        } catch (_: Exception) {
            sourceError = I18n.Export.cannotRead
        } finally {
            inspecting = false
        }
    }

    fun triggerSave(flags: LayerFlags, linesOnlyMode: Boolean) {
        if (busyMode != null) return
        val english = LocaleState.isEnglish
        val useOriginal = exportOriginal
        busyMode = ExportBusyMode.SAVING
        saveError = null
        scope.launch {
            try {
                val input = PhotoExportInput(
                    state.exportSourcePath.orEmpty(),
                    state.solve,
                    state.solveUsesUprightPixels,
                    state.solar.map { it.pos },
                    state.bitmap.width,
                    state.bitmap.height,
                )
                val saved = PhotoExporter.save(context, input, flags, english, useOriginal) {
                    renderPreview(flags, english)
                }
                val locDesc = "${saved.location} (${saved.width}×${saved.height})"
                val msg = if (linesOnlyMode) {
                    I18n.Result.savedLinesOnlyToast(locDesc)
                } else {
                    I18n.Result.savedToast(locDesc)
                }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: PhotoExportException) {
                saveError = exportFailureMessage(e.reason)
            } catch (_: ImageMemoryLimitException) {
                saveError = I18n.Export.memoryLimit
            } catch (_: OutOfMemoryError) {
                saveError = I18n.Export.memoryLimit
            } catch (_: Exception) {
                saveError = I18n.Result.saveFailed
            } finally {
                busyMode = null
            }
        }
    }

    fun triggerShare() {
        if (busyMode != null) return
        val english = LocaleState.isEnglish
        val flags = layerFlags
        busyMode = ExportBusyMode.SHARING
        saveError = null
        scope.launch {
            try {
                val solve = state.solve
                val cons = state.labels.map { it.text }.takeIf { it.isNotEmpty() }?.joinToString(" · ")
                val caption = buildString {
                    if (english) {
                        append("StarCam Solved · FOV ")
                        append("%.1f°×%.1f°".format(Locale.ROOT, solve.fieldWidthDeg, solve.fieldHeightDeg))
                        if (!cons.isNullOrEmpty()) append(" · $cons")
                    } else {
                        append("星空识星 · 视场 ")
                        append("%.1f°×%.1f°".format(Locale.ROOT, solve.fieldWidthDeg, solve.fieldHeightDeg))
                        if (!cons.isNullOrEmpty()) append(" · $cons")
                    }
                }
                val shareUri: Uri? = withContext(Dispatchers.IO) {
                    val bmp = renderPreview(flags, english)
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val fileName = "StarCam_share_${UUID.randomUUID()}.jpg"
                            val values = ContentValues().apply {
                                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/StarCam")
                                put(MediaStore.Images.Media.IS_PENDING, 1)
                            }
                            val inserted = context.contentResolver.insert(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                values,
                            )
                            if (inserted != null) {
                                var ok = false
                                try {
                                    ok = context.contentResolver.openOutputStream(inserted)?.use { out ->
                                        bmp.compress(Bitmap.CompressFormat.JPEG, 94, out)
                                    } ?: false
                                    if (ok) {
                                        context.contentResolver.update(
                                            inserted,
                                            ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                                            null,
                                            null,
                                        )
                                    }
                                } finally {
                                    if (!ok) {
                                        runCatching { context.contentResolver.delete(inserted, null, null) }
                                    }
                                }
                                if (ok) inserted else null
                            } else null
                        } else null
                    } finally {
                        bmp.recycle()
                    }
                }

                val sendIntent = if (shareUri != null) {
                    Intent(Intent.ACTION_SEND).apply {
                        type = "image/jpeg"
                        putExtra(Intent.EXTRA_STREAM, shareUri)
                        putExtra(Intent.EXTRA_TEXT, caption)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                } else {
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, caption)
                    }
                }
                val chooser = Intent.createChooser(
                    sendIntent,
                    if (english) "Share Star Photo" else "分享星空标注图",
                )
                if (context !is Activity) {
                    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
            } catch (e: CancellationException) {
                throw e
            } catch (_: ImageMemoryLimitException) {
                saveError = I18n.Export.memoryLimit
            } catch (_: OutOfMemoryError) {
                saveError = I18n.Export.memoryLimit
            } catch (_: Exception) {
                saveError = I18n.Result.saveFailed
            } finally {
                busyMode = null
            }
        }
    }

    val isBusy = busyMode != null
    val info = source
    val canSaveSelectedSize = !isBusy && (!exportOriginal || (info != null && sourceError == null && !inspecting))

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = I18n.Export.title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(
                        text = "JPEG",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }

            // 分段尺寸选择器：预览尺寸 vs 原图尺寸
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ResolutionSegmentOption(
                        title = if (isEn) "Preview Size" else "预览尺寸",
                        subtitle = "${state.bitmap.width} × ${state.bitmap.height}",
                        selected = !exportOriginal,
                        enabled = !isBusy,
                        onClick = {
                            exportOriginal = false
                            saveError = null
                        },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )

                    val origTitle = if (info?.kind == PhotoSourceKind.RAW_PREVIEW) {
                        if (isEn) "Embedded Preview" else "内嵌预览尺寸"
                    } else {
                        if (isEn) "Original Size" else "原图尺寸"
                    }
                    val origSubtitle = when {
                        !hasSourceFile -> if (isEn) "Preview only" else "仅支持预览"
                        inspecting -> if (isEn) "Checking…" else "读取中…"
                        info != null -> "${info.outputWidth} × ${info.outputHeight}"
                        else -> if (isEn) "Unavailable" else "不可用"
                    }
                    ResolutionSegmentOption(
                        title = origTitle,
                        subtitle = origSubtitle,
                        selected = exportOriginal,
                        enabled = hasSourceFile && !isBusy,
                        onClick = {
                            exportOriginal = true
                            saveError = null
                        },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            }

            AnimatedVisibility(
                visible = exportOriginal && (info != null || sourceError != null || inspecting),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                when {
                    inspecting -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 1.8.dp,
                            )
                            Text(
                                text = if (isEn) "Inspecting original image dimensions…" else "正在读取原始图像尺寸…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    sourceError != null -> {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.errorContainer,
                        ) {
                            Text(
                                text = sourceError.orEmpty(),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                    info != null -> {
                        Text(
                            text = when (info.kind) {
                                PhotoSourceKind.RAW_PREVIEW -> I18n.Export.rawNote
                                PhotoSourceKind.FITS -> I18n.Export.fitsNote
                                else -> I18n.Export.originalNote
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // 主按钮：保存当前图层标注
            Button(
                onClick = { triggerSave(layerFlags, linesOnlyMode = layerFlags == LayerFlags.LINES_ONLY) },
                enabled = canSaveSelectedSize,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Icon(
                    imageVector = ExportSaveIcon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = I18n.Result.saveAnnotated.removePrefix("💾 ").trim(),
                    fontWeight = FontWeight.SemiBold,
                )
            }

            // 次级按钮排：仅星座连线（色调填充） + 系统分享（描边）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FilledTonalButton(
                    onClick = { triggerSave(LayerFlags.LINES_ONLY, linesOnlyMode = true) },
                    enabled = canSaveSelectedSize,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                ) {
                    Icon(
                        imageVector = ExportLinesIcon,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = I18n.Result.saveLinesOnly.removePrefix("📐 ").trim(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                OutlinedButton(
                    onClick = { triggerShare() },
                    enabled = !isBusy,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                ) {
                    Icon(
                        imageVector = ExportShareIcon,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (isEn) "Share" else "分享结果",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // 平滑导出/分享进度反馈
            AnimatedVisibility(
                visible = isBusy,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(13.dp),
                            strokeWidth = 1.8.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = if (busyMode == ExportBusyMode.SHARING) {
                                if (isEn) "Preparing annotated image for sharing…" else "正在生成分享图片…"
                            } else {
                                I18n.Export.saving
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            saveError?.let { err ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Text(
                        text = err,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ResolutionSegmentOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        Color.Transparent
    }
    val border = if (selected) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
    } else {
        null
    }
    val titleColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        selected -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val subColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        selected -> MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.clip(MaterialTheme.shapes.small),
        shape = MaterialTheme.shapes.small,
        color = bg,
        border = border,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = subColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun exportFailureMessage(reason: PhotoExportFailure): String = when (reason) {
    PhotoExportFailure.SOURCE_MISSING -> I18n.Export.sourceMissing
    PhotoExportFailure.CANNOT_READ -> I18n.Export.cannotRead
    PhotoExportFailure.MEMORY_LIMIT -> I18n.Export.memoryLimit
    PhotoExportFailure.UNKNOWN_BASIS -> I18n.Export.unknownBasis
    PhotoExportFailure.SAVE_FAILED -> I18n.Result.saveFailed
}
