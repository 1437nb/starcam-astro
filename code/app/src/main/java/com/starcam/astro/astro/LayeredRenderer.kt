package com.starcam.astro.astro

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface

/**
 * 叠加图层开关（§0.47）：独立控制 5 类标注是否绘制。
 * - lines：星座连线
 * - starNames：恒星专名 / Bayer 标号
 * - constellationNames：星座名称标签
 * - messier：梅西耶深空天体标注
 * - starCircles：检测星点圈（诊断类）
 *
 * 供结果页预览 Canvas、放大查看器与保存相册位图三处共用，保证渲染一致。
 */
data class LayerFlags(
    val lines: Boolean = true,
    val starNames: Boolean = true,
    val constellationNames: Boolean = true,
    val messier: Boolean = true,
) {
    val allEnabled: Boolean get() = lines && starNames && constellationNames && messier

    companion object {
        val ALL = LayerFlags()
    }
}

/**
 * 分层渲染统一入口（§0.47）：把投影好的 [OverlayScene] 按 [flags] 画到 canvas。
 * 与位图渲染（保存/查看器）及 Compose Canvas 预览共用一套绘制逻辑。
 */
object LayeredRenderer {

    /** 已投影的叠加场景数据（结果页 Success 状态持有，复用避免重复投影） */
    data class OverlayScene(
        val width: Int,
        val height: Int,
        val stars: List<StarChartOverlay.Star2D>,
        val lines: List<StarChartOverlay.Line2D>,
        val labels: List<StarChartOverlay.Label2D>,
        val messier: List<StarChartOverlay.Messier2D>,
    )

    /**
     * 把场景各图层绘制到 [canvas]（位图像素坐标系，与 [scene.width/height] 对应）。
     * [density] 控制字号/线宽缩放（位图渲染传 2.0，Compose 预览传 1.sp 的像素值）。
     * [drawWidth]/[drawHeight]：绘制区域实际尺寸（像素）。Compose 预览必须传
     * DrawScope.size —— nativeCanvas.width 是整块窗口画布而非组合件绘制区，
     * 直接用 canvas.width 会让 x/y 缩放比不一致、星座严重变形（v1.5.33 实测）。
     */
    fun draw(
        canvas: Canvas,
        scene: OverlayScene,
        flags: LayerFlags,
        isEnglish: Boolean,
        density: Float = 2.0f,
        night: Boolean = false,
        drawWidth: Float = canvas.width.toFloat(),
        drawHeight: Float = canvas.height.toFloat(),
    ) {
        val w = scene.width
        val h = scene.height
        // 坐标缩放（图像坐标 → 画布像素）；字号/线宽为画布绝对像素，不随 sx 缩放
        val sx = drawWidth / w
        val sy = drawHeight / h

        val lineColor = if (night) 0x66FF8A80.toInt() else 0x8C7FD0FF.toInt()
        val nameColor = if (night) 0xFFE8A8A0.toInt() else 0xFFF7EDCB.toInt()
        val conColor = if (night) 0x55E8A8A0.toInt() else 0x5CBBD8FF.toInt()

        // ① 星座连线：两端按星等留空（星星不被线覆盖）
        if (flags.lines) {
            val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = lineColor
                strokeWidth = 1.2f * density
                style = Paint.Style.STROKE
            }
            for (line in scene.lines) {
                val seg = shrink(
                    line.a.x, line.a.y, line.a.entry.mag,
                    line.b.x, line.b.y, line.b.entry.mag,
                ) ?: continue
                canvas.drawLine(seg[0] * sx, seg[1] * sy, seg[2] * sx, seg[3] * sy, linePaint)
            }
        }

        // ② 恒星专名（亮星且能解析出名字）
        if (flags.starNames) {
            val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = nameColor
                textSize = 12f * density
                typeface = Typeface.DEFAULT_BOLD
                setShadowLayer(3f * density, 1f, 1f, 0xFF000000.toInt())
            }
            for (s in scene.stars) {
                if (!s.visible || s.entry.mag > 3.2) continue
                val name = StarNames.displayName(s.entry.hip, s.entry.name, isEnglish)
                if (name.isEmpty()) continue
                canvas.drawText(name, s.x * sx + 7f * density, s.y * sy - 7f * density, namePaint)
            }
        }

        // ③ 星座名称标签
        if (flags.constellationNames) {
            val conPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = conColor
                textSize = 11f * density
                typeface = Typeface.DEFAULT_BOLD
                setShadowLayer(3f * density, 1f, 1f, 0xFF000000.toInt())
            }
            for (l in scene.labels) {
                canvas.drawText(l.text, l.x * sx, l.y * sy, conPaint)
            }
        }

        // ④ 梅西耶深空天体标注（小圆圈 + 编号与名称，颜色按类型）
        if (flags.messier) {
            val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.4f * density
            }
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = nameColor
                textSize = 10f * density
                typeface = Typeface.DEFAULT_BOLD
                setShadowLayer(2.5f * density, 1f, 1f, 0xFF000000.toInt())
            }
            for (m in scene.messier) {
                if (!m.visible) continue
                ringPaint.color = MessierCatalog.typeColor(m.obj.type)
                canvas.drawCircle(m.x * sx, m.y * sy, 7f * density, ringPaint)
                canvas.drawText(
                    m.obj.label(isEnglish), m.x * sx + 10f * density, m.y * sy - 6f * density,
                    textPaint,
                )
            }
        }
    }

    /** 带图层开关的标注位图渲染：把 [scene] 按 [flags] 叠加到 [src] 副本上 */
    fun renderBitmap(src: Bitmap, scene: OverlayScene, flags: LayerFlags, isEnglish: Boolean): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        draw(canvas, scene, flags, isEnglish, density = 2.0f)
        return out
    }

    /** 线段两端按星等收缩：越亮的星空隙越大（与 OverlayRenderer.shrink 一致） */
    fun shrink(
        ax: Float, ay: Float, magA: Double,
        bx: Float, by: Float, magB: Double,
    ): FloatArray? {
        val dx = bx - ax
        val dy = by - ay
        val len = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val gapA = gapOf(magA)
        val gapB = gapOf(magB)
        if (len <= gapA + gapB + 2f) return null
        val ux = dx / len
        val uy = dy / len
        return floatArrayOf(ax + ux * gapA, ay + uy * gapA, bx - ux * gapB, by - uy * gapB)
    }

    private fun gapOf(mag: Double): Float = ((4.8 - mag).coerceIn(0.8, 5.0) * 6f).toFloat()
}
