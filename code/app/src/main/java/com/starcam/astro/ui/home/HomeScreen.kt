package com.starcam.astro.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.starcam.astro.ui.AppIcons
import com.starcam.astro.util.ImageUtils

/**
 * 主页：拍照认星 / 相册选图 / 离线演示 / 设置
 */
@Composable
fun HomeScreen(
    hasApiKey: Boolean,
    onCapture: () -> Unit,
    onPickImage: (String) -> Unit,
    onPickImages: (List<String>) -> Unit,
    onOpenGallery: () -> Unit,
    onDemo: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    val context = LocalContext.current
    // 多选照片 → 批量识别导出（最多 9 张）
    val multiPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 9)
    ) { uris ->
        if (uris.isNotEmpty()) {
            val files = uris.mapNotNull { uri ->
                ImageUtils.copyUriToCache(
                    context, uri,
                    "picked_${System.currentTimeMillis()}_${uris.indexOf(uri)}.jpg",
                )
            }
            if (files.size == 1) {
                onPickImage(files[0].absolutePath)
            } else if (files.isNotEmpty()) {
                onPickImages(files.map { it.absolutePath })
            }
        }
    }


    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(40.dp))

            // 标题区
            Icon(
                imageVector = AppIcons.Camera,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            Text(com.starcam.astro.ui.I18n.appName, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(
                com.starcam.astro.ui.I18n.appDesc,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(36.dp))

            // 主按钮：拍照认星
            Button(
                onClick = onCapture,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text(com.starcam.astro.ui.I18n.Home.takePhoto, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))

            // 相册选择：原图模式（内置相册，保留拍摄参数）优先；
            // 系统选择器为兼容模式（HyperOS 等安全访问会对第三方脱敏 EXIF）
            Button(
                onClick = onOpenGallery,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ),
            ) {
                Icon(AppIcons.Gallery, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondary)
                Spacer(Modifier.size(8.dp))
                Text(com.starcam.astro.ui.I18n.Home.chooseGalleryOriginal, fontSize = 16.sp)
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    multiPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(com.starcam.astro.ui.I18n.Home.chooseGalleryCompat, fontSize = 15.sp)
            }
            Spacer(Modifier.height(12.dp))

            // 离线演示
            OutlinedButton(
                onClick = onDemo,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(com.starcam.astro.ui.I18n.Home.offlineDemo, fontSize = 16.sp)
            }
            Spacer(Modifier.height(12.dp))

            // 识别历史入口
            OutlinedButton(
                onClick = onOpenHistory,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(com.starcam.astro.ui.I18n.Home.history, fontSize = 16.sp)
            }
            Spacer(Modifier.height(12.dp))

            // 设置入口
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(com.starcam.astro.ui.I18n.Home.settings, fontSize = 16.sp)
            }

            Spacer(Modifier.height(24.dp))

            // 状态提示
            val hintColor = if (hasApiKey) Color(0xFF8FD694) else MaterialTheme.colorScheme.error
            val hintText = if (hasApiKey) com.starcam.astro.ui.I18n.Home.apiKeyConfigured else com.starcam.astro.ui.I18n.Home.apiKeyNotConfigured
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            ) {
                Text(
                    hintText,
                    modifier = Modifier.padding(16.dp),
                    color = hintColor,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(16.dp))

            // 使用说明
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(com.starcam.astro.ui.I18n.Home.howToUseTitle, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                    Spacer(Modifier.height(8.dp))
                    Text(com.starcam.astro.ui.I18n.Home.howToUse1, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    Text(com.starcam.astro.ui.I18n.Home.howToUse2, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    Text(com.starcam.astro.ui.I18n.Home.howToUse3, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    Text(com.starcam.astro.ui.I18n.Home.howToUse4, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    Text(com.starcam.astro.ui.I18n.Home.howToUseTip, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
