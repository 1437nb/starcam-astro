package com.starcam.astro

import com.starcam.astro.astro.DetectedStar
import com.starcam.astro.astro.LocalStarMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/**
 * §0.107（二期审计 2026-10-14）：求解留档**随结果锁内返回**，不再跨锁读全局单槽。
 *
 * ## 修的是什么
 *
 * `LocalStarMatcher` 的投票轮/打分轮数字原是进程级 `@Volatile` 单槽，`StarSolver`
 * 在 `match()` 返回后（**已出锁**）才读。批量识别两 worker 并发时，Worker A 刚出锁、
 * 等在锁上的 Worker B 立刻进入并在入口清零/覆写这些字段 → A 的日志读到 null 或
 * B 的数字。现在 `matchWithDiagnostics()` 在 `solveLock` 内一次性快照，随结果返回。
 *
 * ## 本类钉住三件事
 *
 *  1. 深域被跳过的原因（§0.103 闸门）出现在**本次**快照里；
 *  2. 快照是**不可变的副本** —— 后续求解不会改写它（旧写法读全局字段时必踩）；
 *  3. 并发两个求解的留档不串台（旧写法下 Worker 之间会互相看到对方的数字）。
 *
 * 素材用仓库自带的公开演示图（CI 照跑），与 [OfflinePhotosTruthTest] 同源。
 */
class MatchDiagnosticsTest {

    private companion object {
        val grayCache = HashMap<String, JpegLuma.Gray>()
    }

    private fun gray(name: String): JpegLuma.Gray =
        grayCache.getOrPut(name) { JpegLuma.read(File(JpegLuma.demoPhotosDir(), name)) }

    /** 与生产同口径的检出（maxStars=100 是 detectStars(bitmap) 的默认值）。 */
    private fun detect(name: String, maxStars: Int = 100): Pair<List<DetectedStar>, JpegLuma.Gray> {
        val g = gray(name)
        return LocalStarMatcher.detectStarsGray(g.w, g.h, g.px, maxStars) to g
    }

    /**
     * ① §0.103 闸门的原因必须出现在**本次**快照里。
     *
     * 输入刻意用「5 颗星 + 可信视场 76°（> 45° 上界）」：浅域必然解不出 →
     * 走到深域准入处被闸门拦下（真机上这条路径曾白跑 60 秒，§0.97/PROGRESS）。
     * 用真实照片（5031）不行 —— 它在浅域就解出来了，根本到不了闸门；
     * 5 颗星让这条路径既确定又便宜。
     */
    @Test
    fun `深域跳过的原因随快照返回`() {
        val five = listOf(
            DetectedStar(100f, 120f, 300f),
            DetectedStar(400f, 260f, 280f),
            DetectedStar(760f, 180f, 260f),
            DetectedStar(1100f, 700f, 240f),
            DetectedStar(1500f, 900f, 220f),
        )
        val attempt = LocalStarMatcher.matchWithDiagnostics(
            five, 2200, 1650,
            pointingHint = null,
            fallbackDetected = null,
            fovHintDeg = 76.0,
        )
        val d = attempt.diagnostics
        println("DIAG-105 5星 fovHint=76 → result=${attempt.result != null} deep=${d.deepRejectedReason}")
        assertNull("必败输入", attempt.result)
        assertNotNull("可信视场 >45° 时快照必须带「深域已跳过」的原因", d.deepRejectedReason)
        assertTrue(
            "原因文案应说明是视场超界跳过：${d.deepRejectedReason}",
            d.deepRejectedReason!!.contains("已跳过"),
        )
        assertFalse("未用备用表时不得置位", d.usedFallback)
        assertNotNull("失败路径也要有主路径的投票数字", d.voteStats)
        assertNull("浅域就是最后一次尝试 → 不设浅域备份（两份同源时取哪份都一样）", d.shallowVoteStats)
    }

    /**
     * ② 快照是不可变副本：拿到之后再来一次求解，不得改写它。
     *
     * 这正是旧写法的病根 —— 旧字段是共享单槽，第二次求解在入口清零/覆写，
     * 第一次的结果读出来就是第二次的数字（或 null）。
     */
    @Test
    fun `快照不被后续求解改写`() {
        val (starsA, gA) = detect("photo5031.jpg")
        val first = LocalStarMatcher.matchWithDiagnostics(
            starsA, gA.w, gA.h, fovHintDeg = 76.0,
        )
        val snapshot = first.diagnostics
        val statsBefore = snapshot.voteStats
        val reasonBefore = snapshot.deepRejectedReason

        // 第二次求解：换一张照片、不带视场提示（会走深域那条路）→ 全局单槽被覆写
        val (starsB, gB) = detect("photo5040.jpg")
        val second = LocalStarMatcher.matchWithDiagnostics(starsB, gB.w, gB.h)
        println(
            "DIAG-105 前后对比：first.vote=${statsBefore} second.vote=${second.diagnostics.voteStats}",
        )

        assertEquals("第一次的快照不得被第二次求解改写", statsBefore, snapshot.voteStats)
        assertEquals(reasonBefore, snapshot.deepRejectedReason)
        assertEquals(
            "第一次的 usedFallback 不得被改写",
            first.diagnostics.usedFallback,
            snapshot.usedFallback,
        )
        assertTrue(
            "两次求解的留档应来自各自输入（5031 与 5040 的检出数不同）",
            second.diagnostics.voteStats != null,
        )
    }

