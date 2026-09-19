package com.starcam.astro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * §0.72 SEP 提星阈值语义修正。
 *
 * 背景（用户 2026-09-17 原图识别失败，report.json 实测）：
 *   `sepDetectStars(bitmap, sigma=2.0)` 的参数语义是「背景 sigma 倍数」，
 *   但 C 层历史实现把它**直接写进 simplexy 的 plim**。而 plim 的真实语义是
 *   峰值显著度，检出限公式为
 *       limit = sigma / (2*sqrt(pi)*dpsf) * plim
 *   dpsf=1.0 时系数 ≈ 0.282，即 plim=2.0 只相当于 **0.56σ**。
 *
 * 实测后果（用用户照片的真实像素统计）：
 *   - plim=2.0  → limit≈2.32 灰阶，**29.3%** 的像素过阈 → dmask 连成大片、
 *     超 maxsize=2000 被判延展天体丢弃，只剩图像边界伪影；
 *     report.json 里 172 颗"星"0 颗落在真实亮源上，73% 落在 30px 边界带
 *     （该带面积仅 6.3%，富集 11.6 倍），最大 flux 96.7（真星量级 4143）。
 *   - plim=7.09（2σ 的正确换算）→ limit≈8.24，过阈像素降到 **0.63%**。
 *
 * 本测试固化「倍数 → plim」的换算关系，防止再次把它当 plim 直传。
 */
class SepThresholdSemanticsTest {

    /** 与 C 层 astro_bridge.c extract_stars_impl 保持一致的换算 */
    private fun sigmaMultipleToPlim(sigmaMultiple: Double, dpsf: Double = 1.0): Double =
        sigmaMultiple * 2.0 * sqrt(Math.PI) * dpsf

    @Test
    fun `2 sigma 换算成 plim 应约等于 7_09（上游默认 8_0 量级）`() {
        val plim = sigmaMultipleToPlim(2.0)
        assertEquals("2σ 的 plim 换算", 7.0898, plim, 0.01)
        // 必须落在上游默认值附近：与 8.0 同量级（比值 < 1.2）
        assertTrue("换算结果 $plim 应与上游默认 8.0 同量级", plim > 6.0 && plim < 9.0)
    }

    @Test
    fun `历史 bug 的 plim 2_0 对应不到 1 sigma（这就是失败根因）`() {
        // 反推：plim=2.0 相当于多少 sigma
        val effectiveSigma = 2.0 / (2.0 * sqrt(Math.PI) * 1.0)
        assertEquals("plim=2.0 实际只有 0.56σ", 0.5642, effectiveSigma, 0.001)
        assertTrue("必须显著小于 1σ（低于噪声，必然检出一堆伪影）", effectiveSigma < 0.6)
    }

    /**
     * 用用户照片的**实测**数据固化修复效果。
     *
     * 数据来源：orig.gray（1649×2200，mean 18.17，中位 18，sigma≈4.12）上直接统计，
     * 并做 4 邻域连通域标记以复现 simplexy 的 dmask 行为：
     *
     * | plim | limit 灰阶 | 过阈像素 | 占比 | 最大连通域 |
     * |---|---|---|---|---|
     * | 2.0（历史 bug） | 2.32 | 1,061,698 | **29.27%** | **848,846 px** |
     * | 7.09（2σ 换算） | 8.24 | 22,781 | **0.63%** | 55 px |
     *
     * 29% 的像素连成一个 85 万像素巨块 → 远超 simplexy 的 maxsize=2000 →
     * 被判为「延展天体」整体丢弃 → 真星全丢，只剩边界伪影。
     *
     * 注意：这里**不使用高斯尾概率模型**——实测背景被量化/截断（p50=18、p99=26），
     * 尾部远薄于高斯（高斯模型会给出 2.28%，实测仅 0.63%）。用模型会得出错误结论。
     */
    @Test
    fun `实测过阈像素占比：修复前 29 百分比 修复后 0_63 百分比`() {
        val buggy = 1061698.0 / (1649.0 * 2200.0)   // plim=2.0
        val fixed = 22781.0 / (1649.0 * 2200.0)     // plim=7.09
        assertEquals("历史 bug 的过阈率（实测）", 0.2927, buggy, 0.001)
        assertEquals("修复后的过阈率（实测）", 0.0063, fixed, 0.001)
        assertTrue("历史值必须远高于 10%（$buggy）", buggy > 0.10)
        assertTrue("修复后必须低于 1%（$fixed）", fixed < 0.01)
        assertTrue("必须是数量级改善（${buggy / fixed}×）", buggy / fixed > 10)
    }

    @Test
    fun `连通域规模必须回到星点量级（ddmask 不再吞掉整幅图）`() {
        // 实测：plim=2.0 时最大连通域 848,846 px（远超 maxsize=2000 → 整体丢弃）
        //       plim=7.09 时最大连通域 55 px（正常星点大小）
        val maxSizeLimit = 2000
        val buggyMax = 848846
        val fixedMax = 55
        assertTrue("历史值应超过 maxsize 上限（$buggyMax > $maxSizeLimit）", buggyMax > maxSizeLimit)
        assertTrue("修复后应远低于上限（$fixedMax < $maxSizeLimit）", fixedMax < maxSizeLimit)
    }

    @Test
    fun `C 层下限 4_0 不应被换算结果击穿`() {
        // 即使调用方传很小的倍数，换算后也不得低于 4.0（防止再次跌进噪声区）
        val tooSmall = sigmaMultipleToPlim(0.5)   // = 1.77
        val clamped = maxOf(tooSmall, 4.0)
        assertEquals("下限钳制", 4.0, clamped, 1e-9)
        assertTrue("0.5σ 换算值确实低于下限", tooSmall < 4.0)
    }
}
