package com.starcam.astro

import com.starcam.astro.data.AppLanguage
import com.starcam.astro.ui.I18n
import com.starcam.astro.ui.theme.LocaleState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * §0.90 识别耗时文本格式化。
 *
 * 口径：不足 1 分钟保留一位小数，1 分钟以上用「分 + 秒」；先四舍五入到 0.1 秒再分档，
 * 避免 "60.0 秒" 这种怪值；非正数返回空串（调用方据此不显示该行）。
 */
class SolveTimeFormatTest {

    /** 语言是全局状态：用完必须还原，避免污染同一 JVM 里后续的测试类 */
    private var saved: AppLanguage? = null

    @org.junit.Before
    fun saveLanguage() {
        saved = LocaleState.language
    }

    @org.junit.After
    fun restoreLanguage() {
        saved?.let { LocaleState.language = it }
    }

    private fun zh() { LocaleState.language = AppLanguage.ZH }
    private fun en() { LocaleState.language = AppLanguage.EN }

    @Test
    fun `中文格式：秒档与分档`() {
        zh()
        assertEquals("18.4 秒", I18n.Result.formatSolveTime(18_400))
        assertEquals("1.0 秒", I18n.Result.formatSolveTime(1_000))
        assertEquals("59.9 秒", I18n.Result.formatSolveTime(59_900))
        assertEquals("1 分 0 秒", I18n.Result.formatSolveTime(60_000))
        assertEquals("1 分 23 秒", I18n.Result.formatSolveTime(83_400))
        assertEquals("2 分 5 秒", I18n.Result.formatSolveTime(125_400))
    }

    @Test
    fun `英文格式：秒档与分档`() {
        en()
        assertEquals("18.4 s", I18n.Result.formatSolveTime(18_400))
        assertEquals("1 m 23 s", I18n.Result.formatSolveTime(83_400))
    }

    @Test
    fun `四舍五入到 0_1 秒后再分档，不出现 60_0 秒`() {
        zh()
        // 59.95 秒 → 四舍五入即 60.0 秒，应走分档而不是显示 "60.0 秒"
        assertEquals("1 分 0 秒", I18n.Result.formatSolveTime(59_950))
        assertEquals("59.9 秒", I18n.Result.formatSolveTime(59_949))
    }

    @Test
    fun `无耗时不显示（演示模式与求解前失败）`() {
        zh()
        assertEquals("", I18n.Result.formatSolveTime(0))
        assertEquals("", I18n.Result.formatSolveTime(-1))
        assertEquals("", I18n.Result.solveTimeLine(0))
    }

    @Test
    fun `失败页整行：中英标点各自正确`() {
        zh()
        assertEquals("识别耗时：18.4 秒", I18n.Result.solveTimeLine(18_400))
        en()
        assertEquals("Recognition Time: 18.4 s", I18n.Result.solveTimeLine(18_400))
    }
}
