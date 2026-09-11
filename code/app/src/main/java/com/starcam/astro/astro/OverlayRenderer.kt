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

    /** 把 WCS 从求解图尺寸换算到目标位图尺寸。 */
    fun scaleWcs(wcs: WcsTransform, sw: Int, sh: Int, w: Int, h: Int): WcsTransform {
        return wcs.rescaledFor(sw, sh, w, h)
    }

    /**
     * 识别成功 → 叠加星座的位图；缺 WCS 或渲染失败返回 null。
     *
     * @param solarPositions §0.58 太阳系天体站心位置（可空 = 不标注）。
     *   调用方用 [ExifPriorsReader.solarSystemForPhoto] 获取。
     */
    fun render(
        bitmap: Bitmap,
        solve: SolveResult,
        isEnglish: Boolean = false,
        solarPositions: List<SolarSystemEphemeris.SolarPosition> = emptyList(),
    ): Bitmap? {
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
        // §0.58 太阳系天体（月亮/行星）：与识别页保持同一观感——
        // 日月在有板比例时按真实视直径绘制，行星用标记环。
        if (solarPositions.isNotEmpty()) {
            val degPerPx = if (solve.fieldWidthDeg > 0.0 && w > 0) {
                (solve.fieldWidthDeg / w).toFloat()
            } else {
                null
            }
            val diskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
            val solarRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.6f * density
            }
            val solarText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFF7EDCB.toInt()
                textSize = 11f * density
                typeface = Typeface.DEFAULT_BOLD
                setShadowLayer(3f * density, 1f, 1f, 0xFF000000.toInt())
            }
            val maxDiskR = minOf(w, h) / 3f
            for (s in StarChartOverlay.projectSolarSystem(wcs, w, h, solarPositions)) {
                if (!s.visible) continue
                val body = s.pos.body
                val color = SolarSystemCatalog.color(body)
                val markerR = 8f * density
                var diskR = 0f
                if (degPerPx != null && degPerPx > 0f && SolarSystemCatalog.isDisk(body)) {
                    diskR = (s.pos.angularDiameterDeg / 2.0 / degPerPx).toFloat()
                        .coerceIn(2.5f * density, maxDiskR)
                }
                if (SolarSystemCatalog.isDisk(body) && diskR > 0f) {
                    glowPaint.color = withAlpha(color, 0.22f)
                    canvas.drawCircle(s.x, s.y, diskR * 1.35f, glowPaint)
                    diskPaint.color = withAlpha(color, 0.95f)
                    canvas.drawCircle(s.x, s.y, diskR, diskPaint)
                    solarRing.color = withAlpha(color, 0.9f)
                    canvas.drawCircle(s.x, s.y, diskR, solarRing)
                } else {
                    diskPaint.color = withAlpha(color, 0.95f)
                    canvas.drawCircle(s.x, s.y, markerR * 0.42f, diskPaint)
                    solarRing.color = withAlpha(color, 0.9f)
                    canvas.drawCircle(s.x, s.y, markerR, solarRing)
                }
                val labelX = s.x + (if (diskR > 0f) diskR else markerR) + 5f * density
                canvas.drawText(
                    SolarSystemCatalog.name(body, isEnglish), labelX, s.y + 4f * density, solarText,
                )
            }
        }
        return out
    }

    /** 按比例降低 alpha（绘制半透明叠加用） */
    private fun withAlpha(argb: Int, factor: Float): Int {
        val a = ((argb ushr 24) and 0xFF) * factor.coerceIn(0f, 1f)
        return (a.toInt().coerceIn(0, 255) shl 24) or (argb and 0x00FFFFFF)
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
