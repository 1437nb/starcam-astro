package com.starcam.astro

import com.starcam.astro.astro.SolarSystemCatalog
import com.starcam.astro.astro.SolarSystemEphemeris
import com.starcam.astro.astro.SolarSystemEphemeris.SolarBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

/**
 * 太阳系历表验证（§0.58）。
 *
 * 校验基准取自 in-the-sky.org 公布的星历（其后端使用 JPL DE430 星历），
 * 以及 JPL《Keplerian Elements for Approximate Positions of the Major Planets》
 * 的根数定义。部分基准为站点给定的精确整时刻，容差按各天体理论精度设定。
 */
class SolarSystemEphemerisTest {

    @Before
    fun reset() {
        SolarSystemEphemeris.clearCache()
    }

    // ── 与公开星历逐项对照 ────────────────────────────────────────

    @Test
    fun jupiterMatchesPublishedEphemeris() {
        // in-the-sky.org 星历表：2026-09-10 04:00 UTC（00:00 EDT）
        // 木星 RA 09h11m23s = 137.84583°，Dec +16°47'34" = +16.79278°（J2000）
        val jd = SolarSystemEphemeris.julianDay(2026, 9, 10, 4.0)
        val p = SolarSystemEphemeris.geocentric(SolarBody.JUPITER, jd)
        val dRa = angularDelta(p.raDeg, 137.84583)
        val dDec = abs(p.decDeg - 16.79278)
        assertTrue(
            "木星 RA=${p.raDeg}°（期望 137.84583°，差 $dRa°）Dec=${p.decDeg}°（期望 16.79278°，差 $dDec°）",
            dRa < 0.10 && dDec < 0.10,
        )
    }

    @Test
    fun saturnMatchesPublishedEphemeris() {
        // in-the-sky.org 天体页（计算于 2026-09-11）：
        // 土星 RA 00h50m ≈ 12.7364°，Dec +02°33' ≈ +2.5514°
        val jd = SolarSystemEphemeris.julianDay(2026, 9, 11, 0.0)
        val p = SolarSystemEphemeris.geocentric(SolarBody.SATURN, jd)
        val dRa = angularDelta(p.raDeg, 12.7364)
        val dDec = abs(p.decDeg - 2.5514)
        assertTrue(
            "土星 RA=${p.raDeg}°（期望 12.7364°，差 $dRa°）Dec=${p.decDeg}°（期望 2.5514°，差 $dDec°）",
            dRa < 0.12 && dDec < 0.12,
        )
        // 同一页给出的距离 8.52 AU 与视直径 19.5″
        assertEquals("土星地心距", 8.52, p.distanceAu, 0.10)
        assertEquals("土星视直径(度)", 19.5 / 3600.0, p.angularDiameterDeg, 0.0015)
    }

    @Test
    fun sunMatchesPublishedPosition() {
        // in-the-sky.org 2026-09-09 太阳 RA 11h11m36s ≈ 167.9017°，Dec +5°11'31" ≈ +5.1919°
        // （该页未标注精确时区，故容差放到 0.5°；太阳每日移动约 1°）
        val jd = SolarSystemEphemeris.julianDay(2026, 9, 9, 12.0)
        val p = SolarSystemEphemeris.geocentric(SolarBody.SUN, jd)
        val dRa = angularDelta(p.raDeg, 167.9017)
        val dDec = abs(p.decDeg - 5.1919)
        assertTrue(
            "太阳 RA=${p.raDeg}°（期望 ≈167.90°）Dec=${p.decDeg}°（期望 ≈+5.19°）",
            dRa < 0.5 && dDec < 0.5,
        )
    }

