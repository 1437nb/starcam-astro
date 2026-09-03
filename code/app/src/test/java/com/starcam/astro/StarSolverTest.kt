package com.starcam.astro

import com.starcam.astro.astro.EngineMode
import com.starcam.astro.astro.FovEstimate
import com.starcam.astro.astro.SolveEngine
import com.starcam.astro.astro.StarSolver
import com.starcam.astro.astro.SolveResult
import com.starcam.astro.astro.WcsTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三层引擎调度与视场估计单元测试（纯 JVM）。
 */
class StarSolverTest {

    // ---------------- FovEstimate ----------------

    @Test
    fun fovFromFocal35_knownValues() {
        // 35mm 等效 24mm 广角（横拍，长边 36mm）：2·atan(18/24) ≈ 73.74°
        val f24 = FovEstimate.fovDegFromFocal35(24.0, portrait = false)
        assertNotNull(f24)
        assertEquals(73.74, f24!!, 0.01)
        // 50mm 标准镜：2·atan(18/50) ≈ 39.60°
        val f50 = FovEstimate.fovDegFromFocal35(50.0)
        assertNotNull(f50)
        assertEquals(39.60, f50!!, 0.01)
        // 手机主摄等效 ~26mm：2·atan(18/26) ≈ 69.39°
        val f26 = FovEstimate.fovDegFromFocal35(26.0)
        assertNotNull(f26)
        assertEquals(69.39, f26!!, 0.01)
    }

    @Test
    fun fovFromFocal35_portraitUsesShortFrame() {
        // 竖拍：最长边对应 35mm 画幅短边（24mm）
        val p = FovEstimate.fovDegFromFocal35(24.0, portrait = true)
        assertNotNull(p)
        assertEquals(53.13, p!!, 0.01) // 2·atan(12/24)
        // 长焦 200mm（望远镜/长焦镜头）：视场很小
        val tele = FovEstimate.fovDegFromFocal35(200.0)
        assertNotNull(tele)
        assertEquals(10.29, tele!!, 0.01) // 2·atan(18/200)
    }

    @Test
    fun fovFromFocal35_invalidReturnsNull() {
        assertNull(FovEstimate.fovDegFromFocal35(0.0))
        assertNull(FovEstimate.fovDegFromFocal35(-5.0))
        assertNull(FovEstimate.fovDegFromFocal35(Double.NaN))
        assertNull(FovEstimate.fovDegFromFocal35(Double.POSITIVE_INFINITY))
    }

    @Test
    fun fovRange_boundsAndFloor() {
        val (lo, hi) = FovEstimate.fovRange(40.0)
        assertEquals(30.0, lo, 0.001) // 0.75 × 40
        assertEquals(54.0, hi, 0.001) // 1.35 × 40
        // 极小视场下限不低于 0.5°
        val (lo2, _) = FovEstimate.fovRange(0.4)
        assertEquals(0.5, lo2, 0.001)
        // 区间宽度保证
        val (lo3, hi3) = FovEstimate.fovRange(1.0)
        assertTrue(hi3 - lo3 >= 0.5)
    }

    @Test
    fun isPortrait_onlyRotate90Or270() {
        assertTrue(FovEstimate.isPortrait(6))  // ORIENTATION_ROTATE_90
        assertTrue(FovEstimate.isPortrait(8))  // ORIENTATION_ROTATE_270
        assertTrue(!FovEstimate.isPortrait(1))  // NORMAL
        assertTrue(!FovEstimate.isPortrait(3))  // ROTATE_180
    }

    // ---------------- StarSolver.planSteps ----------------

    @Test
    fun auto_narrowField_prefersNativeEngine() {
        // 2° 视场：亮星表适用范围（≥8°）之外自动跳过，直接官方引擎；有 Key 在线兜底
        val steps = StarSolver.planSteps(2.0, EngineMode.AUTO, hasApiKey = true)
        assertEquals(listOf(SolveEngine.ASTROMETRY_NATIVE, SolveEngine.ONLINE_NOVA), steps)
    }

    @Test
    fun auto_wideField_starTableFirst() {
        // 60° 手机广角视场：Hipparcos 亮星表先快匹配（秒级），失败再官方引擎，
        // 有 Key 在线兜底
        val steps = StarSolver.planSteps(60.0, EngineMode.AUTO, hasApiKey = true)
        assertEquals(
            listOf(
                SolveEngine.LOCAL_MATCHER,
                SolveEngine.ASTROMETRY_NATIVE,
                SolveEngine.ONLINE_NOVA,
            ),
            steps,
        )
    }

