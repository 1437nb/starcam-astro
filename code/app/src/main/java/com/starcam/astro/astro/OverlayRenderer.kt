package com.starcam.astro.astro

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface

/**
 * 叠加渲染公共入口：识别结果 → 叠加星座连线/星名的位图。
 * 供「保存到相册」与「批量识别导出」共用（渲染样式保持一致）。
 */
object OverlayRenderer {

    /** 把 WCS 从求解图尺寸换算到目标位图尺寸（与 ResultScreen.scaleWcs 同式） */
    fun scaleWcs(wcs: WcsTransform, sw: Int, sh: Int, w: Int, h: Int): WcsTransform {
        val sx = w.toDouble() / sw
        val sy = h.toDouble() / sh
        return WcsTransform(
            crpix1 = (wcs.crpix1 - 0.5) * sx + 0.5,
            crpix2 = (wcs.crpix2 - 0.5) * sy + 0.5,
            crval1 = wcs.crval1,
            crval2 = wcs.crval2,
            cd11 = wcs.cd11 / sx,
            cd12 = wcs.cd12 / sx,
            cd21 = wcs.cd21 / sy,
            cd22 = wcs.cd22 / sy,
        )
    }

    /** 识别成功 → 叠加星座的位图；缺 WCS 或渲染失败返回 null */
    fun render(bitmap: Bitmap, solve: SolveResult, isEnglish: Boolean = false): Bitmap? {
        val rawWcs = solve.wcs ?: return null
        val w = bitmap.width
        val h = bitmap.height
        val wcs = if (solve.imageWidth == w && solve.imageHeight == h) {
            rawWcs
        } else {
            scaleWcs(rawWcs, solve.imageWidth, solve.imageHeight, w, h)
        }
        val stars = StarChartOverlay.projectStars(wcs, w, h)
        val lines = StarChartOverlay.projectLines(stars)

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(bitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))

        val density = 2.0f // 按原图分辨率绘制（Compose 画布上的等效绘制密度）
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x8C7FD0FF.toInt() // alpha 0.55：细线半透明，不抢照片
            strokeWidth = 1.2f * density
            style = Paint.Style.STROKE
        }
        for (line in lines) {
            // 线段两端按星等留出空隙：星星不被线覆盖，保留照片原星
            val seg = shrink(line.a.x, line.a.y, line.a.entry.mag,
                line.b.x, line.b.y, line.b.entry.mag) ?: continue
            canvas.drawLine(seg[0], seg[1], seg[2], seg[3], linePaint)
        }
        // 星名（亮星且能解析出名字：中文名优先，其次拜耳命名法）
        val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF7EDCB.toInt()
            textSize = 12f * density
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(4f * density, 1f, 1f, 0xFF000000.toInt())
        }
        for (s in stars) {
            if (!s.visible || s.entry.mag > 3.2) continue
            val name = StarNames.displayName(s.entry.hip, s.entry.name, isEnglish)
            if (name.isEmpty()) continue
            canvas.drawText(name, s.x + 7f, s.y - 7f, namePaint)
        }
        // §0.43c：梅西耶深空天体标注（小圆圈 + M 编号与名称，颜色按类型）
        val messierRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.4f * density
        }
        val messierPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF7EDCB.toInt()
            textSize = 10f * density
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(3f * density, 1f, 1f, 0xFF000000.toInt())
        }
        for (m in StarChartOverlay.projectMessier(wcs, w, h)) {
            if (!m.visible) continue
            messierRing.color = MessierCatalog.typeColor(m.obj.type)
            canvas.drawCircle(m.x, m.y, 7f * density, messierRing)
            canvas.drawText(m.obj.label(isEnglish), m.x + 10f * density, m.y - 6f * density, messierPaint)
        }
        return out
    }

    /** 线段两端按星等收缩（bitmap 像素）：越亮的星空隙越大 */
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
