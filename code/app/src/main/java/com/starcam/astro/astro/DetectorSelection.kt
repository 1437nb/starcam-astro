package com.starcam.astro.astro

/**
 * §0.97 / §0.97b 的选表决策：两张提星表（box-blur / SEP）→
 * 「喂给匹配器的主表 + 备用表 + 界面预览表」。
 *
 * ## 为什么单独抽出来（审计 P1-2.2）
 *
 * 2026-09 的事故（PROGRESS §0.97b）不是匹配算法错，而是**选表错**：
 * StarSolver 里 try 块的「末表达式」被改成了给界面预览的 SEP 表，于是
 * ① 喂给匹配器的主表被顶掉；② 备用表与主表成了同一个对象，匹配器据对象同一性
 * 判定「无备用」，box-blur 表从未参与 —— 真机 5031 从「2 秒解出」退化成
 * 「230 秒失败」（日志指纹：星点 172 颗）。
 *
 * 而当时所有真值回归台都是**直接调 LocalStarMatcher.match**、自己构造干净的
 * 星表列表，绕过了选表环节，所以选表错了回归台照样全绿。
 *
 * 本对象把这段决策变成纯函数（不依赖 Android / 原生库），于是：
 *  - 生产路径（[StarSolver] 的 LOCAL_MATCHER 分支）与 CI 测试走**同一个函数**；
 *  - 事故形态（主表被顶掉、备用退化、重试分支没走）可以被测试直接钉住。
 *
 * 语义与抽取前的内联实现逐条一致（含诊断文案）—— 抽取本身不改变任何行为。
 */
internal object DetectorSelection {

    /** 少于 5 颗星匹配器不开工（与 [LocalStarMatcher.match] 的门一致）。 */
    const val MIN_MATCH_STARS = 5

    /** SEP 与 box-blur 的重合率门限：低于它说明 SEP 这一轮不可信，不参与（§0.72）。 */
    const val OVERLAP_TRUST_MIN = 0.30f

    /** 重合率比对取 box-blur 最亮的前 N 颗。 */
    const val OVERLAP_TOP_N = 30

    /** 重合判定半径（像素）。 */
    const val OVERLAP_RADIUS_PX = 8f

    /**
     * 选表结果。
     *
     * @param primary 喂给匹配器的主表；null = 两张表都不可用（调用方不应调用 match）。
     *   注意**可能是 <5 颗的列表**（box-blur 重试拿回来的结果）——调用方据此走
     *   「星点太少」的失败文案，不要在这里过滤掉。
     * @param fallback 备用表（主表浅域无解时匹配器用它再试一次）；null = 无备用。
     * @param preview 给界面预览的表（「看得更全」的那张）；**与匹配输入无关**。
     * @param detectedTotal 未截断的检出总数（UI 显示用；列表本身有上限）。
     * @param note 提星诊断行（原样进识别日志）。
     * @param fallbackShadowedByPrimary §0.97b 自查：备用表与主表是**同一个对象**时，
     *   匹配器会判定「无备用」、备用机制静默失效（事故指纹）。生产只记录告警，
     *   测试直接钉住它为 false。
     */
    data class Plan(
        val primary: List<DetectedStar>?,
        val fallback: List<DetectedStar>?,
        val preview: List<DetectedStar>?,
        val detectedTotal: Int,
        val note: String,
        val fallbackShadowedByPrimary: Boolean,
    )

    /** 重合率统计：命中数 / 比对总数。 */
    data class Overlap(val hits: Int, val of: Int) {
        val rate: Float get() = if (of == 0) 0f else hits.toFloat() / of
    }

    /**
     * box-blur 表前 [topN] 颗中，有多少颗在 SEP 表里有 [radiusPx] 内的近邻。
     * 依赖调用方保证 boxBlur 按亮度降序 —— 这是检测器的输出顺序
     * （[LocalStarMatcher.detectStarsGray] 末尾 `sortedByDescending { brightness }`）。
     */
    fun overlap(
        boxBlur: List<DetectedStar>,
        sep: List<DetectedStar>,
        topN: Int = OVERLAP_TOP_N,
        radiusPx: Float = OVERLAP_RADIUS_PX,
    ): Overlap {
        val bbTop = boxBlur.take(topN)
        if (bbTop.isEmpty()) return Overlap(0, 0)
        val hits = bbTop.count { b ->
            sep.any { p ->
                kotlin.math.abs(p.x - b.x) < radiusPx && kotlin.math.abs(p.y - b.y) < radiusPx
            }
        }
        return Overlap(hits, bbTop.size)
    }

