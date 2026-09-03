package com.starcam.astro

import com.starcam.astro.astro.DemoSolver
import com.starcam.astro.astro.DetectedStar
import com.starcam.astro.astro.LocalStarMatcher
import com.starcam.astro.astro.SkyRegion
import com.starcam.astro.astro.StarCatalogData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** 本地星场匹配器的 JVM 测试（合成星空图，端到端） */
class LocalMatcherTest {

    private val W = 1200
    private val H = 1600

    /** 用与 DemoSolver 相同的 WCS 把星表渲染成灰度星空图（高斯星点 + 背景 + 噪声） */
    private fun renderStarField(
        region: SkyRegion,
        noise: Float = 2f,
        extraStars: Int = 0,
    ): FloatArray {
        val wcs = DemoSolver.wcsFor(region)
        val gray = FloatArray(W * H)
        var base = 14f
        // 背景渐变（模拟光污染）
        for (y in 0 until H) {
            val row = y * W
            val v = base + (H - y) * 0.004f
            for (x in 0 until W) gray[row + x] = v
        }
        fun drawStar(x: Float, y: Float, mag: Double) {
            if (x < 2 || y < 2 || x >= W - 2 || y >= H - 2) return
            val amp = (5.2 - mag).coerceIn(0.6, 4.5) * 42f
            val r = 2.0
            val x0 = x.toInt()
            val y0 = y.toInt()
            for (dy in -4..4) {
                for (dx in -4..4) {
                    val px = x0 + dx
                    val py = y0 + dy
                    if (px in 0 until W && py in 0 until H) {
                        val d2 = (px - x) * (px - x) + (py - y) * (py - y)
                        gray[py * W + px] += (amp * exp(-d2 / (2 * r * r))).toFloat()
                    }
                }
            }
        }
        for (star in StarCatalogData.stars) {
            // 只渲染索引域内的星（mag ≤ 4.0，与 buildIndex 契约一致）；
            // 索引域外的星会成为不可匹配干扰源，稀释亮星近邻列表（实测回归）
            if (star.mag > 4.0f) continue
            val p = wcs.skyToScreen(star.ra, star.dec, W, H)
            if (p[0].isNaN() || p[1].isNaN()) continue
            drawStar(p[0], p[1], star.mag)
        }
        // 额外暗星（不在 919 星表内，模拟真实照片干扰）
        val rnd = Random(3)
        for (k in 0 until extraStars) {
            drawStar(rnd.nextFloat() * W, rnd.nextFloat() * H, 4.7 + rnd.nextDouble() * 1.5)
        }
        if (noise > 0) {
            val rnd2 = Random(11)
            for (i in gray.indices) gray[i] += rnd2.nextFloat() * noise
        }
        return gray
    }

    private fun assertClose(expected: Double, actual: Double, tol: Double, msg: String) {
        assertTrue("$msg: 期望 $expected 实际 $actual", abs(expected - actual) < tol)
    }

    @Test
    fun detectStarsOnSyntheticField() {
        val gray = renderStarField(DemoSolver.regions[0], noise = 2f)
        val detected = LocalStarMatcher.detectStarsGray(W, H, gray)
        assertTrue("应检测到至少 8 颗星，实际 ${detected.size}", detected.size >= 8)
        // 检测星按亮度降序
        for (i in 1 until detected.size) {
            assertTrue("应按亮度降序", detected[i - 1].brightness >= detected[i].brightness)
        }
    }

    @Test
    fun matchSyntheticFieldAllRegions() {
        for (region in DemoSolver.regions) {
            val gray = renderStarField(region, noise = 1f, extraStars = 5)
            val detected = LocalStarMatcher.detectStarsGray(W, H, gray)
            assertTrue("天区「${region.name}」检测星过少: ${detected.size}", detected.size >= 6)
            val res = LocalStarMatcher.match(detected, W, H)
            assertNotNull("天区「${region.name}」匹配失败", res)
            res!!
            assertTrue("天区「${region.name}」内点过少: ${res.inlierCount}", res.inlierCount >= 5)
            // 中心应接近（误差 < 1°）
            assertClose(region.raDeg, res.solve.raDeg, 1.0, "${region.name} RA")
            assertClose(region.decDeg, res.solve.decDeg, 1.0, "${region.name} Dec")
            // 像素比例尺误差 < 8%
            val expectedPs = region.fovDeg * 3600.0 / W
            assertClose(expectedPs, res.solve.pixScaleArcsec, expectedPs * 0.08, "${region.name} pixscale")
        }
    }

    @Test
    fun matchIsRotationInvariant() {
        val region = DemoSolver.regions[0] // 猎户座
        val gray = renderStarField(region, noise = 1f)
        val detected = LocalStarMatcher.detectStarsGray(W, H, gray)

        // 绕图像中心旋转 37°
        val phi = Math.toRadians(37.0)
        val cx = W / 2f
        val cy = H / 2f
        val rotated = detected.mapNotNull {
            val dx = it.x - cx
            val dy = it.y - cy
            val rx = cx + (dx * cos(phi) - dy * sin(phi)).toFloat()
            val ry = cy + (dx * sin(phi) + dy * cos(phi)).toFloat()
            if (rx in 5f..(W - 5f) && ry in 5f..(H - 5f)) DetectedStar(rx, ry, it.brightness) else null
        }
        assertTrue("旋转后可用星过少: ${rotated.size}", rotated.size >= 6)
        val res = LocalStarMatcher.match(rotated, W, H)
        assertNotNull("旋转后匹配失败", res)
        res!!
        assertClose(region.raDeg, res.solve.raDeg, 1.0, "旋转 RA")
        assertClose(region.decDeg, res.solve.decDeg, 1.0, "旋转 Dec")
        val expectedPs = region.fovDeg * 3600.0 / W
        assertClose(expectedPs, res.solve.pixScaleArcsec, expectedPs * 0.08, "旋转 pixscale")
    }

