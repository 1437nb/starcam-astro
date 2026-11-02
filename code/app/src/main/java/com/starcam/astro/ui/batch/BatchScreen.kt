package com.starcam.astro.ui.batch

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.starcam.astro.data.SettingsRepository
import com.starcam.astro.ui.I18n
import com.starcam.astro.ui.result.ResultScreen
import com.starcam.astro.util.ThumbnailCache
import java.io.File
import kotlinx.coroutines.CancellationException

/** 批量完成后逐张查看，手动选择图层和导出；列表与求解结果保留在原批次。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchScreen(
    paths: List<String>,
    sessionId: String,
    settings: SettingsRepository,
    onBack: () -> Unit,
    onRetake: () -> Unit,
    viewModel: BatchViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        key = "batch-$sessionId",
        factory = BatchViewModel.factory(LocalContext.current.applicationContext as Application),
    ),
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var selectedIndex by rememberSaveable { mutableIntStateOf(-1) }
    val outcomes = viewModel.outcomes
    val counts = viewModel.counts
    val finished = viewModel.finished
    LaunchedEffect(viewModel) { viewModel.start(paths, settings) }
    DisposableEffect(viewModel) {
        onDispose {
            if ((context as? Activity)?.isChangingConfigurations != true) viewModel.cancel()
        }
    }

    // 详情是当前批次的子页。保持 BatchScreen 在组合中，返回不会重启批量识别。
    if (selectedIndex in paths.indices) {
        val solved = outcomes.getOrNull(selectedIndex) as? BatchOutcome.Solved
        ResultScreen(
            imagePath = paths[selectedIndex],
            demoRegion = null,
            cachedSolve = solved?.solve,
            cachedEngine = solved?.engineDetail,
            cachedDisplayPath = solved?.displayPath,
            settings = settings,
            onBack = { selectedIndex = -1 },
            onRetake = onRetake,
        )
        return
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(com.starcam.astro.ui.I18n.Batch.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = com.starcam.astro.ui.I18n.back)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Text(
                I18n.Batch.reviewSummary(
                    paths.size, counts.solved, counts.failed, finished,
                ),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
            ) {
                itemsIndexed(paths) { i, path ->
                    val outcome = outcomes.getOrNull(i)
                    val isProcessing = i in viewModel.processing
                    val canOpen = finished && outcome != null
                    val note = outcome?.note.orEmpty()
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable(enabled = canOpen) { selectedIndex = i },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BatchThumbnail(path)
                            Spacer(Modifier.size(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    File(path).name,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                )
                                if (canOpen) {
                                    Text(
                                        if (outcome?.solved == true) I18n.Batch.viewResult else I18n.Batch.retryPhoto,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                if (note.isNotEmpty()) {
                                    Text(
                                        note,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Text(
                                when {
                                    isProcessing -> I18n.Batch.processing
                                    outcome == null -> I18n.Batch.queued
                                    outcome.solved -> I18n.Batch.solved
                                    else -> I18n.Batch.failed
                                },
                                modifier = Modifier.padding(start = 8.dp),
                                color = when {
                                    outcome?.solved == true -> MaterialTheme.colorScheme.primary
                                    outcome != null -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
                item {
                    if (finished) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            I18n.Batch.reviewNotice,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun BatchThumbnail(path: String) {
    var bitmap by remember(path) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(path) {
        bitmap = try {
            ThumbnailCache.load(path, 160)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
    val modifier = Modifier.size(56.dp).clip(MaterialTheme.shapes.small)
    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        Spacer(modifier)
    }
}
