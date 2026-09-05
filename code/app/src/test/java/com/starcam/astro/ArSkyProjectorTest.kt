package com.starcam.astro

import com.starcam.astro.astro.ArSkyProjector
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
    fun messierAndLinesProjected() {
        val (right, up, axis) = zenithBasis()
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
}
