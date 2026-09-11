package com.starcam.astro

import com.starcam.astro.astro.DemoSolver
import com.starcam.astro.astro.StarCatalogData
import com.starcam.astro.astro.StarChartOverlay
import com.starcam.astro.astro.WcsTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 天文数学与星表数据的 JVM 单元测试（不依赖 Android 运行时） */
class AstroMathTest {

    @Test
    fun catalogIntegrity() {
        val stars = StarCatalogData.stars
        assertTrue("星表不应为空", stars.isNotEmpty())

        // 按 HIP 升序且不重复
        for (i in 1 until stars.size) {
            assertTrue("星表应按 HIP 升序（第 $i 项）", stars[i].hip > stars[i - 1].hip)
        }
        assertEquals("HIP 不应重复", stars.size, stars.map { it.hip }.toSet().size)

        // 连线端点必须在星表范围内
        assertTrue("星座连线不应为空", StarCatalogData.constellationLines.isNotEmpty())
        for ((i, seg) in StarCatalogData.constellationLines.withIndex()) {
            assertEquals("连线应有 2 个端点 #$i", 2, seg.size)
            assertTrue(
                "连线端点越界 #$i: ${seg[0]},${seg[1]}",
                seg[0] in stars.indices && seg[1] in stars.indices,
            )
        }

        // 坐标与星等范围
        for (s in stars) {
            assertTrue("RA 应在 0..360: ${s.hip}", s.ra in 0.0..360.0)
            assertTrue("Dec 应在 -90..90: ${s.hip}", s.dec in -90.0..90.0)
            assertFalse("mag 应有效: ${s.hip}", s.mag.isNaN())
        }
    }

    @Test
    fun messierIntegrity() {
        // §0.43c：梅西耶目录坐标/编号/名称有效性
        val objs = com.starcam.astro.astro.MessierCatalog.byNumber
        assertTrue("梅西耶目录不应为空", objs.isNotEmpty())
        assertEquals("M 编号不应重复", objs.size, objs.keys.toSet().size)
        for ((n, o) in objs) {
            assertEquals("key 应与编号一致", n, o.number)
            assertTrue("RA 应在 0..360: M$n", o.ra in 0.0..360.0)
            assertTrue("Dec 应在 -90..90: M$n", o.dec in -90.0..90.0)
            assertTrue("中文名不应为空: M$n", o.zh.isNotBlank())
            assertTrue("类型应合法: M$n", o.type in setOf("G", "N", "PN", "GC", "OC"))
            // §0.43d：防止出现 M38 M38 这类重复标签
            assertFalse("标签不应重复 M 编号: ${o.label}", o.label.matches(Regex("""^M\d+\s+M\d+.*""")))
        }
        assertEquals("M38 标签规范", "M38", objs.getValue(38).label)
        assertEquals("M45 标签规范", "M45 昴星团", objs.getValue(45).label)
    }

    @Test
    fun constellationsEnglishNames() {
        // 测试 88 个星座在英文模式下均返回标准英文/拉丁名称
        val latins = com.starcam.astro.astro.Constellations.LATIN
        assertEquals("全天应有 88 个星座英文名", 88, latins.size)
        assertEquals("Andromeda", com.starcam.astro.astro.Constellations.enName("And"))
        assertEquals("Orion", com.starcam.astro.astro.Constellations.enName("Ori"))
        assertEquals("Ursa Major", com.starcam.astro.astro.Constellations.enName("UMa"))
        assertEquals("Cassiopeia", com.starcam.astro.astro.Constellations.enName("Cas"))
        for ((abbr, en) in latins) {
            assertEquals("缩写 $abbr 英文解析一致", en, com.starcam.astro.astro.Constellations.name(abbr, isEnglish = true))
            assertFalse("英文名不应含中文字符: $en", en.any { it.code in 0x4e00..0x9fff })
        }
    }

