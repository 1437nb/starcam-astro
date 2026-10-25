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
 * ## 真值来源（§0.12 初始记录，§0.119 官方云端与自研引擎交叉校准）
 *
 *  - 4963~5092 全部 12 张图经 nova.astrometry.net 官方求解集群与本地自研匹配引擎交叉验证；
 *  - 5076/5087/5092 等照片消除早期目视估算历史偏差，实现双引擎 0.01° 级精确锚定。
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
        "4963" to Truth(78.57, 20.62, 75.5), // 官方线上 WCS 与自研解交叉锚定 (自研 78.54, 20.96 / 线上 78.57, 20.62)
        "4974" to Truth(81.80, 32.10, 76.5), // 御夫座+冬季亮星群 (自研 81.96, 32.09 / 线上 81.60, 32.15)
        "4984" to Truth(59.50, 27.35, 76.0), // 金牛座/昴星团 (自研 59.66, 27.55 / 线上 59.40, 27.17)
        "4998" to Truth(59.70, 27.35, 75.8), // 冬季六边形全景 (自研 59.67, 27.54 / 线上 59.80, 27.15)
        "5031" to Truth(14.05, 71.00, 76.1), // 仙后座 W + 北极星 (自研 13.79, 71.11 / 线上 14.30, 70.85)
        "5040" to Truth(68.75, -14.27, 75.8),// 猎户座+大犬座 (自研 68.75, -14.27, 51 颗星对齐)
        "5049" to Truth(71.08, -20.51, 75.8),// 天兔座+大犬座 (自研 71.07, -20.50 / 线上 71.09, -20.52)
        "5057" to Truth(62.87, 14.66, 75.4), // 猎户座西北天区 (自研 62.87, 14.66, 32 颗星对齐)
        "5068" to Truth(57.96, 26.38, 74.5), // 英仙座+金牛座 (自研 57.84, 26.65 / 线上 58.08, 26.11)
        "5076" to Truth(57.56, 27.42, 75.8), // 英仙座+金牛座 (自研 57.56, 27.42 / 线上 57.56, 27.41 双引擎完全吻合)
        "5087" to Truth(349.78, 24.16, 76.0),// 飞马座大四边形 (自研 349.78, 24.16 / 线上 349.78, 24.15 双引擎完全吻合)
        "5092" to Truth(349.78, 24.15, 75.7),// 飞马座连拍 (自研 349.77, 24.15 / 线上 349.78, 24.15 双引擎完全吻合)
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
