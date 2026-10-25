package com.starcam.astro

import com.starcam.astro.astro.SolveResult
import com.starcam.astro.astro.StarSolver
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地解官方复核决策矩阵（§0.32，纯决策函数，无需 .so）：
 * 高内点免复核；低内点 + 官方无解不拦截；官方一致接受、不一致拒绝。
 */
class CrossCheckTest {

    /** 构造官方解；fov = imageWidth × pixScale / 3600 = 2200 × ps / 3600 */
    private fun native(ra: Double, dec: Double, pixScaleArcsec: Double): SolveResult =
        SolveResult(
            raDeg = ra,
            decDeg = dec,
            pixScaleArcsec = pixScaleArcsec,
            orientationDeg = 0.0,
            parity = 1,
            imageWidth = 2200,
            imageHeight = 1650,
            nMatch = 30,
            indexId = 4117,
            logodds = 500.0,
        )

    @Test
    fun highInlierAcceptedWithoutNativeRun() {
        // 内点 22 ≥ 18：无需官方，直接接受（null 传入也不拦截）
        assertTrue(StarSolver.crossCheckAccept(60.0, 25.0, 76.0, 22, null))
    }

    @Test
    fun lowInlierWithNoNativeSolutionIsTolerated() {
        // 官方复核无解（引擎不可用/限时未出）→ 不误杀，接受
        assertTrue(StarSolver.crossCheckAccept(60.0, 25.0, 76.0, 8, null))
    }

    @Test
    fun consistentNativeSolutionAccepts() {
        // 官方在自研视场附近解出，天区/视场一致 → 接受
        // 官方 fov = 2200×120/3600 = 73.3°，ratio 76.0/73.3 = 1.04
        val n = native(60.1, 25.2, 120.0)
        assertTrue(StarSolver.crossCheckAccept(60.0, 25.0, 76.0, 8, n))
    }

    @Test
    fun disjointSkyRegionRejects() {
        // 官方解出在完全不同的天区（如飞马 vs 猎户）→ 拒绝
        val n = native(351.0, 24.0, 120.0)
        assertFalse(StarSolver.crossCheckAccept(60.0, 25.0, 76.0, 8, n))
    }

    @Test
    fun scaleCollapseRejects() {
        // 官方解的视场与自研严重不符（0.73° vs 76°）→ 拒绝
        val n = native(60.2, 25.3, 1.2)
        assertFalse(StarSolver.crossCheckAccept(60.0, 25.0, 76.0, 8, n))
    }

    @Test
    fun raWrapAroundIsConsistent() {
        // 赤经跨 0°/360° 边界仍应判为一致
        val n = native(359.8, 25.1, 120.0)
        assertTrue(StarSolver.crossCheckAccept(0.2, 25.0, 76.0, 8, n))
    }

    @Test
    fun nearThresholdSkyOffsetRejects() {
        // 天区偏移 5° > 3° 窗口 → 拒绝
        val n = native(65.0, 25.0, 120.0)
        assertFalse(StarSolver.crossCheckAccept(60.0, 25.0, 76.0, 8, n))
    }

    @Test
    fun highDeclinationSphericalSeparationAccepts() {
        // 高赤纬（Dec = 80°）：ΔRA = 5°，旧逻辑 dra < 3° 会错误拦截；
        // 实际上 cos(80°) ≈ 0.1736，天球真实角距约为 5° × 0.1736 ≈ 0.87° < 3°，应判定一致接受。
        val n = native(65.0, 80.0, 120.0)
        assertTrue(StarSolver.crossCheckAccept(60.0, 80.0, 76.0, 8, n))
    }

    @Test
    fun highDeclinationLargeOffsetRejects() {
        // 高赤纬（Dec = 80°）：ΔRA = 25°，真实角距约为 25° × 0.1736 ≈ 4.34° > 3°，判定不一致拒绝。
        val n = native(85.0, 80.0, 120.0)
        assertFalse(StarSolver.crossCheckAccept(60.0, 80.0, 76.0, 8, n))
    }

    @Test
    fun angularSepDeg_accuratelyComputesSphericalDistance() {
        // 赤道上 10° RA 差：精确为 10°
        val dEq = StarSolver.angularSepDeg(10.0, 0.0, 20.0, 0.0)
        org.junit.Assert.assertEquals(10.0, dEq, 1e-4)

        // 赤经相同，赤纬相差 15°：精确为 15°
        val dDec = StarSolver.angularSepDeg(100.0, 10.0, 100.0, 25.0)
        org.junit.Assert.assertEquals(15.0, dDec, 1e-4)

        // 北极点（Dec = 90°）：无论 RA 怎么变，角距都为 0
        val dPole = StarSolver.angularSepDeg(0.0, 90.0, 180.0, 90.0)
        org.junit.Assert.assertEquals(0.0, dPole, 1e-4)
    }
}