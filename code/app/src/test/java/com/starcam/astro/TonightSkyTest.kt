package com.starcam.astro

import com.starcam.astro.astro.SolarSystemEphemeris
import com.starcam.astro.astro.TonightSky
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TonightSky] 今日天象业务逻辑单测（纯 JVM，毫秒级执行）。
 */
class TonightSkyTest {

    @Test
    fun compute_newMoon_producesLowIlluminationAndNewMoonSymbol() {
        // 2026-09-11 14:00 UTC 附近为新月
        val epochSec = 1789135200L // 2026-09-11 14:00:00 UTC
        val sky = TonightSky.compute(epochSec)

        assertNotNull(sky.moon)
        assertTrue("新月照度应接近 0%，实测=${sky.moon.illuminationPct}%", sky.moon.illuminationPct <= 5)
        assertEquals("新月 / 朔", sky.moon.phaseNameZh)
        assertEquals("🌑", sky.moon.symbol)
        assertTrue("月龄应接近 0 天或 29 天，实测=${sky.moon.moonAgeDays}", sky.moon.moonAgeDays < 2.5 || sky.moon.moonAgeDays > 27.5)
        assertTrue("无月夜应推荐深空摄影", sky.recommendationZh.contains("深空"))
    }

    @Test
    fun compute_fullMoon_producesHighIlluminationAndFullMoonSymbol() {
        // 2026-09-26 16:49 UTC 附近为满月
        val epochSec = 1790441340L // 2026-09-26 16:49:00 UTC
        val sky = TonightSky.compute(epochSec)

        assertNotNull(sky.moon)
        assertTrue("满月照度应接近 100%，实测=${sky.moon.illuminationPct}%", sky.moon.illuminationPct >= 95)
        assertEquals("满月 / 望", sky.moon.phaseNameZh)
        assertEquals("🌕", sky.moon.symbol)
        assertTrue("满月月龄应在 13~16 天之间，实测=${sky.moon.moonAgeDays}", sky.moon.moonAgeDays in 13.0..16.5)
        assertTrue("满月夜应提示月光强烈", sky.recommendationZh.contains("月光强烈"))
    }

    @Test
    fun compute_visiblePlanets_containsExpectedBrightPlanets() {
        // 2026-10-24 12:00:00 UTC (当前测试基准时刻)
        val epochSec = 1792843200L
        val sky = TonightSky.compute(epochSec)

        assertTrue("应至少检测到可见行星", sky.visiblePlanets.isNotEmpty())
        for (p in sky.visiblePlanets) {
            assertTrue("行星名不应为空", p.nameZh.isNotEmpty())
            assertTrue("观测窗口文案不应为空", p.windowZh.isNotEmpty())
            assertTrue("视星等应在合理区间 (-5.0 ~ 7.0)", p.magnitude in -5.0..7.0)
            assertTrue("距角应在 0° ~ 180°", p.elongationDeg in 0.0..180.0)
        }
    }

    @Test
    fun compute_recommendationAdaptsToMoonIllumination() {
        // 蛾眉月/上弦月附近 (照度约 50%)
        // 2026-09-18 12:00:00 UTC
        val epochSec = 1789732800L
        val sky = TonightSky.compute(epochSec)

        assertTrue(
            "上弦月附近应推荐观测环形山或行星",
            sky.recommendationZh.contains("环形山") || sky.recommendationZh.contains("主要行星"),
        )
    }
}
