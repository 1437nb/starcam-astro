package com.starcam.astro.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.starcam.astro.data.AppLanguage
import java.util.Locale

/** 主题模式（§0.33）：夜视红为天文暗适应专用——红光对夜间视力影响最小 */
enum class AppThemeMode(val key: String, val label: String) {
    DEEP_SKY("deep_sky", "深空蓝（默认）"),
    NIGHT_RED("night_red", "夜视红（暗适应）"),
}

/** 主题运行时状态：设置页修改即时生效（无需重建 Activity） */
object ThemeState {
    var mode by mutableStateOf(AppThemeMode.DEEP_SKY)
}

/** 语言运行时状态：设置页修改即时生效（无需重建 Activity） */
object LocaleState {
    var language by mutableStateOf(AppLanguage.FOLLOW_SYSTEM)

    val isEnglish: Boolean
        get() = when (language) {
            AppLanguage.EN -> true
            AppLanguage.ZH -> false
            AppLanguage.FOLLOW_SYSTEM -> Locale.getDefault().language.lowercase().startsWith("en")
        }
}

/**
 * M3 没有定义"成功"语义色（§0.85）——但本 App 有明确需要它的场景
 * （API 配置状态、求解成功标记）。按各主题的色相派生，而不是硬编码，
 * 这样夜视红模式下成功标记也是暗适应友好的。
 */
@Immutable
data class StarCamExtraColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
)

val LocalStarCamExtraColors = staticCompositionLocalOf {
    StarCamExtraColors(
        success = Color(0xFF8FD694), onSuccess = Color(0xFF06210C),
        successContainer = Color(0xFF1E3A22), onSuccessContainer = Color(0xFFB8F0C2),
    )
}

// ── 深空蓝：深夜蓝底 + 星辉金强调 ──────────────────────────────────────
// M3 深色方案的层次感靠 surfaceContainer 五档**色调分层**，而不是灰阶或阴影。
// 这套档位从 background(0xFF0B1026) 的色相派生，逐级提亮，
// Google 系应用的"卡片浮在页面上"的质感即来自这组角色。
private val DeepSkyColors = darkColorScheme(
    primary = Color(0xFFE3C567),            // 星辉金
    onPrimary = Color(0xFF2A2410),
    primaryContainer = Color(0xFF3A3118),
    onPrimaryContainer = Color(0xFFFFE3A8),
    inversePrimary = Color(0xFF6B5A1A),

    secondary = Color(0xFF7FB3E8),          // 星空蓝
    onSecondary = Color(0xFF0C1B33),
    secondaryContainer = Color(0xFF1E3455),
    onSecondaryContainer = Color(0xFFBBD8FF),

    tertiary = Color(0xFF9BD4C0),           // 星云薄荷
    onTertiary = Color(0xFF062019),
    tertiaryContainer = Color(0xFF1E4A3C),
    onTertiaryContainer = Color(0xFFC8F0DF),

    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2B0808),
    errorContainer = Color(0xFF4B1616),
    onErrorContainer = Color(0xFFFFDAD6),

    background = Color(0xFF0B1026),         // 深夜蓝
    onBackground = Color(0xFFE8ECF8),
    surface = Color(0xFF121838),
    onSurface = Color(0xFFE8ECF8),
    surfaceVariant = Color(0xFF1C2450),
    onSurfaceVariant = Color(0xFFAAB3D9),
    surfaceDim = Color(0xFF0B1026),
    surfaceBright = Color(0xFF2C3360),
    surfaceContainerLowest = Color(0xFF06091A),
    surfaceContainerLow = Color(0xFF0F1430),
    surfaceContainer = Color(0xFF141A38),
    surfaceContainerHigh = Color(0xFF1B2145),
    surfaceContainerHighest = Color(0xFF242B54),

    outline = Color(0xFF3A4470),
    outlineVariant = Color(0xFF2B3458),
    inverseSurface = Color(0xFFE8ECF8),
    inverseOnSurface = Color(0xFF2B3154),
    scrim = Color(0xFF000000),
)

