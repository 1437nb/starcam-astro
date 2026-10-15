package com.starcam.astro

import com.starcam.astro.astro.CatalogDomain
import com.starcam.astro.astro.DetectedStar
import com.starcam.astro.astro.LocalMatchResult
import com.starcam.astro.astro.LocalStarMatcher
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * §0.103「宽场跳过深域」闸门的**真值台**（审计 P0-2.5 (b) 的验收面）。
 *
 * ## 为什么需要它
 *
 * `DEEP_PATH_MAX_FOV_DEG = 45.0` 是一条**生产决策**：手持 EXIF 视场 > 45° 时，浅域整条阶梯
 * 失败也不进深域（深域是窄场兜底路径；宽场跑它白跑且只会出尺度坍缩伪解 —— 真机 5057 曾
 * 白跑 60 秒）。此前这条线**没有任何真实素材样本**恰好跨它：既没有 >45° 的场，
 * 也没有"浅域失败"的对照，只能靠单元测试（`MatchDiagnosticsTest` 用 5 颗星的必败输入）
 * 间接覆盖。本台补上：
 *
 *  - **素材**（`testdata/gate-sweep/`，本地/验证机，不入库）：
 *    - `syn40/47/50` —— 合成渲染（同一天区中心，只差视场），真值 = 渲染参数；
 *    - `ctrl10` —— SkyView DSS 实拍 10° 场，真值 = 图自带 WCS（**真实素材对照**）；
 *  - **方法**：每场跑四种组合 —— 干净表 / 污染表（+400 颗同量级噪声星，把浅域逼到全败）
 *    × 无先验 / 带可信视场，读 `debugDeepRejectedReason` 判断闸门是否生效。
 *
 * ## 实测结论（2026-10-15，写进断言）
 *
 *  | 场 | 干净表 | 污染表·无先验 | 污染表·带可信视场 |
 *  |---|---|---|---|
 *  | 40°（≤45°） | 浅域解出 ~2.8s | 深域跑完 37.0s | **深域跑完 35.6s**（不该跳 ⇒ 没跳 ✓） |
 *  | 47°（>45°） | 浅域解出 ~1.8s | 深域跑完 37.2s | **跳过 3.8s**（该跳 ⇒ 跳了，省 33 秒） |
 *  | 50°（>45°） | 浅域解出 ~1.8s | 深域跑完 6.8s | **跳过 4.2s** |
 *  | 10°（真实，≤45°） | 深域解出 | 深域解出 | 深域解出（≤45° 不跳 ✓） |
 *
 * 另有两条来自本台的**能力基线**：① 40°/47°/50° 宽场在**浅域**就解出（1.7~3.2 秒）——
 * 这正是 45° 闸门的设计前提「宽场在浅域就该解出」；② 10° 真实场需要深域（7~21 秒），
 * 闸门正确放行。
 */
class GateFovSweepTest {

    private fun dir(): File = listOf(
        "../../testdata/gate-sweep", "../testdata/gate-sweep", "testdata/gate-sweep",
    ).map { File(it) }.firstOrNull { it.isDirectory }
        ?: error("闸门扫描素材缺失：testdata/gate-sweep（生成：tools/fetch_gate_sweep.py + 合成渲染）")

    private class Truth(val ra: Double, val dec: Double, val fov: Double)
    private class Case(
        val w: Int, val h: Int, val gray: FloatArray, val truth: Truth,
    )

    private fun load(id: String, truth: JSONObject): Case {
        val f = File(dir(), "$id.gray")
        assertTrue("素材缺失：${f.absolutePath}", f.isFile)
        val bytes = f.readBytes()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val w = bb.int
        val h = bb.int
        val gray = FloatArray(w * h)
        bb.asFloatBuffer().get(gray)
        val t = truth.getJSONObject(id)
        return Case(w, h, gray, Truth(t.getDouble("ra"), t.getDouble("dec"), t.getDouble("fov")))
    }

