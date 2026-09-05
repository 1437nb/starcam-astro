package com.starcam.astro.ui.batch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.starcam.astro.astro.OverlayRenderer
import com.starcam.astro.data.HistoryStore
import com.starcam.astro.data.SettingsRepository
import com.starcam.astro.astro.StarSolver
import com.starcam.astro.util.ImageUtils
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 批量识别页：多选照片 → 逐张识别 → 叠加渲染 → 自动保存到相册
 * （Pictures/StarCam/），完成后汇总。求解引擎非线程安全，严格串行处理。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchScreen(
    paths: List<String>,
    settings: SettingsRepository,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    // status: 0=排队 1=识别中 2=成功 3=失败
    var states by remember { mutableStateOf(paths.map { 0 to "" }) }
    var doneCount by remember { mutableStateOf(0) }
    var finished by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()) }

    LaunchedEffect(Unit) {
        var saved = 0
        for ((i, path) in paths.withIndex()) {
            states = states.toMutableList().also { it[i] = 1 to "" }
            val note: String = try {
                withContext(Dispatchers.IO) {
                    val bmp = ImageUtils.decodeSampledBitmap(path, 2200)
                        ?: return@withContext "无法读取照片文件"
                    val result = StarSolver.solve(context, bmp, path, settings) { }
                        ?: return@withContext "未能识别（亮星不足或视场不支持）"
                    val solve = result.solve
                    val out = OverlayRenderer.render(bmp, solve)
                        ?: return@withContext "渲染失败（结果缺坐标系）"
                    val name = "StarCam_${fmt.format(Date())}_$i.jpg"
                    val loc = ImageUtils.saveBitmapToGallery(context, out, name)
                    try {
                        HistoryStore.save(
                            context,
                            HistoryStore.Entry(
                                timestamp = System.currentTimeMillis(),
                                imagePath = path,
                                raDeg = solve.raDeg,
                                decDeg = solve.decDeg,
                                fovDeg = solve.fieldWidthDeg,
                                engine = "批量导出",
                                constellation = HistoryStore.nearestConstellationZh(
                                    solve.raDeg, solve.decDeg,
                                ),
                            ),
                        )
                    } catch (_: Exception) {
                    }
                    saved++
                    loc ?: "已识别但保存相册失败"
                }
            } catch (e: Exception) {
                "处理异常：${e.message ?: "未知错误"}"
            }
            val ok = !note.startsWith("未能") && !note.startsWith("无法") &&
                !note.startsWith("渲染") && !note.startsWith("处理")
            states = states.toMutableList().also {
                it[i] = (if (ok) 2 else 3) to note
            }
            if (ok) doneCount = saved
        }
        doneCount = saved
        finished = true
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
                com.starcam.astro.ui.I18n.Batch.summary(paths.size, doneCount, finished),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
            ) {
                itemsIndexed(states) { i, (status, note) ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
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
                            Column(Modifier.weight(1f)) {
                                Text(
                                    File(paths[i]).name,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                )
                                if (note.isNotEmpty()) {
                                    Text(
                                        note,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Text(
                                when (status) {
                                    0 -> com.starcam.astro.ui.I18n.Batch.queued
                                    1 -> com.starcam.astro.ui.I18n.Batch.processing
                                    2 -> com.starcam.astro.ui.I18n.Batch.exported
                                    else -> "✗"
                                },
                                color = when (status) {
                                    2 -> MaterialTheme.colorScheme.primary
                                    3 -> MaterialTheme.colorScheme.error
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
                            com.starcam.astro.ui.I18n.Batch.exportNotice,
                            fontSize = 13.sp,
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
