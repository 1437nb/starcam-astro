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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.starcam.astro.ui.AppIcons
import com.starcam.astro.ui.I18n
import com.starcam.astro.ui.theme.StarCamDimens
import com.starcam.astro.ui.theme.onSuccessContainer
import com.starcam.astro.ui.theme.successContainer

/**
 * 主页（§0.85 Material 3 重构 + 品牌动效升级）：拍照认星 / 相册选图 / 离线演示 / 设置。
 *
 * Google 风格的要点：
 *  - 主操作**只有这一个** filled 药丸按钮（M3 默认 shape，不再 16dp 方圆角）；
 *  - 次级动作收进一张 surfaceContainerLow 卡片，按"列表行 + 尾部箭头"排布；
 *  - 字号一律走 [MaterialTheme.typography]，间距走 [StarCamDimens]。
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    val importer: PhotoImportViewModel = viewModel()
    val importState = importer.state
    var chooseImport by remember { mutableStateOf(false) }
    LaunchedEffect(importState.finished) {
        if (importState.finished) {
            val paths = importState.paths
            if (importState.overLimit) {
                android.widget.Toast.makeText(context, I18n.Home.importLimit, android.widget.Toast.LENGTH_LONG).show()
            } else if (importState.failed > 0) {
                android.widget.Toast.makeText(context, I18n.Home.importFailures(importState.failed), android.widget.Toast.LENGTH_LONG).show()
            }
            importer.consumeResult()
            if (paths.size == 1) onPickImage(paths.first())
            else if (paths.isNotEmpty()) onPickImages(paths)
        }
    }
    // 相册兼容入口也保留文件扩展名，避免把相册中的 DNG 误存为 JPEG。
    val multiPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 9)
    ) { uris ->
        importer.importPhotos(uris)
    }

    // 统一使用 SAF 选择普通图片 / RAW / FITS；*/* 避免提供方将天文文件标成
    // application/octet-stream 时被过滤。保留文件名，由现有解码管线分派格式。
    val docPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        importer.importPhotos(uris)
    }

    if (chooseImport) {
        ModalBottomSheet(onDismissRequest = { chooseImport = false }) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(I18n.Home.importTitle, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                Text(I18n.Home.importHint, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                HomeActionRow(AppIcons.Gallery, I18n.Home.chooseGalleryOriginal) {
                    chooseImport = false
                    onOpenGallery()
                }
                HomeActionRow(AppIcons.Gallery, I18n.Home.chooseGalleryCompat) {
                    chooseImport = false
                    multiPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    if (importState.running) {
        AlertDialog(
            onDismissRequest = importer::cancel,
            title = { Text(I18n.Home.importing) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LinearProgressIndicator(
                        progress = { importState.completed.toFloat() / importState.total.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(I18n.Home.importProgress(importState.completed, importState.total))
                }
            },
            confirmButton = { TextButton(onClick = importer::cancel) { Text(I18n.cancel) } },
        )
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
            Spacer(Modifier.height(28.dp))

            // ── 品牌图标与标题区（M3 用 400/500 字重，图标随深空蓝/夜视红主题自适应）──
            StarCamBrandIcon(modifier = Modifier.size(72.dp))
            Spacer(Modifier.height(StarCamDimens.md))

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

            // ── 主操作：拍照认星（M3 默认药丸形 + 柔和阴影触控）──
            Button(
                onClick = onCapture,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(StarCamDimens.primaryButtonHeight),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = 2.dp,
                    pressedElevation = 6.dp,
                ),
            ) {
                Icon(
                    AppIcons.Camera,
                    contentDescription = null,
                    modifier = Modifier.size(StarCamDimens.iconSize),
                )
                Spacer(Modifier.size(StarCamDimens.sm))
                Text(I18n.Home.takePhoto, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(StarCamDimens.md))

            // ── 统一文件导入：照片 / RAW / FITS 可以混合选择 ──
            FilledTonalButton(
                onClick = { docPicker.launch(arrayOf("*/*")) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(StarCamDimens.buttonHeight),
            ) {
                Icon(AppIcons.Gallery, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(StarCamDimens.sm))
                Text(I18n.Home.importPhotos, style = MaterialTheme.typography.labelLarge)
            }
            Text(
                I18n.Home.supportedFormats,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            TextButton(onClick = { chooseImport = true }) { Text(I18n.Home.moreImportMethods) }

            Spacer(Modifier.height(StarCamDimens.sectionGap))

            // ── 今日天象卡片（Material 3 风格 + 弹性微动效）──
            TonightSkyCard()

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
                else MaterialTheme.colorScheme.surfaceContainerHigh,
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
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(StarCamDimens.sm))
                    Text(
                        if (hasApiKey) I18n.Home.apiKeyConfigured else I18n.Home.apiKeyNotConfigured,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (hasApiKey) MaterialTheme.colorScheme.onSuccessContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
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