    /** 污染表：干净表 + [count] 颗同量级（150~500 亮度）噪声星，按亮度降序 —— 浅域候选人被挤坏。 */
    private fun pollute(clean: List<DetectedStar>, w: Int, h: Int, seed: Int, count: Int): List<DetectedStar> {
        // 单个 Random 顺序取数（与 A/B 实测脚本同序）—— 换写法会得到不同的噪声位置，
        // "浅域必败"这个前提就不成立了（本台的第一版就踩过：每颗星各起一个 Random）。
        val rnd = Random(seed)
        val noise = List(count) {
            DetectedStar(
                rnd.nextFloat() * (w - 20f) + 10f,
                rnd.nextFloat() * (h - 20f) + 10f,
                150f + rnd.nextFloat() * 350f,
            )
        }
        return (clean + noise).sortedByDescending { it.brightness }
    }

    private fun angDist(ra1: Double, d1: Double, ra2: Double, d2: Double): Double {
        val r1 = ra1 * Math.PI / 180; val dd1 = d1 * Math.PI / 180
        val r2 = ra2 * Math.PI / 180; val dd2 = d2 * Math.PI / 180
        return acos((sin(dd1) * sin(dd2) + cos(dd1) * cos(dd2) * cos(r1 - r2)).coerceIn(-1.0, 1.0)) * 180 / Math.PI
    }

    private fun solve(list: List<DetectedStar>, c: Case, fovHint: Double?): LocalMatchResult? =
        LocalStarMatcher.match(list, c.w, c.h, null, fallbackDetected = null, fovHintDeg = fovHint)

    private fun inTruthWindow(r: LocalMatchResult?, c: Case): Boolean {
        if (r == null) return false
        val s = r.solve
        val off = angDist(s.raDeg, s.decDeg, c.truth.ra, c.truth.dec)
        val fv = max(s.fieldWidthDeg, s.fieldHeightDeg)
        return off < c.truth.fov * 0.25 && abs(fv - c.truth.fov) / c.truth.fov < 0.25
    }

    /**
     * ① 设计前提：**宽场在浅域就该解出**（40°/47°/50° 干净表）。
     *
     * 这条同时是 45° 闸门的存在理由 —— 若宽场必须靠深域才能解，闸门就是在砍能力。
     * 另断言解落在真值窗口内（合成场真值 = 渲染参数）。
     */
    @Test
    fun `宽场干净表在浅域解出且落在真值窗口`() {
        val truth = JSONObject(File(dir(), "truth.json").readText())
        for (id in listOf("syn40", "syn47", "syn50")) {
            val c = load(id, truth)
            val stars = LocalStarMatcher.detectStarsGray(c.w, c.h, c.gray, 100)
            val t0 = System.currentTimeMillis()
            val r = solve(stars, c, fovHint = c.truth.fov)
            val ms = System.currentTimeMillis() - t0
            println("GATE-105 %-6s fov=%.0f° 检出=%d %s %dms".format(
                id, c.truth.fov, stars.size,
                r?.let { "SOLVED inl=${it.inlierCount} domain=${it.catalogDomain}" } ?: "UNSOLVED", ms))
            assertNotNull("$id 宽场干净表应解出", r)
            assertEquals("$id 应在**浅域**解出（闸门的设计前提）", CatalogDomain.SHALLOW, r!!.catalogDomain)
            assertTrue("$id 解应落在真值窗口内", inTruthWindow(r, c))
        }
    }

    /** ② 真实素材对照：SkyView DSS 实拍 10° 场（≤45° → 深域照跑）解出且落真值窗口。 */
    @Test
    fun `真实10度场解出（深域允许）`() {
        val truth = JSONObject(File(dir(), "truth.json").readText())
        val c = load("ctrl10", truth)
        val stars = LocalStarMatcher.detectStarsGray(c.w, c.h, c.gray, 100)
        val r = solve(stars, c, fovHint = c.truth.fov)
        println("GATE-105 ctrl10 fov=10° 检出=${stars.size} %s".format(
            r?.let { "SOLVED inl=${it.inlierCount} domain=${it.catalogDomain}" } ?: "UNSOLVED"))
        assertNotNull("真实 10° 场应解出", r)
        assertTrue("真实 10° 场解应落在 WCS 真值窗口内", inTruthWindow(r, c))
        assertNull("≤45° 不得触闸门：${LocalStarMatcher.debugDeepRejectedReason}",
            LocalStarMatcher.debugDeepRejectedReason)
    }