    @Test
    fun matchDetectsMirrorParity() {
        val region = DemoSolver.regions[2] // 仙后座
        val gray = renderStarField(region, noise = 1f)
        val detected = LocalStarMatcher.detectStarsGray(W, H, gray)

        // 镜像翻转 x
        val mirrored = detected.map { DetectedStar(W - 1 - it.x, it.y, it.brightness) }
        val res = LocalStarMatcher.match(mirrored, W, H)
        assertNotNull("镜像后匹配失败", res)
        res!!
        assertEquals("镜像应识别为 parity=-1", -1, res.solve.parity)
        assertClose(region.raDeg, res.solve.raDeg, 1.0, "镜像 RA")
        assertClose(region.decDeg, res.solve.decDeg, 1.0, "镜像 Dec")

        // 正常照片应识别为 parity=+1
        val normal = LocalStarMatcher.match(detected, W, H)
        assertNotNull("正常匹配失败", normal)
        assertEquals("正常应识别为 parity=+1", 1, normal!!.solve.parity)
    }

    @Test
    fun noMatchOnRandomPoints() {
        val rnd = Random(7)
        val pts = (0 until 20).map {
            DetectedStar(rnd.nextFloat() * W, rnd.nextFloat() * H, 100f + rnd.nextFloat() * 60f)
        }
        assertNull("随机点不应匹配成功", LocalStarMatcher.match(pts, W, H))
    }

    @Test
    fun fitSimilarityRecoversTransform() {
        // 用已知相似变换生成对应点，验证拟合能还原参数
        val rnd = Random(5)
        val a = -0.028
        val b = 0.0
        val tx = 0.7
        val ty = -0.3
        val n = 12
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        val Xs = FloatArray(n)
        val Ys = FloatArray(n)
        for (i in 0 until n) {
            val x = (rnd.nextFloat() - 0.5f) * 800f
            val y = (rnd.nextFloat() - 0.5f) * 800f
            xs[i] = x
            ys[i] = y
            Xs[i] = (a * x - b * y + tx).toFloat()
            Ys[i] = (b * x + a * y + ty).toFloat()
        }
        val fit = LocalStarMatcher.fitSimilarity(xs, ys, Xs, Ys)
        assertNotNull("拟合不应失败", fit)
        fit!!
        assertEquals(a, fit[0], 1e-6, "a")
        assertEquals(b, fit[1], 1e-6, "b")
        assertEquals(tx, fit[2], 1e-6, "tx")
        assertEquals(ty, fit[3], 1e-6, "ty")
    }

    @Test
    fun fitSimilarityWithRotation() {
        val rnd = Random(6)
        val s = 0.03
        val theta = 0.6 // 弧度
        val a = s * Math.cos(theta)
        val b = s * Math.sin(theta)
        val tx = 1.2
        val ty = -0.8
        val n = 15
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        val Xs = FloatArray(n)
        val Ys = FloatArray(n)
        for (i in 0 until n) {
            val x = (rnd.nextFloat() - 0.5f) * 600f
            val y = (rnd.nextFloat() - 0.5f) * 600f
            xs[i] = x
            ys[i] = y
            Xs[i] = (a * x - b * y + tx).toFloat()
            Ys[i] = (b * x + a * y + ty).toFloat()
        }
        val fit = LocalStarMatcher.fitSimilarity(xs, ys, Xs, Ys)
        assertNotNull(fit)
        fit!!
        assertEquals(a, fit[0], 1e-6, "a")
        assertEquals(b, fit[1], 1e-6, "b")
        assertEquals(tx, fit[2], 1e-6, "tx")
        assertEquals(ty, fit[3], 1e-6, "ty")
    }

    @Test
    fun fitSimilarityOrionData() {
        // 猎户座 4 星的真实数据（x',y' 像素偏移；X,Y 切平面坐标，度）
        // 由 tanXY 相对原点 (83.25, -3.29) 手算
        val xs = floatArrayOf(174.16f, -186.53f, 77.34f, -20.18f)
        val ys = floatArrayOf(456.35f, -104.34f, -65.97f, 201.40f)
        val Xs = floatArrayOf(-4.597f, 5.61f, -1.988f, 0.800f)
        val Ys = floatArrayOf(-4.956f, 10.86f, 9.73f, 2.093f)
        val fit = LocalStarMatcher.fitSimilarity(xs, ys, Xs, Ys)
        assertNotNull("拟合失败", fit)
        fit!!
        // 期望：a≈-0.0282（比例尺 34°/1200px），b≈0，tx≈0.31，ty≈7.87
        assertEquals(-0.0282, fit[0], 0.002, "a")
        assertEquals(0.0, fit[1], 0.002, "b")
        assertEquals(0.31, fit[2], 0.1, "tx")
        assertEquals(7.87, fit[3], 0.1, "ty")
    }

    private fun assertEquals(expected: Double, actual: Double, tol: Double, msg: String) {
        assertTrue("$msg: 期望 $expected 实际 $actual", abs(expected - actual) < tol)
    }

    @Test
    fun detectStarsOnEmptyImage() {
        val gray = FloatArray(W * H) { 10f }
        val detected = LocalStarMatcher.detectStarsGray(W, H, gray)
        assertTrue("纯背景图不应检测到星点", detected.isEmpty())
    }
}