    @Test
    fun auto_superWideField_over180_skipsNative() {
        // >180°（鱼眼全景）：官方引擎 maxwidth 上限之外，仅自研 + 在线
        val steps = StarSolver.planSteps(200.0, EngineMode.AUTO, hasApiKey = true)
        assertEquals(listOf(SolveEngine.LOCAL_MATCHER, SolveEngine.ONLINE_NOVA), steps)
    }

    @Test
    fun auto_boundary180Deg() {
        // 边界：180° 亮星表 + 官方都适用（亮星表先）；>180° 仅亮星表
        val atMax = StarSolver.planSteps(180.0, EngineMode.AUTO, hasApiKey = false)
        assertEquals(listOf(SolveEngine.LOCAL_MATCHER, SolveEngine.ASTROMETRY_NATIVE), atMax)
        val overMax = StarSolver.planSteps(180.1, EngineMode.AUTO, hasApiKey = false)
        assertEquals(listOf(SolveEngine.LOCAL_MATCHER), overMax)
    }

    @Test
    fun auto_midField_threeEngines() {
        // 20° 视场：亮星表 + 官方 + 在线（亮星表 8°~180° 适用，先试）
        val steps = StarSolver.planSteps(20.0, EngineMode.AUTO, hasApiKey = true)
        assertEquals(
            listOf(
                SolveEngine.LOCAL_MATCHER,
                SolveEngine.ASTROMETRY_NATIVE,
                SolveEngine.ONLINE_NOVA,
            ),
            steps,
        )
    }

    @Test
    fun auto_midWideField_starTableFirst() {
        // 40° 视场（同样属于广角段）：亮星表先试，官方引擎兜底精解
        val steps = StarSolver.planSteps(40.0, EngineMode.AUTO, hasApiKey = true)
        assertEquals(
            listOf(
                SolveEngine.LOCAL_MATCHER,
                SolveEngine.ASTROMETRY_NATIVE,
                SolveEngine.ONLINE_NOVA,
            ),
            steps,
        )
    }

    @Test
    fun auto_unknownFov_starTableFirst() {
        // 无 EXIF 焦距：Hipparcos 亮星表先试（秒级判断），未命中再官方分段盲解
        val steps = StarSolver.planSteps(null, EngineMode.AUTO, hasApiKey = true)
        assertEquals(
            listOf(
                SolveEngine.LOCAL_MATCHER,
                SolveEngine.ASTROMETRY_NATIVE,
                SolveEngine.ONLINE_NOVA,
            ),
            steps,
        )
    }

    @Test
    fun nativeFirst_alwaysStartsWithNative() {
        val steps = StarSolver.planSteps(60.0, EngineMode.NATIVE_FIRST, hasApiKey = true)
        assertEquals(
            listOf(
                SolveEngine.ASTROMETRY_NATIVE,
                SolveEngine.LOCAL_MATCHER,
                SolveEngine.ONLINE_NOVA,
            ),
            steps,
        )
        // 无 Key：本地两步
        val steps2 = StarSolver.planSteps(60.0, EngineMode.NATIVE_FIRST, hasApiKey = false)
        assertEquals(
            listOf(SolveEngine.ASTROMETRY_NATIVE, SolveEngine.LOCAL_MATCHER),
            steps2,
        )
    }

    @Test
    fun offlineOnly_noOnlineStep() {
        // 仅离线：广角亮星表先试，官方精解兜底
        val mid = StarSolver.planSteps(60.0, EngineMode.OFFLINE_ONLY, hasApiKey = true)
        assertEquals(
            listOf(SolveEngine.LOCAL_MATCHER, SolveEngine.ASTROMETRY_NATIVE),
            mid,
        )
        val unknown = StarSolver.planSteps(null, EngineMode.OFFLINE_ONLY, hasApiKey = true)
        assertEquals(
            listOf(SolveEngine.LOCAL_MATCHER, SolveEngine.ASTROMETRY_NATIVE),
            unknown,
        )
    }

    @Test
    fun onlineOnly_requiresApiKey() {
        assertEquals(
            listOf(SolveEngine.ONLINE_NOVA),
            StarSolver.planSteps(null, EngineMode.ONLINE_ONLY, hasApiKey = true),
        )
        assertTrue(StarSolver.planSteps(null, EngineMode.ONLINE_ONLY, hasApiKey = false).isEmpty())
    }

    // ---------------- 盲解分段试探（无 EXIF 先验） ----------------

