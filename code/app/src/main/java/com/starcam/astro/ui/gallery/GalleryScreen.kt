package com.starcam.astro.ui.gallery

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.starcam.astro.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 相册条目：MediaStore 原文件路径（DATA 列） */
data class GalleryItem(val id: Long, val path: String)

private fun mediaPermission(): String =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
    else Manifest.permission.READ_EXTERNAL_STORAGE

private fun hasMediaPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, mediaPermission()) ==
        PackageManager.PERMISSION_GRANTED

/**
 * 内置相册（原图模式，§0.31）。
 *
 * 背景：HyperOS/鸿蒙等厂商的"安全访问"在**系统选择器层**对第三方应用做 EXIF
 * 脱敏（位置、焦距等拍摄参数被隐藏），而本 App 的解算先验（EXIF 焦距 → 视场、
 * GPS+时间 → 天区）恰恰依赖这些参数。这里用 AOSP 标准媒体权限
 * （READ_MEDIA_IMAGES，用户一次性显式授权）直接经 MediaStore 读取**原文件**
 * ——脱敏发生在厂商选择器/分享层，不在文件本身，原文件 EXIF 完整。
 * 各厂商系统行为一致（标准合同），无需任何厂商私有 API。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    onBack: () -> Unit,
    onPickImage: (String) -> Unit,
    onPickImages: (List<String>) -> Unit,
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasMediaPermission(context)) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok -> granted = ok }

    // 首次组合即请求授权（用户拒绝后显示说明，可再次触发）
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!granted) permLauncher.launch(mediaPermission())
    }

    var multi by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    val maxSelect = 9

    val items by produceState<List<GalleryItem>?>(initialValue = null, granted) {
        value = if (!granted) emptyList() else withContext(Dispatchers.IO) {
            try {
                queryRecentImages(context, limit = 600)
            } catch (e: SecurityException) {
                emptyList()
            }
        }
    }

    fun confirmSelection() {
        if (selected.size == 1) onPickImage(selected[0])
        else if (selected.isNotEmpty()) onPickImages(selected.toList())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(com.starcam.astro.ui.I18n.Gallery.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("←", fontSize = 22.sp) }
                },
                actions = {
                    if (items != null && items!!.isNotEmpty()) {
                        IconButton(onClick = {
                            multi = !multi
                            selected.clear()
                        }) {
                            Text(
                                if (multi) com.starcam.astro.ui.I18n.Gallery.cancelMulti else com.starcam.astro.ui.I18n.Gallery.multiSelect,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            if (multi && selected.isNotEmpty()) {
                Surface(shadowElevation = 8.dp) {
                    Button(
                        onClick = { confirmSelection() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .padding(bottom = 12.dp),
                        enabled = selected.isNotEmpty(),
                    ) {
                        Text(
                            com.starcam.astro.ui.I18n.Gallery.identifySelected(selected.size, maxSelect),
                            fontSize = 16.sp,
                        )
                    }
                }
            }
        },
    ) { padding ->
        val list = items
        when {
            !granted -> PermissionDenied(
                onRetry = { permLauncher.launch(mediaPermission()) },
                modifier = Modifier.padding(padding),
            )

            list == null -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            list.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(com.starcam.astro.ui.I18n.Gallery.empty, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(list, key = { it.id }) { item ->
                    GalleryCell(
                        item = item,
                        selected = item.path in selected,
                        selectionOrder = selected.indexOf(item.path) + 1,
                        multi = multi,
                        onClick = {
                            if (!multi) {
                                onPickImage(item.path)
                            } else {
                                if (item.path in selected) {
                                    selected.remove(item.path)
                                } else if (selected.size < maxSelect) {
                                    selected.add(item.path)
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun GalleryCell(
    item: GalleryItem,
    selected: Boolean,
    selectionOrder: Int,
    multi: Boolean,
    onClick: () -> Unit,
) {
    val thumb by produceState<Bitmap?>(initialValue = null, item.path) {
        value = withContext(Dispatchers.IO) {
            try {
                ImageUtils.decodeSampledBitmap(item.path, 256)
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
        }
        if (multi) {
            Box(
                modifier = Modifier
                    .padding(6.dp)
                    .size(26.dp)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                        RoundedCornerShape(6.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Text(
                        "$selectionOrder",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 14.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionDenied(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            com.starcam.astro.ui.I18n.Gallery.permissionTitle,
            fontSize = 18.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(12.dp))
        Text(
            com.starcam.astro.ui.I18n.Gallery.permissionDesc,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(20.dp))
        Button(onClick = onRetry) { Text(com.starcam.astro.ui.I18n.grantPermission) }
    }
}

/** 按添加时间倒序列出最近的照片（原文件路径）；[limit] 防止超大相册全量加载 */
private fun queryRecentImages(context: Context, limit: Int): List<GalleryItem> {
    val out = ArrayList<GalleryItem>(minOf(limit, 128))
    val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DATA,
        MediaStore.Images.Media.DATE_ADDED,
    )
    context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        projection,
        "${MediaStore.Images.Media.SIZE} > 0",
        null,
        "${MediaStore.Images.Media.DATE_ADDED} DESC",
    )?.use { c ->
        val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val dataCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
        while (c.moveToNext() && out.size < limit) {
            val path = c.getString(dataCol) ?: continue
            if (path.isBlank()) continue
            out.add(GalleryItem(c.getLong(idCol), path))
        }
    }
    return out
}
