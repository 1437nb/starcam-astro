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
     * fov = 2·atan(画幅半长 / f35)。
     *
     * ⚠️ 2026-09-13 修正（§0.61）：`portrait` 参数不再改变结果。
     * 此前竖拍分支用 24mm 短边计算长边视场，但传感器物理与 EXIF 方向无关——
     * 竖拍只是把整幅画面旋转 90°，文件像素与传感器长边（36mm 等效方向）不变，
     * 故长边视场始终 = 2·atan(18/f35)。
     *
     * 旧 bug 的实害：手机 4:3 传感器 + 竖拍（EXIF Orientation=6/8）时，
     * 长边视场被低估（26mm 等效 → 49.55° 而非 67.3°），而官方引擎按
     * 位图宽度换算 pixscale 先验 [fov×0.75, fov×1.35]°，
     * 真实 pixscale 恰好落在先验上限之外 → 官方引擎必然解不出。
     * 实例：南宁秋季四边形 30s 照片（2026-09-13 用户报告）识别失败。
     *
     * 保留 `portrait` 参数仅为调用方/测试兼容（历史语义已废弃）。
     */
    fun fovDegFromFocal35(focal35mm: Double, portrait: Boolean = false): Double? {
        if (!focal35mm.isFinite() || focal35mm <= 0.0) return null
        val frameHalf = FRAME_LONG_MM / 2.0
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