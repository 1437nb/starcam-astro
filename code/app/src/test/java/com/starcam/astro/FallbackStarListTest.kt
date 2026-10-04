package com.starcam.astro

import com.starcam.astro.astro.DetectedStar
import com.starcam.astro.astro.LocalStarMatcher
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * §0.88 备用星表重试的依据回归。
 *
 * 真机实测（5031，76° 宽场欠曝，177 颗 SEP 星）：主星表把浅域打分轮打满 2 万候选上限，
 * 最优仍只有 8/206（对齐率 0.04）；而同一张照片的 box-blur 星表只有 32 颗，
 * 本机用同口径像素复刻是 16/30 = 0.533 轻松过门 —— 多余星把邻域与候选生成挤坏，
 * **真解候选根本生成不出来**（不是门槛误杀）。
 *
 * 本测试把这一机制固定下来：同一份干净星表，注入与真星同量级的噪声星后主列表解不出，
 * 而干净列表（= 备用星表）可解出。若将来有人「优化」掉备用重试，本测试会提醒其代价。
 */
class FallbackStarListTest {

    private fun loadGray(file: File): Triple<Int, Int, FloatArray> {
        val bytes = file.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val w = buf.int
        val h = buf.int
        val gray = FloatArray(w * h)
        buf.asFloatBuffer().get(gray)
        return Triple(w, h, gray)
    }

    private fun photoDir(): File = listOf(
        "../../testdata/gray12", "../testdata/gray12", "testdata/gray12",
    ).map { File(it) }.firstOrNull { it.isDirectory }
        ?: error("12 张回归素材缺失")

    @Test
    fun `主星表被同量级噪声星污染时解不出，备用干净星表可解出`() {
        val (w, h, gray) = loadGray(File(photoDir(), "1787670195031.gray"))
        val clean = LocalStarMatcher.detectStarsGray(w, h, gray)
        assertTrue("干净星表应可解（前提）", LocalStarMatcher.match(clean, w, h) != null)

        // 与真星同量级（150~500）的噪声星 100 颗，固定种子保证可复现
        val rnd = Random(31100)
        val noise = List(100) {
            DetectedStar(
                x = rnd.nextFloat() * (w - 20f) + 10f,
                y = rnd.nextFloat() * (h - 20f) + 10f,
                brightness = 150f + rnd.nextFloat() * 350f,
            )
        }
        val polluted = (clean + noise).sortedByDescending { it.brightness }

        assertNull(
            "被同量级噪声污染的主星表应当解不出（真机 5031 的失败形态）",
            LocalStarMatcher.match(polluted, w, h),
        )
        assertNotNull(
            "备用干净星表必须仍可解出 —— 这是 §0.88 备用重试的价值所在",
            LocalStarMatcher.match(clean, w, h),
        )
    }

    /**
     * §0.88 主入口行为：主星表解不出时，传入 [LocalStarMatcher.match] 的备用星表
     * 应在**浅域**接管并解出（无需重跑深域），且 `lastMatchUsedFallback` 置位。
     */
    @Test
    fun `主星表失败时备用星表应在浅域接管并解出`() {
        val (w, h, gray) = loadGray(File(photoDir(), "1787670195031.gray"))
        val clean = LocalStarMatcher.detectStarsGray(w, h, gray)

        val rnd = Random(31100)
        val noise = List(100) {
            DetectedStar(
                x = rnd.nextFloat() * (w - 20f) + 10f,
                y = rnd.nextFloat() * (h - 20f) + 10f,
                brightness = 150f + rnd.nextFloat() * 350f,
            )
        }
        val polluted = (clean + noise).sortedByDescending { it.brightness }

        val res = LocalStarMatcher.match(
            polluted, w, h, null, allowDeepRetry = true, fallbackDetected = clean,
        )
        assertNotNull("备用星表应在浅域接管并解出", res)
        assertTrue(
            "应当由备用星表解出（lastMatchUsedFallback 置位）",
            LocalStarMatcher.lastMatchUsedFallback,
        )
        // 必须解到正确天区（5031 真值 13.4 / 71.0，容差同 Photo12 回归口径）
        val s = res!!.solve
        val dra = Math.abs(s.raDeg - 13.4).let { minOf(it, 360.0 - it) }
        assertTrue("备用星表解出的赤经偏出真值窗口：${s.raDeg}", dra < 3.0)
        assertTrue("备用星表解出的赤纬偏出真值窗口：${s.decDeg}", Math.abs(s.decDeg - 71.0) < 3.0)

        // 反例：不给备用列表时，同一份污染列表仍然解不出（保证新参数是唯一变量）
        assertNull(
            "不给备用列表时应维持原行为（解不出）",
            LocalStarMatcher.match(polluted, w, h),
        )
        assertTrue("未用备用星表时标志不应置位", !LocalStarMatcher.lastMatchUsedFallback)
    }
}
