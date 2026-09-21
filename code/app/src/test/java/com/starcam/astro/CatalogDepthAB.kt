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
 * §0.75 A/B：星表域从生产默认 4.0 等加深到 6.5 等（仓库星表本身就有 8415 颗到 6.5 等，
 * 只是被 PROD_CATALOG_MAG 滤掉）后，窄场/中场能否解出。
 *
 * 目的：在决定是否引入外部深星表（hip_main.dat，需新增约 7000 颗、索引体积 ×3）之前，
 * 先用**零新数据**的上限做一次测量。若 6.5 等已能救回 10° 视场，则不必加数据。
 */
class CatalogDepthAB {

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

    private fun run(mag: Float?) {
        val dir = File(System.getenv("NF_DIR") ?: "C:/starword/testdata/narrowfield")
        val truth = JSONObject(File(dir, "truth.json").readText())
        val grays = dir.listFiles { f -> f.extension == "gray" }?.sorted() ?: emptyList()
        LocalStarMatcher.debugForceCatalogMag = mag
        var ok = 0; var wrong = 0; var unsolved = 0
        val sb = StringBuilder()
        for (f in grays) {
            val id = f.nameWithoutExtension
            val t = truth.optJSONObject(id) ?: continue
            val (w, h, gray) = loadGray(f)
            val t0 = System.currentTimeMillis()
            val stars = LocalStarMatcher.detectStarsGray(w, h, gray)
            val res = LocalStarMatcher.match(stars, w, h)
            val ms = System.currentTimeMillis() - t0
            val solve = res?.solve
            val tf = t.getDouble("fov")
            val verdict: String
            if (solve == null) { verdict = "UNSOLVED"; unsolved++ }
            else {
                val cOff = angDist(solve.raDeg, solve.decDeg, t.getDouble("ra"), t.getDouble("dec"))
                val fovErr = kotlin.math.abs(maxOf(solve.fieldWidthDeg, solve.fieldHeightDeg) - tf) / tf
                verdict = if (cOff < tf * 0.25 && fovErr < 0.25) { ok++; "SOLVED" }
                          else { wrong++; "WRONG(off=%.2f fov=%.1f/%.1f)".format(cOff, maxOf(solve.fieldWidthDeg, solve.fieldHeightDeg), tf) }
            }
            sb.append("  %-12s fov=%5.1f° %7dms %s内点 %-9s | %s\n".format(
                id, tf, ms, res?.inlierCount ?: "-", verdict, t.optString("note", "").take(22)))
        }
        LocalStarMatcher.debugForceCatalogMag = null
        println("=== mag<=${mag ?: "PROD 4.0"}  ok=$ok wrong=$wrong unsolved=$unsolved / ${grays.size}")
        print(sb.toString())
    }

    @Test
    fun depthAB() {
        val (nTri, ms) = LocalStarMatcher.debugIndexBuild()
        println("索引（mag<=4.0 生产）: $nTri 条三角形, ${ms}ms")
        run(6.5f)
    }
}
