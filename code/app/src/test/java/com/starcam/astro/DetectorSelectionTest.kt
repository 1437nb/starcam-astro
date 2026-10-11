package com.starcam.astro

import com.starcam.astro.astro.DetectedStar
import com.starcam.astro.astro.DetectorSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 选表接缝的单元回归（纯合成列表，秒级、CI 照跑）。
 *
 * 被钉住的是 §0.97b 事故的**全部形态**（真机 5031 从「2 秒解出」退化成「230 秒失败」）：
 *  ① 喂给匹配器的主表被预览表顶掉；
 *  ② 备用表与主表成了**同一个对象** → 匹配器判定「无备用」，box-blur 表从未参与；
 *  ③ SEP 与 box-blur 重合率过低时 SEP 仍被当可信备用；
 *  ④ 两表都不可用时没有走「再算一次 box-blur」的重试。
 *
 * 这些断言的意义：生产路径（StarSolver → [DetectorSelection.plan] → match）与
 * 本测试走的是同一个函数，所以这里绿 = 生产不会犯这类错。
 */
class DetectorSelectionTest {

    /** n 颗成网格状排开的星（互不重合、彼此距离远大于 8px 半径）；顺序即「亮度降序」的替身。 */
    private fun grid(n: Int, startX: Float = 20f, stepX: Float = 40f, y: Float = 50f): List<DetectedStar> =
        (0 until n).map { DetectedStar(startX + it * stepX, y, 100f) }

    @Test
    fun `首选必是 box-blur 表，预览表不影响匹配输入`() {
        // 真机 5031 的形态：SEP 172 颗（更多），box-blur 28 颗（更便宜、且是回归台口径）
        val box = grid(28)
        val sep = grid(172)
        val plan = DetectorSelection.plan(boxBlur = box, sep = sep, sepTrustworthy = true, detectedTotal = 172)

        assertSame("主表必须是 box-blur 表本身（§0.97b 前半：不许被预览表顶掉）", box, plan.primary)
        assertSame("备用表必须是 SEP 表本身", sep, plan.fallback)
        assertFalse("备用表与主表不得是同一个对象（§0.97b 事故指纹）", plan.fallbackShadowedByPrimary)
        assertSame("预览给「看得更全」的那张（SEP）", sep, plan.preview)
        assertEquals("detectedTotal 应沿用未截断的总数", 172, plan.detectedTotal)
        assertTrue("诊断行应写明首选与备用：${plan.note}", plan.note.contains("首选box-blur(28颗)"))
        assertTrue("诊断行应写明备用颗数：${plan.note}", plan.note.contains("备用SEP(172颗)"))
    }

    @Test
    fun `SEP 与 box-blur 重合率过低时撤销可信，不再作备用`() {
        val box = grid(28, startX = 20f, stepX = 40f)
        // SEP 全在另一片区域：重合 0/28 → 不可信（典型：阈值失配出一堆边界伪影）
        val sep = grid(28, startX = 20f, stepX = 40f, y = 4000f)
        val plan = DetectorSelection.plan(boxBlur = box, sep = sep, sepTrustworthy = true, detectedTotal = 28)

        assertSame("主表仍是 box-blur", box, plan.primary)
        assertNull("SEP 不可信 → 不得作备用", plan.fallback)
        assertTrue("诊断行应记录重合率：${plan.note}", plan.note.contains("与box-blur重合=0/28"))
        assertTrue("诊断行应说明只留 box-blur：${plan.note}", plan.note.contains("SEP不可信"))
    }

    @Test
    fun `重合率达标时 SEP 作备用`() {
        val box = grid(28, startX = 20f, stepX = 40f)
        // SEP 与 box-blur 前 30 颗几乎重合（偏移 5px < 8px 半径）
        val sep = box.map { DetectedStar(it.x + 5f, it.y, it.brightness) }
        val plan = DetectorSelection.plan(boxBlur = box, sep = sep, sepTrustworthy = true, detectedTotal = 28)
        assertSame("重合率 28/28 达标 → SEP 作备用", sep, plan.fallback)
    }