    @Test
    fun moonMatchesOccultationInstantPosition() {
        // in-the-sky.org 月掩木星事件：最近时刻 2026-09-08 18:44 UTC，
        // 该时刻月亮 J2000 位置 RA 09h11m20s = 137.83333°，Dec +17°35' = +17.58333°，
        // 且与木星相距 46.4′（两者数据自洽，可作为月亮定位的硬基准）。
        val jd = SolarSystemEphemeris.julianDay(2026, 9, 8, 18.0) + 44.0 / 1440.0
        val moon = SolarSystemEphemeris.geocentric(SolarBody.MOON, jd)
        val dRa = angularDelta(moon.raDeg, 137.83333) * kotlin.math.cos(Math.toRadians(17.58333))
        val dDec = abs(moon.decDeg - 17.58333)
        val sep = kotlin.math.sqrt(dRa * dRa + dDec * dDec)
        assertTrue(
            "月亮 RA=${moon.raDeg}°（基准 137.83333°）Dec=${moon.decDeg}°（基准 17.58333°）" +
                "，偏差 $sep°",
            sep < 0.20,
        )
        // 同刻木星应落在月亮 46.4′ 附近（两者共用同一参考系，交叉验证）
        val jup = SolarSystemEphemeris.geocentric(SolarBody.JUPITER, jd)
        val pairSep = com.starcam.astro.astro.SkyEphemeris.angularSeparationDeg(
            moon.raDeg, moon.decDeg, jup.raDeg, jup.decDeg,
        )
        assertTrue("月木相距 ${pairSep}° 应在 46.4′≈0.77° 附近（±0.4°）", abs(pairSep - 0.773) < 0.4)
    }

    @Test
    fun moonFirstQuarterElongationIsNinetyDegrees() {
        // in-the-sky.org：上弦月 2026-09-19 05:44 JST = 2026-09-18 20:44 UTC
        // 上弦定义即日月黄经差 90°
        val jd = SolarSystemEphemeris.julianDay(2026, 9, 18, 20.0) + 44.0 / 1440.0
        val moon = SolarSystemEphemeris.geocentric(SolarBody.MOON, jd)
        println("上弦时刻距角 = ${moon.elongationDeg}°（基准 90°）")
        assertEquals("上弦月距角", 90.0, moon.elongationDeg, 1.5)
    }

    @Test
    fun moonNewMoonHasNearZeroElongation() {
        // in-the-sky.org：新月 2026-09-11
        val jd = SolarSystemEphemeris.julianDay(2026, 9, 11, 12.0)
        val moon = SolarSystemEphemeris.geocentric(SolarBody.MOON, jd)
        assertTrue("新月当日距角应很小，实得 ${moon.elongationDeg}°", moon.elongationDeg < 12.0)
    }

    // ── 开普勒求解 ────────────────────────────────────────────────

    @Test
    fun keplerSolverIsExactForCircularOrbit() {
        // e = 0 时 E ≡ M。注意输入会先被归一到 (−180,180]（牛顿迭代需要），
        // 故 250° 应得 −110°，这是刻意行为而非误差。
        val cases = listOf(
            0.0 to 0.0,
            30.0 to 30.0,
            90.0 to 90.0,
            179.0 to 179.0,
            -120.0 to -120.0,
            250.0 to -110.0,
            360.0 to 0.0,
        )
        for ((m, expected) in cases) {
            assertEquals("M=$m", expected, SolarSystemEphemeris.solveKepler(m, 0.0), 1e-9)
        }
    }

    @Test
    fun keplerSolverConvergesForHighEccentricity() {
        // 哈雷量级偏心率 e=0.9 也应收敛（留出迭代余量）
        val e = 0.9
        for (m in listOf(1.0, 45.0, 120.0, 175.0, -90.0)) {
            val ea = SolarSystemEphemeris.solveKepler(m, e)
            val mr = Math.toRadians(m)
            val residual = mr - (Math.toRadians(ea) - e * kotlin.math.sin(Math.toRadians(ea)))
            assertTrue("M=$m 残差 $residual", abs(residual) < 1e-9)
        }
    }

    @Test
    fun keplerSolutionObeysDomainConstraints() {
        // 椭圆轨道偏近点角必须与平近点角同号且 |E| 略大于 |M|
        val e = 0.2056 // 水星
        for (m in listOf(10.0, 60.0, 150.0, -40.0)) {
            val ea = SolarSystemEphemeris.solveKepler(m, e)
            assertTrue("M=$m E=$ea 应为同号", (m > 0) == (ea > 0))
            assertTrue("E 必须大于 M（e>0）", abs(ea) >= abs(m) - 1e-9)
        }
    }

    // ── 轨道力学一致性 ────────────────────────────────────────────

