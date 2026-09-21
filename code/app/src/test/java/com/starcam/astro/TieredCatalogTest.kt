package com.starcam.astro

import com.starcam.astro.astro.LocalStarMatcher
import org.json.JSONObject
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/**
 * §0.75 分级星表域：窄场兜底的效果与代价。
 *
 * 生产路径（match）在浅域 mag<=4.0 整条阶梯无解时，才用深域 mag<=6.5 重试一次。
 * 本测试同时量三个数，用于判断分级是否达成了「宽场行为不变 + 窄场可解」：
 *   ① 生产路径（含深域兜底）的判定；
 *   ② 关掉深域兜底后的判定（= 改动前的行为基线）；
 *   ③ 深域索引的三角形数与构建耗时（决定兜底路径的首帧延迟）。
 */
class TieredCatalogTest {

    private fun loadGray(file: File): Triple<Int, Int, FloatArray> {
        val bytes = file.readBytes()
        val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val w = buf.int; val h = buf.int
        val gray = FloatArray(w * h)
        buf.asFloatBuffer().get(gray)
        return Triple(w, h, gray)
    }

    private fun angDist(ra1: Double, dec1: Double, ra2: Double, dec2: Double): Double {
        val r1 = ra1 * PI / 180; val d1 = dec1 * PI / 180
        val r2 = ra2 * PI / 180; val d2 = dec2 * PI / 180
        val c = sin(d1) * sin(d2) + cos(d1) * cos(d2) * cos(r1 - r2)
        return acos(c.coerceIn(-1.0, 1.0)) * 180 / PI
    }

    private fun verdict(id: String, t: JSONObject, res: com.starcam.astro.astro.LocalMatchResult?): String {
        val solve = res?.solve ?: return "UNSOLVED"
        val tf = t.getDouble("fov")
        val cOff = angDist(solve.raDeg, solve.decDeg, t.getDouble("ra"), t.getDouble("dec"))
        val fovErr = kotlin.math.abs(maxOf(solve.fieldWidthDeg, solve.fieldHeightDeg) - tf) / tf
        return if (cOff < tf * 0.25 && fovErr < 0.25) "SOLVED"
        else "WRONG(off=%.1f fov=%.0f/%.0f)".format(cOff, maxOf(solve.fieldWidthDeg, solve.fieldHeightDeg), tf)
    }

    @Test
    fun tieredNarrowField() {
        val dir = File(System.getenv("NF_DIR") ?: "C:/starword/testdata/narrowfield")
        val truth = JSONObject(File(dir, "truth.json").readText())
        val grays = dir.listFiles { f -> f.extension == "gray" }?.sorted() ?: emptyList()

        // ③ 深域索引成本
        val (nTriShallow, msShallow) = LocalStarMatcher.debugIndexBuild()
        println("浅域索引（mag<=4.0）: $nTriShallow 条三角形, ${msShallow}ms")
        val saved = LocalStarMatcher.debugForceCatalogMag
        LocalStarMatcher.debugForceCatalogMag = 6.5f
        val (nTriDeep, msDeep) = LocalStarMatcher.debugIndexBuild()
        LocalStarMatcher.debugForceCatalogMag = saved
        println("深域索引（mag<=6.5）: $nTriDeep 条三角形, ${msDeep}ms  (×%.1f)".format(
            nTriDeep.toFloat() / nTriShallow.coerceAtLeast(1)))

        var okA = 0; var wrongA = 0; var unA = 0
        var okB = 0; var wrongB = 0; var unB = 0
        val sb = StringBuilder()
        for (f in grays) {
            val id = f.nameWithoutExtension
            val t = truth.optJSONObject(id) ?: continue
            val (w, h, gray) = loadGray(f)
            val stars = LocalStarMatcher.detectStarsGray(w, h, gray)

            // A：生产路径（含深域兜底）
            val tA = System.currentTimeMillis()
            val resA = LocalStarMatcher.match(stars, w, h)
            val msA = System.currentTimeMillis() - tA
            val vA = verdict(id, t, resA)
            when (vA) { "SOLVED" -> okA++; "UNSOLVED" -> unA++; else -> wrongA++ }

            // B：关掉深域兜底（= 改动前行为）
            LocalStarMatcher.debugDisableDeepCatalog = true
            val resB = LocalStarMatcher.match(stars, w, h)
            LocalStarMatcher.debugDisableDeepCatalog = false
            val vB = verdict(id, t, resB)
            when (vB) { "SOLVED" -> okB++; "UNSOLVED" -> unB++; else -> wrongB++ }

            sb.append("  %-12s fov=%5.1f° %7dms  A=%-22s B=%-22s %s\n".format(
                id, t.getDouble("fov"), msA, vA, vB,
                if (vA != vB) "  <<< 变化" else ""))
        }
        println("=== 生产路径（浅域 + 深域兜底）: ok=$okA wrong=$wrongA unsolved=$unA / ${grays.size}")
        println("=== 仅浅域（= 改动前基线）    : ok=$okB wrong=$wrongB unsolved=$unB / ${grays.size}")
        print(sb.toString())
    }
}
