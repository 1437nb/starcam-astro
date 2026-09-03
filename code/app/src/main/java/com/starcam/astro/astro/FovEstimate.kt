package com.starcam.astro.astro

/**
 * 视场（FOV）估计：由 EXIF 35mm 等效焦距推算照片视场（度）。
 *
 * 供引擎调度与 astrometry.net 索引档位选择使用：
 * 视场 ~ 焦段（等效 35mm）直接换算，手机拍摄时 EXIF 通常带
 * FocalLengthIn35mmFilm 标签；没有时返回 null（未知，走全范围盲解）。
 */
object FovEstimate {

    /** 35mm 全幅等效画幅：长边 / 短边（mm） */
    const val FRAME_LONG_MM = 36.0
    const val FRAME_SHORT_MM = 24.0

    /**
     * 由 35mm 等效焦距计算照片**最长边方向**的视场（度）。
     * fov = 2·atan(画幅半长 / f35)。[portrait] 竖拍时最长边对应传感器短边。
     * 返回 null：焦距缺失/非正/非有限。
     */
    fun fovDegFromFocal35(focal35mm: Double, portrait: Boolean = false): Double? {
        if (!focal35mm.isFinite() || focal35mm <= 0.0) return null
        val frameHalf = (if (portrait) FRAME_SHORT_MM else FRAME_LONG_MM) / 2.0
        return Math.toDegrees(2.0 * Math.atan(frameHalf / focal35mm))
    }

    /**
     * 视场估计范围 [lo, hi]（度）：中心值 ± 容差。
     * 容差吸收传感器比例差异（4:3 与 3:2）与焦距标注误差；
     * 下限不低于 0.5°，区间保证至少 0.5° 宽度。
     */
    fun fovRange(fovDeg: Double, loFactor: Double = 0.75, hiFactor: Double = 1.35): Pair<Double, Double> {
        val lo = maxOf(0.5, fovDeg * loFactor)
        val hi = maxOf(lo + 0.5, fovDeg * hiFactor)
        return lo to hi
    }

    /** 竖拍判定：EXIF Orientation 为 90 或 270 */
    fun isPortrait(exifOrientation: Int): Boolean =
        exifOrientation == 6 /* ROTATE_90 */ || exifOrientation == 8 /* ROTATE_270 */
}