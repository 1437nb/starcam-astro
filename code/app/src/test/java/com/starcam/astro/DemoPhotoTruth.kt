package com.starcam.astro

/**
 * 12 张**公开演示照片**的真值表 —— CI 侧回归（[OfflinePhotosTruthTest]）与开发机
 * 真值台（[Photo12RegressionTest]）共用，避免两处真值各写一份而漂移。
 *
 * ## 为什么这份真值可以入库（审计 P1-2.2）
 *
 * 素材本身就是 app 自带的公开演示图（`assets/offline_photos/photo49xx.jpg`，
 * 随 APK 发布，无隐私问题），且真值只描述**天区坐标**，不涉及拍摄者信息。
 * 相比之下 testdata/ 里的私拍原图与 .gray 不入库，只能在本机/验证机跑 ——
 * 那 8 个真值台在 CI 里是跳过的，这正是审计要补的缺口。
 *
 * ## 真值来源（docs/06 §0.12）
 *
 *  - 官方 solve-field 解 + 919 星表叠加目视核实；
 *  - 4963 于 §0.43 修订（官方旧解物理不可能，与 4974 同天区的交叉验证为准）；
 *  - 4998 由官方 WCS 锚定（68.08″/px @4000px）。
 *
 * ## 判定口径
 *
 * 与 Photo12RegressionTest 的常驻断言一致（§0.32 起）：
 *  - TRUE-SOLVE：解出且天区/视场都落真值窗口；
 *  - FALSE-POSITIVE：解出但天区或视场不符（错误天区、尺度坍缩/膨胀——§0.28 门槛的对象）；
 *  - MISS：未解出（能力边界，不算回归；但**绝不能**是假阳性）。
 */
internal object DemoPhotoTruth {

    /** 真值：中心 RA/Dec（度）、长边视场（度）。 */
    data class Truth(val ra: Double, val dec: Double, val fovDeg: Double)

    /** 引擎解出的天区/视场（长边）。 */
    data class Solved(val ra: Double, val dec: Double, val fovDeg: Double)

    enum class Verdict(val label: String) {
        TRUE_SOLVE("TRUE-SOLVE"),
        FALSE_POSITIVE("FALSE-POSITIVE"),
        MISS("MISS"),
    }

    const val RA_TOL_DEG = 3.0
    const val DEC_TOL_DEG = 3.0

    /** 视场比值窗口：0.65~1.55（旧口径，勿随手收紧——真值本身带 ±35% 的不确定度）。 */
    val FOV_RATIO_RANGE = 0.65..1.55

    val byId: Map<String, Truth> = mapOf(
        "4963" to Truth(78.70, 20.85, 77.2), // §0.43 修订：旧真值(73.4,19.1)来自物理不可能的官方 0.2° 解；
                                             // 交叉验证（DiagCrossCheckTest）证明 4963 与 4974 同天区
                                             // （中心距 11.6°，星点天球重合 23/64），自研解即真值
        "4974" to Truth(82.0, 32.1, 77.0),   // 御夫座+冬季亮星群
        "4984" to Truth(60.0, 25.3, 76.0),   // 与 4998 同天区同时段连拍（blob 尺寸/密度=广角；旧 17×22° 官方解是错尺度产物）
        "4998" to Truth(60.03, 25.26, 75.6), // 冬季六边形全景（官方 WCS 锚定：68.08″/px @4000px）
        "5031" to Truth(13.4, 71.0, 76.0),   // 仙后座 W + 北极星
        "5040" to Truth(68.7, -14.3, 77.0),  // 猎户座+大犬座
        "5049" to Truth(70.5, -20.9, 71.0),  // 天兔座+大犬座
        "5057" to Truth(61.9, 15.0, 75.0),   // 用户口径=广角；blob 形态（星少而大）曾有变焦疑义，真值窗口按广角
        "5068" to Truth(57.3, 26.7, 72.0),   // 英仙座+金牛座
        "5076" to Truth(55.3, 26.0, 72.0),   // 英仙座+金牛座
        "5087" to Truth(351.2, 24.3, 76.0),  // §0.43b：左上角灯光/月亮伪影，亮源掩蔽后飞马座真解浮现
                                             // （ra≈349.8/dec≈24.2 inliers=16）
        "5092" to Truth(351.3, 24.3, 76.0),  // 同上二连拍（掩蔽后 inliers=13）
    )

    /** 素材文件名（`photo<id>.jpg`）→ 照片号。 */
    fun idOf(photoFileName: String): String? {
        val m = Regex("^photo(\\d{4})\\..+$").find(photoFileName) ?: return null
        return m.groupValues[1].takeIf { byId.containsKey(it) }
    }

    /** 判定：见类注释的口径。 */
    fun classify(truth: Truth, solved: Solved?): Verdict {
        if (solved == null) return Verdict.MISS
        val dra = kotlin.math.abs(solved.ra - truth.ra).let { kotlin.math.min(it, 360.0 - it) }
        val ddec = kotlin.math.abs(solved.dec - truth.dec)
        val ratio = solved.fovDeg / truth.fovDeg
        return if (dra < RA_TOL_DEG && ddec < DEC_TOL_DEG && ratio in FOV_RATIO_RANGE) {
            Verdict.TRUE_SOLVE
        } else {
            Verdict.FALSE_POSITIVE
        }
    }
}