    /**
     * 选表。
     *
     * @param boxBlur box-blur 表的检出结果；null = 尚未算出（SEP 不可信时不算它）
     *   或算出即失败。
     * @param sep SEP 表的检出结果（§0.97b 之后它是**备用**，不是首选）。
     * @param sepTrustworthy SEP 的初始可信状态（通常 = [sep] 非空且 ≥ [MIN_MATCH_STARS]）。
     *   本函数会按重合率复核，复核不通过就撤销可信 —— 撤销后它仍可作为主表兜底
     *   （box-blur 不可用时），但不再作为备用表。
     * @param detectedTotal 未截断的检出总数（SEP 给 totalPeaks；无则 0）。
     * @param boxBlurRetry 两表都不可用时的**重试**（生产里是再跑一次 box-blur 检出；
     *   JVM 测试传 null 表示不重试）。异常由调用方在 lambda 内处理。
     */
    fun plan(
        boxBlur: List<DetectedStar>?,
        sep: List<DetectedStar>?,
        sepTrustworthy: Boolean,
        detectedTotal: Int,
        boxBlurRetry: (() -> List<DetectedStar>?)? = null,
    ): Plan {
        val note = StringBuilder()
        var trusted = sepTrustworthy
        // §0.72 提星质量核验：SEP 与 box-blur 重合率过低（典型：阈值失配导致
        // 全是边界伪影）→ SEP 这一轮不可信。
        if (trusted && boxBlur != null && boxBlur.size >= MIN_MATCH_STARS && sep != null) {
            val ov = overlap(boxBlur, sep)
            note.append("与box-blur重合=${ov.hits}/${ov.of}(${"%.0f".format(ov.rate * 100)}%)；")
            trusted = ov.rate >= OVERLAP_TRUST_MIN
            if (!trusted) note.append("→SEP不可信，只留box-blur；")
        }
        val primaryByBox = boxBlur?.takeIf { it.size >= MIN_MATCH_STARS }
        val primarySep = sep?.takeIf { it.size >= MIN_MATCH_STARS }
        val primary = primaryByBox ?: primarySep
        if (primary == null) {
            // 两张表都不可用：再算一次 box-blur（生产里这是 SEP 不可信时的首次 box-blur）。
            val retry = boxBlurRetry?.invoke()
            note.append("回退box-blur=${retry?.size ?: 0}颗；")
            return Plan(
                primary = retry,
                fallback = null,
                preview = retry,
                detectedTotal = retry?.size ?: 0,
                note = note.toString(),
                fallbackShadowedByPrimary = false,
            )
        }
        var fallback: List<DetectedStar>? = null
        if (primaryByBox != null) {
            // §0.97 首选顺序：**便宜的表先试**（box-blur 星少、阶梯便宜），SEP 作备用。
            fallback = primarySep?.takeIf { trusted }
            note.append(
                "首选box-blur(${primary.size}颗)" +
                    (fallback?.let { "，备用SEP(${it.size}颗)" } ?: "") + "；",
            )
        } else {
            note.append("box-blur 表不可用，直接用 SEP 表(${primary.size}颗)；")
        }
        val shadowed = fallback != null && fallback === primary
        if (shadowed) note.append("【内部告警：备用表与主表相同，备用机制将失效】")
        return Plan(
            primary = primary,
            fallback = fallback,
            // 预览给「看得更全」的那张表；匹配输入始终是 primary —— 两者分离是 §0.97b 的教训
            preview = if (primaryByBox != null) (primarySep ?: primary) else primary,
            detectedTotal = if (detectedTotal <= 0) primary.size else detectedTotal,
            note = note.toString(),
            fallbackShadowedByPrimary = shadowed,
        )
    }
}
