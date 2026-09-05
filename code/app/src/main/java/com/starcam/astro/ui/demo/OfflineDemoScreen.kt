package com.starcam.astro.ui.demo

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.starcam.astro.util.ImageUtils
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 离线演示页（§0.39）：内置 8 张真实手机星空照片（回归台验证可离线识别），
 * 点选即走完整识别链路（无需网络/API Key）；顶部保留随机合成星空演示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineDemoScreen(
    onBack: () -> Unit,
    onPickImage: (String) -> Unit,
    onRandomDemo: () -> Unit,
) {
    val context = LocalContext.current
    val photoAssets = listOf(
        "photo4963.jpg", "photo4974.jpg", "photo4984.jpg", "photo4998.jpg",
        "photo5031.jpg", "photo5040.jpg", "photo5049.jpg", "photo5057.jpg",
        "photo5068.jpg", "photo5076.jpg", "photo5087.jpg", "photo5092.jpg",
    )

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
        topBar = {
            TopAppBar(
                title = { Text(com.starcam.astro.ui.I18n.Demo.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = com.starcam.astro.ui.I18n.back)
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
                .padding(padding)
                .padding(16.dp),
        ) {
            Button(
                onClick = onRandomDemo,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(com.starcam.astro.ui.I18n.Demo.randomSky, fontSize = 16.sp)
            }
            Spacer(Modifier.height(16.dp))
            Text(
                com.starcam.astro.ui.I18n.Demo.selectRealPhoto,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            // 网格占剩余高度（有界约束）；不可嵌套在 verticalScroll 内
            // （LazyVerticalGrid 在无限高度下会抛异常崩溃——实测闪退根因）
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                items(photos, key = { it.name }) { file ->
                    OfflinePhotoCell(file, onClick = { onPickImage(file.absolutePath) })
                }
            }
        }
    }
}

@Composable
private fun OfflinePhotoCell(file: File, onClick: () -> Unit) {
    val thumb by produceState<Bitmap?>(initialValue = null, file.path) {
        value = withContext(Dispatchers.IO) {
            try {
                ImageUtils.decodeSampledBitmap(file.path, 256)
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
        // 照片名小标（如 5040）
        Text(
            file.name.removePrefix("photo").removeSuffix(".jpg"),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(4.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                .padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}
