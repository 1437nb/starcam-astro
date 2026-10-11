package com.starcam.astro

import com.starcam.astro.astro.LocalStarMatcher
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 12 张真实手机照片回归台（§0.12 真值表，testdata/photos12 + testdata/gray12）。
 *
 * 素材：bundle testdata/photos12 目录（1787670194xxx 命名，后 4 位为照片号）；
 * 测试读 2200px 长边 .gray（tools/gray12_convert.py 生成，对齐 APP
 * decodeSampledBitmap(2200) 管线；JVM 无 .so，提星走 box-blur 回退路径，
 * 与真机 SEP 提星的差异见 docs/25）。
 *
 * 判定（中心 ±3° + 长边视场 ±35%）：
 *  - TRUE-SOLVE：天区与视场均落真值窗口；
 *  - FALSE-POSITIVE：解出但天区/视场不符（错误天区或尺度坍缩/膨胀，§0.28 门槛的对象）；
 *  - MISS：未解出。
 * 真值来源：docs/06 §0.12（官方 solve-field + 919 星表叠加目视核实）。
 * 素材缺失时直接失败（回归保护不可静默跳过）。
 */
class Photo12RegressionTest {

    private val photoDir: String =
        System.getenv("PHOTO12_DIR")
            ?: listOf(
                "../../testdata/gray12",
                "../testdata/gray12",
                "testdata/gray12",
            ).firstOrNull { File(it).isDirectory } ?: "testdata/gray12"

    /** 照片号 → 真值（中心 RA/Dec、长边视场度）—— 与 CI 侧 [OfflinePhotosTruthTest] 共用同一份表 */
    private val truth: Map<String, DemoPhotoTruth.Truth> = DemoPhotoTruth.byId

    private fun loadGray(file: File): Triple<Int, Int, FloatArray> {
        val bytes = file.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val w = buf.int
        val h = buf.int
        val gray = FloatArray(w * h)
        buf.asFloatBuffer().get(gray)
        return Triple(w, h, gray)
    }

    private fun classify(id: String, solved: DemoPhotoTruth.Solved?): String {
        val t = truth.getValue(id)
        return DemoPhotoTruth.classify(t, solved).label
    }

    /** 观察台 + 汇总：PHOTO 行 + SUMMARY true=X false=Y miss=Z */
    @Test
    fun photo12SolveRate() {
        val dir = File(photoDir)
        val grays = dir.listFiles { f -> f.extension == "gray" }?.sorted()
            ?: emptyList()
        org.junit.Assert.assertTrue(
            "12 张回归素材缺失：$photoDir（需 tools/gray12_convert.py 先生成）",
            grays.size >= 12,
        )
        var trueSolves = 0
        var falsePositives = 0
        var misses = 0
        for (f in grays) {
            val id = f.nameWithoutExtension.takeLast(4)
            val t = truth[id]
            val (w, h, gray) = loadGray(f)
            val t0 = System.currentTimeMillis()
            val stars = LocalStarMatcher.detectStarsGray(w, h, gray)
            val res = LocalStarMatcher.match(stars, w, h)
            val ms = System.currentTimeMillis() - t0
            if (System.getenv("PHOTO12_DEBUG")?.split(',')?.contains(id) == true) {
                println(debugVoteTable(id, w, h, gray))
            }
            val solve = res?.solve
            val solved = if (solve != null) {
                DemoPhotoTruth.Solved(solve.raDeg, solve.decDeg, maxOf(solve.fieldWidthDeg, solve.fieldHeightDeg))
            } else null
            val verdict = if (t != null) classify(id, solved) else "NO-TRUTH"
            when (verdict) {
                "TRUE-SOLVE" -> trueSolves++
                "FALSE-POSITIVE" -> falsePositives++
                "MISS" -> misses++
            }
            println(
                "PHOTO %s %dx%d stars=%d totalMs=%d %s %s".format(
                    id, w, h, stars.size, ms,
                    solved?.let {
                        "SOLVED ra=%.2f dec=%.2f fov=%.1f inliers=%d".format(
                            it.ra, it.dec, it.fovDeg, res!!.inlierCount,
                        )
                    } ?: "UNSOLVED",
                    verdict,
                ),
            )
        }
        println("SUMMARY true=$trueSolves false=$falsePositives miss=$misses total=${grays.size}")
        // 常驻断言（§0.32 起）：真值修正口径下（全部广角），真解下界 8/12；
        // 假解 ≤1（4963 天区无独立锚定，暂按旧官方窗口计入，详见 §0.30.2）。
        // 5087/5092（稀疏场）与 5031（欠曝）允许 MISS，不算回归。
        org.junit.Assert.assertTrue(
            "真解低于下界：$trueSolves（期望 ≥8）\n见 docs/26 §0.30",
            trueSolves >= 8,
        )
        org.junit.Assert.assertTrue(
            "假解超标：$falsePositives（期望 ≤1）\n见 docs/26 §0.30.2 真值修正",
            falsePositives <= 1,
        )
    }

    /** 投票明细（PHOTO12_DEBUG=照片号列表 时输出，定位 MISS 根因用） */
    private fun debugVoteTable(id: String, w: Int, h: Int, gray: FloatArray): String {
        val stars = LocalStarMatcher.detectStarsGray(w, h, gray)
        val head = "=== VOTE-TABLE $id detected=${stars.size} " +
            "brightest=${stars.take(5).joinToString { "%.0f".format(it.brightness) }} " +
            "th20=${stars.getOrNull(19)?.brightness?.let { "%.0f".format(it) }}"
        return head + "\n" + LocalStarMatcher.debugVoteTable(stars, w, h)
    }
}
