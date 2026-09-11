package com.starcam.astro

import com.starcam.astro.astro.ExifPriorsReader
import com.starcam.astro.astro.SkyEphemeris
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 天顶星象计算与天区先验参数的纯 JVM 验证 */
class SkyEphemerisTest {

    @Test
    fun gmstAtJ2000IsReferenceValue() {
        // J2000.0（2000-01-01 12:00 TT，JD=2451545.0）的 GMST 为公式基准值
        val jd = 2451545.0
        val g = SkyEphemeris.gmstDeg(jd)
        assertEquals(280.46061837, g, 1e-4)
        assertTrue(g in 0.0..360.0)
    }

    @Test
    fun gmstAdvancesOneSiderealDayPer24h() {
        // 一天前/后 GMST 相差一个恒星日（360.985647°），mod 360 后 ≈ 0.985647°
        val jd = 2451545.0
        val today = SkyEphemeris.gmstDeg(jd)
        val tomorrow = SkyEphemeris.gmstDeg(jd + 1.0)
        val diff = (tomorrow - today + 360.0) % 360.0
        assertEquals(0.98564736629, diff, 1e-5)
    }

    @Test
    fun gmstWrapsInto0To360() {
        // 远离 J2000 也必须在 [0,360)
        for (jd in listOf(2440587.5, 2451545.0, 2460000.5, 2451545.0 + 1000.0)) {
            val g = SkyEphemeris.gmstDeg(jd)
            assertTrue("jd=$jd gmst=$g", g >= 0.0 && g < 360.0)
        }
    }

    @Test
    fun zenithDecEqualsLatitude() {
        // 天顶赤纬 ≡ 观测地纬度
        val (ra, dec) = SkyEphemeris.zenithRaDec(39.9042, 116.4074, 1700000000L)
        assertEquals(39.9042, dec, 1e-9)
        assertTrue(ra >= 0.0 && ra < 360.0)
    }

    @Test
    fun zenithRaFollowsLstAcrossMidnight() {
        // 同一地点两天同一 UTC 时刻，天顶 RA 前进约 0.986°（恒星日）
        val (ra1, _) = SkyEphemeris.zenithRaDec(30.0, 120.0, 1700000000L)
        val (ra2, _) = SkyEphemeris.zenithRaDec(30.0, 120.0, 1700000000L + 86400)
        val diff = (ra2 - ra1 + 360.0) % 360.0
        assertEquals(0.98564736629, diff, 1e-5)
    }

    @Test
    fun angularSeparationKnownCases() {
        // 赤道两点相距 90°；同一星相距 0°
        assertEquals(90.0, SkyEphemeris.angularSeparationDeg(0.0, 0.0, 90.0, 0.0), 1e-6)
        assertEquals(0.0, SkyEphemeris.angularSeparationDeg(10.0, 20.0, 10.0, 20.0), 1e-9)
        // (0,0) 到 (0,90)（天北极）为 90°
        assertEquals(90.0, SkyEphemeris.angularSeparationDeg(0.0, 0.0, 0.0, 90.0), 1e-6)
    }

    @Test
    fun skyPriorRadiusClamped() {
        // 半径按 视场半宽+45° 计算并限制在 60°~85°：广角给足覆盖，窄场也受限
        assertEquals(60.0, ExifPriorsReader.skyPriorRadiusDeg(2.0), 1e-9)
        assertEquals(75.0, ExifPriorsReader.skyPriorRadiusDeg(60.0), 1e-9)
        assertEquals(85.0, ExifPriorsReader.skyPriorRadiusDeg(120.0), 1e-9)
        // fov 未知按 45° 估算：45/2+45 = 67.5°
        assertEquals(67.5, ExifPriorsReader.skyPriorRadiusDeg(null), 1e-9)
    }

    @Test
    fun altAzZenithMatchesZenithRaDec() {
        val lat = 39.9042
        val lon = 116.4074
        val time = 1700000000L
        val (expectedRa, expectedDec) = SkyEphemeris.zenithRaDec(lat, lon, time)

        // 任意方位角的高度角 90° 均为天顶
        for (az in listOf(0.0, 90.0, 180.0, 270.0)) {
            val (ra, dec) = SkyEphemeris.altAzToRaDec(90.0, az, lat, lon, time)
            assertEquals("天顶赤纬等于纬度", expectedDec, dec, 1e-6)
            assertEquals("天顶赤经等于LST", expectedRa, ra, 1e-6)
        }
    }

    @Test
    fun altAzNorthCelestialPole() {
        // 在北纬 45°，朝正北仰角 45° 即为天北极（Dec = +90°）
        val lat = 45.0
        val lon = 120.0
        val time = 1700000000L
        val (_, dec) = SkyEphemeris.altAzToRaDec(45.0, 0.0, lat, lon, time)
        assertEquals("指向北天极赤纬应为90°", 90.0, dec, 1e-6)
    }

    @Test
    fun altAzSouthMeridianTransit() {
        // 在北纬 30°，朝正南仰角 50° 过中天：Dec = 50 + 30 - 90 = -10°，时角 H=0 故 RA=LST
        val lat = 30.0
        val lon = 100.0
        val time = 1700000000L
        val (zenithRa, _) = SkyEphemeris.zenithRaDec(lat, lon, time)
        val (ra, dec) = SkyEphemeris.altAzToRaDec(50.0, 180.0, lat, lon, time)
        assertEquals("正南中天赤纬", -10.0, dec, 1e-6)
        assertEquals("正南中天赤经等于LST", zenithRa, ra, 1e-6)
    }

    @Test
    fun altAzEastHorizonOnEquator() {
        // 赤道上（Lat=0°），正东地平线（Alt=0°, Az=90°）：Dec=0°，H=270°（-90°）故 RA=(LST+90°) mod 360
        val lat = 0.0
        val lon = 0.0
        val time = 1700000000L
        val (zenithRa, _) = SkyEphemeris.zenithRaDec(lat, lon, time)
        val (ra, dec) = SkyEphemeris.altAzToRaDec(0.0, 90.0, lat, lon, time)
        assertEquals("赤道正东地平线赤纬为0°", 0.0, dec, 1e-6)
        val expectedRa = (zenithRa + 90.0) % 360.0
        assertEquals("正东地平线赤经", expectedRa, ra, 1e-6)
    }

    @Test
    fun altAzNegativeAltitudeIsValid() {
        // §0.53 全天星空：负仰角（朝下）也能算 RA/Dec——地平线以下星空需要
        val lat = 30.0
        val lon = 100.0
        val time = 1700000000L
        // 正南 alt=-20°（南中天以下 20°，非退化点）：dec = alt + lat - 90 = -80°，
        // 中天时角 H=0 → RA = LST
        val (ra, dec) = SkyEphemeris.altAzToRaDec(-20.0, 180.0, lat, lon, time)
        assertTrue("负仰角赤经应在 0..360: $ra", ra in 0.0..360.0)
        assertTrue("负仰角赤纬应在 -90..90: $dec", dec in -90.0..90.0)
        assertEquals(-80.0, dec, 1e-6)
        val (zenithRa, _) = SkyEphemeris.zenithRaDec(lat, lon, time)
        assertEquals(zenithRa, ra, 1e-6)
    }
}