package com.starcam.astro

import com.starcam.astro.astro.DetectorSelection
import com.starcam.astro.astro.LocalStarMatcher
import com.starcam.astro.util.FitsImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * §0.108 端到端：**合成星空 → 写成 FITS → 用 APP 的解码器读回 → 走生产编排求解**。
 *
 * 为什么值得单测：FITS 这条路有三个"静默出错"点，任一走偏都不会崩、只会解不出或解错：
 *   1. **行序**——FITS 自下而上，忘了翻转就是上下颠倒的星场（匹配必失败）；
 *   2. **拉伸**——分位拉伸把数据压到 0..255，压错则星点与背景糊在一起；
 *   3. **视场/先验**——`CDELT × NAXIS` 算错会让深域闸门（§0.103）判错。
 * 本测试把"渲染参数"当真值：解出的天区/视场必须落回渲染参数上；并用亮斑质心钉住方向。
 *
 * 素材全部现场生成（不占仓库体积），CI 照跑。
 */
class FitsSolveEndToEndTest {

    // ---------------------------------------------------------------- FITS 写出

    private fun card(key: String, value: Any?): String {
        val body = when (value) {
            null -> ""
            is String -> "'$value'"
            is Double -> "%.10g".format(value)
            else -> value.toString()
        }
        val text = if (body.isEmpty()) key else "%s= %s".format(key.padEnd(8), body)
        return text.padEnd(80).take(80)
    }

