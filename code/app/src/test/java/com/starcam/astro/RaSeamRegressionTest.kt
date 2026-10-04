package com.starcam.astro

import com.starcam.astro.astro.LocalStarMatcher
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * §0.91 跨赤经 0°/360° 接缝的**投票轮**回归（外部缺陷报告 S1）。
 *
 * ## 缺陷
 *
 * 候选验证 `verifyCandidate` 的切平面投影中心原用**算术平均**
 * `(eA.ra + eB.ra + eC.ra) / 3`。天区跨接缝时（如 359.5° 与 0.9°）这个平均值会落到
 * 天区对面，投影完全错位 → 第 4 星验证恒失败 → **投票轮在该天区零产出**，只能靠
 * 打分轮兜底，识别率与速度双输。同文件的 `fitPairs` 早已改用球面平均（§0.60 记录过
 * 同一教训：「算术平均 117° 偏出真实中心」），`verifyCandidate` 是漏改的一处。
 *
 * ## 为什么本测试要关掉打分轮
 *
 * 打分轮是兜底：即使投票轮因该缺陷全灭，它仍可能把照片解出来。那样断言「能解出」
 * 就会被兜底掩盖，测不出缺陷。故这里用项目既有的 A/B 开关 `debugMinAligned`
 * （设为极大即停用打分轮，见 LocalStarMatcher 该字段注释），使**投票轮成为唯一出路**。
 *
 * 合成图由 [SyntheticSkyRenderer] 渲染，**不读任何外部素材**，故 CI 中照常执行。
 * 对照组与接缝组只差赤经中心（一个跨 0°、一个不跨），其余几何完全相同 ——
 * 差异只能归因于接缝。
 */
class RaSeamRegressionTest {

    @After
    fun restoreScoredRound() {
        LocalStarMatcher.debugMinAligned = null
    }

    private fun angDist(ra1: Double, dec1: Double, ra2: Double, dec2: Double): Double {
        val r1 = ra1 * PI / 180; val d1 = dec1 * PI / 180
        val r2 = ra2 * PI / 180; val d2 = dec2 * PI / 180
        val c = sin(d1) * sin(d2) + cos(d1) * cos(d2) * cos(r1 - r2)
        return acos(c.coerceIn(-1.0, 1.0)) * 180 / PI
    }

    /** 停用打分轮后求解：返回「解出且中心/视场落在真值窗口内」 */
    private fun voteRoundOnly(id: String, ra: Double, dec: Double, fov: Double, px: Int): Boolean {
        LocalStarMatcher.debugMinAligned = Int.MAX_VALUE // 只留投票轮（含弱星/备用轮）
        val r = SyntheticSkyRenderer.render(ra, dec, fov, px, 6.5)
        val stars = LocalStarMatcher.detectStarsGray(px, px, r.gray)
        val res = LocalStarMatcher.match(stars, px, px)
        if (res == null) {
            println("SEAM %-10s ra=%6.1f dec=%5.1f fov=%4.1f° det=%-3d UNSOLVED（投票轮）".format(id, ra, dec, fov, stars.size))
            return false
        }
        val s = res.solve
        val sep = angDist(s.raDeg, s.decDeg, ra, dec)
        val solvedFov = max(s.fieldWidthDeg, s.fieldHeightDeg)
        val fovErr = abs(solvedFov - fov) / fov
        val ok = sep < fov * 0.25 && fovErr < 0.25
        println(
            "SEAM %-10s ra=%6.1f dec=%5.1f fov=%4.1f° det=%-3d %-8s sep=%5.2f° fovOut=%5.1f° err=%4.1f%% inl=%d".format(
                id, ra, dec, fov, stars.size, if (ok) "SOLVED" else "MISMATCH",
                sep, solvedFov, fovErr * 100, res.inlierCount,
            ),
        )
        return ok
    }

    /**
     * 接缝组：中心 RA=0.3°、20° 视场（dec=25° 处赤经跨度约 22°），**跨越 0°/360°**。
     * 缺陷在时投票轮全灭 → 本断言失败。
     */
    @Test
    fun `跨接缝天区的投票轮必须能解出`() {
        assertTrue(
            "跨 RA 0° 接缝的合成场必须能由**投票轮**解出（S1：投影中心须用球面平均）",
            voteRoundOnly("seam-20", 0.3, 25.0, 20.0, 700),
        )
    }

    /** 对照组：同几何、赤经中心 30°（不跨接缝）—— 用来证明差异只来自接缝。 */
    @Test
    fun `对照组_非接缝天区的投票轮必须能解出`() {
        assertTrue(
            "不跨接缝的对照组必须能由投票轮解出（否则本测试的基座不成立）",
            voteRoundOnly("ctrl-20", 30.0, 25.0, 20.0, 700),
        )
    }
}
