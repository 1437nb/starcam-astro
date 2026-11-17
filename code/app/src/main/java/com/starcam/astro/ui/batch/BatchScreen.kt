package com.starcam.astro.ui.batch

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.starcam.astro.data.SettingsRepository
import com.starcam.astro.ui.AppIcons
import com.starcam.astro.ui.I18n
import com.starcam.astro.ui.result.ResultScreen
import com.starcam.astro.ui.theme.LocaleState
import com.starcam.astro.ui.theme.onSuccessContainer
import com.starcam.astro.ui.theme.successContainer
import com.starcam.astro.util.ThumbnailCache
import java.io.File
import kotlinx.coroutines.CancellationException

/** 批量求解与逐张审阅（§0.143 交互升级：支持单张就绪即时打开查看/重试，不等全批结束）。 */
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
    val isEn = LocaleState.isEnglish
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
    // 注意：此处仅以 selectedIndex 记忆打开瞬间的缓存解，避免在详情页内重试成功写回 outcomes 时
    // 改变 cachedSolve 触发 ResultScreen ViewModel key 切换与位图重载；退出详情返回列表再点入即取最新解。
    if (selectedIndex in paths.indices) {
        val solved = remember(selectedIndex) { outcomes.getOrNull(selectedIndex) as? BatchOutcome.Solved }
        ResultScreen(
            imagePath = paths[selectedIndex],
            demoRegion = null,
            cachedSolve = solved?.solve,
            cachedEngine = solved?.engineDetail,
            cachedDisplayPath = solved?.displayPath,
            settings = settings,
            onBack = { selectedIndex = -1 },
            onRetake = onRetake,
            onSolveCompleted = { viewModel.recordRetry(selectedIndex, it.solve, it.engine, it.engineDetail) },
        )
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        I18n.Batch.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = I18n.back,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            BatchProgressCard(
                total = paths.size,
                counts = counts,
                activeCount = viewModel.processing.size,
                finished = finished,
                isEn = isEn,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(paths) { i, path ->
                    val outcome = outcomes.getOrNull(i)
                    val isProcessing = i in viewModel.processing
                    // §0.143 解锁提前审阅：单张求解完成（成功或失败）即可立即点开查看或单独重试
                    val canOpen = outcome != null
                    val note = outcome?.note.orEmpty()
                    val detailLine = (outcome as? BatchOutcome.Solved)?.engineDetail.orEmpty()

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .clickable(enabled = canOpen) { selectedIndex = i },
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (isProcessing) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                            } else {
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                            },
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BatchThumbnail(path)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    File(path).name,
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (detailLine.isNotEmpty()) {
                                    Text(
                                        detailLine,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 2.dp),
                                    )
                                }
                                if (canOpen) {
                                    Text(
                                        if (outcome?.solved == true) I18n.Batch.viewResult else I18n.Batch.retryPhoto,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 3.dp),
                                    )
                                }
                                if (note.isNotEmpty()) {
                                    Text(
                                        note,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 2.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            BatchStatusBadge(
                                outcome = outcome,
                                isProcessing = isProcessing,
                            )
                            if (canOpen) {
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
                item {
                    if (finished || counts.done > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            I18n.Batch.reviewNotice,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
}

@Composable
private fun BatchProgressCard(
    total: Int,
    counts: BatchCounts,
    activeCount: Int,
    finished: Boolean,
    isEn: Boolean,
    modifier: Modifier = Modifier,
) {
    val safeTotal = total.coerceAtLeast(1)
    val done = (counts.solved + counts.failed).coerceIn(0, safeTotal)
    val progress = done.toFloat() / safeTotal
    val remaining = (total - done).coerceAtLeast(0)

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (finished) {
                        if (isEn) "Batch Solving Complete" else "批量识别已完成"
                    } else {
                        if (isEn) "Solving Batch…" else "正在批量识别…"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "$done / $total",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(Modifier.height(10.dp))

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BatchMetricPill(
                    label = if (isEn) "Solved ${counts.solved}" else "成功 ${counts.solved}",
                    containerColor = if (counts.solved > 0) {
                        MaterialTheme.colorScheme.successContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    contentColor = if (counts.solved > 0) {
                        MaterialTheme.colorScheme.onSuccessContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f),
                )
                BatchMetricPill(
                    label = if (isEn) "Failed ${counts.failed}" else "失败 ${counts.failed}",
                    containerColor = if (counts.failed > 0) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    contentColor = if (counts.failed > 0) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f),
                )
                BatchMetricPill(
                    label = when {
                        finished -> if (isEn) "Total $total" else "共 $total 张"
                        activeCount > 0 -> if (isEn) "Active $activeCount · Left $remaining" else "进行 $activeCount · 剩 $remaining"
                        else -> if (isEn) "Queued $remaining" else "待处理 $remaining"
                    },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1.25f),
                )
            }

            if (!finished && done > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    if (isEn) {
                        "Completed photos can be opened immediately while remaining items finish."
                    } else {
                        "已完成的照片现在即可点击查看或单独重试，无需等待全部跑完。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BatchMetricPill(
    label: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = containerColor,
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun BatchStatusBadge(
    outcome: BatchOutcome?,
    isProcessing: Boolean,
) {
    val containerColor: Color
    val contentColor: Color
    val label: String
    when {
        isProcessing -> {
            containerColor = MaterialTheme.colorScheme.primaryContainer
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            label = I18n.Batch.processing
        }
        outcome == null -> {
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            label = I18n.Batch.queued
        }
        outcome.solved -> {
            containerColor = MaterialTheme.colorScheme.successContainer
            contentColor = MaterialTheme.colorScheme.onSuccessContainer
            label = if (outcome.exported) I18n.Batch.exported else I18n.Batch.solved
        }
        else -> {
            containerColor = MaterialTheme.colorScheme.errorContainer
            contentColor = MaterialTheme.colorScheme.onErrorContainer
            label = I18n.Batch.failed
        }
    }

    Surface(
        shape = CircleShape,
        color = containerColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isProcessing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = contentColor,
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = contentColor,
                maxLines = 1,
            )
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
    val modifier = Modifier
        .size(56.dp)
        .clip(MaterialTheme.shapes.small)
    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                AppIcons.Gallery,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
