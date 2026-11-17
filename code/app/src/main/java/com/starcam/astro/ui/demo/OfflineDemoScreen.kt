package com.starcam.astro.ui.demo

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.Button
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.starcam.astro.ui.AppIcons
import com.starcam.astro.ui.I18n
import com.starcam.astro.ui.theme.AppThemeMode
import com.starcam.astro.ui.theme.LocaleState
import com.starcam.astro.ui.theme.ThemeState
import com.starcam.astro.util.ThumbnailCache
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 离线演示页（§0.39 / §0.143 视觉升级）：
 * 顶部零网络合成星空 Hero 卡片 + 12 张真实手机星空照片网格（走完整本地盲解链路，无需网络/API Key）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineDemoScreen(
    onBack: () -> Unit,
    onPickImage: (String) -> Unit,
    onRandomDemo: () -> Unit,
) {
    val context = LocalContext.current
    val isEn = LocaleState.isEnglish
    val photoAssets = remember {
        listOf(
            "photo4963.jpg", "photo4974.jpg", "photo4984.jpg", "photo4998.jpg",
            "photo5031.jpg", "photo5040.jpg", "photo5049.jpg", "photo5057.jpg",
            "photo5068.jpg", "photo5076.jpg", "photo5087.jpg", "photo5092.jpg",
        )
    }

    // 首次进入：assets 拷到缓存目录（缩略图与识别都读文件路径）
    var photos by remember { mutableStateOf<List<File>>(emptyList()) }
    LaunchedEffect(Unit) {
        photos = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "offline_photos")
            dir.mkdirs()
            photoAssets.map { name ->
                val f = File(dir, name)
                if (!f.exists()) {
                    context.assets.open("offline_photos/$name").use { input ->
                        FileOutputStream(f).use { input.copyTo(it) }
                    }
                }
                f
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        I18n.Demo.title,
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
        // 单一 LazyVerticalGrid 承载全宽 Hero 卡片与 3 列实拍网格（严禁嵌套 verticalScroll，横竖屏均安全）
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SyntheticSkyHeroCard(
                    isEn = isEn,
                    onRandomDemo = onRandomDemo,
                )
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (isEn) "Real Smartphone Night Sky" else "内置真实手机星空实拍",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                if (isEn) "${photoAssets.size} verified" else "${photoAssets.size} 张实测样本",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        I18n.Demo.selectRealPhoto,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(photos, key = { it.name }) { file ->
                OfflinePhotoCell(
                    file = file,
                    onClick = { onPickImage(file.absolutePath) },
                )
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun SyntheticSkyHeroCard(
    isEn: Boolean,
    onRandomDemo: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    val accent = MaterialTheme.colorScheme.onPrimaryContainer
                    Canvas(modifier = Modifier.padding(10.dp)) {
                        val cx = size.width * 0.45f
                        val cy = size.height * 0.52f
                        val rOut = size.minDimension * 0.42f
                        val rIn = size.minDimension * 0.12f
                        val mainStar = Path().apply {
                            moveTo(cx, cy - rOut)
                            lineTo(cx + rIn, cy - rIn)
                            lineTo(cx + rOut, cy)
                            lineTo(cx + rIn, cy + rIn)
                            lineTo(cx, cy + rOut)
                            lineTo(cx - rIn, cy + rIn)
                            lineTo(cx - rOut, cy)
                            lineTo(cx - rIn, cy - rIn)
                            close()
                        }
                        drawPath(mainStar, color = accent)
                        val sx = size.width * 0.80f
                        val sy = size.height * 0.24f
                        val sr = size.minDimension * 0.16f
                        val subStar = Path().apply {
                            moveTo(sx, sy - sr)
                            lineTo(sx + sr * 0.3f, sy - sr * 0.3f)
                            lineTo(sx + sr, sy)
                            lineTo(sx + sr * 0.3f, sy + sr * 0.3f)
                            lineTo(sx, sy + sr)
                            lineTo(sx - sr * 0.3f, sy + sr * 0.3f)
                            lineTo(sx - sr, sy)
                            lineTo(sx - sr * 0.3f, sy - sr * 0.3f)
                            close()
                        }
                        drawPath(subStar, color = accent.copy(alpha = 0.8f))
                        drawCircle(
                            color = accent.copy(alpha = 0.65f),
                            radius = size.minDimension * 0.06f,
                            center = Offset(size.width * 0.82f, size.height * 0.76f),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (isEn) "Instant Synthetic Sky Preview" else "即时合成星空预览",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (isEn) "Offline · Zero Network · Instant Render" else "纯离线 · 零网络依赖 · 秒级出图",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            Text(
                if (isEn) {
                    "Synthesizes a random sky region from the built-in Hipparcos star catalog so you can explore constellation lines, bright star names, and Messier overlays right away."
                } else {
                    "基于内置 Hipparcos 星表随机生成天区模拟照片，无需联网或外出拍摄，即可体验星座连线、亮星专名与梅西耶深空天体叠加。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(14.dp))

            Button(
                onClick = onRandomDemo,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Text(
                    I18n.Demo.randomSky,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// 同上：ProduceStateDoesNotAssignValue 误报，定点豁免（value 确实在 producer 顶层赋值）
@Suppress("ProduceStateDoesNotAssignValue")
@Composable
private fun OfflinePhotoCell(file: File, onClick: () -> Unit) {
    val thumb by produceState<Bitmap?>(initialValue = null, file.path) {
        value = withContext(Dispatchers.IO) {
            try {
                ThumbnailCache.load(file.path, 256)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }
    val cellShape = MaterialTheme.shapes.small
    val scrimColor = MaterialTheme.colorScheme.scrim
    val overlayFg = if (ThemeState.mode == AppThemeMode.NIGHT_RED) {
        MaterialTheme.colorScheme.onSurface
    } else {
        Color(0xFFF5F7FC)
    }
    val photoCode = remember(file.name) {
        "#" + file.name.removePrefix("photo").removeSuffix(".jpg")
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(cellShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), cellShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick),
    ) {
        val bmp = thumb
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = photoCode,
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

        // 底部平滑暗渐变遮罩 + #4963 编号徽标
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            scrimColor.copy(alpha = 0.82f),
                        ),
                    ),
                )
                .padding(top = 18.dp, bottom = 5.dp, start = 6.dp, end = 6.dp),
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(scrimColor.copy(alpha = 0.45f))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            ) {
                Text(
                    photoCode,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = overlayFg,
                    maxLines = 1,
                )
            }
        }
    }
}
