package com.starcam.astro

import com.starcam.astro.astro.LocalStarMatcher
import org.json.JSONObject
import org.junit.Test
import java.io.File

/** §0.74 分段时间基准：定位 matchInternal 各阶段的真实耗时分布。 */
class PhaseTimingBench {

    private fun loadGray(file: File): Triple<Int, Int, FloatArray> {
        val bytes = file.readBytes()
        val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val w = buf.int; val h = buf.int
        val gray = FloatArray(w * h)
        buf.asFloatBuffer().get(gray)
        return Triple(w, h, gray)
    }

    private fun bench(label: String, dir: File) {
        val grays = dir.listFiles { f -> f.extension == "gray" }?.sorted() ?: emptyList()
        if (grays.isEmpty()) { println("$label: 无素材"); return }
        var sum = 0L
        println("=== $label ===")
        println("%-8s %7s %8s %8s %8s %8s %8s %8s %9s".format(
            "id", "detect", "primary", "multi", "weak", "backup", "bkMulti", "scored", "match总"))
        for (f in grays) {
            val id = f.nameWithoutExtension.takeLast(4)
            val (w, h, gray) = loadGray(f)
            LocalStarMatcher.debugPhaseTimings = null
            LocalStarMatcher.debugVoteScanTake()   // 清零
            val t0 = System.currentTimeMillis()
            val stars = LocalStarMatcher.detectStarsGray(w, h, gray)
            val dMs = System.currentTimeMillis() - t0
            val t1 = System.currentTimeMillis()
            val res = LocalStarMatcher.match(stars, w, h)
            val mMs = System.currentTimeMillis() - t1
            sum += dMs + mMs
            val scan = LocalStarMatcher.debugVoteScanTake()
            val ph = LocalStarMatcher.debugPhaseTimings ?: "-"
            val m = Regex("(\\w+)=(\\d+)").findAll(ph).associate { it.groupValues[1] to it.groupValues[2] }
            println("%-8s %7d %8s %8s %8s %8s %8s %8s %9d".format(
                id, dMs, m["primary"] ?: "-", m["multi"] ?: "-", m["weak"] ?: "-",
                m["backup"] ?: "-", m["backupMulti"] ?: "-", m["scored"] ?: "-",
                mMs) + "   ${res?.inlierCount ?: "-"}内点  $scan")
        }
        println("$label 合计 ${sum} ms\n")
    }

    @Test
    fun phaseTiming() {
        bench("宽场 gray12", File(System.getenv("PHOTO12_DIR") ?: "C:/starword/testdata/gray12"))
    }
}