    /**
     * ③ **闸门本体**：污染表（浅域必败）下，>45° 跳过深域、≤45° 不跳。
     *
     * 40° 与 47°/50° 三场**同中心、同污染强度**，只差视场 —— 因此这一对断言把
     * 「阈值 45° 的效果」隔离到只剩视场一个变量。断言不依赖计时（CI/验证机负载不同
     * 会让毫秒数漂移），只判**是否触发跳过**这一确定事实 + 跳过时不得有解。
     */
    @Test
    fun `闸门只在超过45度时跳过深域`() {
        val truth = JSONObject(File(dir(), "truth.json").readText())
        val outcomes = ArrayList<String>()
        for (id in listOf("syn40", "syn47", "syn50")) {
            val c = load(id, truth)
            val clean = LocalStarMatcher.detectStarsGray(c.w, c.h, c.gray, 100)
            val polluted = pollute(clean, c.w, c.h, seed = 20500, count = 400)
            val t0 = System.currentTimeMillis()
            val r = solve(polluted, c, fovHint = c.truth.fov)
            val ms = System.currentTimeMillis() - t0
            val reason = LocalStarMatcher.debugDeepRejectedReason
            val skipped = reason?.contains("已跳过") == true
            outcomes.add(
                "%-6s fov=%2.0f° 污染表 → %s %s %s %dms".format(
                    id, c.truth.fov, if (r == null) "UNSOLVED" else "SOLVED(inl=${r.inlierCount})",
                    if (skipped) "闸门:跳过" else "闸门:未跳过", reason?.take(12) ?: "", ms,
                ),
            )
            if (c.truth.fov > 45.0) {
                assertTrue(">45°（${c.truth.fov}°）应跳过深域，原因字段：$reason", skipped)
                assertNull("跳过深域后不得有解（宽场拿到深域解基本是坍缩伪影）", r)
            } else {
                assertTrue(
                    "≤45°（${c.truth.fov}°）不得触闸门跳过，原因字段：$reason",
                    reason == null || !skipped,
                )
            }
        }
        println("GATE-105 闸门对照：\n  " + outcomes.joinToString("\n  "))
    }

    /**
     * ④ 同一场上「无先验 vs 带可信视场」的对照：>45° 时**必须产生时间差**（无先验要跑深域）。
     *
     * 计时只做**方向性**断言（跳过 ≪ 未跳过，比值 ≥ 2），不做绝对值 —— 机器负载会让绝对
     * 毫秒漂移，但 37 秒 vs 3.8 秒这种量级差不会翻向。
     */
    @Test
    fun `无先验会白跑深域而带可信视场不会`() {
        val truth = JSONObject(File(dir(), "truth.json").readText())
        val c = load("syn47", truth)
        val clean = LocalStarMatcher.detectStarsGray(c.w, c.h, c.gray, 100)
        val polluted = pollute(clean, c.w, c.h, seed = 20500, count = 400)

        val t0 = System.currentTimeMillis()
        solve(polluted, c, fovHint = null)
        val msNoHint = System.currentTimeMillis() - t0
        val t1 = System.currentTimeMillis()
        solve(polluted, c, fovHint = c.truth.fov)
        val msHint = System.currentTimeMillis() - t1
        println("GATE-105 syn47 47° 污染表：无先验 %dms vs 带可信视场 %dms（比值 %.1f）".format(
            msNoHint, msHint, msNoHint.toDouble() / max(1, msHint)))
        assertTrue(
            "无先验时应白跑深域（明显更慢）：%dms vs %dms".format(msNoHint, msHint),
            msNoHint > msHint * 2,
        )
    }
}
