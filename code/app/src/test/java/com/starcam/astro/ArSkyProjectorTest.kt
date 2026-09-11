package com.starcam.astro

import com.starcam.astro.astro.ArSkyProjector
import com.starcam.astro.astro.SolarSystemEphemeris
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.tan

/**
 * §0.49 AR 实时星图投影引擎的纯 JVM 验证。
 * 构造已知的相机三轴基（东北天坐标系），验证针孔投影的几何正确性：
 * 中心对准、方向约定（北在上/东在右）、FOV 比例尺、身后剔除、校准平移。
 */
class ArSkyProjectorTest {

    private fun projector() = ArSkyProjector

    private fun project(
        right: FloatArray,
        up: FloatArray,
        axis: FloatArray,
        lstDeg: Double = 0.0,
        latDeg: Double = 40.0,
        fovDeg: Double = 60.0,
        w: Float = 1000f,
        h: Float = 1200f,
        correction: FloatArray? = null,
    ): ArSkyProjector.ProjectedSky {
        val out = ArSkyProjector.ProjectedSky()
        projector().project(right, up, axis, lstDeg, latDeg, fovDeg, w, h, correction, out)
        return out
    }

    /** 相机指向天顶、屏幕上方向北的标准基（赤道→东北天用 LST=0, lat=40） */
    private fun zenithBasis(): Triple<FloatArray, FloatArray, FloatArray> {
        // axis = U(0,0,1)，up = N(0,1,0)，right = E(1,0,0)
        return Triple(
            floatArrayOf(1f, 0f, 0f),
            floatArrayOf(0f, 1f, 0f),
            floatArrayOf(0f, 0f, 1f),
        )
    }

    @Test
    fun zenithStarProjectsToCenter() {
        val (right, up, axis) = zenithBasis()
        // LST=0、lat=40 时天顶的赤道坐标 (RA=0, Dec=40)
        val out = project(right, up, axis, lstDeg = 0.0, latDeg = 40.0)
        val zenith = out.stars.firstOrNull {
            it.entry.hip == 0 || (kotlin.math.abs(it.entry.dec - 40.0) < 3.0 && kotlin.math.abs(it.entry.ra) < 0.001)
        }
        // 星表中未必恰有 (0, 40) 的星：改用"最近天顶星"距离断言
        assertTrue("应投影出可见星", out.stars.isNotEmpty())
        val center = out.stars.minByOrNull {
            val dx = it.x - 500f; val dy = it.y - 600f
            dx * dx + dy * dy
        }!!
        // 最靠近中心的星必须在中心附近（星表 mag≤5.2 约 2000 颗，最近邻典型 2~4°；
        // 60° 视场 1000px → 1° ≈ 16.7px，3.5° ≈ 54px，取 60px 容差）
        val d = kotlin.math.hypot((center.x - 500f).toDouble(), (center.y - 600f).toDouble())
        assertTrue("天顶星应落在画面中心附近，实际偏离 ${"%.1f".format(d)}px", d < 60f)
    }

