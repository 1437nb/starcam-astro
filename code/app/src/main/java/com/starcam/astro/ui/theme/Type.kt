package com.starcam.astro.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Material 3 规范字阶（§0.85）。
 *
 * 取值即 Google 官方 M3 规范：字号 / 字重 / 字距（letterSpacing）/ 行高
 * 全部按 spec 来，字体沿用系统 Roboto（Google 自家字体，零 APK 体积成本）。
 *
 * 这套字阶是"Google 感"的主要来源：
 *  - 标题用 400/500 字重、负或零字距 —— 大而克制，不靠加粗堆气势；
 *  - 正文 400、正字距，行高 1.5 倍；
 *  - label（按钮/徽标/标签）用 500 + 0.1~0.5 的正字距 —— 小字微微散开，
 *    这是 Google UI 与"普通安卓 UI"最容易被感知的差别。
 *
 * 使用规约：屏幕内**不要再写 `fontSize = …sp`**，一律取
 * `MaterialTheme.typography.<role>`；需要强调时用 fontWeight 覆盖字重即可。
 */
internal val StarCamTypography = Typography(
    displayLarge = TextStyle(
        fontSize = 57.sp, lineHeight = 64.sp, letterSpacing = (-0.25).sp, fontWeight = FontWeight.Normal,
    ),
    displayMedium = TextStyle(
        fontSize = 45.sp, lineHeight = 52.sp, letterSpacing = 0.sp, fontWeight = FontWeight.Normal,
    ),
    displaySmall = TextStyle(
        fontSize = 36.sp, lineHeight = 44.sp, letterSpacing = 0.sp, fontWeight = FontWeight.Normal,
    ),
    headlineLarge = TextStyle(
        fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = 0.sp, fontWeight = FontWeight.Normal,
    ),
    headlineMedium = TextStyle(
        fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = 0.sp, fontWeight = FontWeight.Normal,
    ),
    headlineSmall = TextStyle(
        fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = 0.sp, fontWeight = FontWeight.Normal,
    ),
    titleLarge = TextStyle(
        fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = 0.sp, fontWeight = FontWeight.Normal,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp, fontWeight = FontWeight.Medium,
    ),
    titleSmall = TextStyle(
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp, fontWeight = FontWeight.Medium,
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.5.sp, fontWeight = FontWeight.Normal,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.25.sp, fontWeight = FontWeight.Normal,
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp, fontWeight = FontWeight.Normal,
    ),
    labelLarge = TextStyle(
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp, fontWeight = FontWeight.Medium,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp, fontWeight = FontWeight.Medium,
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp, fontWeight = FontWeight.Medium,
    ),
)