private val DeepSkyExtra = StarCamExtraColors(
    success = Color(0xFF8FD694), onSuccess = Color(0xFF06210C),
    successContainer = Color(0xFF1E3A22), onSuccessContainer = Color(0xFFB8F0C2),
)

// ── 夜视红：纯黑底 + 低亮度红（暗适应友好）────────────────────────────
private val NightRedColors = darkColorScheme(
    primary = Color(0xFFFF5252),
    onPrimary = Color(0xFF331010),
    primaryContainer = Color(0xFF3A1212),
    onPrimaryContainer = Color(0xFFFFB4A8),
    inversePrimary = Color(0xFF8A2A26),

    secondary = Color(0xFFFF8A80),
    onSecondary = Color(0xFF2B0A0A),
    secondaryContainer = Color(0xFF3A100E),
    onSecondaryContainer = Color(0xFFFFD0C9),

    tertiary = Color(0xFFFFB74D),           // 暖琥珀（红模式下承担"成功/提示"）
    onTertiary = Color(0xFF2E1600),
    tertiaryContainer = Color(0xFF4A2A00),
    onTertiaryContainer = Color(0xFFFFDDB5),

    error = Color(0xFFFF8A80),
    onError = Color(0xFF330D0A),
    errorContainer = Color(0xFF5C1A16),
    onErrorContainer = Color(0xFFFFDAD6),

    background = Color(0xFF000000),         // 纯黑：OLED 省电 + 不干扰暗适应
    onBackground = Color(0xFFE8A8A0),
    surface = Color(0xFF0D0505),
    onSurface = Color(0xFFE8A8A0),
    surfaceVariant = Color(0xFF1C0A0A),
    onSurfaceVariant = Color(0xFFC88F88),
    surfaceDim = Color(0xFF000000),
    surfaceBright = Color(0xFF2B0D0D),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0A0404),
    surfaceContainer = Color(0xFF0F0707),
    surfaceContainerHigh = Color(0xFF170909),
    surfaceContainerHighest = Color(0xFF1F0D0D),

    outline = Color(0xFF5A2622),
    outlineVariant = Color(0xFF3D1A16),
    inverseSurface = Color(0xFFF0DDDA),
    inverseOnSurface = Color(0xFF3B1714),
    scrim = Color(0xFF000000),
)

private val NightRedExtra = StarCamExtraColors(
    success = Color(0xFFFFB74D), onSuccess = Color(0xFF2E1600),
    successContainer = Color(0xFF4A2A00), onSuccessContainer = Color(0xFFFFDDB5),
)

/** 语义"成功"色的便捷读取（按当前主题派生，见 [StarCamExtraColors]） */
val ColorScheme.success: Color
    @Composable get() = LocalStarCamExtraColors.current.success

val ColorScheme.onSuccess: Color
    @Composable get() = LocalStarCamExtraColors.current.onSuccess

val ColorScheme.successContainer: Color
    @Composable get() = LocalStarCamExtraColors.current.successContainer

val ColorScheme.onSuccessContainer: Color
    @Composable get() = LocalStarCamExtraColors.current.onSuccessContainer

/** 应用主题（Material 3 深色；夜视红模式见 [AppThemeMode.NIGHT_RED]） */
@Composable
fun StarCamTheme(
    mode: AppThemeMode = ThemeState.mode,
    content: @Composable () -> Unit,
) {
    val scheme = if (mode == AppThemeMode.NIGHT_RED) NightRedColors else DeepSkyColors
    val extra = if (mode == AppThemeMode.NIGHT_RED) NightRedExtra else DeepSkyExtra
    androidx.compose.runtime.CompositionLocalProvider(LocalStarCamExtraColors provides extra) {
        MaterialTheme(
            colorScheme = scheme,
            // §0.85：Typography / Shapes 走 M3 规范值（原先是默认构造，
            // "Google 感"主要来自这两处，而不是换配色）。
            typography = StarCamTypography,
            shapes = StarCamShapes,
            content = content,
        )
    }
}
