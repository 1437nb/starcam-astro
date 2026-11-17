package com.starcam.astro.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.starcam.astro.data.SettingsRepository
import com.starcam.astro.ui.AppIcons
import com.starcam.astro.ui.I18n
import com.starcam.astro.ui.theme.AppThemeMode
import com.starcam.astro.ui.theme.LocaleState
import com.starcam.astro.ui.theme.StarCamDimens
import com.starcam.astro.ui.theme.ThemeState
import com.starcam.astro.ui.theme.onSuccessContainer
import com.starcam.astro.ui.theme.success
import com.starcam.astro.ui.theme.successContainer

/** 记忆最近一次非夜视红主题，以便户外一键开关夜视红时恢复用户原日常主题 */
private var lastNonNightRedTheme: AppThemeMode = AppThemeMode.DEEP_SKY

/**
 * 主页（§0.85 / §0.143 Material 3 控制台升级）：
 * 保持单页中枢（无底部导航栏），集成户外一键夜视红切换、引擎模式状态胶囊、
 * 可点击在线状态条、卡片化导入方式选择器与默认折叠的使用指南。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    settings: SettingsRepository = SettingsRepository(LocalContext.current),
) {
    val context = LocalContext.current
    val isEn = LocaleState.isEnglish
    val currentTheme = ThemeState.mode
    val isNightRed = currentTheme == AppThemeMode.NIGHT_RED
    val currentEngineMode = settings.engineMode

    LaunchedEffect(currentTheme) {
        if (currentTheme != AppThemeMode.NIGHT_RED) {
            lastNonNightRedTheme = currentTheme
        }
    }

    val importer: PhotoImportViewModel = viewModel()
    val importState = importer.state
    var chooseImport by remember { mutableStateOf(false) }
    var howToExpanded by rememberSaveable { mutableStateOf(false) }
    val howToChevronRotation by animateFloatAsState(
        targetValue = if (howToExpanded) 180f else 0f,
        animationSpec = tween(260, easing = FastOutSlowInEasing),
        label = "howToChevron",
    )

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
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 9),
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
        ModalBottomSheet(
            onDismissRequest = { chooseImport = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = StarCamDimens.screenPadding)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = I18n.Home.importTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = I18n.Home.importHint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))

                ImportMethodOptionCard(
                    icon = AppIcons.Gallery,
                    title = I18n.Home.chooseGalleryOriginal,
                    subtitle = if (isEn) {
                        "Reads raw gallery files directly to preserve full focal-length, timestamp & GPS EXIF priors for faster solving."
                    } else {
                        "直接读取相册原图，完整保留焦距、拍摄时间与 GPS 坐标等 EXIF 先验参数，大幅提升离线盲解速度。"
                    },
                    badge = if (isEn) "Recommended" else "推荐",
                    highlighted = true,
                    onClick = {
                        chooseImport = false
                        onOpenGallery()
                    },
                )

                ImportMethodOptionCard(
                    icon = AppIcons.Gallery,
                    title = I18n.Home.chooseGalleryCompat,
                    subtitle = if (isEn) {
                        "Uses Android System Photo Picker with zero storage permission; some OEMs may strip GPS or focal EXIF."
                    } else {
                        "调用系统照片选择器，无需授予存储权限；部分厂商隐私保护可能隐藏照片 GPS 位置参数。"
                    },
                    badge = null,
                    highlighted = false,
                    onClick = {
                        chooseImport = false
                        multiPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                )
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
            Spacer(Modifier.height(24.dp))

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
            Spacer(Modifier.height(StarCamDimens.md))

            // ── 快捷观星状态与开关胶囊条（户外一键夜视红 + 当前引擎模式）──
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 1. 一键夜视红（暗适应）开关胶囊
                Surface(
                    onClick = {
                        val nextMode = if (isNightRed) {
                            lastNonNightRedTheme.takeIf { it != AppThemeMode.NIGHT_RED } ?: AppThemeMode.DEEP_SKY
                        } else {
                            lastNonNightRedTheme = currentTheme
                            AppThemeMode.NIGHT_RED
                        }
                        ThemeState.mode = nextMode
                        settings.appTheme = nextMode
                    },
                    shape = CircleShape,
                    color = if (isNightRed) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (isNightRed) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = AppIcons.Night,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (isNightRed) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (isNightRed) {
                                if (isEn) "Night Red · ON" else "夜视红 · 已开"
                            } else {
                                if (isEn) "Night Red" else "夜视红"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isNightRed) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (isNightRed) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }

                // 2. 当前识别引擎模式状态胶囊（轻触直达设置）
                Surface(
                    onClick = onOpenSettings,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = AppIcons.Engine,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = currentEngineMode.label(isEn),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.width(2.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(StarCamDimens.xl))

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

            Spacer(Modifier.height(StarCamDimens.lg))

            // ── 今日天象卡片（Material 3 风格 + 真实月相圆盘）──
            TonightSkyCard()

            Spacer(Modifier.height(StarCamDimens.lg))

            // ── 次级入口：分组列表卡片（Google 设置列表风格）──
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
                        icon = Icons.AutoMirrored.Filled.List,
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

            Spacer(Modifier.height(StarCamDimens.md))

            // ── API 状态：可点击状态横幅（点击直达设置页在线服务配置）──
            Surface(
                onClick = onOpenSettings,
                shape = MaterialTheme.shapes.large,
                color = if (hasApiKey) {
                    MaterialTheme.colorScheme.successContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
                border = BorderStroke(
                    width = 1.dp,
                    color = if (hasApiKey) {
                        MaterialTheme.colorScheme.success.copy(alpha = 0.45f)
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = StarCamDimens.lg, vertical = StarCamDimens.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (hasApiKey) Icons.Filled.CheckCircle else AppIcons.Cloud,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = if (hasApiKey) {
                            MaterialTheme.colorScheme.onSuccessContainer
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                    Spacer(Modifier.size(StarCamDimens.md))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (hasApiKey) I18n.Home.apiKeyConfigured else I18n.Home.apiKeyNotConfigured,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium,
                            color = if (hasApiKey) {
                                MaterialTheme.colorScheme.onSuccessContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (hasApiKey) {
                                if (isEn) "Online fallback ready · Tap to manage API Key" else "在线兜底已就绪 · 点击管理 API Key"
                            } else {
                                if (isEn) "API Key not configured · Tap to set up in Settings" else "未配置 API Key · 点击前往设置填写"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (hasApiKey) {
                                MaterialTheme.colorScheme.onSuccessContainer.copy(alpha = 0.82f)
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                    Spacer(Modifier.size(StarCamDimens.sm))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = if (hasApiKey) {
                            MaterialTheme.colorScheme.onSuccessContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            Spacer(Modifier.height(StarCamDimens.md))

            // ── 使用说明：默认折叠紧凑卡片，一键展开 4 步指引 ──
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { howToExpanded = !howToExpanded }
                            .padding(horizontal = StarCamDimens.lg, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(StarCamDimens.md))
                        Text(
                            text = I18n.Home.howToUseTitle,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = if (howToExpanded) {
                                if (isEn) "Hide" else "收起"
                            } else {
                                if (isEn) "4-step guide" else "4 步快速上手"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier
                                .size(20.dp)
                                .rotate(howToChevronRotation),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    AnimatedVisibility(
                        visible = howToExpanded,
                        enter = expandVertically(tween(240, easing = FastOutSlowInEasing)) + fadeIn(tween(200)),
                        exit = shrinkVertically(tween(200, easing = FastOutLinearInEasing)) + fadeOut(tween(160)),
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(horizontal = StarCamDimens.lg)
                                .padding(bottom = StarCamDimens.lg),
                        ) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Spacer(Modifier.height(StarCamDimens.md))
                            UsageLine(I18n.Home.howToUse1)
                            UsageLine(I18n.Home.howToUse2)
                            UsageLine(I18n.Home.howToUse3)
                            UsageLine(I18n.Home.howToUse4)
                            Spacer(Modifier.height(StarCamDimens.xs))
                            Text(
                                I18n.Home.howToUseTip,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(StarCamDimens.xxl))
        }
    }
}

/** 底部导入方式弹层选项卡片：包含前导圆章图标、标题、推荐徽标、EXIF 差异说明与尾部箭头 */
@Composable
private fun ImportMethodOptionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    badge: String?,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (highlighted) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        border = BorderStroke(
            width = 1.dp,
            color = if (highlighted) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = if (highlighted) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                modifier = Modifier.size(42.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = if (highlighted) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (badge != null) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Text(
                                text = badge,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
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
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
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
        modifier = Modifier.padding(bottom = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .padding(top = 7.dp)
                .padding(end = StarCamDimens.sm)
                .size(5.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

