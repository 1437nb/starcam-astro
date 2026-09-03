package com.starcam.astro

import com.starcam.astro.astro.LocalStarMatcher
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.min

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
        System.getenv("PHOTO12_DIR") ?: "testdata/gray12"

    /** 照片号 → 真值（中心 RA/Dec、长边视场度） */
    private data class Truth(val ra: Double, val dec: Double, val fovDeg: Double)

    private val truth: Map<String, Truth> = mapOf(
        "4963" to Truth(78.70, 20.85, 77.2), // §0.43 修订：旧真值(73.4,19.1)来自物理不可能的官方 0.2° 解；
                                             // 交叉验证（DiagCrossCheckTest）证明 4963 与 4974 同天区
                                             // （中心距 11.6°，星点天球重合 23/64），自研解即真值
        "4974" to Truth(82.0, 32.1, 77.0),  // 御夫座+冬季亮星群
        "4984" to Truth(60.0, 25.3, 76.0),  // 与 4998 同天区同时段连拍（blob 尺寸/密度=广角；旧 17×22° 官方解是错尺度产物）
        "4998" to Truth(60.03, 25.26, 75.6),// 冬季六边形全景（官方 WCS 锚定：68.08″/px @4000px）
        "5031" to Truth(13.4, 71.0, 76.0),  // 仙后座 W + 北极星
        "5040" to Truth(68.7, -14.3, 77.0), // 猎户座+大犬座
        "5049" to Truth(70.5, -20.9, 71.0), // 天兔座+大犬座
        "5057" to Truth(61.9, 15.0, 75.0),  // 用户口径=广角；blob 形态（星少而大）曾有变焦疑义，真值窗口按广角
        "5068" to Truth(57.3, 26.7, 72.0),  // 英仙座+金牛座
        "5076" to Truth(55.3, 26.0, 72.0),  // 英仙座+金牛座
        "5087" to Truth(351.2, 24.3, 76.0), // §0.43b：左上角灯光/月亮伪影，亮源掩蔽后
                                             // 飞马座真解浮现（ra≈349.8/dec≈24.2 inliers=16）
        "5092" to Truth(351.3, 24.3, 76.0), // 同上二连拍（掩蔽后 inliers=13）
    )

    private fun loadGray(file: File): Triple<Int, Int, FloatArray> {
        val bytes = file.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val w = buf.int
        val h = buf.int
        val gray = FloatArray(w * h)
        buf.asFloatBuffer().get(gray)
        return Triple(w, h, gray)
    }

    private fun classify(id: String, solved: LocalSolve?): String {
        val t = truth.getValue(id)
        if (solved == null) return "MISS"
        val dra = abs(solved.ra - t.ra).let { min(it, 360.0 - it) }
        val ddec = abs(solved.dec - t.dec)
        val fovRatio = solved.fov / t.fovDeg
        return if (dra < 3.0 && ddec < 3.0 && fovRatio in 0.65..1.55) "TRUE-SOLVE" else "FALSE-POSITIVE"
    }

    private class LocalSolve(val ra: Double, val dec: Double, val fov: Double)

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
                LocalSolve(solve.raDeg, solve.decDeg, maxOf(solve.fieldWidthDeg, solve.fieldHeightDeg))
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
                            it.ra, it.dec, it.fov, res!!.inlierCount,
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