    /**
     * 按 FITS 行序（**第 0 行 = 图像最底行**）写出 BITPIX=-32 图像。
     * [grayImageOrder] 是图像顺序（自上而下，与 SyntheticSkyRenderer 的输出一致）。
     */
    private fun fitsBytes(w: Int, h: Int, grayImageOrder: FloatArray, extra: List<String>): ByteArray {
        val cards = StringBuilder()
        for (c in listOf(
            card("SIMPLE", "T"), card("BITPIX", -32), card("NAXIS", 2),
            card("NAXIS1", w), card("NAXIS2", h),
        )) cards.append(c)
        for (e in extra) cards.append(e)
        cards.append("END".padEnd(80))
        while (cards.length % 2880 != 0) cards.append(" ".repeat(80))
        val out = ByteArrayOutputStream()
        out.write(cards.toString().toByteArray(Charsets.US_ASCII))
        val bb = ByteBuffer.allocate(w * h * 4).order(ByteOrder.BIG_ENDIAN)
        for (r in 0 until h) {                       // r = FITS 行号（自下而上）
            val srcRow = (h - 1 - r) * w             // 对应的图像行
            for (x in 0 until w) bb.putFloat(grayImageOrder[srcRow + x])
        }
        val data = bb.array()
        out.write(data)
        val pad = (2880 - (data.size % 2880)) % 2880
        out.write(ByteArray(pad))
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- 几何助手

    private fun angDist(ra1: Double, dec1: Double, ra2: Double, dec2: Double): Double {
        val r1 = ra1 * PI / 180; val d1 = dec1 * PI / 180
        val r2 = ra2 * PI / 180; val d2 = dec2 * PI / 180
        val c = sin(d1) * sin(d2) + cos(d1) * cos(d2) * cos(r1 - r2)
        return acos(c.coerceIn(-1.0, 1.0)) * 180 / PI
    }

    /**
     * 期望灰度（与 [FitsImage] 同口径，在测试里**独立实现**）：NaN 清零 → 1.0%/99.5% 分位拉伸 → 截断。
     *
     * 注意这里**不再翻转**：本测试的写入函数已按 FITS 约定写成自下而上（见 [fitsBytes]），
     * 解码器的行序翻转恰好把它还原成输入（图像顺序）。所以期望值就是"输入数组 + 拉伸"。
     * 若解码器忘了翻转（或翻错方向），逐点比对会立刻炸 —— 这正是本条断言的价值。
     */
    private fun expectedStretch(grayImageOrder: FloatArray): FloatArray {
        val n = grayImageOrder.size
        val v = grayImageOrder.clone()
        for (i in 0 until n) if (!v[i].isFinite()) v[i] = 0f
        val sorted = v.clone()
        java.util.Arrays.sort(sorted)
        fun pct(p: Double): Float {
            val rank = (p / 100.0) * (sorted.size - 1)
            val lo = kotlin.math.floor(rank).toInt()
            val hi = kotlin.math.ceil(rank).toInt()
            if (lo == hi) return sorted[lo]
            val f = (rank - lo).toFloat()
            return sorted[lo] * (1f - f) + sorted[hi] * f
        }
        val lo = pct(1.0)
        val hi = pct(99.5)
        val span = hi - lo
        return FloatArray(n) { i -> ((v[i] - lo) * (255f / span)).coerceIn(0f, 255f) }
    }

    // ---------------------------------------------------------------- 测试

    @Test
    fun `合成星空写成 FITS 后解码求解，天区与渲染参数一致`() {
        val ra = 83.0
        val dec = -5.0
        val fov = 20.0
        val px = 800
        val rendered = SyntheticSkyRenderer.render(ra, dec, fov, px, 6.5)

        val fits = fitsBytes(
            px, px, rendered.gray,
            listOf(
                card("CRVAL1", ra), card("CRVAL2", dec),
                card("CDELT1", fov / px), card("CDELT2", fov / px),
                card("DATE-OBS", "2024-12-01T16:30:00"),
                card("OBJECT", "SYNTH"),
            ),
        )
        val frame = FitsImage.decodeBytes(fits, maxDim = 2200)
        assertNotNull("FITS 应能解码", frame)
        assertEquals(px, frame!!.w)
        assertEquals(px, frame.h)

        // ① 视场（元数据）应等于渲染参数
        assertEquals("CDELT × 长边应还原渲染视场", fov, frame.meta.fovDeg!!, 1e-6)

        // ② 逐元素比对：方向（行序翻转）与拉伸必须与独立算出的期望完全一致。
        //    为什么不用"亮斑质心"这类启发式：分位拉伸会把高对比场的亮部**饱和**成 255，
        //    前 N 亮像素因此退化为并列（谁排前取决于遍历序），质心不可靠 —— 实测踩过。
        val expect = expectedStretch(rendered.gray)
        var maxDiff = 0f
        for (i in expect.indices) maxDiff = maxOf(maxDiff, abs(expect[i] - frame.gray[i]))
        val satCount = frame.gray.count { it >= 254.9f }
        println(
            "FITS-E2E 解码 %dx%d 最大逐点误差=%.4f 饱和像素=%d(%.2f%%)".format(
                frame.w, frame.h, maxDiff, satCount, satCount * 100.0 / frame.gray.size,
            ),
        )
        assertTrue("方向/拉伸应与期望逐点一致（最大误差 %.4f）".format(maxDiff), maxDiff < 1e-3f)

        // ③ 求解：走生产编排（检出 → 选表 → 匹配），视场提示用 FITS 元数据算出的那个
        val stars = LocalStarMatcher.detectStarsGray(frame.w, frame.h, frame.gray, 100)
        val plan = DetectorSelection.plan(
            boxBlur = stars, sep = null, sepTrustworthy = false, detectedTotal = stars.size,
        )
        assertNotNull("合成 FITS 的星点应够匹配（检出 ${stars.size} 颗）", plan.primary)
        val res = LocalStarMatcher.match(
            plan.primary!!, frame.w, frame.h,
            pointingHint = null,
            fallbackDetected = plan.fallback,
            fovHintDeg = frame.meta.fovDeg,
        )
        assertNotNull("合成 FITS 应能解出（当前 UNSOLVED —— FITS 解码链有问题）", res)
        val s = res!!.solve
        val sep = angDist(s.raDeg, s.decDeg, ra, dec)
        val solvedFov = max(s.fieldWidthDeg, s.fieldHeightDeg)
        val fovErr = abs(solvedFov - fov) / fov
        println(
            "FITS-E2E 解码 %dx%d → 检出 %d → SOLVED ra=%.2f dec=%.2f fov=%.1f inliers=%d sep=%.2f° err=%.1f%%"
                .format(frame.w, frame.h, stars.size, s.raDeg, s.decDeg, solvedFov, res.inlierCount, sep, fovErr * 100),
        )
        assertTrue("中心偏离渲染参数 %.2f°（上限 %.1f°）".format(sep, fov * 0.25), sep < fov * 0.25)
        assertTrue("视场相对误差 %.1f%%（上限 25%%）".format(fovErr * 100), fovErr < 0.25)
    }

    @Test
    fun `拉伸把暗场映射到可见区间（不是全黑或全白）`() {
        // 低对比输入：1000±5 的均匀场 + 一颗亮斑
        val w = 64
        val h = 64
        val gray = FloatArray(w * h) { 1000f }
        gray[20 * w + 20] = 1400f
        val frame = FitsImage.decodeBytes(
            fitsBytes(w, h, gray, listOf(card("OBJECT", "LOWCONTRAST"))),
            maxDim = 2200,
        )!!
        val min = frame.gray.min()
        val max = frame.gray.max()
        println("FITS-E2E 低对比拉伸：min=%.1f max=%.1f".format(min, max))
        assertEquals("背景应被压到 0 附近", 0f, min, 1.0f)
        assertEquals("亮斑应被拉到 255 附近", 255f, max, 1.0f)
    }
}