    /**
     * ③ 并发不串台：两 worker 各解各的，各自快照里的投票轮数字必须与**自己**的
     * 单独运行结果一致（逐字比对）。
     *
     * 旧写法在这里会读到对方的数字（或 null）—— 本断言从测试侧把「谁的数字归谁」
     * 固定下来。多轮重复以扩大交错窗口。
     */
    @Test
    fun `并发两个求解的留档不串台`() {
        val (starsA, gA) = detect("photo5031.jpg")
        val (starsB, gB) = detect("photo5040.jpg")

        // 单独运行基线：留档字符串必须可区分，否则本测试没有判别力
        val soloA = LocalStarMatcher.matchWithDiagnostics(starsA, gA.w, gA.h).diagnostics
        val soloB = LocalStarMatcher.matchWithDiagnostics(starsB, gB.w, gB.h).diagnostics
        println("DIAG-105 solo A=${soloA.voteStats}")
        println("DIAG-105 solo B=${soloB.voteStats}")
        assertNotNull(soloA.voteStats)
        assertNotNull(soloB.voteStats)
        assertTrue(
            "两张照片的投票轮数字应当不同（否则本测试无判别力）",
            soloA.voteStats != soloB.voteStats,
        )

        val rounds = 6
        repeat(rounds) { round ->
            val start = CountDownLatch(1)
            val errA = AtomicReference<Throwable>()
            val errB = AtomicReference<Throwable>()
            fun worker(
                stars: List<DetectedStar>,
                g: JpegLuma.Gray,
                expected: String,
                err: AtomicReference<Throwable>,
            ) = Thread {
                try {
                    start.await()
                    val d = LocalStarMatcher.matchWithDiagnostics(stars, g.w, g.h).diagnostics
                    if (d.voteStats != expected) {
                        err.set(
                            AssertionError(
                                "留档串台（第 $round 轮）：期望 $expected，实得 ${d.voteStats}",
                            ),
                        )
                    }
                } catch (t: Throwable) {
                    err.set(t)
                }
            }
            val ta = worker(starsA, gA, soloA.voteStats!!, errA)
            val tb = worker(starsB, gB, soloB.voteStats!!, errB)
            ta.start(); tb.start()
            start.countDown()
            ta.join(TimeUnit.SECONDS.toMillis(120))
            tb.join(TimeUnit.SECONDS.toMillis(120))
            errA.get()?.let { throw it }
            errB.get()?.let { throw it }
        }
        println("DIAG-105 并发 $rounds 轮 × 2 worker：留档各自归位")
    }

    /**
     * ④ 备用表命中时快照置位（§0.88 机制 + §0.107 快照口径）。
     *
     * 主表 = 干净表 + 100 颗同量级噪声（§0.88 真机 5031 的失败形态），
     * 备用 = 干净表 → 匹配器应改由备用表命中，且**快照**里 usedFallback=true。
     */
    @Test
    fun `备用表命中时快照置位`() {
        val (clean, g) = detect("photo5031.jpg")
        val rnd = Random(31100)
        val noise = List(100) {
            DetectedStar(
                x = rnd.nextFloat() * (g.w - 20f) + 10f,
                y = rnd.nextFloat() * (g.h - 20f) + 10f,
                brightness = 150f + rnd.nextFloat() * 350f,
            )
        }
        val polluted = (clean + noise).sortedByDescending { it.brightness }

        val attempt = LocalStarMatcher.matchWithDiagnostics(
            polluted, g.w, g.h, fallbackDetected = clean,
        )
        val d = attempt.diagnostics
        println(
            "DIAG-105 备用路径：result=${attempt.result?.let { "inliers=${it.inlierCount}" } ?: "UNSOLVED"} " +
                "usedFallback=${d.usedFallback} vote=${d.voteStats}",
        )
        assertNotNull("干净备用表应当把这张解出来（§0.88 机制前提）", attempt.result)
        assertTrue("由备用表命中时快照必须置位", d.usedFallback)
        assertNull("未涉深域时不得有深域拒绝原因", d.deepRejectedReason)
    }
}
