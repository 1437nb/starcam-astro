package com.starcam.astro.astro

/** EXIF 的八种方向；坐标为零起点像素中心，尺寸为解码器读到的原始栅格。 */
data class PhotoOrientation(val value: Int = 1) {
    init { require(value in 1..8) }
    val swapsAxes: Boolean get() = value >= 5
    fun width(w: Int, h: Int): Int = if (swapsAxes) h else w
    fun height(w: Int, h: Int): Int = if (swapsAxes) w else h

    fun forward(x: Float, y: Float, w: Int, h: Int): FloatArray = when (value) {
        2 -> floatArrayOf(w - 1f - x, y)
        3 -> floatArrayOf(w - 1f - x, h - 1f - y)
        4 -> floatArrayOf(x, h - 1f - y)
        5 -> floatArrayOf(y, x)
        6 -> floatArrayOf(h - 1f - y, x)
        7 -> floatArrayOf(h - 1f - y, w - 1f - x)
        8 -> floatArrayOf(y, w - 1f - x)
        else -> floatArrayOf(x, y)
    }

    fun inverse(x: Float, y: Float, w: Int, h: Int): FloatArray = when (value) {
        6 -> floatArrayOf(y, h - 1f - x)
        8 -> floatArrayOf(w - 1f - y, x)
        else -> forward(x, y, width(w, h), height(w, h))
    }

    /** 现有在线上传只处理 EXIF 6 / 3 / 8 的旋转，不处理镜像。 */
    fun uploadOrientation(): PhotoOrientation = if (value in listOf(3, 6, 8)) this else PhotoOrientation()

    /** Canvas 作用于像素边界；从像素中心变换补偿半个像素。 */
    fun canvasMatrix(w: Int, h: Int): FloatArray {
        val o = forward(0f, 0f, w, h)
        val x = forward(1f, 0f, w, h)
        val y = forward(0f, 1f, w, h)
        val a = x[0] - o[0]; val b = y[0] - o[0]
        val c = x[1] - o[1]; val d = y[1] - o[1]
        return floatArrayOf(a, b, o[0] + .5f - .5f * (a + b), c, d, o[1] + .5f - .5f * (c + d), 0f, 0f, 1f)
    }
}

/** 在线定标和输出方向分别处理，不能只按横竖边长缩放。 */
class PhotoExportGeometry(
    val rawWidth: Int,
    val rawHeight: Int,
    val solveOrientation: PhotoOrientation,
    val outputOrientation: PhotoOrientation,
) {
    init { require(rawWidth > 0 && rawHeight > 0) }
    val basisWidth = solveOrientation.width(rawWidth, rawHeight)
    val basisHeight = solveOrientation.height(rawWidth, rawHeight)
    val width = outputOrientation.width(rawWidth, rawHeight)
    val height = outputOrientation.height(rawWidth, rawHeight)

    /** Bitmap 缩放按像素中心采样；FITS 面积平均可能舍弃末尾不足一组的行列。 */
    fun wcs(
        solve: SolveResult,
        footprintWidth: Int = basisWidth,
        footprintHeight: Int = basisHeight,
    ): WcsTransform {
        require(solve.imageWidth > 0 && solve.imageHeight > 0)
        val source = requireNotNull(solve.wcs)
        val sx = footprintWidth.toDouble() / solve.imageWidth
        val sy = footprintHeight.toDouble() / solve.imageHeight
        return WcsTransform(
            (source.crpix1 - .5) * sx + .5,
            (source.crpix2 - .5) * sy + .5 + basisHeight - footprintHeight,
            source.crval1, source.crval2,
            source.cd11 / sx, source.cd12 / sy, source.cd21 / sx, source.cd22 / sy,
        )
    }

    fun outputPoint(x: Float, y: Float): FloatArray {
        val raw = solveOrientation.inverse(x, y, rawWidth, rawHeight)
        return outputOrientation.forward(raw[0], raw[1], rawWidth, rawHeight)
    }
}
