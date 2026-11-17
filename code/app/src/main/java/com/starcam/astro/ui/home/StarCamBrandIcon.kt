package com.starcam.astro.ui.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp
import com.starcam.astro.ui.theme.AppThemeMode
import com.starcam.astro.ui.theme.ThemeState

/**
 * 首页顶部品牌徽章图标（与桌面 Adaptive Icon 视觉同源：「镜头取景 + 盲解三角形与星座连线 + 十字星芒」）。
 * 支持深空蓝（默认）与夜视红（暗适应）双主题自动切换配色，带有微光宇宙呼吸动效。
 */
@Composable
internal fun StarCamBrandIcon(modifier: Modifier = Modifier) {
    val isNightRed = ThemeState.mode == AppThemeMode.NIGHT_RED
    val shape = RoundedCornerShape(20.dp)

    // 宇宙微光呼吸动效（平滑纯 GPU 渲染，零每帧分配）
    val infiniteTransition = rememberInfiniteTransition(label = "brandPulse")
    val starGlowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.72f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "starGlowAlpha",
    )
    val starScale by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "starScale",
    )
    val ringRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(90000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ringRotation",
    )

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
            .border(1.dp, borderColor, shape),
    ) {
        val s = size.minDimension / 108f
        fun pt(x: Float, y: Float) = Offset(x * s, y * s)

        // 1. 深空径向渐变背景
        drawRect(
            brush = Brush.radialGradient(
                colors = bgColors,
                center = pt(44f, 36f),
                radius = 80f * s,
            ),
        )

        // 外围天球赤纬环暗纹（缓慢旋转微动）
        rotate(ringRotation, pivot = pt(54f, 54f)) {
            drawCircle(
                color = skyColor.copy(alpha = 0.12f),
                radius = 40f * s,
                center = pt(54f, 54f),
                style = Stroke(width = 0.8f * s),
            )
        }

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

        // 6. 主星十字星芒（61.0, 44.8）（伴随微光呼吸）
        val mainStar = pt(61.0f, 44.8f)
        drawCircle(color = goldColor.copy(alpha = 0.20f * starGlowAlpha), radius = 8.0f * s * starScale, center = mainStar)
        drawCircle(color = goldColor.copy(alpha = 0.40f * starGlowAlpha), radius = 4.8f * s * starScale, center = mainStar)

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
        scale(starScale, pivot = mainStar) {
            drawPath(starSpikes, color = goldColor.copy(alpha = 0.88f + 0.12f * starGlowAlpha))
        }
        drawCircle(color = starCoreColor, radius = 2.2f * s, center = mainStar)
    }
}
