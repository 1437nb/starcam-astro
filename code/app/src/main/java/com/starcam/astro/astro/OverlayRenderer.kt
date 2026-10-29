package com.starcam.astro.astro

import android.graphics.Bitmap

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
     * 直接委托给 [LayeredRenderer.renderBitmap]，消除双渲染器架构分裂，
     * 确保批量导出与单张查看同样拥有 4 象限避让、边缘安全收敛及 M45 邻近星名抑制。
     *
     * @param solarPositions §0.58 太阳系天体站心位置（可空 = 不标注）。
     *   调用方用 [ExifPriorsReader.solarSystemForPhoto] 获取。
     */
    fun render(
        bitmap: Bitmap,
        solve: SolveResult,
        isEnglish: Boolean = false,
        solarPositions: List<SolarSystemEphemeris.SolarPosition> = emptyList(),
    ): Bitmap? = try {
        val rawWcs = solve.wcs ?: null
        val w = bitmap.width
        val h = bitmap.height
        if (rawWcs == null || w <= 0 || h <= 0 || bitmap.isRecycled) {
            null
        } else {
            val wcs = if (solve.imageWidth == w && solve.imageHeight == h) {
                rawWcs
            } else {
                scaleWcs(rawWcs, solve.imageWidth, solve.imageHeight, w, h)
            }
            val stars = StarChartOverlay.projectStars(wcs, w, h)
            val lines = StarChartOverlay.projectLines(stars)
            val labels = StarChartOverlay.constellationLabels(stars, isEnglish)
            val messier = StarChartOverlay.projectMessier(wcs, w, h)
            val solar = if (solarPositions.isNotEmpty()) {
                StarChartOverlay.projectSolarSystem(wcs, w, h, solarPositions)
            } else {
                emptyList()
            }

            val scene = LayeredRenderer.OverlayScene(
                width = w,
                height = h,
                stars = stars,
                lines = lines,
                labels = labels,
                messier = messier,
                solar = solar,
            )

            val degPerPx = if (solve.fieldWidthDeg > 0.0 && w > 0 && solve.fieldWidthDeg.isFinite()) {
                (solve.fieldWidthDeg / w).toFloat()
            } else {
                null
            }

            LayeredRenderer.renderBitmap(
                src = bitmap,
                scene = scene,
                flags = LayerFlags.ALL,
                isEnglish = isEnglish,
                degPerPx = degPerPx,
            )
        }
    } catch (_: Throwable) {
        null
    }

    /** 线段两端按星等收缩（bitmap 像素）：越亮的星空隙越大（委托给 LayeredRenderer） */
    fun shrink(
        ax: Float, ay: Float, magA: Double,
        bx: Float, by: Float, magB: Double,
    ): FloatArray? = LayeredRenderer.shrink(ax, ay, magA, bx, by, magB)
}