    @Test
    fun heliocentricPositionRepeatsAfterOneOrbitalPeriod() {
        // 走完一个完整公转周期后，日心位置必须回到原处（验证 Ldot 与整套开普勒机制）
        val jd0 = SolarSystemEphemeris.julianDay(2026, 1, 1, 0.0)
        val periods = mapOf(
            SolarBody.MERCURY to 87.969,
            SolarBody.VENUS to 224.701,
            SolarBody.MARS to 686.980,
            SolarBody.JUPITER to 4332.589,
            SolarBody.SATURN to 10759.22,
        )
        for ((body, period) in periods) {
            val a = SolarSystemEphemeris.heliocentricEcliptic(body, jd0)
            val b = SolarSystemEphemeris.heliocentricEcliptic(body, jd0 + period)
            val lonA = Math.toDegrees(kotlin.math.atan2(a[1], a[0]))
            val lonB = Math.toDegrees(kotlin.math.atan2(b[1], b[0]))
            val dLon = abs(((lonB - lonA + 540.0) % 360.0) - 180.0)
            assertTrue("$body 一个周期后黄经漂移 $dLon°（应 <2°）", dLon < 2.0)

            val rA = kotlin.math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
            val rB = kotlin.math.sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2])
            assertEquals("$body 一个周期后日心距漂移", rA, rB, 0.01)
        }
    }

    @Test
    fun heliocentricDistanceStaysWithinOrbitalBounds() {
        // 采样一年，日心距必须落在 a(1−e) ~ a(1+e) 区间（容 3% 松弛）
        val bounds = mapOf(
            SolarBody.MERCURY to (0.38709927 to 0.20563593),
            SolarBody.VENUS to (0.72333566 to 0.00677672),
            SolarBody.MARS to (1.52371034 to 0.09339410),
            SolarBody.JUPITER to (5.20288700 to 0.04838624),
            SolarBody.SATURN to (9.53667594 to 0.05386179),
            SolarBody.URANUS to (19.18916464 to 0.04725744),
            SolarBody.NEPTUNE to (30.06992276 to 0.00859048),
        )
        val jd0 = SolarSystemEphemeris.julianDay(2026, 1, 1, 0.0)
        for ((body, ae) in bounds) {
            val (a, e) = ae
            val rMin = a * (1 - e)
            val rMax = a * (1 + e)
            for (k in 0..36) {
                val v = SolarSystemEphemeris.heliocentricEcliptic(body, jd0 + k * 10.0)
                val r = kotlin.math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
                assertTrue(
                    "$body 第 $k 采样 r=$r 越界（$rMin ~ $rMax）",
                    r >= rMin * 0.97 && r <= rMax * 1.03,
                )
            }
        }
    }

    // ── 月亮专项 ──────────────────────────────────────────────────

    @Test
    fun moonDistanceAndLatitudeWithinKnownBounds() {
        // 地心距 356,400 ~ 406,700 km；黄纬 |β| ≤ 5.3°（由赤纬与黄赤交角间接约束）
        val jd0 = SolarSystemEphemeris.julianDay(2026, 1, 1, 0.0)
        var minKm = Double.MAX_VALUE
        var maxKm = -Double.MAX_VALUE
        for (k in 0..120) {
            val m = SolarSystemEphemeris.geocentric(SolarBody.MOON, jd0 + k)
            val km = m.moonDistanceKm
            minKm = minOf(minKm, km)
            maxKm = maxOf(maxKm, km)
            assertTrue("月亮赤纬越界 ${m.decDeg}", abs(m.decDeg) <= 28.8)
            assertTrue("月相应在 0..1：${m.phase}", m.phase in 0.0..1.0)
        }
        assertTrue("月地距离下界 $minKm km", minKm > 350_000 && minKm < 370_000)
        assertTrue("月地距离上界 $maxKm km", maxKm > 395_000 && maxKm < 410_000)
    }

    @Test
    fun moonAngularDiameterMatchesNakedEyeRange() {
        // 视直径 29.3′ ~ 34.1′（近地点/远地点极值）
        val jd0 = SolarSystemEphemeris.julianDay(2026, 1, 1, 0.0)
        var minD = Double.MAX_VALUE
        var maxD = -Double.MAX_VALUE
        for (k in 0..120) {
            val d = SolarSystemEphemeris.geocentric(SolarBody.MOON, jd0 + k).angularDiameterDeg * 60.0
            minD = minOf(minD, d); maxD = maxOf(maxD, d)
        }
        assertTrue("月面视直径下限 $minD′ 应 ≈29.3′", minD > 28.5 && minD < 30.5)
        assertTrue("月面视直径上限 $maxD′ 应 ≈34.1′", maxD > 33.0 && maxD < 35.0)
    }

    @Test
    fun moonPhaseTracksElongation() {
        // 距角 180° → 满月（phase≈1）；距角 0° → 新月（phase≈0）
        val jd0 = SolarSystemEphemeris.julianDay(2026, 1, 1, 0.0)
        for (k in 0..60) {
            val m = SolarSystemEphemeris.geocentric(SolarBody.MOON, jd0 + k)
            // 相位与距角应单调对应：phase ≈ (1 − cos(elong))/2
            val expected = (1.0 - kotlin.math.cos(Math.toRadians(m.elongationDeg))) / 2.0
            assertEquals("第 $k 天月相", expected, m.phase, 1e-6)
        }
    }

    @Test
    fun topocentricParallaxShiftsMoonByAboutOneDegree() {
        // 月亮地平视差 ~57′，站心与地心位置最多相差 ~1.0°
        val jd = SolarSystemEphemeris.julianDay(2026, 9, 10, 16.0)
        val lat = 23.13 // 广州
        val lon = 113.26
        val lst = com.starcam.astro.astro.SkyEphemeris.lstDeg(jd, lon)
        val geo = SolarSystemEphemeris.geocentric(SolarBody.MOON, jd)
        val topo = SolarSystemEphemeris.topocentric(geo, lat, lst)
        val shift = angularDelta(geo.raDeg, topo.raDeg)
        val shiftDec = abs(geo.decDeg - topo.decDeg)
        val total = kotlin.math.sqrt(shift * shift + shiftDec * shiftDec)
        assertTrue("月亮站心视差位移 $total° 应在 0.3°~1.1°", total in 0.3..1.1)
    }

    @Test
    fun topocentricCorrectionIsLargerForMoonThanForSun() {
        // 月亮视差远大于太阳（太阳视差仅 ~8.8″）
        val jd = SolarSystemEphemeris.julianDay(2026, 9, 10, 16.0)
        val lat = 23.13
        val lst = com.starcam.astro.astro.SkyEphemeris.lstDeg(jd, 113.26)
        val moonGeo = SolarSystemEphemeris.geocentric(SolarBody.MOON, jd)
        val sunGeo = SolarSystemEphemeris.geocentric(SolarBody.SUN, jd)
        val moonShift = angularDelta(moonGeo.raDeg, SolarSystemEphemeris.topocentric(moonGeo, lat, lst).raDeg)
        val sunShift = angularDelta(sunGeo.raDeg, SolarSystemEphemeris.topocentric(sunGeo, lat, lst).raDeg)
        assertTrue("月亮视差 $moonShift° 应远大于太阳视差 $sunShift°", moonShift > sunShift * 20)
        assertTrue("太阳视差应小于 0.01°", sunShift < 0.01)
    }

    // ── 太阳与行星几何约束 ────────────────────────────────────────

    @Test
    fun sunDeclinationReachesObliquityAtSolstices() {
        // 夏至前后太阳赤纬 ≈ +23.44°，冬至 ≈ −23.44°（2026 年夏至 6-21、冬至 12-21）
        val summer = SolarSystemEphemeris.julianDay(2026, 6, 21, 12.0)
        val winter = SolarSystemEphemeris.julianDay(2026, 12, 21, 12.0)
        val s = SolarSystemEphemeris.geocentric(SolarBody.SUN, summer).decDeg
        val w = SolarSystemEphemeris.geocentric(SolarBody.SUN, winter).decDeg
        assertTrue("夏至太阳赤纬 $s 应接近 +23.44°", abs(s - 23.44) < 0.4)
        assertTrue("冬至太阳赤纬 $w 应接近 −23.44°", abs(w + 23.44) < 0.4)
    }

    @Test
    fun sunDeclinationIsZeroAtEquinoxes() {
        // 春分（3-20）与秋分（9-23）前后太阳赤纬过零
        val spring = SolarSystemEphemeris.julianDay(2026, 3, 20, 12.0)
        val autumn = SolarSystemEphemeris.julianDay(2026, 9, 23, 12.0)
        assertTrue("春分太阳赤纬应接近 0", abs(SolarSystemEphemeris.geocentric(SolarBody.SUN, spring).decDeg) < 0.6)
        assertTrue("秋分太阳赤纬应接近 0", abs(SolarSystemEphemeris.geocentric(SolarBody.SUN, autumn).decDeg) < 0.6)
    }

    @Test
    fun innerPlanetElongationIsBounded() {
        // 水星最大距角 ~28°，金星 ~47°；外行星可达 180°
        val jd0 = SolarSystemEphemeris.julianDay(2026, 1, 1, 0.0)
        var maxMercury = 0.0
        var maxVenus = 0.0
        for (k in 0..400) {
            maxMercury = maxOf(maxMercury, SolarSystemEphemeris.geocentric(SolarBody.MERCURY, jd0 + k).elongationDeg)
            maxVenus = maxOf(maxVenus, SolarSystemEphemeris.geocentric(SolarBody.VENUS, jd0 + k).elongationDeg)
        }
        assertTrue("水星最大距角 $maxMercury° 应 ≤30°", maxMercury < 30.0)
        assertTrue("金星最大距角 $maxVenus° 应 ≤48°", maxVenus < 48.0)
        assertTrue("金星最大距角应显著大于水星", maxVenus > maxMercury)
    }

    @Test
    fun outerPlanetMagnitudeIsNegativeAndReasonable() {
        // 木星/土星常年为负星等（是全天最醒目的"星"）
        val jd = SolarSystemEphemeris.julianDay(2026, 9, 10, 4.0)
        val jup = SolarSystemEphemeris.geocentric(SolarBody.JUPITER, jd)
        val sat = SolarSystemEphemeris.geocentric(SolarBody.SATURN, jd)
        assertTrue("木星星等 ${jup.magnitude} 应接近 −2", jup.magnitude in -2.6..-1.4)
        assertTrue("土星星等 ${sat.magnitude} 应在 0 附近", sat.magnitude in -0.5..1.0)
    }

    @Test
    fun moonMagnitudePeaksAtFullMoonAndDimsTowardNew() {
        // 月亮星等随月相变化极大：满月约 −12.7，新月附近仅 −4 左右。
        // 扫描一个朔望月，最亮时刻必须落在满月附近。
        val jd0 = SolarSystemEphemeris.julianDay(2026, 9, 1, 0.0)
        var brightest: SolarSystemEphemeris.SolarPosition? = null
        var faintest: SolarSystemEphemeris.SolarPosition? = null
        for (k in 0..30) {
            val m = SolarSystemEphemeris.geocentric(SolarBody.MOON, jd0 + k)
            if (brightest == null || m.magnitude < brightest.magnitude) brightest = m
            if (faintest == null || m.magnitude > faintest.magnitude) faintest = m
        }
        val b = brightest!!
        val f = faintest!!
        assertTrue("最亮月相应接近满月，实测距角 ${b.elongationDeg}°", b.elongationDeg > 150.0)
        assertTrue("满月比例应 >0.95，实测 ${b.phase}", b.phase > 0.95)
        assertTrue("满月星等应 ≈ −12.7，实测 ${b.magnitude}", b.magnitude < -12.0)
        assertTrue("新月附近应明显变暗，实测 ${f.magnitude}", f.magnitude > -7.0)
        // 一个月内亮度摆幅（星等越小越亮，故摆幅 = 最暗 − 最亮）
        val swing = f.magnitude - b.magnitude
        assertTrue("月相亮度摆幅应 >5 等，实测 ${"%.1f".format(swing)}（满月 ${b.magnitude} / 最暗 ${f.magnitude}）", swing > 5.0)
    }

    @Test
    fun sunIsBrightestObjectInTheSky() {
        val jd = SolarSystemEphemeris.julianDay(2026, 9, 26, 12.0) // 满月前后
        val all = SolarSystemEphemeris.geocentricPositions(jd)
        val sun = all.first { it.body == SolarBody.SUN }
        assertEquals("太阳视星等", -26.74, sun.magnitude, 1e-6)
        assertTrue(
            "太阳应比所有天体都亮",
            all.filter { it.body != SolarBody.SUN }.all { it.magnitude > sun.magnitude },
        )
    }

    // ── 鲁棒性与输出契约 ──────────────────────────────────────────

    @Test
    fun allBodiesReturnValidCoordinatesOverAYear() {
        // 全年逐旬扫描：RA ∈ [0,360)、Dec ∈ [−90,90]、距离 > 0、相位 ∈ [0,1]
        val jd0 = SolarSystemEphemeris.julianDay(2026, 1, 1, 0.0)
        for (k in 0..36) {
            for (p in SolarSystemEphemeris.geocentricPositions(jd0 + k * 10.0)) {
                assertTrue("${p.body} RA=${p.raDeg}", p.raDeg >= 0.0 && p.raDeg < 360.0)
                assertTrue("${p.body} Dec=${p.decDeg}", p.decDeg >= -90.0 && p.decDeg <= 90.0)
                assertTrue("${p.body} 距离=${p.distanceAu}", p.distanceAu > 0.0)
                assertTrue("${p.body} 视直径=${p.angularDiameterDeg}", p.angularDiameterDeg > 0.0)
                assertTrue("${p.body} 相位=${p.phase}", p.phase in 0.0..1.0)
                assertTrue("${p.body} 距角=${p.elongationDeg}", p.elongationDeg in 0.0..180.0)
                assertFalse("${p.body} 星等为 NaN", p.magnitude.isNaN())
            }
        }
    }

    @Test
    fun topocentricPositionsCoverAllBodies() {
        val jd = SolarSystemEphemeris.julianDay(2026, 9, 10, 16.0)
        val list = SolarSystemEphemeris.topocentricPositions(jd, 23.13, 113.26)
        assertEquals("应返回全部 9 个天体", SolarBody.entries.size, list.size)
        assertEquals("顺序应与枚举一致", SolarBody.entries.toList(), list.map { it.body })
    }

    @Test
    fun cacheReturnsSameInstanceWithinAgeWindowAndRefreshesAfter() {
        // 30 秒内应命中同一份结果；超过窗口后必须重算
        val t0 = 1789000000L
        val a = SolarSystemEphemeris.cachedTopocentric(t0, 23.13, 113.26)
        val b = SolarSystemEphemeris.cachedTopocentric(t0 + 20, 23.13, 113.26)
        assertTrue("窗口内应复用同一实例", a === b)
        val c = SolarSystemEphemeris.cachedTopocentric(t0 + 120, 23.13, 113.26)
        assertFalse("超出窗口应重算", a === c)
    }

    @Test
    fun catalogCoversEveryBodyInBothLanguages() {
        for (body in SolarBody.entries) {
            val zh = SolarSystemCatalog.name(body, isEnglish = false)
            val en = SolarSystemCatalog.name(body, isEnglish = true)
            assertTrue("$body 中文名缺失", zh.isNotBlank())
            assertTrue("$body 英文名缺失", en.isNotBlank())
            assertTrue("$body 科普条目缺失", SolarSystemCatalog.info.containsKey(body))
            // 颜色必须不透明（alpha=FF）
            assertTrue("$body 颜色应不透明", (SolarSystemCatalog.color(body) ushr 24) == 0xFF)
        }
    }

    @Test
    fun diagnosticTableForManualReview() {
        // 打印一张位置表，便于人工比对（结果在 TEST-*.xml 的 <system-out>）
        val jd = SolarSystemEphemeris.julianDay(2026, 9, 10, 16.0)
        println("=== 太阳系历表 @ 2026-09-10 16:00 UTC（地心 J2000，广州站心修正另列）===")
        for (p in SolarSystemEphemeris.geocentricPositions(jd)) {
            val raH = p.raDeg / 15.0
            val h = raH.toInt()
            val m = ((raH - h) * 60).toInt()
            val s = ((raH - h) * 60 - m) * 60
            val decSign = if (p.decDeg < 0) "-" else "+"
            val decAbs = abs(p.decDeg)
            val dd = decAbs.toInt()
            val dm = ((decAbs - dd) * 60).toInt()
            println(
                "%-9s RA=%2dh%02dm%04.1fs Dec=%s%02d°%02d′ mag=%6.2f Δ=%9.5f AU ⌀=%6.2f′ phase=%.3f elong=%6.2f°"
                    .format(p.body.name, h, m, s, decSign, dd, dm, p.magnitude, p.distanceAu,
                        p.angularDiameterDeg * 60, p.phase, p.elongationDeg),
            )
        }
        assertTrue(true)
    }

    /** RA 环形差（度），处理 0/360 跨越 */
    private fun angularDelta(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }
}
