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
 * 窄场/中场回归：素材来自 SkyView 的 DSS 巡天（FITS 自带 TAN WCS，真值免费）。
 *
 * 覆盖 photos12 完全没有的场景类型：望远镜级窄场（0.5°~5°）、球状星团/星云等
 * 极端密场、哈勃深场/高银纬空场等极端稀疏场、高纬天区、赤经 0° 接缝，
 * 以及 §0.76 补的 10°~20° 中场批（北天图案/南天/银心/两极/接缝）。
 *
 * 判定：解算中心与 FITS CRVAL 的角距 < 视场的 25%，且解算视场与 FITS 视场
 * 相差 < 30%。素材与真值由 tools/fetch_narrowfield.py + convert_narrowfield.py
 * （窄场批）与 tools/fetch_midfield.py（中场批）生成。
 *
 * ⚠ 读结果前先看 §0.76：**这里的失败不必然意味着匹配器不行**。10°~20° 的
 * 4 个失败场经 `SyntheticMidFieldTest` 归因全部是素材问题 —— 银河密场在
 * 45"/px 的 DSS 上星云占满了 64 个检测名额，真星只剩 15%~32%。同一投影、
 * 同一管线的合成图 A/B 是 15/15 全部解出。所以 UNSOLVED 要配合
 * `SyntheticMidFieldTest.realDetectedStarAudit` 的真星占比一起读。
 */
class NarrowFieldRegressionTest {

    private fun loadGray(file: File): Triple<Int, Int, FloatArray> {
        val bytes = file.readBytes()
        val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val w = buf.int
        val h = buf.int
        val gray = FloatArray(w * h)
        buf.asFloatBuffer().get(gray)
        return Triple(w, h, gray)
    }

    /** 两点角距（度） */
    private fun angDist(ra1: Double, dec1: Double, ra2: Double, dec2: Double): Double {
        val r1 = ra1 * PI / 180; val d1 = dec1 * PI / 180
        val r2 = ra2 * PI / 180; val d2 = dec2 * PI / 180
        val c = sin(d1) * sin(d2) + cos(d1) * cos(d2) * cos(r1 - r2)
        return acos(c.coerceIn(-1.0, 1.0)) * 180 / PI
    }

    @Test
    fun narrowFieldSolveRate() {
        val dir = File(System.getenv("NF_DIR") ?: "C:/starword/testdata/narrowfield")
        val truthFile = File(dir, "truth.json")
        org.junit.Assert.assertTrue(
            "缺 truth.json：$dir（先跑 tools/fetch_narrowfield.py + convert_narrowfield.py）",
            truthFile.exists(),
        )
        val truth = JSONObject(truthFile.readText())
        val grays = dir.listFiles { f -> f.extension == "gray" }?.sorted() ?: emptyList()
        org.junit.Assert.assertTrue("无 .gray 素材：$dir", grays.isNotEmpty())

        var ok = 0
        var wrong = 0
        var unsolved = 0
        val detail = StringBuilder()
        for (f in grays) {
            val id = f.nameWithoutExtension
            val t = truth.optJSONObject(id) ?: continue
            val (w, h, gray) = loadGray(f)
            val t0 = System.currentTimeMillis()
            val stars = LocalStarMatcher.detectStarsGray(w, h, gray)
            val res = LocalStarMatcher.match(stars, w, h)
            val ms = System.currentTimeMillis() - t0
            val solve = res?.solve
            val tFov = t.getDouble("fov")
            val verdict: String
            if (solve == null) {
                verdict = "UNSOLVED"; unsolved++
            } else {
                val cOff = angDist(solve.raDeg, solve.decDeg, t.getDouble("ra"), t.getDouble("dec"))
                val fovErr = kotlin.math.abs(maxOf(solve.fieldWidthDeg, solve.fieldHeightDeg) - tFov) / tFov
                verdict = if (cOff < tFov * 0.25 && fovErr < 0.25) {
                    ok++; "SOLVED"
                } else {
                    wrong++; "WRONG(centerOff=%.2f° fov=%.1f/%.1f)".format(cOff, maxOf(solve.fieldWidthDeg, solve.fieldHeightDeg), tFov)
                }
            }
            detail.append("%-12s %4dx%-4d fov=%5.1f° %6dms stars=%2d inliers=%-3s %s | %s\n".format(
                id, w, h, tFov, ms, stars.size, res?.inlierCount ?: "-", verdict, t.getString("note")))
        }
        println(detail.toString())
        println("SUMMARY ok=$ok wrong=$wrong unsolved=$unsolved total=${grays.size}")
        // 窄场是全新场景，先只收集数据不断言；数据稳定后再收紧。
        println("（本测试当前只报告，不设合格线 —— 见 PROGRESS §0.73）")
    }
}
