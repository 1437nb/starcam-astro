package com.starcam.astro.astro

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface

/**
 * 叠加图层开关（§0.47）：独立控制 6 类标注是否绘制。
 * - lines：星座连线
 * - starNames：恒星专名 / Bayer 标号
 * - constellationNames：星座名称标签
 * - messier：梅西耶深空天体标注
 * - planets：太阳系天体（月亮 / 行星，§0.58）
 * - starCircles：检测星点圈（诊断类）
 *
 * 供结果页预览 Canvas、放大查看器与保存相册位图三处共用，保证渲染一致。
 */
data class LayerFlags(
    val lines: Boolean = true,
    val starNames: Boolean = true,
    val constellationNames: Boolean = true,
    val messier: Boolean = true,
    val planets: Boolean = true,
) {
    val allEnabled: Boolean get() = lines && starNames && constellationNames && messier && planets

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
        /** §0.58 太阳系天体（缺省空列表，兼容既有调用点） */
        val solar: List<StarChartOverlay.Solar2D> = emptyList(),
    )

    /**
     * 把场景各图层绘制到 [canvas]（位图像素坐标系，与 [scene.width/height] 对应）。
     * [density] 控制字号/线宽缩放（位图渲染传 2.0，Compose 预览传 1.sp 的像素值）。
     * [drawWidth]/[drawHeight]：绘制区域实际尺寸（像素）。Compose 预览必须传
     * DrawScope.size —— nativeCanvas.width 是整块窗口画布而非组合件绘制区，
     * 直接用 canvas.width 会让 x/y 缩放比不一致、星座严重变形（v1.5.33 实测）。
     * [dimBelowHorizon]：地平线以下（belowHorizon=true）的元素变暗（§0.53 全天星空）。
     * [degPerPx]：天球角度 / 图像像素（板比例）。非空时日月按**真实视直径**绘制
     * （这才是天文软件该有的样子）；为空则退化为固定尺寸标记。行星因视直径远小于
     * 一像素，一律用标记环绘制。
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
        dimBelowHorizon: Boolean = false,
        degPerPx: Float? = null,
    ) {
        val w = scene.width
        val h = scene.height
        // 坐标缩放（图像坐标 → 画布像素）；字号/线宽为画布绝对像素，不随 sx 缩放
        val sx = drawWidth / w
        val sy = drawHeight / h

        val lineColor = if (night) 0x66FF8A80.toInt() else 0x8C7FD0FF.toInt()
        val nameColor = if (night) 0xFFE8A8A0.toInt() else 0xFFF7EDCB.toInt()
        val conColor = if (night) 0x55E8A8A0.toInt() else 0x5CBBD8FF.toInt()
        // 地平线以下变暗色（透明度乘 ~0.35；仅 AR 全天星空模式启用）
        val dimLine = if (night) 0x22FF8A80.toInt() else 0x2E7FD0FF.toInt()
        val dimName = if (night) 0x55E8A8A0.toInt() else 0x55F7EDCB.toInt()
        val dimMessier = if (night) 0x55E8A8A0.toInt() else 0x55F7EDCB.toInt()

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
                if (dimBelowHorizon && (line.a.belowHorizon || line.b.belowHorizon)) {
                    linePaint.color = dimLine
                } else {
                    linePaint.color = lineColor
                }
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
                if (dimBelowHorizon && s.belowHorizon) namePaint.color = dimName else namePaint.color = nameColor
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
                val dim = dimBelowHorizon && m.belowHorizon
                ringPaint.color = if (dim) dimMessier else MessierCatalog.typeColor(m.obj.type)
                if (dim) textPaint.color = dimMessier else textPaint.color = nameColor
                canvas.drawCircle(m.x * sx, m.y * sy, 7f * density, ringPaint)
                canvas.drawText(
                    m.obj.label(isEnglish), m.x * sx + 10f * density, m.y * sy - 6f * density,
                    textPaint,
                )
            }
        }

        // ⑤ 太阳系天体（§0.58）：月亮 / 行星 / 太阳
        //    日月按真实视直径绘制（给定板比例时），行星用标记环 + 名称。
        if (flags.planets && scene.solar.isNotEmpty()) {
            val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.6f * density
            }
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
            }
            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
            }
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = nameColor
                textSize = 11f * density
                typeface = Typeface.DEFAULT_BOLD
                setShadowLayer(3f * density, 1f, 1f, 0xFF000000.toInt())
            }
            // 真实视直径上限：不超过画面短边的 1/3（防止极端长焦下日月糊满屏）
            val maxDiskR = minOf(drawWidth, drawHeight) / 3f
            for (s in scene.solar) {
                if (!s.visible) continue
                val body = s.pos.body
                val dim = dimBelowHorizon && s.belowHorizon
                val baseColor = SolarSystemCatalog.color(body)
                val color = if (dim) withAlpha(baseColor, 0.35f) else baseColor
                val cx = s.x * sx
                val cy = s.y * sy

                val markerR = 8f * density
                // 日月：优先按真实视直径绘制
                var diskR = 0f
                if (degPerPx != null && degPerPx > 0f && SolarSystemCatalog.isDisk(body)) {
                    val px = (s.pos.angularDiameterDeg / 2.0 / degPerPx).toFloat() * sx
                    diskR = px.coerceIn(2.5f * density, maxDiskR)
                }

                if (SolarSystemCatalog.isDisk(body) && diskR > 0f) {
                    // 光晕 → 实心圆面 → 细描边，模拟目视观感
                    glowPaint.color = withAlpha(color, if (dim) 0.10f else 0.22f)
                    canvas.drawCircle(cx, cy, diskR * 1.35f, glowPaint)
                    fillPaint.color = withAlpha(color, if (dim) 0.55f else 0.95f)
                    canvas.drawCircle(cx, cy, diskR, fillPaint)
                    ringPaint.color = withAlpha(color, if (dim) 0.5f else 0.9f)
                    canvas.drawCircle(cx, cy, diskR, ringPaint)
                } else {
                    // 行星 / 未知板比例：实心小点 + 外环
                    fillPaint.color = withAlpha(color, if (dim) 0.5f else 0.95f)
                    canvas.drawCircle(cx, cy, markerR * 0.42f, fillPaint)
                    ringPaint.color = withAlpha(color, if (dim) 0.5f else 0.9f)
                    canvas.drawCircle(cx, cy, markerR, ringPaint)
                }

                textPaint.color = if (dim) dimName else nameColor
                val labelX = cx + (if (diskR > 0f) diskR else markerR) + 5f * density
                canvas.drawText(SolarSystemCatalog.name(body, isEnglish), labelX, cy + 4f * density, textPaint)
            }
        }
    }

    /** 按比例降低 alpha（用于地平线以下变暗） */
    private fun withAlpha(argb: Int, factor: Float): Int {
        val a = ((argb ushr 24) and 0xFF) * factor.coerceIn(0f, 1f)
        return (a.toInt().coerceIn(0, 255) shl 24) or (argb and 0x00FFFFFF)
    }

    /** 带图层开关的标注位图渲染：把 [scene] 按 [flags] 叠加到 [src] 副本上 */
    fun renderBitmap(
        src: Bitmap,
        scene: OverlayScene,
        flags: LayerFlags,
        isEnglish: Boolean,
        degPerPx: Float? = null,
    ): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        draw(canvas, scene, flags, isEnglish, density = 2.0f, degPerPx = degPerPx)
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
