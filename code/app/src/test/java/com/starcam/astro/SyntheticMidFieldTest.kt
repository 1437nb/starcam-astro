package com.starcam.astro

import com.starcam.astro.astro.LocalStarMatcher
import com.starcam.astro.astro.StarCatalogData
import org.json.JSONObject
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * §0.76 合成图 A/B：把星表按真实 TAN WCS 渲染成同样视场的图，跑同一条
 * 检测+匹配管线，与 DSS 实拍结果对照。
 *
 * 用途只有一个 —— **归因**。DSS 回归里失败的场，到底是「匹配器不行」还是
 * 「素材不行」（银河星云/星团糊成一团，检测出的 64 个 blob 里没几颗是真星），
 * 从实拍结果上看不出来，因为两个因素混在一起。合成图把素材固定成理想情况：
 *   - 合成图能解、实拍不能 → 素材问题，匹配器无罪；
 *   - 合成图也不能解 → 匹配器问题。
 *
 * 同时内建投影自检：渲染出的星位应当落在 DSS 实拍的亮像素上，否则说明投影
 * 写错了，整个 A/B 不成立。
 */
class SyntheticMidFieldTest {

    private fun loadGray(file: File): Triple<Int, Int, FloatArray> {
        val bytes = file.readBytes()
        val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val w = buf.int
        val h = buf.int
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


    @Test
    fun syntheticVsReal() {
        val dir = File(System.getenv("NF_DIR") ?: "C:/starword/testdata/narrowfield")
        val truth = JSONObject(File(dir, "truth.json").readText())
        val band = truth.keys().asSequence()
            .map { it to truth.getJSONObject(it) }
            .filter { it.second.getDouble("fov") in 10.0..20.0 }
            .sortedBy { it.second.getDouble("fov") }
            .toList()

        val sb = StringBuilder()
        var synOk = 0
        var synShallowOk = 0
        var projChecked = 0
        var projHit = 0
        for ((id, t) in band) {
            val ra = t.getDouble("ra"); val dec = t.getDouble("dec"); val fov = t.getDouble("fov")
            val w = t.getInt("w")
            val line = StringBuilder("%-12s fov=%4.1f° ".format(id, fov))

            // --- 投影自检：合成星位是否落在 DSS 亮像素上 ---
            val dssFile = File(dir, "$id.gray")
            if (dssFile.exists()) {
                val (dw, dh, dg) = loadGray(dssFile)
                val (_, _, pos) = SyntheticSkyRenderer.render(ra, dec, fov, w, 6.5)
                var hit = 0
                for (p in pos) {
                    val x = p.first.toInt().coerceIn(0, dw - 1)
                    val y = p.second.toInt().coerceIn(0, dh - 1)
                    if (dg[y * dw + x] > 150f) hit++
                }
                if (pos.isNotEmpty()) {
                    projChecked += pos.size
                    projHit += hit
                    line.append("proj=%3d/%3d ".format(hit, pos.size))
                }
            }

            // --- 合成图（深域 mag<=6.5）跑真实管线 ---
            val (g65, n65, _) = SyntheticSkyRenderer.render(ra, dec, fov, w, 6.5)
            val stars65 = LocalStarMatcher.detectStarsGray(w, w, g65)
            val res65 = LocalStarMatcher.match(stars65, w, w)
            val ok65 = res65 != null && angDist(res65.solve.raDeg, res65.solve.decDeg, ra, dec) < fov * 0.25 &&
                kotlin.math.abs(maxOf(res65.solve.fieldWidthDeg, res65.solve.fieldHeightDeg) - fov) / fov < 0.25
            if (ok65) synOk++
            line.append("| syn6.5 n=%-3d det=%-2d %-7s ".format(n65, stars65.size, if (ok65) "SOLVED" else "FAIL"))

            // --- 合成图（浅域 mag<=4.0）单独跑 ---
            val (g40, n40, _) = SyntheticSkyRenderer.render(ra, dec, fov, w, 4.0)
            val stars40 = LocalStarMatcher.detectStarsGray(w, w, g40)
            val res40 = LocalStarMatcher.match(stars40, w, w)
            val ok40 = res40 != null && angDist(res40.solve.raDeg, res40.solve.decDeg, ra, dec) < fov * 0.25
            if (ok40) synShallowOk++
            line.append("syn4.0 n=%-3d det=%-2d %s".format(n40, stars40.size, if (ok40) "SOLVED" else "FAIL"))

            sb.append(line).append('\n')
        }
        println(sb.toString())
        println("SUMMARY synthetic(deep)=$synOk/${band.size} synthetic(shallow)=$synShallowOk/${band.size}")
        if (projChecked > 0) {
            val pct = 100.0 * projHit / projChecked
            println("投影自检：$projHit/$projChecked = %.1f%% 合成星位落在 DSS 亮像素上".format(pct))
            org.junit.Assert.assertTrue(
                "投影自检失败（%.1f%% < 70%%）——A/B 前提不成立，先修投影".format(pct), pct >= 70.0,
            )
        }
    }

    /**
     * 审计：真实 DSS 图里，检测器交给匹配器的那 64 个 blob，有多少真的是星表星。
     *
     * 这是比合成图更直接的归因 —— 合成图回答「星表星够不够解」，本测试回答
     * 「实拍素材到底给了匹配器什么」。银河密场里星云/星团被减背景后仍是
     * 大面积亮斑，会以 blob 形式抢占 64 个名额，把真星挤掉。
     */
    @Test
    fun realDetectedStarAudit() {
        val dir = File(System.getenv("NF_DIR") ?: "C:/starword/testdata/narrowfield")
        val truth = JSONObject(File(dir, "truth.json").readText())
        val band = truth.keys().asSequence()
            .map { it to truth.getJSONObject(it) }
            .filter { it.second.getDouble("fov") in 10.0..20.0 }
            .sortedBy { it.second.getDouble("fov") }
            .toList()

        val sb = StringBuilder()
        for ((id, t) in band) {
            val f = File(dir, "$id.gray")
            if (!f.exists()) continue
            val (w, h, gray) = loadGray(f)
            val ra = t.getDouble("ra"); val dec = t.getDouble("dec"); val fov = t.getDouble("fov")
            val pxDeg = fov / w
            val detected = LocalStarMatcher.detectStarsGray(w, h, gray)
            // 画面内全部 mag<=6.5 星表星的像素位置
            val catPos = StarCatalogData.stars
                .filter { it.mag <= 6.5 }
                .mapNotNull { SyntheticSkyRenderer.project(it.ra, it.dec, ra, dec, pxDeg, w, h) }
            var realStars = 0
            for (d in detected) {
                val hit = catPos.any { p ->
                    val dx = p.first - d.x.toDouble(); val dy = p.second - d.y.toDouble()
                    dx * dx + dy * dy <= 9.0 // 3 px 内
                }
                if (hit) realStars++
            }
            val pct = if (detected.isEmpty()) 0 else 100 * realStars / detected.size
            sb.append(
                "%-12s fov=%4.1f° detected=%-2d 其中真星表星=%-2d (%3d%%) 画面内星表星=%d%n".format(
                    id, fov, detected.size, realStars, pct, catPos.size,
                )
            )
        }
        println(sb.toString())
    }
}
