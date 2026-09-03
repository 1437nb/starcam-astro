package com.starcam.astro.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** 主题模式（§0.33）：夜视红为天文暗适应专用——红光对夜间视力影响最小 */
enum class AppThemeMode(val key: String, val label: String) {
    DEEP_SKY("deep_sky", "深空蓝（默认）"),
    NIGHT_RED("night_red", "夜视红（暗适应）"),
}

/** 主题运行时状态：设置页修改即时生效（无需重建 Activity） */
object ThemeState {
    var mode by mutableStateOf(AppThemeMode.DEEP_SKY)
}

// 深色夜空主题配色（星空蓝底 + 星辉金强调）
private val DeepSkyColors = darkColorScheme(
    primary = Color(0xFFE3C567),        // 星辉金
    onPrimary = Color(0xFF2A2410),
    primaryContainer = Color(0xFF3A3118),
    onPrimaryContainer = Color(0xFFFFE3A8),
    secondary = Color(0xFF7FB3E8),      // 星空蓝
    onSecondary = Color(0xFF0C1B33),
    secondaryContainer = Color(0xFF1E3455),
    onSecondaryContainer = Color(0xFFBBD8FF),
    tertiary = Color(0xFF9BD4C0),
    background = Color(0xFF0B1026),     // 深夜蓝
    onBackground = Color(0xFFE8ECF8),
    surface = Color(0xFF121838),
    onSurface = Color(0xFFE8ECF8),
    surfaceVariant = Color(0xFF1C2450),
    onSurfaceVariant = Color(0xFFAAB3D9),
    outline = Color(0xFF3A4470),
    error = Color(0xFFFF6B6B),
)

// 夜视红主题：纯黑底 + 低亮度红（暗适应友好，肉眼夜间模式主色）
private val NightRedColors = darkColorScheme(
    primary = Color(0xFFFF5252),
    onPrimary = Color(0xFF331010),
    primaryContainer = Color(0xFF3A1212),
    onPrimaryContainer = Color(0xFFFFB4A8),
    secondary = Color(0xFFFF8A80),
    onSecondary = Color(0xFF2B0A0A),
    secondaryContainer = Color(0xFF3A100E),
    onSecondaryContainer = Color(0xFFFFD0C9),
    tertiary = Color(0xFFFFB74D),
    background = Color(0xFF000000),     // 纯黑：OLED 省电 + 不干扰暗适应
    onBackground = Color(0xFFE8A8A0),
    surface = Color(0xFF0D0505),
    onSurface = Color(0xFFE8A8A0),
    surfaceVariant = Color(0xFF1C0A0A),
    onSurfaceVariant = Color(0xFFC88F88),
    outline = Color(0xFF5A2622),
    error = Color(0xFFFF8A80),
)

/** 应用主题（Material 3 深色；夜视红模式见 [AppThemeMode.NIGHT_RED]） */
@Composable
fun StarCamTheme(
    mode: AppThemeMode = ThemeState.mode,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (mode == AppThemeMode.NIGHT_RED) NightRedColors else DeepSkyColors,
        typography = Typography(),
        content = content,
    )
}