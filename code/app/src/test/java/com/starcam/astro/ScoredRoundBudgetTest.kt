package com.starcam.astro

import com.starcam.astro.astro.LocalStarMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * §0.87 打分轮预算回归：**它必须是确定性的**，不能是墙上时钟。
 *
 * 背景（真机 5031 识别失败的根因）：
 *   §0.62 给打分轮加了 3 秒墙上时钟预算，依据是「真解候选通常出现在序列前段」。
 *   该假设对宽场照片**不成立**：候选按比值偏差升序排列，而 gnomonic 投影下真
 *   三角形的比值偏差最大，于是真解恒排在候选序列**末位**。实测 12 张演示照中
 *   凡走打分轮的 8 张，获胜候选序号都等于已评分总数（218/218、1116/1116、
 *   8730/8730，重复运行稳定）。
 *
 *   打分轮因此没有任何余量：墙上时钟任意一次检查点触发，就必然错过唯一正解。
 *   结果是**同一张照片在快机器上解得出来、在慢/冷机器上解不出来** —— 真机失败
 *   页显示的深域数字（7/715），正是浅域被打断后深域重试留下的。
 *
 * 本测试把「跑完整串才能解出」这一性质钉住：若今后有人重新引入工作/时间预算
 * 把打分轮截断，`winAt == scored` 的断言会先失败。
 */
class ScoredRoundBudgetTest {

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

    private fun scoredCount(): Long =
        Regex("scored=(\\d+)").find(LocalStarMatcher.debugScoredStats ?: "")
            ?.groupValues?.get(1)?.toLong() ?: -1L

    /** 真机失败的那张：宽场、欠曝、32 颗星。本地必须能解出，且**跑完整串候选**。 */
    @Test
    fun `5031 宽场欠曝照片：打分轮跑完整串候选才能解出（不得被预算截断）`() {
        val (w, h, gray) = loadGray(File(photoDir(), "1787670195031.gray"))
        val stars = LocalStarMatcher.detectStarsGray(w, h, gray)
        assertTrue("提星数异常：${stars.size}", stars.size >= 8)

        val res = LocalStarMatcher.match(stars, w, h)
        assertTrue("5031 必须解出（宽场浅域）", res != null)

        val scored = scoredCount()
        assertTrue("打分轮未执行或统计缺失：${LocalStarMatcher.debugScoredStats}", scored > 0)
        assertTrue(
            "打分轮被截断（CUT 标记）：${LocalStarMatcher.debugScoredStats}",
            LocalStarMatcher.debugScoredStats?.contains("CUT=") != true,
        )
        assertEquals(
            "获胜候选应位于候选序列末位（宽场的固有性质，见类注释）：" +
                "winAt=${LocalStarMatcher.debugScoredWinAt} scored=$scored",
            scored, LocalStarMatcher.debugScoredWinAt,
        )
    }

    /** 另一张宽场难图：同样必须跑完整串。 */
    @Test
    fun `5092 宽场照片同样不得被预算截断`() {
        val f = File(photoDir(), "1787670195092.gray")
        if (!f.isFile) return
        val (w, h, gray) = loadGray(f)
        val stars = LocalStarMatcher.detectStarsGray(w, h, gray)
        val res = LocalStarMatcher.match(stars, w, h)
        assertTrue("5092 必须解出", res != null)
        assertTrue(
            "打分轮被截断：${LocalStarMatcher.debugScoredStats}",
            LocalStarMatcher.debugScoredStats?.contains("CUT=") != true,
        )
    }
}
