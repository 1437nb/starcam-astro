package com.starcam.astro.astro

/**
 * 识别失败诊断（§0.33）：把"引擎为什么没认出来"翻译成用户能看懂的信息。
 * 星点坐标为归一化值（0..1，相对提星时的显示位图），供 UI 在缩略图上标注。
 */
data class SolveDiagnostics(
    /** 最后一次本地提星的星点数 */
    val starCount: Int,
    /** 检出星点（归一化坐标 + 归一化亮度 0..1），用于失败可视化标注 */
    val stars: List<DiagStar>,
    /** 依次尝试过的引擎标签 */
    val enginesTried: List<String>,
) {
    data class DiagStar(val x: Float, val y: Float, val brightness01: Float)

    val verdict: Verdict = when {
        starCount < 8 -> Verdict.TOO_FEW
        starCount < 20 -> Verdict.FEW
        else -> Verdict.PLENTY_UNSOLVED
    }

    enum class Verdict { TOO_FEW, FEW, PLENTY_UNSOLVED }

    fun verdictTitle(isEnglish: Boolean = false): String = if (isEnglish) {
        when (verdict) {
            Verdict.TOO_FEW -> "Barely any stars detected"
            Verdict.FEW -> "Only a few stars detected"
            Verdict.PLENTY_UNSOLVED -> "Plenty of stars, but catalog match failed"
        }
    } else {
        when (verdict) {
            Verdict.TOO_FEW -> "画面里几乎看不到星点"
            Verdict.FEW -> "只检测到少量星点"
            Verdict.PLENTY_UNSOLVED -> "星点充足，但没能匹配星表"
        }
    }

    fun verdictDetail(isEnglish: Boolean = false): String = if (isEnglish) {
        when (verdict) {
            Verdict.TOO_FEW ->
                "Detected $starCount stars (fewer than 8). Common causes: exposure too short or ISO too low, " +
                    "strong moonlight or light pollution, camera out of focus or not aimed at night sky."
            Verdict.FEW ->
                "Detected $starCount stars. Too few for reliable plate solving. " +
                    "Suggestions: increase exposure time or ISO, wait for moon to set, or aim at a wider sky area."
            Verdict.PLENTY_UNSOLVED ->
                "Detected $starCount stars, but no matching star pattern was found. Common causes: " +
                    "zoom field of view too narrow (try Official Engine First in Settings), " +
                    "field dominated by deep sky objects, or star trails caused by camera shake."
        }
    } else {
        when (verdict) {
            Verdict.TOO_FEW ->
                "共检测到 $starCount 个星点（不足 8 个）。常见原因：曝光太短或感光度太低、" +
                    "月光/光污染太亮、镜头没对准星空或没对上焦。"
            Verdict.FEW ->
                "共检测到 $starCount 个星点。数量太少，匹配算法没有把握。" +
                    "建议：延长曝光或提高 ISO、等待月亮落下，或换一片更开阔的天区。"
            Verdict.PLENTY_UNSOLVED ->
                "共检测到 $starCount 个星点，但没有找到与内置亮星表一致的图案。常见原因：" +
                    "变焦视场太窄（亮星表覆盖有限，可到设置改用官方引擎优先）、" +
                    "画面以深空天体为主，或星点被抖动拉成了线。"
        }
    }

    fun enginesText(isEnglish: Boolean = false): String =
        if (enginesTried.isEmpty()) "" else (if (isEnglish) "Engines tried: " else "已尝试：") + enginesTried.joinToString(" → ")
}
