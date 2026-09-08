package com.starcam.astro

import com.starcam.astro.astro.StarCatalogData
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §0.51 星座连线（Stellarium 数据源）形状回归测试。
 * 连线数据由 tools/gen_lines_stellarium.py 从 Stellarium 西方星文化
 * (skycultures/modern/constellationship.fab) 生成。
 * 这些测试防止"连线被手工/意外改动破坏形状"（如北冕座冠形断开）。
 */
class StellariumLinesTest {

    private fun idxOfNearest(raDeg: Double, decDeg: Double, tolDeg: Double = 0.2): Int? {
        var best = -1
        var bestD = tolDeg
        for (i in StarCatalogData.stars.indices) {
            val s = StarCatalogData.stars[i]
            var dra = kotlin.math.abs(s.ra - raDeg)
            if (dra > 180.0) dra = 360.0 - dra
            val d = maxOf(kotlin.math.abs(s.dec - decDeg), dra)
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return if (best >= 0) best else null
    }

    private val segments: Set<Pair<Int, Int>> by lazy {
        StarCatalogData.constellationLines
            .map { seg ->
                val a = seg[0]; val b = seg[1]
                if (a <= b) a to b else b to a
            }
            .toSet()
    }

    @Test
    fun totalLineCountIsStellariumScale() {
        // Stellarium western 数据 676 条原始线段，去重后 ~672；过少说明数据被裁掉，
        // 过多说明混入噪声
        val n = StarCatalogData.constellationLines.size
        assertTrue("线段数应 ~672（Stellarium 数据），实际 $n", n in 600..720)
    }

    @Test
    fun coronaBorealisCrownShape() {
        // 北冕座冠形：θ-β-α-γ-δ-ε-ι 七颗星链式相连（6 段），每颗星度 ≥1 且连通
        // （用户实测反馈旧手工连线该星座连线错乱，v1.5.39 以 Stellarium 数据重建）
        val crown = listOf(
            idxOfNearest(233.222, 31.359),  // θ CrB  HIP 76127
            idxOfNearest(231.957, 29.106),  // β CrB  HIP 75695 (Nusakan)
            idxOfNearest(233.672, 26.715),  // α CrB  HIP 76267 (Alphecca/贯索四)
            idxOfNearest(235.685, 26.298),  // γ CrB  HIP 76952
            idxOfNearest(237.405, 26.070),  // δ CrB  HIP 77512
            idxOfNearest(239.399, 26.880),  // ε CrB  HIP 78159
            idxOfNearest(240.362, 29.851),  // ι CrB  HIP 78493
        )
        assertTrue("北冕座 7 颗冠星都应匹配到星表: $crown", crown.all { it != null })
        val ids = crown.filterNotNull()
        // 相邻对全部存在（θ-β-α-γ-δ-ε-ι 六段）
        val links = ids.zip(ids.drop(1))
        for ((a, b) in links) {
            val key = if (a <= b) a to b else b to a
            assertTrue("北冕座冠形缺段 ($a,$b)", key in segments)
        }
        // 所有冠星度 ≥ 1（无孤立点）
        for (i in ids) {
            assertTrue("北冕座冠星 $i 不应孤立", segments.any { it.first == i || it.second == i })
        }
    }

    @Test
    fun bigDipperShape() {
        // 北斗七星勺形：斗口 4 星（αβγδ）+ 勺柄 3 星（εζη）为链式
        val dipper = listOf(
            idxOfNearest(165.932, 61.751),  // α UMa  HIP 54061 (Dubhe)
            idxOfNearest(165.460, 56.382),  // β UMa  HIP 53910 (Merak)
            idxOfNearest(178.458, 53.695),  // γ UMa  HIP 58001 (Phecda)
            idxOfNearest(183.856, 57.033),  // δ UMa  HIP 59774 (Megrez)
            idxOfNearest(193.507, 55.960),  // ε UMa  HIP 62956 (Alioth)
            idxOfNearest(200.981, 54.925),  // ζ UMa  HIP 65378 (Mizar)
            idxOfNearest(206.885, 49.313),  // η UMa  HIP 67301 (Alkaid)
        )
        assertTrue("北斗七星都应匹配到星表", dipper.all { it != null })
        val ids = dipper.filterNotNull()
        val links = ids.zip(ids.drop(1))
        for ((a, b) in links) {
            val key = if (a <= b) a to b else b to a
            assertTrue("北斗勺形缺段 ($a,$b)", key in segments)
        }
    }
}
