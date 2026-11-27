package com.starcam.astro

import com.starcam.astro.astro.DemoSolver
import com.starcam.astro.astro.SkyRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * 离线演示模拟星空生成器回归测试：
 * 覆盖经典星座无放回轮换、指定星座定位、全天球均匀随机采样、以及旋转 WCS 的精确可逆性。
 */
class DemoSolverTest {

    @Test
    fun allClassicRegionsHaveEnoughVisibleStarsAndShortLabels() {
        assertEquals(18, DemoSolver.regions.size)
        for ((index, region) in DemoSolver.regions.withIndex()) {
            val count = DemoSolver.countVisibleStars(DemoSolver.wcsFor(region))
            assertTrue(
                "经典天区「${region.name}」可见亮星过少：$count",
                count >= 8,
            )
            assertTrue(DemoSolver.regionShortLabel(index, isEn = false).isNotBlank())
            assertTrue(DemoSolver.regionShortLabel(index, isEn = true).isNotBlank())
        }
    }

    @Test
    fun classicShuffleBagCoversAllRegionsWithoutRepeatingInCycle() {
        val rnd = Random(20261127)
        val seenNames = HashSet<String>()
        var hasRotation = false
        for (i in 0 until DemoSolver.regions.size) {
            val r = DemoSolver.nextClassicRegion(selectedIndex = null, random = rnd)
            seenNames.add(r.name)
            if (abs(r.rotationDeg) > 0.1) hasRotation = true
        }
        assertEquals(
            "一轮 18 次经典轮换应覆盖全部 18 个天区且不重复",
            DemoSolver.regions.size,
            seenNames.size,
        )
        assertTrue("轮换模式应带有轻微构图倾角变化", hasRotation)
    }

    @Test
    fun classicSelectedIndexReturnsExactCenteredRegion() {
        val orion = DemoSolver.nextClassicRegion(selectedIndex = 0)
        assertEquals(DemoSolver.regions[0], orion)
        assertEquals(0.0, orion.rotationDeg, 1e-9)
    }

    @Test
    fun fullSkyRandomProducesDiverseRichFieldsAndRespectsFovPreset() {
        val rnd = Random(42)
        val samples = List(20) { DemoSolver.randomFullSkyRegion(fovPresetDeg = null, isEn = false, random = rnd) }
        val uniqueCoords = samples.map { "${it.raDeg.toInt()},${it.decDeg.toInt()}" }.toSet()
        assertTrue("20 次全天随机采样应产生高度多样的天区（实际去重=${uniqueCoords.size}）", uniqueCoords.size >= 15)
        for (s in samples) {
            val count = DemoSolver.countVisibleStars(DemoSolver.wcsFor(s))
            assertTrue("全天随机天区 ${s.name} 可见亮星过少：$count", count >= 10)
            assertTrue("视场应在 28°..68° 范围内：${s.fovDeg}", s.fovDeg in 28.0..68.0)
        }

        val fixedFov = DemoSolver.randomFullSkyRegion(fovPresetDeg = 48.0, isEn = true, random = rnd)
        assertEquals(48.0, fixedFov.fovDeg, 1e-9)
        assertTrue(fixedFov.name.startsWith("All-Sky"))
    }

    @Test
    fun rotatedWcsPreservesScaleParityAndRoundTripAccuracy() {
        val region = SkyRegion("RotatedTest", raDeg = 123.4, decDeg = -22.5, fovDeg = 45.0, rotationDeg = 137.5)
        val wcs = DemoSolver.wcsFor(region)
        val baseWcs = DemoSolver.wcsFor(region.copy(rotationDeg = 0.0))

        val detRot = wcs.cd11 * wcs.cd22 - wcs.cd12 * wcs.cd21
        val detBase = baseWcs.cd11 * baseWcs.cd22 - baseWcs.cd12 * baseWcs.cd21
        assertEquals("旋转 WCS 行列式应严格守恒（保比例尺与正像手性）", detBase, detRot, 1e-12)
        assertNotEquals(0.0, wcs.cd12, 1e-6)

        val solve = DemoSolver.solveFor(region)
        assertEquals(137.5, solve.orientationDeg, 1e-9)

        val (fx, fy) = wcs.skyToFitsPixel(130.0, -18.0)
        val (raBack, decBack) = wcs.fitsPixelToSky(fx, fy)
        assertEquals(130.0, raBack, 1e-4)
        assertEquals(-18.0, decBack, 1e-4)
    }
}