    @Test
    fun `box-blur 不可用时直接用 SEP 表`() {
        val sep = grid(20)
        val plan = DetectorSelection.plan(boxBlur = null, sep = sep, sepTrustworthy = true, detectedTotal = 20)
        assertSame("box-blur 表不可用（原生/检测失败的形态）→ 主表用 SEP", sep, plan.primary)
        assertNull("单表没有备用", plan.fallback)
        assertTrue("诊断行应说明走了 SEP：${plan.note}", plan.note.contains("box-blur 表不可用"))
    }

    @Test
    fun `box-blur 少于门限时也直接用 SEP 表`() {
        val sep = grid(20)
        val few = grid(4) // <5 颗，匹配器不开工
        val plan = DetectorSelection.plan(boxBlur = few, sep = sep, sepTrustworthy = true, detectedTotal = 20)
        assertSame("box-blur 只有 4 颗（<5）→ 主表用 SEP", sep, plan.primary)
        assertNull(plan.fallback)
    }

    @Test
    fun `两表都不可用时走重试分支`() {
        var retryCalls = 0
        val retryList = grid(12)
        val plan = DetectorSelection.plan(
            boxBlur = null,
            sep = null,
            sepTrustworthy = false,
            detectedTotal = 0,
            boxBlurRetry = { retryCalls++; retryList },
        )
        assertEquals("重试应当被调用一次", 1, retryCalls)
        assertSame("重试结果即主表", retryList, plan.primary)
        assertNull("重试路径没有备用", plan.fallback)
        assertEquals("detectedTotal 应改为重试的颗数", 12, plan.detectedTotal)
        assertTrue("诊断行应记录回退颗数：${plan.note}", plan.note.contains("回退box-blur=12颗"))
    }

    @Test
    fun `重试也失败时主表为空、调用方不应匹配`() {
        val plan = DetectorSelection.plan(
            boxBlur = null, sep = null, sepTrustworthy = false, detectedTotal = 0,
            boxBlurRetry = { null },
        )
        assertNull("重试仍失败 → 无主表", plan.primary)
        assertNull(plan.preview)
        assertEquals(0, plan.detectedTotal)
        assertTrue(plan.note.contains("回退box-blur=0颗"))
    }

    @Test
    fun `重试结果不足5颗时仍作主表返回（让调用方报「星点太少」）`() {
        val few = grid(3)
        val plan = DetectorSelection.plan(
            boxBlur = null, sep = null, sepTrustworthy = false, detectedTotal = 0,
            boxBlurRetry = { few },
        )
        assertSame("不足 5 颗的重试结果仍要透传（失败文案依赖它）", few, plan.primary)
    }

    @Test
    fun `备用与主表同一对象时被识别为退化`() {
        // 事故的原形态：有人把「主表」和「备用」都指向同一个列表
        val both = grid(20)
        val plan = DetectorSelection.plan(boxBlur = both, sep = both, sepTrustworthy = true, detectedTotal = 20)
        assertTrue("同一对象必须被识别为备用退化", plan.fallbackShadowedByPrimary)
        assertTrue(
            "诊断行必须留下事故指纹告警：${plan.note}",
            plan.note.contains("备用表与主表相同"),
        )
    }

    @Test
    fun `重合率无星可比值时为0`() {
        val ov = DetectorSelection.overlap(emptyList(), grid(5))
        assertEquals(0, ov.hits)
        assertEquals(0, ov.of)
        assertEquals(0f, ov.rate, 0.0001f)
    }

    @Test
    fun `重合率按最亮前30颗比对（与内联实现同口径）`() {
        // 50 颗 box-blur，其中前 30 颗与 SEP 重合、后 20 颗不重合 → 28/28 形态应为 30/30
        val first = grid(30, startX = 20f, stepX = 40f, y = 50f)
        val rest = grid(20, startX = 20f, stepX = 40f, y = 9000f)
        val box = first + rest
        val sep = first
        val ov = DetectorSelection.overlap(box, sep)
        assertEquals("只比 box-blur 最亮前 30 颗", 30, ov.of)
        assertEquals(30, ov.hits)
    }
}
