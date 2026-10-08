package com.starcam.astro.ui.batch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * P0-2.2 / P0-2.3 回归（外部审计 2026-10-06）。
 *
 * 两条被测试锁住的契约：
 *  1. **文案不是控制流**（P0-2.2）：结果的成败分类只由类型决定。旧实现用
 *     `note.startsWith("未能"/"无法"/"渲染"/"处理")` 判失败 —— 本测试故意构造
 *     带这些前缀的**成功**文案，验证分类不受影响；
 *  2. **保存失败不等于已导出**（P0-2.3）：`SolvedButSaveFailed` 只计入 solved，
 *     不计入 saved；旧实现无条件自增，汇总会说"成功并导出 N 张"的假话。
 */
class BatchOutcomeTest {

    private fun saved(note: String = "Pictures/StarCam/a.jpg") = BatchOutcome.Saved(note)
    private fun saveFailed(note: String = "已识别但保存相册失败") = BatchOutcome.SolvedButSaveFailed(note)
    private fun solveFailed(note: String = "未能识别（亮星不足或视场不支持）") = BatchOutcome.SolveFailed(note)
    private fun processingFailed(note: String = "处理异常：boom") = BatchOutcome.ProcessingFailed(note)

    @Test
    fun savedCountsAsSolvedAndExported() {
        val t = BatchTally()
        t.record(saved())
        assertEquals(1, t.solved)
        assertEquals(1, t.saved)
        assertEquals(0, t.failed)
        assertEquals(1, t.done)
    }

    @Test
    fun solvedButSaveFailedCountsAsSolvedOnly() {
        val t = BatchTally()
        t.record(saveFailed())
        assertEquals("识别成功应计入 solved", 1, t.solved)
        assertEquals("保存失败绝不能计入已导出", 0, t.saved)
        assertEquals(0, t.failed)
        assertEquals(1, t.done)
    }

    @Test
    fun failuresCountAsFailed() {
        val t = BatchTally()
        t.record(solveFailed())
        t.record(processingFailed())
        assertEquals(0, t.solved)
        assertEquals(0, t.saved)
        assertEquals(2, t.failed)
        assertEquals(2, t.done)
    }

    @Test
    fun mixedBatchCountsExactly() {
        val t = BatchTally()
        repeat(3) { t.record(saved()) }
        repeat(2) { t.record(saveFailed()) }
        t.record(solveFailed())
        repeat(4) { t.record(processingFailed()) }
        assertEquals(5, t.solved)     // 3 导出 + 2 已识别未导出
        assertEquals(3, t.saved)      // 只有 3 张真的落到相册
        assertEquals(5, t.failed)
        assertEquals(10, t.done)
    }

    /**
     * P0-2.2 核心：**文案不参与分类**。
     * 故意让成功结果的文案带上旧实现的失败前缀（未能/无法/渲染/处理），
     * 分类必须不受影响 —— 旧代码在这里会把"成功"误判为失败。
     */
    @Test
    fun noteTextDoesNotAffectClassification() {
        val t = BatchTally()
        t.record(saved("未能看清？不，这张其实存成功了"))
        t.record(saved("无法读取？不，这是导出后的路径名"))
        t.record(saveFailed("渲染失败？不，渲染成功了，只是相册写入失败"))
        t.record(saveFailed("处理异常？不，这是文案而已"))
        assertEquals(4, t.solved)
        assertEquals(2, t.saved)
        assertEquals(0, t.failed)
    }

    /** 反向锁：失败结果的文案即使看起来像成功，也必须是失败。 */
    @Test
    fun failureStaysFailureRegardlessOfNote() {
        val t = BatchTally()
        t.record(solveFailed("✓ 已导出（这句是文案，不是事实）"))
        t.record(processingFailed("Pictures/StarCam/looks-like-a-path.jpg"))
        assertEquals(0, t.solved)
        assertEquals(0, t.saved)
        assertEquals(2, t.failed)
    }

    @Test
    fun outcomeFlagsAreConsistent() {
        assertTrue(saved().solved)
        assertTrue(saved().exported)
        assertTrue(saveFailed().solved)
        assertFalse("已识别未导出不得标记为 exported", saveFailed().exported)
        assertFalse(solveFailed().solved)
        assertFalse(solveFailed().exported)
        assertFalse(processingFailed().solved)
        assertFalse(processingFailed().exported)
    }

    @Test
    fun snapshotIsConsistentWithCounters() {
        val t = BatchTally()
        t.record(saved())
        t.record(saveFailed())
        t.record(solveFailed())
        val s = t.snapshot()
        assertEquals(t.solved, s.solved)
        assertEquals(t.saved, s.saved)
        assertEquals(t.failed, s.failed)
        assertEquals(t.done, s.done)
    }

    /** 两个 worker 并发记账（与生产一致）：计数不丢不重。 */
    @Test
    fun concurrentRecordIsThreadSafe() {
        val t = BatchTally()
        val perThread = 500
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        repeat(2) { worker ->
            pool.execute {
                start.await()
                repeat(perThread) {
                    // worker 0 记成功，worker 1 记失败
                    if (worker == 0) t.record(saved("ok")) else t.record(processingFailed("boom"))
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue("并发记账未在 30 秒内完成", pool.awaitTermination(30, TimeUnit.SECONDS))
        assertEquals(perThread, t.solved)
        assertEquals(perThread, t.saved)
        assertEquals(perThread, t.failed)
        assertEquals(2 * perThread, t.done)
    }
}