    @Test
    fun northIsUpAndEastIsRight() {
        // 相机指向正北（axis=N=(0,1,0)）、天顶在屏幕上方（up=U=(0,0,1)）：
        // right 必须为东 (1,0,0)（右手系 right = axis×up = N×U = E）
        val r = floatArrayOf(1f, 0f, 0f)
        val out = project(r, floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 1f, 0f), lstDeg = 0.0, latDeg = 40.0)
        assertTrue("应投影出可见星", out.stars.isNotEmpty())
        // 天顶 (Dec=40, RA=0) 应在画面中心偏上（up=U 指屏幕上方）
        val zenith = out.stars.filter { kotlin.math.abs(it.entry.dec - 40.0) < 0.5 && it.entry.ra < 0.5 }
        if (zenith.isNotEmpty()) {
            val z = zenith.minByOrNull { kotlin.math.abs(it.x - 500f) }!!
            assertTrue("天顶星应偏上（北在上）: y=${z.y}", z.y < 600f)
        }
        // 全部可见星的 x 都应为有限值
        assertTrue(out.stars.all { !it.x.isNaN() && !it.y.isNaN() })
    }

    @Test
    fun fovScaleMatchesPinholeMath() {
        // FOV=60°、宽 1000px → f = 500/tan(30°) = 866；天顶方向偏北 10° 的星
        // 屏幕偏移 = f·tan(10°)/cos…（沿视轴小角度）≈ f·tan(10°) = 152.7px
        val (right, up, axis) = zenithBasis()
        val out = project(right, up, axis, lstDeg = 0.0, latDeg = 40.0, fovDeg = 60.0, w = 1000f, h = 1000f)
        val f = (1000f / 2f) / tan(Math.toRadians(30.0)).toFloat()
        // 找 dec=50（天顶以北 10°，HA=0）的星
        val target = out.stars.filter { kotlin.math.abs(it.entry.dec - 50.0) < 0.5 && it.entry.ra < 0.5 }
        if (target.isNotEmpty()) {
            val s = target.minByOrNull { kotlin.math.abs(it.x - 500f) }!!
            val expected = f * tan(Math.toRadians(10.0)).toFloat()
            val actual = 500f - s.y // y 向下，北在上 → 偏移 = cy - y
            assertEquals("FOV 针孔比例尺应吻合", expected.toDouble(), actual.toDouble(), expected * 0.06 + 3f)
        }
    }

    @Test
    fun starsBehindCameraAreExcluded() {
        // 相机指向天顶时，背面的天区（如南天极方向）不应出现在结果里
        val (right, up, axis) = zenithBasis()
        val out = project(right, up, axis, lstDeg = 0.0, latDeg = 40.0)
        // 所有可见星都必须在画布范围内（留边距）
        assertTrue(out.stars.all { it.x > -60f && it.x < 1060f && it.y > -60f && it.y < 1260f })
        assertTrue(out.messier.all { it.visible && it.x > -40f && it.x < 1040f && it.y > -40f && it.y < 1240f })
    }

    @Test
    fun correctionShiftsChartToCenter() {
        val (right, up, axis) = zenithBasis()
        // 校准中心取天顶以北 5°（RA=0, Dec=45）：校准前该处离画面中心 f·tan5° ≈ 76px，
        // 校准后整图平移、该处最近的星应显著靠近中心
        val corrRa = 0.0
        val corrDec = 45.0
        var bestEntry: com.starcam.astro.astro.StarEntry? = null
        var bestSep = Double.MAX_VALUE
        for (e in ArSkyProjector.entries) {
            val dra = e.ra.let { minOf(it, 360.0 - it) } // RA=0 环绕
            val d = kotlin.math.hypot(dra, e.dec - corrDec)
            if (d < bestSep) {
                bestSep = d
                bestEntry = e
            }
        }
        val entry = bestEntry!!
        val without = project(right, up, axis, lstDeg = 0.0, latDeg = 40.0)
        val withCorr = project(
            right, up, axis, lstDeg = 0.0, latDeg = 40.0,
            correction = ArSkyProjector.unitVector(corrRa, corrDec),
        )
        fun distToCenter(sky: ArSkyProjector.ProjectedSky): Double {
            val s = sky.stars.first { it.entry === entry }
            return kotlin.math.hypot((s.x - 500f).toDouble(), (s.y - 600f).toDouble())
        }
        val dWithout = distToCenter(without)
        val dWith = distToCenter(withCorr)
        assertTrue(
            "校准后目标天区最近星应显著靠近中心：before=${"%.1f".format(dWithout)} after=${"%.1f".format(dWith)}",
            dWith < dWithout && dWith < 45.0,
        )
    }

    @Test
    fun messierAndLinesProjected() {        val (right, up, axis) = zenithBasis()
        val out = project(right, up, axis, lstDeg = 0.0, latDeg = 40.0)
        // 连线端点必须来自可见星集合
        val starSet = out.stars.map { it.entry }.toSet()
        for (line in out.lines) {
            assertTrue(line.a.entry in starSet && line.b.entry in starSet)
        }
        // 防射线扇面回归：每条连线的端点对必须是星表里的真实线段。
        // （v1.5.36 曾因可见性索引数组复用时被清成 0，所有"一端不可见"的
        //   线段都被错误连到第 0 颗可见星，形成从单点发散的射线扇面遮挡视野）
        val realSegments = HashSet<Long>()
        for (seg in com.starcam.astro.astro.StarCatalogData.constellationLines) {
            realSegments += segKey(seg[0], seg[1])
        }
        for (line in out.lines) {
            val ia = com.starcam.astro.astro.StarCatalogData.indexOfHip(line.a.entry.hip)
            val ib = com.starcam.astro.astro.StarCatalogData.indexOfHip(line.b.entry.hip)
            assertTrue(
                "连线端点对 ($ia,$ib) 不是真实星表线段",
                segKey(ia, ib) in realSegments,
            )
        }
        // 梅西耶可见项必须落在画布内
        for (m in out.messier) {
            assertTrue(m.visible)
            assertTrue(m.x >= -40f && m.x <= 1040f && m.y >= -40f && m.y <= 1240f)
        }
    }

    private fun segKey(a: Int, b: Int): Long =
        if (a <= b) (a.toLong() shl 32) or b.toLong() else (b.toLong() shl 32) or a.toLong()

    @Test
    fun pointingDownStillProjectsStars() {
        // §0.53 全天星空：相机朝下（axis=(0,0,-1)，right×up=axis 右手系）时，
        // 星空依然投影（"脚下半球"），且可见星全部为地平线以下
        val right = floatArrayOf(1f, 0f, 0f)
        val up = floatArrayOf(0f, -1f, 0f)
        val axis = floatArrayOf(0f, 0f, -1f)
        val out = project(right, up, axis, lstDeg = 0.0, latDeg = 40.0)
        assertTrue("朝下也应投影出可见星", out.stars.isNotEmpty())
        assertTrue("朝下时可见星应全部 belowHorizon", out.stars.all { it.belowHorizon })
        // 梅西耶同理
        assertTrue("朝下时梅西耶可见项应全部 belowHorizon", out.messier.all { it.belowHorizon })
    }

    @Test
    fun pointingUpStarsAreAboveHorizon() {
        // 相机朝上（天顶方向）：可见星全部为地平线以上
        val (right, up, axis) = zenithBasis()
        val out = project(right, up, axis, lstDeg = 0.0, latDeg = 40.0)
        assertTrue(out.stars.isNotEmpty())
        assertTrue("朝上时可见星应全部 above horizon", out.stars.none { it.belowHorizon })
        assertTrue(out.messier.none { it.belowHorizon })
    }

    private fun projectPoint(
        raDeg: Double,
        decDeg: Double,
        right: FloatArray,
        up: FloatArray,
        axis: FloatArray,
        lstDeg: Double = 0.0,
        latDeg: Double = 40.0,
        fovDeg: Double = 60.0,
        w: Float = 1000f,
        h: Float = 1000f,
        correction: FloatArray? = null,
    ): FloatArray? {
        val out = FloatArray(3)
        val ok = ArSkyProjector.projectPoint(
            raDeg, decDeg, right, up, axis,
            lstDeg, latDeg, fovDeg, w, h, correction, out,
        )
        return if (ok) out else null
    }

    @Test
    fun projectPointAtViewCenter() {
        // §0.54b 找星导航：视轴正对天顶 (0,40) → 中心方向的目标精确投影到画面中心
        val (right, up, axis) = zenithBasis()
        val pos = projectPoint(0.0, 40.0, right, up, axis)!!
        assertEquals(500f, pos[0], 0.5f)
        assertEquals(500f, pos[1], 0.5f)
        assertTrue(pos[2] > 0.08f)
    }

    @Test
    fun projectPointBehindCameraIsNull() {
        // 指向天顶时，南天极（身后）目标应返回 null
        val (right, up, axis) = zenithBasis()
        assertTrue("身后目标应不可见", projectPoint(0.0, -90.0, right, up, axis) == null)
    }

    @Test
    fun projectPointMatchesPinholeScale() {
        // 天顶以北 10°（dec=50, ra=0）在 fov=60° 下偏离中心 f·tan(10°)
        val (right, up, axis) = zenithBasis()
        val pos = projectPoint(0.0, 50.0, right, up, axis)!!
        val f = (1000f / 2f) / kotlin.math.tan(Math.toRadians(30.0)).toFloat()
        val expected = f * kotlin.math.tan(Math.toRadians(10.0)).toFloat()
        assertEquals(500f, pos[0], 1f) // 东西居中
        val actual = 500f - pos[1] // 北在上 → y 偏移 = cy - y
        assertEquals(expected.toDouble(), actual.toDouble(), expected * 0.05 + 2f)
    }

    @Test
    fun projectPointAppliesCorrection() {
        // 校准向量 = 天顶以北 5°：目标 (0,45) 应在画面中心附近
        val (right, up, axis) = zenithBasis()
        val pos = projectPoint(0.0, 45.0, right, up, axis, correction = ArSkyProjector.unitVector(0.0, 45.0))!!
        val d = kotlin.math.hypot((pos[0] - 500f).toDouble(), (pos[1] - 500f).toDouble())
        assertTrue("校准后目标应居中，实际偏离 ${"%.1f".format(d)}px", d < 2.0)
    }

    // ── §0.58 太阳系天体投影 ─────────────────────────────────────

    private fun solarAt(ra: Double, dec: Double, body: SolarSystemEphemeris.SolarBody =
        SolarSystemEphemeris.SolarBody.JUPITER) =
        SolarSystemEphemeris.SolarPosition(
            body = body,
            raDeg = ra,
            decDeg = dec,
            distanceAu = 6.0,
            angularDiameterDeg = 0.0088,
            magnitude = -2.0,
            phase = 0.99,
            elongationDeg = 90.0,
        )

    @Test
    fun solarBodyAtViewCenterProjectsToCenter() {
        // 相机指向天顶（LST=0, lat=40 → 天顶为 RA=0, Dec=40）：
        // 位于该天球坐标的太阳系天体必须落在画面正中
        val (right, up, axis) = zenithBasis()
        val out = ArSkyProjector.ProjectedSky()
        ArSkyProjector.project(
            right, up, axis, 0.0, 40.0, 60.0, 1000f, 1000f, null, out,
            listOf(solarAt(0.0, 40.0)),
        )
        assertEquals("应有 1 个太阳系天体", 1, out.solar.size)
        val s = out.solar[0]
        assertEquals(500f, s.x, 0.5f)
        assertEquals(500f, s.y, 0.5f)
        assertTrue(s.visible)
        assertTrue("天顶方向应在地平线以上", !s.belowHorizon)
    }

    @Test
    fun solarBodyOffAxisMatchesPinholeScale() {
        // 天顶以北 10°（Dec=50）在 fov=60°/1000px 下偏移 f·tan(10°)
        val (right, up, axis) = zenithBasis()
        val out = ArSkyProjector.ProjectedSky()
        ArSkyProjector.project(
            right, up, axis, 0.0, 40.0, 60.0, 1000f, 1000f, null, out,
            listOf(solarAt(0.0, 50.0, SolarSystemEphemeris.SolarBody.MOON)),
        )
        assertEquals(1, out.solar.size)
        val s = out.solar[0]
        val f = (1000f / 2f) / tan(Math.toRadians(30.0)).toFloat()
        val expected = f * tan(Math.toRadians(10.0)).toFloat()
        assertEquals("东西应居中", 500f, s.x, 0.5f)
        assertEquals("北在上 → 向上偏移", expected.toDouble(), (500f - s.y).toDouble(), expected * 0.05 + 2.0)
    }

    @Test
    fun solarBodyBehindCameraIsExcluded() {
        val (right, up, axis) = zenithBasis()
        val out = ArSkyProjector.ProjectedSky()
        ArSkyProjector.project(
            right, up, axis, 0.0, 40.0, 60.0, 1000f, 1000f, null, out,
            listOf(solarAt(0.0, -90.0)), // 南天极：相机指向天顶时在身后
        )
        assertTrue("身后天体不应被投影", out.solar.isEmpty())
    }

    @Test
    fun solarBodyBelowHorizonIsFlaggedWhenPointingDown() {
        // 相机朝下（axis = −U）：视野中心是 nadir（lat=40 → Dec=−40、RA=180），
        // 该方向位于地平线以下，必须被标记 belowHorizon
        val right = floatArrayOf(1f, 0f, 0f)
        val up = floatArrayOf(0f, -1f, 0f)
        val axis = floatArrayOf(0f, 0f, -1f)
        val out = ArSkyProjector.ProjectedSky()
        ArSkyProjector.project(
            right, up, axis, 0.0, 40.0, 120.0, 1000f, 1000f, null, out,
            listOf(solarAt(180.0, -40.0)),
        )
        assertEquals("朝下的视野中心天体应被投影", 1, out.solar.size)
        assertTrue("朝下时 nadir 方向应为地平线以下", out.solar[0].belowHorizon)
        assertEquals(500f, out.solar[0].x, 0.5f)
        assertEquals(500f, out.solar[0].y, 0.5f)
    }

    @Test
    fun solarListIsClearedOnResetAndEmptyWhenOmitted() {
        val (right, up, axis) = zenithBasis()
        val out = ArSkyProjector.ProjectedSky()
        ArSkyProjector.project(
            right, up, axis, 0.0, 40.0, 60.0, 1000f, 1000f, null, out,
            listOf(solarAt(0.0, 40.0)),
        )
        assertEquals(1, out.solar.size)
        // 不传 solarPositions → reset() 必须清空上一帧残留（否则会画幽灵标记）
        ArSkyProjector.project(right, up, axis, 0.0, 40.0, 60.0, 1000f, 1000f, null, out)
        assertTrue("复位后太阳系列表应为空", out.solar.isEmpty())
    }

    @Test
    fun solarBodyScreenPositionIsFinite() {
        // 全天天体扫描：所有被投影的天体坐标必须是有限值（防 NaN 污染绘制）
        val (right, up, axis) = zenithBasis()
        val jd = SolarSystemEphemeris.julianDay(2026, 9, 10, 16.0)
        val positions = SolarSystemEphemeris.topocentricPositions(jd, 40.0, 116.0)
        val out = ArSkyProjector.ProjectedSky()
        ArSkyProjector.project(
            right, up, axis, 0.0, 40.0, 90.0, 1000f, 1000f, null, out, positions,
        )
        assertTrue("应投影出部分太阳系天体", out.solar.isNotEmpty())
        assertTrue(out.solar.all { it.x.isFinite() && it.y.isFinite() && it.pos.raDeg.isFinite() })
    }
}
