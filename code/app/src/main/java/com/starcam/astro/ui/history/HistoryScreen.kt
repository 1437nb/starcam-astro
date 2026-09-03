package com.starcam.astro.ui.history

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.starcam.astro.data.HistoryStore
import com.starcam.astro.data.StatsStore
import com.starcam.astro.util.ImageUtils
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 识别历史页（§0.40 网格化）：按日期分组缩略图网格、单条删除（确认）、
 * CSV 导出、顶部识别统计。点击格子重新打开对应照片。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onOpenImage: (String) -> Unit,
) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(HistoryStore.load(context)) }
    var deleting by remember { mutableStateOf<HistoryStore.Entry?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    val stats = remember(entries) { StatsStore.summary(context) }
    val dayFmt = remember { SimpleDateFormat("yyyy年M月d日 EEEE", Locale.getDefault()) }
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    // 按日期分组（entries 已时间倒序，LinkedHashMap 保序）
    val groups = remember(entries) {
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
            title = { Text("删除这条识别记录？") },
            text = {
                Text(
                    buildString {
                        if (entry.constellation.isNotEmpty()) append(entry.constellation).append(" · ")
                        append(timeFmt.format(Date(entry.timestamp)))
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    HistoryStore.remove(context, entry.timestamp)
                    entries = HistoryStore.load(context)
                    deleting = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("识别历史") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (entries.isNotEmpty()) {
                        OutlinedButton(
                            onClick = {
                                val desc = HistoryStore.exportCsv(context, entries)
                                toast = desc?.let { "已导出：$it" } ?: "导出失败"
                            },
                            modifier = Modifier.padding(end = 4.dp),
                        ) { Text("导出") }
                        OutlinedButton(
                            onClick = {
                                HistoryStore.clear(context)
                                entries = emptyList()
                            },
                            modifier = Modifier.padding(end = 8.dp),
                        ) { Text("清空") }
                    }
                },
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("还没有识别记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text(
                    "成功识别的照片会自动记录在这里",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // 顶部统计条（§0.40 识别打点）
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        ),
                    ) {
                        Text(
                            if (stats.count > 0) {
                                "共识别 ${stats.count} 次 · 成功率 ${(stats.successRate * 100).toInt()}% · " +
                                    "平均 ${stats.avgMs / 1000} 秒 · 最近 ${stats.lastMs / 1000} 秒"
                            } else {
                                "暂无识别统计"
                            },
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
                for ((day, dayEntries) in groups) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            day,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                    items(dayEntries, key = { it.timestamp }) { e ->
                        HistoryCell(
                            entry = e,
                            timeFmt = timeFmt,
                            onClick = { onOpenImage(e.imagePath) },
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
private fun HistoryCell(
    entry: HistoryStore.Entry,
    timeFmt: SimpleDateFormat,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val thumb by produceState<Bitmap?>(initialValue = null, entry.imagePath) {
        value = withContext(Dispatchers.IO) {
            try {
                if (File(entry.imagePath).isFile) {
                    ImageUtils.decodeSampledBitmap(entry.imagePath, 256)
                } else null
            } catch (e: Exception) {
                null
            }
        }
    }
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
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
            Text(
                "图片已失效",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        // 底部信息：星座 + 时间
        Text(
            buildString {
                if (entry.constellation.isNotEmpty()) append(entry.constellation)
                append(" ").append(timeFmt.format(Date(entry.timestamp)))
            },
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.65f))
                .padding(horizontal = 4.dp, vertical = 2.dp),
        )
        // 右上角删除（§0.40）
        Text(
            "✕",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(3.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
                .clickable(onClick = onDelete)
                .padding(horizontal = 5.dp, vertical = 2.dp),
        )
    }
}