    @Test
    fun starNamesEnglish() {
        // 天狼星、织女一、参宿四等亮星在英文模式下应解析出标准英文专名
        assertEquals("Sirius", com.starcam.astro.astro.StarNames.displayName(32349, "", isEnglish = true))
        assertEquals("Vega", com.starcam.astro.astro.StarNames.displayName(91262, "", isEnglish = true))
        assertEquals("Betelgeuse", com.starcam.astro.astro.StarNames.displayName(27989, "", isEnglish = true))
        assertEquals("Polaris", com.starcam.astro.astro.StarNames.displayName(11767, "", isEnglish = true))
        // 中文模式应保留原有中文名
        assertEquals("天狼", com.starcam.astro.astro.StarNames.displayName(32349, "", isEnglish = false))
        assertEquals("织女一", com.starcam.astro.astro.StarNames.displayName(91262, "", isEnglish = false))
        // 英文模式下任何返回均不应含汉字
        val enName = com.starcam.astro.astro.StarNames.displayName(677, "", isEnglish = true)
        assertTrue("应返回英文专名或拜耳标号: $enName", enName == "Alpheratz" || enName == "α And")
        assertFalse("英文模式绝不应包含汉字", enName.any { it.code in 0x4e00..0x9fff })
    }

    @Test
    fun messierEnglishLabels() {
        val objs = com.starcam.astro.astro.MessierCatalog.byNumber
        val m31 = objs.getValue(31)
        assertEquals("M31 英文名", "M31 Andromeda Galaxy", m31.label(isEnglish = true))
        assertEquals("M31 中文名", "M31 仙女座星系", m31.label(isEnglish = false))
        val m45 = objs.getValue(45)
        assertEquals("M45 英文名", "M45 Pleiades", m45.label(isEnglish = true))
        assertEquals("M45 中文名", "M45 昴星团", m45.label(isEnglish = false))
        val m38 = objs.getValue(38)
        assertEquals("M38 无专名时仅显示编号", "M38", m38.label(isEnglish = true))
        assertEquals("M38 无专名时仅显示编号", "M38", m38.label(isEnglish = false))
    }

    @Test
    fun centerMapsToCrpix() {
        val wcs = WcsTransform(100.5, 200.5, 83.0, 5.0, -0.01, 0.0, 0.0, 0.01)
        val p = wcs.skyToFitsPixel(83.0, 5.0)
        assertEquals(100.5, p[0], 1e-9)
        assertEquals(200.5, p[1], 1e-9)
    }

    @Test
    fun rescaledWcsPreservesProjectionWithIndependentAxes() {
        // 非等比缩放 + 非对角 CD：可捕获把 CD 按“行”而不是按像素轴（列）缩放的错误。
        val sourceW = 1200
        val sourceH = 900
        val targetW = 2000
        val targetH = 1000
        val sx = targetW.toDouble() / sourceW
        val sy = targetH.toDouble() / sourceH
        val source = WcsTransform(
            crpix1 = 601.25, crpix2 = 449.75,
            crval1 = 120.0, crval2 = 22.0,
            cd11 = -0.008, cd12 = 0.015,
            cd21 = 0.011, cd22 = 0.006,
        )
        val scaled = source.rescaledFor(sourceW, sourceH, targetW, targetH)

        assertEquals(source.cd11 / sx, scaled.cd11, 1e-12)
        assertEquals(source.cd12 / sy, scaled.cd12, 1e-12)
        assertEquals(source.cd21 / sx, scaled.cd21, 1e-12)
        assertEquals(source.cd22 / sy, scaled.cd22, 1e-12)

        for ((ra, dec) in listOf(120.0 to 22.0, 119.2 to 22.5, 120.8 to 21.6)) {
            val before = source.skyToFitsPixel(ra, dec)
            val after = scaled.skyToFitsPixel(ra, dec)
            assertEquals((before[0] - 1.0) * sx + 1.0, after[0], 1e-6)
            assertEquals((before[1] - 1.0) * sy + 1.0, after[1], 1e-6)
        }
    }

