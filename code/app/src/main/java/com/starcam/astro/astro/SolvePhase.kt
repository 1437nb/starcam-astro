package com.starcam.astro.astro

/** Stable UI events, independent of the detailed per-engine diagnostic text. */
enum class SolvePhase(private val zh: String, private val en: String) {
    PREPARING("准备照片", "Preparing photo"),
    DETECTING("检测星点", "Detecting stars"),
    MATCHING("匹配本地星表", "Matching local catalog"),
    VERIFYING("复核识别结果", "Verifying result"),
    NATIVE("原生引擎求解", "Native engine solving"),
    RACING("本地星表与原生引擎并行求解", "Local and native engines running in parallel"),
    ONLINE("在线辅助求解", "Online assistance"),
    RENDERING("生成星空标注", "Preparing annotations");

    fun label(isEnglish: Boolean): String = if (isEnglish) en else zh
}