    @Test
    fun blindSegments_twoSegmentsCoverWideRange() {
        val segs = StarSolver.blindSegments()
        // 两段：宽场段优先（§0.15 实测 11/12 命中 40°~120°），窄中场兜底
        assertEquals(2, segs.size)
        assertEquals(40.0, segs[0].first, 1e-9)
        assertEquals(120.0, segs[0].second, 1e-9)
        assertEquals(3.0, segs[1].first, 1e-9)
        assertEquals(40.0, segs[1].second, 1e-9)
        // 覆盖 3°~120°（手机广角与普通镜头照片的主战场）且区间连续无空洞
        assertEquals(segs[1].second, segs[0].first, 1e-9)
        // 每段都在官方引擎能力范围内
        segs.forEach { (lo, hi, _) ->
            assertTrue(lo >= StarSolver.NATIVE_FOV_MIN_DEG)
            assertTrue(hi <= StarSolver.NATIVE_FOV_MAX_DEG)
        }
    }

    @Test
    fun blindSegments_timeBudgetReasonable() {
        // 总盲解预算（28s）不超过已知视场单段（30s），且每段都留有余量
        val segs = StarSolver.blindSegments()
        val total = segs.sumOf { it.third }
        assertTrue(total <= StarSolver.NATIVE_TIME_LIMIT_KNOWN_FOV)
        segs.forEach { (_, _, t) -> assertTrue(t in 8.0..25.0) }
    }
    // ---------------- 降采样重试轮（盲解失败后） ----------------

    @Test
    fun downsampleRetryPlan_onlyBlindAndLargeEnough() {
        // 有视场先验：不需要降采样重试（scale 已按 EXIF 焦距对准）
        assertNull(StarSolver.downsampleRetryPlan(45.0, 3000))
        // 盲解但图像已够小（≤1200 长边）：不再缩放
        assertNull(StarSolver.downsampleRetryPlan(null, 1000))
        assertNull(StarSolver.downsampleRetryPlan(null, 1200))
        // 盲解 + 大图：触发，返回目标长边与分段
        val plan = StarSolver.downsampleRetryPlan(null, 3000)
        assertNotNull(plan)
        assertEquals(StarSolver.NATIVE_DOWNSAMPLE_RETRY_LONG_EDGE, plan!!.first)
        assertEquals(StarSolver.blindSegments(), plan.second)
        // 边界：1201 即触发
        assertNotNull(StarSolver.downsampleRetryPlan(null, 1201))
    }

    @Test
    fun rescaleSolveFor_math() {
        val fromW = 2200
        val fromH = 1650
        val toW = 4000
        val toH = 3000
        val orig = SolveResult(
            raDeg = 83.6, decDeg = 22.0,
            pixScaleArcsec = 30.0,
            orientationDeg = 45.0,
            parity = 1,
            imageWidth = fromW, imageHeight = fromH,
            fieldRadiusArcmin = 120.0,
            wcs = WcsTransform(
                crpix1 = fromW / 2.0 + 0.5, crpix2 = fromH / 2.0 + 0.5,
                crval1 = 83.6, crval2 = 22.0,
                cd11 = 1e-4, cd12 = 2e-4, cd21 = -2e-4, cd22 = 1e-4,
            ),
        )
        val r = StarSolver.rescaleSolveFor(orig, fromW, fromH, toW, toH)
        // 尺寸与天球坐标
        assertEquals(toW, r.imageWidth)
        assertEquals(toH, r.imageHeight)
        assertEquals(83.6, r.raDeg, 1e-9)
        assertEquals(22.0, r.decDeg, 1e-9)
        // crpix 按 FITS 1 起始坐标线性映射：(p-1)*k+1（直接乘 k 会引入 (k-1)px 对角平移）
        assertEquals((fromW / 2.0 + 0.5 - 1.0) * toW / fromW + 1.0, r.wcs!!.crpix1, 1e-9)
        assertEquals((fromH / 2.0 + 0.5 - 1.0) * toH / fromH + 1.0, r.wcs!!.crpix2, 1e-9)
        // CD / pixscale 按缩小比例换算（同尺度更多像素 → 度/像素变小）
        val k = fromW.toDouble() / toW
        assertEquals(1e-4 * k, r.wcs!!.cd11, 1e-12)
        assertEquals(2e-4 * k, r.wcs!!.cd12, 1e-12)
        assertEquals(-2e-4 * k, r.wcs!!.cd21, 1e-12)
        assertEquals(1e-4 * k, r.wcs!!.cd22, 1e-12)
        assertEquals(30.0 * k, r.pixScaleArcsec, 1e-9)
        // 视场半径按新尺寸新比例尺重算
        val expectRadius = Math.hypot(toW.toDouble(), toH.toDouble()) * 30.0 * k / 120.0
        assertEquals(expectRadius, r.fieldRadiusArcmin!!, 1e-9)
        // 无 WCS 时也不崩（在线结果等）
        val noWcs = orig.copy(wcs = null)
        val r2 = StarSolver.rescaleSolveFor(noWcs, fromW, fromH, toW, toH)
        assertNull(r2.wcs)
        assertEquals(toW, r2.imageWidth)
    }
}
