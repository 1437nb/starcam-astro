package com.starcam.astro

import com.starcam.astro.astro.SolveResult
import com.starcam.astro.astro.StarSolver
import com.starcam.astro.astro.WcsTransform
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 复现竖屏叠加错位 bug：降采样重试轮的 rescaleSolveFor 换算
 * 在竖屏（旋转 WCS）场景下是否保持星点投影对齐。
 */
class RescalePortraitTest {

    private fun checkRescale(cd11: Double, cd12: Double, cd21: Double, cd22: Double, tag: String) {
        val fromW = 825
        val fromH = 1100
        val toW = 3000
        val toH = 4000
        val fromWcs = WcsTransform(
            crpix1 = fromW / 2.0 + 0.5, crpix2 = fromH / 2.0 + 0.5,
            crval1 = 60.0, crval2 = 25.3,
            cd11 = cd11, cd12 = cd12, cd21 = cd21, cd22 = cd22,
        )
        val solve = SolveResult(
            raDeg = 60.0, decDeg = 25.3, pixScaleArcsec = 65.0,
            orientationDeg = 0.0, parity = 1,
            imageWidth = fromW, imageHeight = fromH, wcs = fromWcs,
        )
        val r = StarSolver.rescaleSolveFor(solve, fromW, fromH, toW, toH)
        val wcs = r.wcs!!
        // 多个测试点：缩放图坐标 → (乘 sx,sy) 应等于 原图坐标
        val sx = toW.toDouble() / fromW
        val sy = toH.toDouble() / fromH
        for (star in listOf(56.75 to 24.11, 66.75 to 15.87, 63.0 to 22.0, 57.0 to 28.0)) {
            val (ra, dec) = star
            val f = fromWcs.skyToFitsPixel(ra, dec)
            val t = wcs.skyToFitsPixel(ra, dec)
            val expectX = (f[0] - 1.0) * sx + 1.0
            val expectY = (f[1] - 1.0) * sy + 1.0
            val errX = abs(t[0] - expectX)
            val errY = abs(t[1] - expectY)
            assertTrue(
                "%s 星(%.2f,%.2f) 换算误差 x=%.3f y=%.3f px".format(tag, ra, dec, errX, errY),
                errX < 0.05 && errY < 0.05,
            )
        }
    }

    @Test
    fun rescale_landscapeWcs() {
        // 横构图典型 CD（对角主导）
        checkRescale(-5.5e-3, -1.8e-2, 1.8e-2, -5.5e-3, "横屏")
    }

    @Test
    fun rescale_portraitRotatedWcs() {
        // 竖屏旋转 WCS（5087 实测头：反对角主导，接近 90° 旋转）
        checkRescale(-1.81e-2, -5.55e-3, 5.55e-3, -1.81e-2, "竖屏旋转")
    }

    @Test
    fun rescale_portraitParityFlip() {
        // 竖屏镜像 parity（符号组合更极端）
        checkRescale(-1.81e-2, 5.55e-3, 5.55e-3, 1.81e-2, "竖屏镜像")
    }
}
