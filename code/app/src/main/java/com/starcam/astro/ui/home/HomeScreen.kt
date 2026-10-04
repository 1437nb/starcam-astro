package com.starcam.astro.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.starcam.astro.ui.AppIcons
import com.starcam.astro.ui.I18n
import com.starcam.astro.ui.theme.StarCamDimens
import com.starcam.astro.ui.theme.onSuccessContainer
import com.starcam.astro.ui.theme.successContainer
import com.starcam.astro.util.ImageUtils

/**
 * 主页（§0.85 Material 3 重构）：拍照认星 / 相册选图 / 离线演示 / 设置。
 *
 * Google 风格的要点：
 *  - 主操作**只有这一个** filled 药丸按钮（M3 默认 shape，不再 16dp 方圆角）；
 *  - 次级动作收进一张 surfaceContainerLow 卡片，按"列表行 + 尾部箭头"排布；
 *  - 字号一律走 [MaterialTheme.typography]，间距走 [StarCamDimens]。
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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = StarCamDimens.screenPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(40.dp))

            // ── 标题区：大标题克制（M3 用 400/500 字重，不靠加粗堆气势）──
            Text(
                I18n.appName,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(StarCamDimens.xs))
            Text(
                I18n.appDesc,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(StarCamDimens.xxl))

            // ── 主操作：拍照认星（M3 默认药丸形）──
            Button(
                onClick = onCapture,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(StarCamDimens.primaryButtonHeight),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Icon(
                    AppIcons.Camera,
                    contentDescription = null,
                    modifier = Modifier.size(StarCamDimens.iconSize),
                )
                Spacer(Modifier.size(StarCamDimens.sm))
                Text(I18n.Home.takePhoto, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(StarCamDimens.md))

            // ── 相册选择：原图模式优先（保留拍摄参数）──
            FilledTonalButton(
                onClick = onOpenGallery,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(StarCamDimens.buttonHeight),
            ) {
                Icon(AppIcons.Gallery, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(StarCamDimens.sm))
                Text(I18n.Home.chooseGalleryOriginal, style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(StarCamDimens.sm))

            // 系统选择器为兼容模式（HyperOS 等安全访问会对第三方脱敏 EXIF）
            OutlinedButton(
                onClick = {
                    multiPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(StarCamDimens.buttonHeight),
            ) {
                Text(I18n.Home.chooseGalleryCompat, style = MaterialTheme.typography.labelLarge)
            }

            Spacer(Modifier.height(StarCamDimens.sectionGap))

            // ── 次级入口：分组列表卡片（Google 设置列表风格）──
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column {
                    HomeActionRow(
                        icon = Icons.Filled.AddCircle,
                        label = I18n.Home.offlineDemo,
                        onClick = onDemo,
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    HomeActionRow(
                        icon = Icons.Filled.List,
                        label = I18n.Home.history,
                        onClick = onOpenHistory,
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    HomeActionRow(
                        icon = Icons.Filled.Settings,
                        label = I18n.Home.settings,
                        onClick = onOpenSettings,
                    )
                }
            }

            Spacer(Modifier.height(StarCamDimens.sectionGap))

            // ── API 状态：紧凑状态行（不再是一整张卡）──
            Surface(
                shape = MaterialTheme.shapes.small,
                color = if (hasApiKey) MaterialTheme.colorScheme.successContainer
                else MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = StarCamDimens.md, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (hasApiKey) MaterialTheme.colorScheme.onSuccessContainer
                        else MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.size(StarCamDimens.sm))
                    Text(
                        if (hasApiKey) I18n.Home.apiKeyConfigured else I18n.Home.apiKeyNotConfigured,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (hasApiKey) MaterialTheme.colorScheme.onSuccessContainer
                        else MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            Spacer(Modifier.height(StarCamDimens.xl))

            // ── 使用说明 ──
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(Modifier.padding(StarCamDimens.lg)) {
                    Text(
                        I18n.Home.howToUseTitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(StarCamDimens.sm))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(StarCamDimens.sm))
                    UsageLine(I18n.Home.howToUse1)
                    UsageLine(I18n.Home.howToUse2)
                    UsageLine(I18n.Home.howToUse3)
                    UsageLine(I18n.Home.howToUse4)
                    Text(
                        I18n.Home.howToUseTip,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(StarCamDimens.xxl))
        }
    }
}

/** 次级入口列表行：前导图标 + 标签 + 尾部箭头（56dp 触达高度） */
@Composable
private fun HomeActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = StarCamDimens.lg, vertical = StarCamDimens.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(StarCamDimens.iconSize),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(StarCamDimens.lg))
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(StarCamDimens.iconSize),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 使用说明条目：前导小圆点，视觉上把 4 行说明组织成一组 */
@Composable
private fun UsageLine(text: String) {
    Row(
        modifier = Modifier.padding(bottom = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .padding(top = 7.dp)
                .padding(end = StarCamDimens.sm)
                .size(4.dp)
                .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
