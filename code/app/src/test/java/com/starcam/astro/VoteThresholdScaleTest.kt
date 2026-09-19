package com.starcam.astro

import com.starcam.astro.astro.LocalStarMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §0.71 投票阈值量纲自适应。
 *
 * 背景（用户 2026-09-17 照片的真实失败日志）：
 *   App 的 SEP 路径喂入 simplexy 的 **flux**（量级 1~100），而 `voteThresholds`
 *   的绝对阈值 900 / 60 / 25 是按 box-blur 检测器的亮度量级（典型 top≈4154）标定的。
 *   结果：主阈值 60 只放进 2 颗星、备用阈值 900 一颗不进 → 投票轮 work=0，
 *   打分轮因 `work.size < 5` 直接 return（日志里表现为「打分轮=null」）。
 *
 * 修复：三个档位都表达为「占最亮星 top 的比例」，对 box-blur 量纲保持既有标定
 * 等价，对 SEP 量纲自动等比缩放。
 *
 * 本测试的核心断言是**量纲无关性**：同一分布整体乘任意正数，决策必须一致。
 */
class VoteThresholdScaleTest {

    /**
     * 用户日志里的真实亮度分布（SEP flux 量纲，172 颗星的前 48 颗）。
     * 这个样本值得固化：它是唯一一份来自真实失败现场的分布数据。
     */
    private val realSepFlux = listOf(
        96.7f, 75.1f, 51.8f, 31.8f, 25.2f, 25.1f, 18.1f, 12.2f, 9.2f, 8.6f,
        7.9f, 7.3f, 7.1f, 6.8f, 6.7f, 6.6f, 6.4f, 6.3f, 6.1f, 5.8f,
        5.7f, 5.6f, 5.5f, 5.5f, 5.4f, 5.3f, 5.3f, 5.3f, 5.2f, 5.1f,
        5.0f, 4.8f, 4.8f, 4.7f, 4.7f, 4.5f, 4.5f, 4.4f, 4.3f, 4.3f,
        4.2f, 4.2f, 4.1f, 4.0f, 4.0f, 3.9f, 3.9f, 3.9f,
    )

    /** box-blur 检测器的典型量纲：整体放大到 top≈4154（§0.32.3 演示校准基准） */
    private fun toBoxBlurScale(vals: List<Float>): List<Float> {
        val factor = 4154f / vals.max()
        return vals.map { it * factor }
    }

    private fun workCount(vals: List<Float>, threshold: Float) =
        vals.count { it >= threshold }

    @Test
    fun `SEP flux 量纲下主阈值应放行足够星点（回归：修复前只有 2 颗）`() {
        val (t1, _) = LocalStarMatcher.voteThresholds(realSepFlux)
        val work = workCount(realSepFlux, t1)
        // 修复前 t1=60（绝对）→ work=2，投票建不起三角形
        assertTrue(
            "SEP 量纲下主阈值 $t1 只放行 $work 颗星（需 ≥10 才能建三角形）",
            work >= 10,
        )
    }

    @Test
    fun `两种量纲下的 work 数量必须一致（量纲无关性）`() {
        val sep = realSepFlux
        val box = toBoxBlurScale(realSepFlux)
        val (t1sep, t2sep) = LocalStarMatcher.voteThresholds(sep)
        val (t1box, t2box) = LocalStarMatcher.voteThresholds(box)
        assertEquals(
            "主阈值放行数应量纲无关",
            workCount(sep, t1sep), workCount(box, t1box),
        )
        assertEquals(
            "备用阈值放行数应量纲无关",
            workCount(sep, t2sep), workCount(box, t2box),
        )
    }

    @Test
    fun `阈值随量纲等比缩放`() {
        val sep = realSepFlux
        val factor = 4154f / sep.max()
        val box = sep.map { it * factor }
        val (t1sep, _) = LocalStarMatcher.voteThresholds(sep)
        val (t1box, _) = LocalStarMatcher.voteThresholds(box)
        // 容差 1%：比例常量走 Float
        assertEquals(
            "阈值应等比缩放 $factor 倍",
            (t1sep * factor).toDouble(), t1box.toDouble(), t1box * 0.01,
        )
    }

    @Test
    fun `box-blur 量纲下阈值仍与既有标定同量级`() {
        val box = toBoxBlurScale(realSepFlux)
        val (t1, t2) = LocalStarMatcher.voteThresholds(box)
        // 原实现 sharp=900 / adaptive 下限 60；修复后应落在同一量级，
        // 避免破坏 §0.32.3 与 §0.43 的既有标定
        assertTrue("主阈值 $t1 应在 5~2000 量级", t1 in 5f..2000f)
        assertTrue("备用阈值 $t2 应在 5~2000 量级", t2 in 5f..2000f)
    }

    @Test
    fun `退化输入（全零或全负亮度）不放行任何星点`() {
        val zeros = List(30) { 0f }
        val (t1, t2) = LocalStarMatcher.voteThresholds(zeros)
        assertTrue("全零亮度不应放行任何星点", workCount(zeros, t1) == 0)
        assertTrue("全零亮度不应放行任何星点", workCount(zeros, t2) == 0)

        val negatives = List(30) { -5f - it }
        val (n1, n2) = LocalStarMatcher.voteThresholds(negatives)
        assertTrue("全负亮度不应放行任何星点", workCount(negatives, n1) == 0)
        assertTrue("全负亮度不应放行任何星点", workCount(negatives, n2) == 0)
    }

    @Test
    fun `星点稀少时不崩溃且返回可用阈值`() {
        val few = listOf(10f, 5f, 2f)
        val (t1, t2) = LocalStarMatcher.voteThresholds(few)
        assertTrue("阈值应为有限正数", t1.isFinite() && t1 > 0f)
        assertTrue("阈值应为有限正数", t2.isFinite() && t2 > 0f)
    }
}
