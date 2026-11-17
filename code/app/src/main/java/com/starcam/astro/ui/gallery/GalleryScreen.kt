package com.starcam.astro.ui.gallery

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.starcam.astro.ui.AppIcons
import com.starcam.astro.ui.I18n
import com.starcam.astro.ui.theme.AppThemeMode
import com.starcam.astro.ui.theme.LocaleState
import com.starcam.astro.ui.theme.ThemeState
import com.starcam.astro.util.ThumbnailCache
import kotlinx.coroutines.CancellationException
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
 * 内置相册（原图模式，§0.31 / §0.143 多选与视觉精修）。
 *
 * 背景：HyperOS/鸿蒙等厂商的"安全访问"在**系统选择器层**对第三方应用做 EXIF
 * 脱敏（位置、焦距等拍摄参数被隐藏），而本 App 的解算先验（EXIF 焦距 → 视场、
 * GPS+时间 → 天区）恰恰依赖这些参数。这里用 AOSP 标准媒体权限
 * （READ_MEDIA_IMAGES，用户一次性显式授权）直接经 MediaStore 读取**原文件**
 * ——脱敏发生在厂商选择器/分享层，不在文件本身，原文件 EXIF 完整。
 */
// lint 的 ProduceStateDoesNotAssignValue 对本文件的写法误报：`value = …` 确实在
// producer lambda 顶层（这些界面在真机上一直正常显示，即运行时证据）。定点豁免，
// 保留该规则对新增代码的约束（2026-10-11 lintDebug 进入 CI 门禁）。
@Suppress("ProduceStateDoesNotAssignValue")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    onBack: () -> Unit,
    onPickImage: (String) -> Unit,
    onPickImages: (List<String>) -> Unit,
) {
    val context = LocalContext.current
    val isEn = LocaleState.isEnglish
    var granted by remember { mutableStateOf(hasMediaPermission(context)) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok -> granted = ok }

    // 首次组合即请求授权（用户拒绝后显示说明，可再次触发）
    LaunchedEffect(Unit) {
        if (!granted) permLauncher.launch(mediaPermission())
    }

    var multi by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    val maxSelect = 9

    val items by produceState<List<GalleryItem>?>(initialValue = null, granted) {
        value = if (!granted) emptyList() else withContext(Dispatchers.IO) {
            try {
                queryRecentImages(context, limit = 600)
            } catch (_: SecurityException) {
                emptyList()
            }
        }
    }

    fun confirmSelection() {
        if (selected.size == 1) onPickImage(selected[0])
        else if (selected.isNotEmpty()) onPickImages(selected.toList())
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        I18n.Gallery.title,
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
                    if (!items.isNullOrEmpty()) {
                        Surface(
                            onClick = {
                                multi = !multi
                                selected.clear()
                            },
                            shape = CircleShape,
                            color = if (multi) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.primaryContainer
                            },
                            modifier = Modifier.padding(end = 10.dp),
                        ) {
                            Text(
                                if (multi) I18n.Gallery.cancelMulti else I18n.Gallery.multiSelect,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (multi) {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                },
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            if (multi) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        tonalElevation = 6.dp,
                        shadowElevation = 8.dp,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 18.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    if (selected.isEmpty()) {
                                        if (isEn) "Select photos" else "请选择照片"
                                    } else {
                                        if (isEn) "${selected.size} / $maxSelect selected" else "已选 ${selected.size} / $maxSelect 张"
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    if (selected.size >= maxSelect) {
                                        if (isEn) "Maximum batch size reached" else "已达单次批量上限"
                                    } else {
                                        if (isEn) "Up to $maxSelect photos per batch" else "单次最多支持 $maxSelect 张"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected.size >= maxSelect) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (selected.isNotEmpty()) {
                                TextButton(
                                    onClick = { selected.clear() },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                ) {
                                    Text(
                                        if (isEn) "Clear" else "清空已选",
                                        style = MaterialTheme.typography.labelLarge,
                                        maxLines = 1,
                                    )
                                }
                                Spacer(Modifier.width(4.dp))
                            }
                            Button(
                                onClick = { confirmSelection() },
                                enabled = selected.isNotEmpty(),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                            ) {
                                Text(
                                    if (selected.isEmpty()) {
                                        if (isEn) "Identify" else "开始识别"
                                    } else {
                                        if (isEn) "Identify (${selected.size})" else "开始识别（${selected.size}）"
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                )
                            }
                        }
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
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            list.isEmpty() -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(
                    modifier = Modifier.size(68.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            AppIcons.Gallery,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    I18n.Gallery.empty,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            else -> {
                val limitReached = multi && selected.size >= maxSelect
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(
                        start = 10.dp,
                        end = 10.dp,
                        top = 6.dp,
                        bottom = if (multi) 16.dp else 20.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    // 原图相册说明条（完整保留焦距与 GPS EXIF）
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        OriginalExifInfoBanner(
                            isEn = isEn,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 4.dp),
                        )
                    }

                    items(list, key = { it.id }) { item ->
                        val isSelected = item.path in selected
                        val isDimmed = limitReached && !isSelected
                        GalleryCell(
                            item = item,
                            selected = isSelected,
                            selectionOrder = if (isSelected) selected.indexOf(item.path) + 1 else 0,
                            multi = multi,
                            dimmed = isDimmed,
                            onClick = {
                                if (!multi) {
                                    onPickImage(item.path)
                                } else {
                                    if (isSelected) {
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
}

@Composable
private fun OriginalExifInfoBanner(
    isEn: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                if (isEn) {
                    "Original file access preserves camera focal length & GPS EXIF often stripped by OEM system pickers, accelerating plate solving."
                } else {
                    "原图直读 · 完整保留焦距与 GPS 拍摄参数，避免系统选择器隐私脱敏导致识别变慢。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// 同上：ProduceStateDoesNotAssignValue 误报，定点豁免
@Suppress("ProduceStateDoesNotAssignValue")
@Composable
private fun GalleryCell(
    item: GalleryItem,
    selected: Boolean,
    selectionOrder: Int,
    multi: Boolean,
    dimmed: Boolean,
    onClick: () -> Unit,
) {
    val thumb by produceState<Bitmap?>(initialValue = null, item.path) {
        value = withContext(Dispatchers.IO) {
            try {
                ThumbnailCache.load(item.path)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }
    val cellShape = RoundedCornerShape(6.dp)
    val scrimColor = MaterialTheme.colorScheme.scrim
    val ringFg = if (ThemeState.mode == AppThemeMode.NIGHT_RED) {
        MaterialTheme.colorScheme.onSurface
    } else {
        Color(0xFFF5F7FC)
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(cellShape)
            .then(
                if (selected) {
                    Modifier.border(2.5.dp, MaterialTheme.colorScheme.primary, cellShape)
                } else {
                    Modifier
                },
            )
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(enabled = !dimmed, onClick = onClick)
            .alpha(if (dimmed) 0.38f else 1f),
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
            Icon(
                AppIcons.Gallery,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(22.dp),
            )
        }

        if (selected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
            )
        }

        if (multi) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            scrimColor.copy(alpha = 0.45f)
                        },
                    )
                    .border(
                        width = 1.5.dp,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.35f)
                        } else {
                            ringFg.copy(alpha = 0.85f)
                        },
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Text(
                        "$selectionOrder",
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionDenied(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            modifier = Modifier.size(72.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    AppIcons.Gallery,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            I18n.Gallery.permissionTitle,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            I18n.Gallery.permissionDesc,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(22.dp))
        Button(onClick = onRetry) { Text(I18n.grantPermission) }
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
