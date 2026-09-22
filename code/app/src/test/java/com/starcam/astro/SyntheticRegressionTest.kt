package com.starcam.astro

import com.starcam.astro.astro.LocalStarMatcher
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * §0.79 CI 侧**合成素材**真值回归。
 *
 * ## 为什么需要它
 *
 * 本机真值回归台（`RealPhotoMatchTest` / `Photo12RegressionTest` /
 * `NarrowFieldRegressionTest` / `SyntheticMidFieldTest`）依赖**不入库**的实拍素材
 * （含私拍原图 —— 隐私与体积上的必要取舍），在 CI 中按 `-PskipPhotoTests=true`
 * 全部跳过。代价是历次关键修复（74° 宽场、暗星、10°~20° 窄场）的唯一保护网
 * 只存在于维护者本机：一旦环境变更或素材丢失，回归能力不可恢复地下降。
 *
 * 本类补的是一层**降级保护**：用星表按**已知** WCS 渲染合成图，不读任何外部素材，
 * 因此 CI 照常执行。合成图的真值就是渲染参数本身（精确已知），所以断言可以直接
 * 落在「解出的中心与视场是否等于真值」上，不需要外部真值文件。
 *
 * ## 它不是等价替代
 *
 * 合成图是**理想素材**：星点干净、无星云污染、无传感器缺陷。它验证的是
 * 「匹配管线在已知真值下能否解出」，**无法**替代真实照片上的假阳性守卫
 * （apod1/2/5、pleiades、m44 必须保持 UNSOLVED 那类断言）与素材缺陷暴露。
 *
 * ## 判定口径
 *
 * 与 [SyntheticMidFieldTest] 保持一致：中心角距 < fov×0.25，视场相对误差 < 25%。
 */
class SyntheticRegressionTest {

    private fun angDist(ra1: Double, dec1: Double, ra2: Double, dec2: Double): Double {
        val r1 = ra1 * PI / 180; val d1 = dec1 * PI / 180
        val r2 = ra2 * PI / 180; val d2 = dec2 * PI / 180
        val c = sin(d1) * sin(d2) + cos(d1) * cos(d2) * cos(r1 - r2)
        return acos(c.coerceIn(-1.0, 1.0)) * 180 / PI
    }

    /**
     * 渲染一场合成图 → 检测 → 匹配。打印诊断行（落进 XML 的 system-out），
     * 解出且落在真值窗口内返回 true。
     *
     * 渲染统一用 mag≤6.5（仓库星表的最深档），让匹配器自己决定走浅域还是深域 ——
     * 这才是生产路径的真实形态。
     */
    private fun runCase(id: String, ra: Double, dec: Double, fov: Double, px: Int): Boolean {
        val r = SyntheticSkyRenderer.render(ra, dec, fov, px, 6.5)
        val stars = LocalStarMatcher.detectStarsGray(px, px, r.gray)
        val res = LocalStarMatcher.match(stars, px, px)
        if (res == null) {
            println("SYN %-12s fov=%5.1f° det=%-3d UNSOLVED".format(id, fov, stars.size))
            return false
        }
        val s = res.solve
        val sep = angDist(s.raDeg, s.decDeg, ra, dec)
        val solvedFov = max(s.fieldWidthDeg, s.fieldHeightDeg)
        val fovErr = abs(solvedFov - fov) / fov
        val ok = sep < fov * 0.25 && fovErr < 0.25
        // 注意：这里不打印 solve.nMatch —— 本地引擎路径不填该字段（恒为 0），
        // 打出来只会误导。内点数的权威来源见 LocalStarMatcher 的调试字段。
        println(
            "SYN %-12s fov=%5.1f° det=%-3d %-9s sep=%5.2f° fovOut=%5.1f° err=%4.1f%%".format(
                id, fov, stars.size, if (ok) "SOLVED" else "MISMATCH",
                sep, solvedFov, fovErr * 100,
            ),
        )
        return ok
    }

    /** 宽场 65°（赤道附近）：主路径，浅星表域即可覆盖 */
    @Test
    fun wideField65Deg() {
        assertTrue(
            "宽场 65° 合成图必须解出，且中心/视场落在真值窗口内",
            runCase("wide-65", 30.0, 0.0, 65.0, 900),
        )
    }

    /**
     * 中场 20°（北天 dec=45）：§0.75 的分级星表域正是把这个区间从「不稳定」
     * 变成「稳定」的，是回归里最值得钉住的一档。
     */
    @Test
    fun midField20Deg() {
        assertTrue(
            "中场 20° 合成图必须解出，且中心/视场落在真值窗口内",
            runCase("mid-20", 120.0, 45.0, 20.0, 700),
        )
    }

    /**
     * 窄场 12°（南天 dec=-40）：需要**深星表域**（mag≤6.5）兜底才能解出 ——
     * 这是 §0.75「窄场支持做到 10°」的核心能力，也是 apod3 真值断言的合成版对照。
     */
    @Test
    fun narrowField12Deg() {
        assertTrue(
            "窄场 12° 合成图必须解出（需深星表域兜底）",
            runCase("narrow-12", 200.0, -40.0, 12.0, 600),
        )
    }
}
