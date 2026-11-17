package com.starcam.astro.ui.history

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.starcam.astro.data.HistoryStore
import com.starcam.astro.data.StatsStore
import com.starcam.astro.ui.AppIcons
import com.starcam.astro.ui.I18n
import com.starcam.astro.ui.theme.AppThemeMode
import com.starcam.astro.ui.theme.LocaleState
import com.starcam.astro.ui.theme.ThemeState
import com.starcam.astro.util.ThumbnailCache
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 识别历史页（§0.40 网格化 / §0.143 交互与视觉精修）：
 * 按日期分组缩略图网格、单条与全部清空二次确认、CSV 导出、三列识别遥测摘要卡。
 */
// lint 的 ProduceStateDoesNotAssignValue 对本文件的写法误报：`value = …` 确实在
// producer lambda 顶层（这些界面在真机上一直正常显示，即运行时证据）。定点豁免，
// 保留该规则对新增代码的约束（2026-10-11 lintDebug 进入 CI 门禁）。
@Suppress("ProduceStateDoesNotAssignValue")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onOpenEntry: (HistoryStore.Entry) -> Unit,
) {
    val context = LocalContext.current
    val isEn = LocaleState.isEnglish
    // §0.81：磁盘读取、统计汇总与 CSV 导出全部在 IO 线程异步执行，初值给空。
    var entries by remember { mutableStateOf<List<HistoryStore.Entry>>(emptyList()) }
    var deleting by remember { mutableStateOf<HistoryStore.Entry?>(null) }
    var confirmClearAll by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        entries = withContext(Dispatchers.IO) { HistoryStore.load(context) }
    }
    val stats by produceState(
        initialValue = StatsStore.Summary(0, 0.0, 0L, 0L),
        entries,
    ) {
        value = withContext(Dispatchers.IO) { StatsStore.summary(context) }
    }
    val dayFmt = remember(isEn) {
        SimpleDateFormat(
            if (isEn) "EEE, MMM d, yyyy" else "yyyy年M月d日 EEEE",
            if (isEn) Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE,
        )
    }
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    // 按日期分组（entries 已时间倒序，LinkedHashMap 保序）
    val groups = remember(entries, dayFmt) {
        val map = LinkedHashMap<String, MutableList<HistoryStore.Entry>>()
        for (e in entries) {
            map.getOrPut(dayFmt.format(Date(e.timestamp))) { mutableListOf() }.add(e)
        }
        map
    }

    toast?.let { msg ->
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
        toast = null
    }

    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(I18n.History.deleteDialogTitle) },
            text = {
                Text(
                    buildString {
                        val con = entry.localizedConstellation(isEn)
                        if (con.isNotEmpty()) append(con).append(" · ")
                        append(timeFmt.format(Date(entry.timestamp)))
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    // §0.81：删除要重写 SharedPreferences（JSON 序列化）—— 移出主线程
                    scope.launch {
                        entries = withContext(Dispatchers.IO) {
                            HistoryStore.remove(context, entry.timestamp)
                            HistoryStore.load(context)
                        }
                        deleting = null
                    }
                }) { Text(I18n.delete, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(I18n.cancel) }
            },
        )
    }

    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            title = {
                Text(if (isEn) "Clear all history?" else "清空全部识别历史？")
            },
            text = {
                Text(
                    if (isEn) {
                        "This will permanently remove all ${entries.size} recognition records and cached history thumbnails. Annotated photos already saved to your gallery will not be affected."
                    } else {
                        "将永久移除全部 ${entries.size} 条识别记录及历史缓存图片（已保存到系统相册的标注图不受影响），此操作无法恢复。"
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearAll = false
                    scope.launch {
                        withContext(Dispatchers.IO) { HistoryStore.clear(context) }
                        entries = emptyList()
                    }
                }) {
                    Text(
                        if (isEn) "Clear All" else "确认清空",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearAll = false }) { Text(I18n.cancel) }
            },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        I18n.History.title,
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
                actions = {
                    if (entries.isNotEmpty()) {
                        Surface(
                            onClick = {
                                // §0.81：导出会走 contentResolver(Binder) + 全量写盘
                                scope.launch {
                                    val desc = withContext(Dispatchers.IO) {
                                        HistoryStore.exportCsv(context, entries)
                                    }
                                    toast = desc?.let {
                                        I18n.History.exportSuccess(it)
                                    } ?: I18n.History.exportFailed
                                }
                            },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.padding(end = 4.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Filled.Share,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(15.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    I18n.History.export,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                        TextButton(
                            onClick = { confirmClearAll = true },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.padding(end = 6.dp),
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                I18n.History.clear,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
            ) {
                if (stats.count > 0) {
                    HistoryTelemetryCard(
                        stats = stats,
                        isEn = isEn,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    HistoryEmptyBadge()
                    Spacer(Modifier.height(18.dp))
                    Text(
                        I18n.History.emptyTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        I18n.History.emptyDesc,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // 顶部三列识别遥测摘要卡（§0.40 识别打点 / §0.143 升级）
                item(span = { GridItemSpan(maxLineSpan) }) {
                    HistoryTelemetryCard(
                        stats = stats,
                        isEn = isEn,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp),
                    )
                }
                for ((day, dayEntries) in groups) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp, bottom = 4.dp, start = 2.dp, end = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                day,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                if (isEn) "${dayEntries.size}" else "${dayEntries.size} 张",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(dayEntries, key = { it.timestamp }) { e ->
                        HistoryCell(
                            entry = e,
                            timeFmt = timeFmt,
                            isEn = isEn,
                            onClick = { onOpenEntry(e) },
                            onDelete = { deleting = e },
                        )
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun HistoryTelemetryCard(
    stats: StatsStore.Summary,
    isEn: Boolean,
    modifier: Modifier = Modifier,
) {
    val hasStats = stats.count > 0
    val countText = if (hasStats) "${stats.count}" else "—"
    val rateText = if (hasStats) "${(stats.successRate * 100).toInt()}%" else "—"
    val avgText = if (hasStats) {
        I18n.Result.formatSolveTime(stats.avgMs.coerceAtLeast(100L)).ifEmpty { "${stats.avgMs / 1000}s" }
    } else {
        "—"
    }

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TelemetryColumn(
                value = countText,
                caption = if (isEn) "Total Solves" else "总识别次数",
                highlight = false,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(28.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            TelemetryColumn(
                value = rateText,
                caption = if (isEn) "Success Rate" else "成功率",
                highlight = hasStats,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(28.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            TelemetryColumn(
                value = avgText,
                caption = if (isEn) "Avg Time" else "平均耗时",
                highlight = false,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TelemetryColumn(
    value: String,
    caption: String,
    highlight: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (highlight) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            caption,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HistoryEmptyBadge() {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    Surface(
        modifier = Modifier.size(76.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Canvas(modifier = Modifier.padding(18.dp)) {
            val w = size.width
            val h = size.height
            val p1 = Offset(w * 0.20f, h * 0.72f)
            val p2 = Offset(w * 0.48f, h * 0.42f)
            val p3 = Offset(w * 0.76f, h * 0.28f)
            val p4 = Offset(w * 0.78f, h * 0.68f)

            val lines = Path().apply {
                moveTo(p1.x, p1.y)
                lineTo(p2.x, p2.y)
                lineTo(p3.x, p3.y)
                lineTo(p4.x, p4.y)
                lineTo(p2.x, p2.y)
            }
            drawPath(
                path = lines,
                color = secondary.copy(alpha = 0.65f),
                style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            for (pt in listOf(p1, p2, p4)) {
                drawCircle(color = secondary, radius = 2.6.dp.toPx(), center = pt)
            }
            // 主亮星十字星芒
            val rOuter = 6.5.dp.toPx()
            val rInner = 1.8.dp.toPx()
            val star = Path().apply {
                moveTo(p3.x, p3.y - rOuter)
                lineTo(p3.x + rInner, p3.y - rInner)
                lineTo(p3.x + rOuter, p3.y)
                lineTo(p3.x + rInner, p3.y + rInner)
                lineTo(p3.x, p3.y + rOuter)
                lineTo(p3.x - rInner, p3.y + rInner)
                lineTo(p3.x - rOuter, p3.y)
                lineTo(p3.x - rInner, p3.y - rInner)
                close()
            }
            drawPath(path = star, color = primary)
        }
    }
}

// 同上：ProduceStateDoesNotAssignValue 误报，定点豁免
@Suppress("ProduceStateDoesNotAssignValue")
@Composable
private fun HistoryCell(
    entry: HistoryStore.Entry,
    timeFmt: SimpleDateFormat,
    isEn: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val thumb by produceState<Bitmap?>(initialValue = null, entry.imagePath) {
        value = withContext(Dispatchers.IO) {
            try {
                if (File(entry.imagePath).isFile) {
                    ThumbnailCache.load(entry.imagePath)
                } else null
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }
    val cellShape = RoundedCornerShape(4.dp)
    val scrimColor = MaterialTheme.colorScheme.scrim
    // 暗色渐变遮罩之上的前景文字/图标：夜视红保持暖红暗适应色，其余三主题（含纯白浅色模式）使用柔和浅亮白以保证高对比度
    val overlayFg = if (ThemeState.mode == AppThemeMode.NIGHT_RED) {
        MaterialTheme.colorScheme.onSurface
    } else {
        Color(0xFFF5F7FC)
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(cellShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick),
    ) {
        val bmp = thumb
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    AppIcons.Gallery,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    I18n.History.imageInvalid,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        // 底部平滑暗渐变遮罩：在亮色调星空/地景照片及浅色主题下均保证星座名与时间清晰可读
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            scrimColor.copy(alpha = 0.84f),
                        ),
                    ),
                )
                .padding(top = 18.dp, bottom = 5.dp, start = 6.dp, end = 6.dp),
        ) {
            Text(
                buildString {
                    val con = entry.localizedConstellation(isEn)
                    if (con.isNotEmpty()) append(con).append(" · ")
                    append(timeFmt.format(Date(entry.timestamp)))
                },
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = overlayFg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // 右上角半透明圆形删除按钮（替代生硬的 "✕" 文本）
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(26.dp)
                .clip(CircleShape)
                .background(scrimColor.copy(alpha = 0.62f))
                .clickable(onClick = onDelete),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = I18n.delete,
                tint = overlayFg,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
