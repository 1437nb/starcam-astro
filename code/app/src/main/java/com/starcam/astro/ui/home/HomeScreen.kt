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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.starcam.astro.ui.AppIcons
import com.starcam.astro.ui.I18n
import com.starcam.astro.ui.theme.AppThemeMode
import com.starcam.astro.ui.theme.StarCamDimens
import com.starcam.astro.ui.theme.ThemeState
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

    // FITS / 相机 RAW（§0.108）：系统照片选择器只认图片（选不到 .fits/.cr2），
    // 因此单独走**系统文档选择器（SAF）**；拷到缓存时保留原扩展名 —— 解码分派按扩展名走。
    val docPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            val stamp = System.currentTimeMillis()
            val files = uris.mapNotNull { uri ->
                ImageUtils.copyUriToCacheKeepingName(context, uri, "picked_$stamp")
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

            Spacer(Modifier.height(StarCamDimens.sm))

            // FITS / 相机 RAW（§0.108）：望远镜与相机的原始素材，走 SAF 文档选择器
            OutlinedButton(
                onClick = { docPicker.launch(arrayOf("*/*")) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(StarCamDimens.buttonHeight),
            ) {
                Text(I18n.Home.chooseFitsRaw, style = MaterialTheme.typography.labelLarge)
            }

            Spacer(Modifier.height(StarCamDimens.sectionGap))

            // ── 今日天象卡片（Material 3 风格）──
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

/**
 * 首页顶部品牌徽章图标（与桌面 Adaptive Icon 视觉同源：「镜头取景 + 盲解三角形与星座连线 + 十字星芒」）。
 * 支持深空蓝（默认）与夜视红（暗适应）双主题自动切换配色。
 */
@Composable
private fun StarCamBrandIcon(modifier: Modifier = Modifier) {
    val isNightRed = ThemeState.mode == AppThemeMode.NIGHT_RED
    val shape = RoundedCornerShape(20.dp)

    val bgColors = if (isNightRed) {
        listOf(Color(0xFF280B0B), Color(0xFF140505), Color(0xFF060202))
    } else {
        listOf(Color(0xFF1A2B63), Color(0xFF0E173B), Color(0xFF060A1C))
    }
    val lensColors = if (isNightRed) {
        listOf(Color(0xFF330E0E), Color(0xFF1A0707), Color(0xFF0A0303))
    } else {
        listOf(Color(0xFF1E3575), Color(0xFF0E1940), Color(0xFF070C22))
    }
    val goldColor = if (isNightRed) Color(0xFFFFB74D) else Color(0xFFE3C567)
    val skyColor = if (isNightRed) Color(0xFFFF5252) else Color(0xFF7FB3E8)
    val mintColor = if (isNightRed) Color(0xFFFF8A80) else Color(0xFF9BD4C0)
    val starCoreColor = if (isNightRed) Color(0xFFFFE0B2) else Color(0xFFFFFDF4)
    val nodeCoreColor = if (isNightRed) Color(0xFFFFCDD2) else Color(0xFFF0F6FF)
    val borderColor = if (isNightRed) Color(0x55FF5252) else Color(0x407FB3E8)

    Canvas(
        modifier = modifier
            .clip(shape)
            .border(1.dp, borderColor, shape)
    ) {
        val s = size.minDimension / 108f
        fun pt(x: Float, y: Float) = Offset(x * s, y * s)

        // 1. 深空径向渐变背景
        drawRect(
            brush = Brush.radialGradient(
                colors = bgColors,
                center = pt(44f, 36f),
                radius = 80f * s,
            )
        )

        // 外围天球赤纬环暗纹
        drawCircle(
            color = skyColor.copy(alpha = 0.10f),
            radius = 40f * s,
            center = pt(54f, 54f),
            style = Stroke(width = 0.8f * s),
        )

        // 2. 相机取景框四角折线
        val bracketStroke = Stroke(width = 2.6f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val topBrackets = Path().apply {
            // 左上
            moveTo(26f * s, 39f * s)
            lineTo(26f * s, 32f * s)
            arcTo(Rect(26f * s, 26f * s, 38f * s, 38f * s), 180f, 90f, false)
            lineTo(39f * s, 26f * s)
            // 右上
            moveTo(69f * s, 26f * s)
            lineTo(76f * s, 26f * s)
            arcTo(Rect(70f * s, 26f * s, 82f * s, 38f * s), 270f, 90f, false)
            lineTo(82f * s, 39f * s)
        }
        drawPath(topBrackets, color = goldColor.copy(alpha = 0.80f), style = bracketStroke)

        val bottomBrackets = Path().apply {
            // 左下
            moveTo(26f * s, 69f * s)
            lineTo(26f * s, 76f * s)
            arcTo(Rect(26f * s, 70f * s, 38f * s, 82f * s), 180f, -90f, false)
            lineTo(39f * s, 82f * s)
            // 右下
            moveTo(69f * s, 82f * s)
            lineTo(76f * s, 82f * s)
            arcTo(Rect(70f * s, 70f * s, 82f * s, 82f * s), 90f, -90f, false)
            lineTo(82f * s, 69f * s)
        }
        drawPath(bottomBrackets, color = skyColor.copy(alpha = 0.80f), style = bracketStroke)

        // 3. 光学镜头圆环与镜片
        val center = pt(54f, 54f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = lensColors,
                center = pt(47f, 45f),
                radius = 30f * s,
            ),
            radius = 24f * s,
            center = center,
        )
        drawCircle(
            color = skyColor.copy(alpha = 0.88f),
            radius = 24f * s,
            center = center,
            style = Stroke(width = 2.4f * s),
        )
        drawCircle(
            color = goldColor.copy(alpha = 0.35f),
            radius = 20.6f * s,
            center = center,
            style = Stroke(width = 1.0f * s),
        )

        // 镜片左上反光弧
        drawArc(
            color = Color.White.copy(alpha = if (isNightRed) 0.14f else 0.24f),
            startAngle = 202f,
            sweepAngle = 48f,
            useCenter = false,
            topLeft = pt(35.5f, 35.5f),
            size = Size(37f * s, 37f * s),
            style = Stroke(width = 1.8f * s, cap = StrokeCap.Round),
        )

        // 4. 盲解特征三角形与星座连线
        val triPath = Path().apply {
            moveTo(45.5f * s, 52.0f * s)
            lineTo(61.0f * s, 44.8f * s)
            lineTo(54.0f * s, 58.0f * s)
            close()
        }
        drawPath(triPath, color = mintColor.copy(alpha = 0.12f))
        drawPath(
            triPath,
            color = mintColor.copy(alpha = 0.32f),
            style = Stroke(width = 0.9f * s, join = StrokeJoin.Round),
        )

        val constellationPath = Path().apply {
            moveTo(38.5f * s, 61.0f * s)
            lineTo(45.5f * s, 52.0f * s)
            lineTo(54.0f * s, 58.0f * s)
            lineTo(61.0f * s, 44.8f * s)
            lineTo(69.5f * s, 49.0f * s)
        }
        drawPath(
            constellationPath,
            color = mintColor,
            style = Stroke(width = 1.85f * s, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawLine(
            color = skyColor,
            start = pt(45.5f, 52.0f),
            end = pt(42.2f, 42.2f),
            strokeWidth = 1.5f * s,
            cap = StrokeCap.Round,
        )

        // 5. 次级星座节点恒星
        val nodes = listOf(
            pt(38.5f, 61.0f),
            pt(45.5f, 52.0f),
            pt(54.0f, 58.0f),
            pt(69.5f, 49.0f),
            pt(42.2f, 42.2f),
        )
        for (node in nodes) {
            drawCircle(color = skyColor.copy(alpha = 0.28f), radius = 3.2f * s, center = node)
            drawCircle(color = nodeCoreColor, radius = 1.75f * s, center = node)
        }

        // 6. 主星十字星芒（61.0, 44.8）
        val mainStar = pt(61.0f, 44.8f)
        drawCircle(color = goldColor.copy(alpha = 0.18f), radius = 8.0f * s, center = mainStar)
        drawCircle(color = goldColor.copy(alpha = 0.35f), radius = 4.8f * s, center = mainStar)

        val starSpikes = Path().apply {
            moveTo(61.0f * s, 34.5f * s)
            lineTo(62.7f * s, 43.1f * s)
            lineTo(71.3f * s, 44.8f * s)
            lineTo(62.7f * s, 46.5f * s)
            lineTo(61.0f * s, 55.1f * s)
            lineTo(59.3f * s, 46.5f * s)
            lineTo(50.7f * s, 44.8f * s)
            lineTo(59.3f * s, 43.1f * s)
            close()
        }
        drawPath(starSpikes, color = goldColor)
        drawCircle(color = starCoreColor, radius = 2.2f * s, center = mainStar)
    }
}
