package com.starcam.astro.data

import android.content.Context
import com.starcam.astro.astro.EngineMode
import com.starcam.astro.ui.theme.AppThemeMode

/** 应用设置（SharedPreferences 持久化） */
class SettingsRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("starcam_settings", Context.MODE_PRIVATE)

    /** astrometry.net API Key（到 https://nova.astrometry.net/api_help 免费注册获取） */
    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value.trim()).apply()

    /** 服务器地址（默认 nova.astrometry.net，可改自建服务） */
    var serverUrl: String
        get() = prefs.getString(KEY_SERVER, DEFAULT_SERVER) ?: DEFAULT_SERVER
        set(value) = prefs.edit().putString(KEY_SERVER, value.trim().removeSuffix("/")).apply()

    /** 是否已配置 API Key */
    val hasApiKey: Boolean get() = apiKey.isNotBlank()

    /**
     * 识别引擎模式：
     * 自动（推荐）/ 官方引擎优先 / 仅离线 / 仅在线。
     * 默认自动：按 EXIF 视场估计智能选择 astrometry.net 官方引擎
     * （本地 NDK 盲求解，0.1°~35°）→ 内置星表宽场匹配 → 在线 nova 兜底。
     */
    var engineMode: EngineMode
        get() = EngineMode.entries.firstOrNull {
            it.key == prefs.getString(KEY_ENGINE_MODE, EngineMode.AUTO.key)
        } ?: EngineMode.AUTO
        set(value) = prefs.edit().putString(KEY_ENGINE_MODE, value.key).apply()

    /**
     * 主题模式（§0.33）：深空蓝（默认）/ 夜视红（暗适应）。
     * 启动时写入 [com.starcam.astro.ui.theme.ThemeState]，设置页修改即时生效。
     */
    var appTheme: AppThemeMode
        get() = AppThemeMode.entries.firstOrNull {
            it.key == prefs.getString(KEY_APP_THEME, AppThemeMode.DEEP_SKY.key)
        } ?: AppThemeMode.DEEP_SKY
        set(value) = prefs.edit().putString(KEY_APP_THEME, value.key).apply()

    companion object {
        private const val KEY_API_KEY = "api_key"
        private const val KEY_SERVER = "server_url"
        private const val KEY_ENGINE_MODE = "engine_mode"
        private const val KEY_APP_THEME = "app_theme"
        const val DEFAULT_SERVER = "https://nova.astrometry.net/api"
    }
}