    @Test
    fun starDirectionsMatchConvention() {
        // parity -1（北朝上、东在左的标准星图）：CD = [-s,0;0,s]
        val chart = WcsTransform(10.5, 10.5, 0.0, 0.0, -0.01, 0.0, 0.0, 0.01)
        val east = chart.skyToFitsPixel(0.1, 0.0) // 赤经增大 = 东
        val north = chart.skyToFitsPixel(0.0, 0.1)
        assertTrue("东侧星应在左侧（x 更小）", east[0] < 10.5)
        assertTrue("赤纬相同则 y 应居中", Math.abs(east[1] - 10.5) < 1e-6)
        assertTrue("北侧星应在上方（FITS y 更大）", north[1] > 10.5)

        // parity +1（+X=East）：东侧星应在右侧
        val wcs2 = WcsTransform(10.5, 10.5, 0.0, 0.0, 0.01, 0.0, 0.0, 0.01)
        val east2 = wcs2.skyToFitsPixel(0.1, 0.0)
        assertTrue("parity+1 时东侧星应在右侧", east2[0] > 10.5)
    }

    @Test
    fun fromCalibrationMatrix() {
        // parity +1, θ=0: CD = [s,0;0,s]（3600″/px = 1°/px）
        val w1 = WcsTransform.fromCalibration(10.0, 20.0, 0.0, 3600.0, 1, 4000, 3000)
        assertEquals(1.0, w1.cd11, 1e-12)
        assertEquals(0.0, w1.cd12, 1e-12)
        assertEquals(0.0, w1.cd21, 1e-12)
        assertEquals(1.0, w1.cd22, 1e-12)
        assertEquals(2000.5, w1.crpix1, 1e-9)
        assertEquals(1500.5, w1.crpix2, 1e-9)
        assertTrue("parity+1 行列式应为正", w1.detForTest() > 0)

        // parity -1, θ=0: CD = [-s,0;0,s]
        val w2 = WcsTransform.fromCalibration(10.0, 20.0, 0.0, 3600.0, -1, 4000, 3000)
        assertEquals(-1.0, w2.cd11, 1e-12)
        assertEquals(1.0, w2.cd22, 1e-12)
        assertTrue("parity-1 行列式应为负", w2.detForTest() < 0)
    }

    @Test
    fun roundTripDemoRegion() {
        for (region in DemoSolver.regions) {
            val wcs = DemoSolver.wcsFor(region)
            // 中心 → 参考像素
            val p = wcs.skyToFitsPixel(region.raDeg, region.decDeg)
            assertEquals(wcs.crpix1, p[0], 1e-6)
            assertEquals(wcs.crpix2, p[1], 1e-6)

            // 视场角边缘的星应落在图像附近。2° 赤经在赤纬 dec 处对应的
            // 东西向角距离为 2°·cos(dec)，投影像素数 ≈ 2·cos(dec)/(fov/W)。
            val w = 1200
            val expected = 2.0 * Math.cos(region.decDeg * Math.PI / 180.0) / region.fovDeg * w
            val offset = wcs.skyToFitsPixel(region.raDeg + 2.0, region.decDeg)
            assertTrue(
                "偏移 2° 后应离开中心约 ${"%.1f".format(expected)} px，实际 ${Math.abs(offset[0] - wcs.crpix1)}",
                Math.abs(offset[0] - wcs.crpix1) > expected * 0.9,
            )
        }
    }

    @Test
    fun demoRegionsHaveVisibleStars() {
        // 每个演示天区在 1200x1600 画面内至少应有若干颗可见亮星
        for (region in DemoSolver.regions) {
            val wcs = DemoSolver.wcsFor(region)
            val stars = StarChartOverlay.projectStars(wcs, 1200, 1600)
            val visible = stars.count { it.visible }
            assertTrue("天区「${region.name}」可见星过少: $visible", visible >= 5)
        }
    }
}

/** 测试辅助：暴露行列式 */
private fun WcsTransform.detForTest(): Double = cd11 * cd22 - cd12 * cd21
