package com.starcam.astro.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.starcam.astro.astro.EngineMode
import com.starcam.astro.ui.theme.AppThemeMode

/** 应用语言选择：跟随系统 / 简体中文 / 英文 */
enum class AppLanguage(val key: String, val labelZh: String, val labelEn: String) {
    FOLLOW_SYSTEM("system", "跟随系统", "Follow System"),
    ZH("zh", "简体中文", "Simplified Chinese"),
    EN("en", "English", "English");

    fun label(isEnglish: Boolean = false): String = if (isEnglish) labelEn else labelZh
}

/** 应用设置（普通项 SharedPreferences；API Key 走加密存储） */
class SettingsRepository(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext
        .getSharedPreferences("starcam_settings", Context.MODE_PRIVATE)

    /**
     * API Key 专用加密存储。
     *
     * API Key 是可计费凭据，MODE_PRIVATE 只挡其他应用，挡不住 root / 取证
     * （AndroidManifest 已设 allowBackup=false，adb backup 这条路径已封）。
     * 加密存储初始化失败时回退普通 prefs——宁可降级也不能让用户配不了 key。
     */
    private val securePrefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                "starcam_secure_settings",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Throwable) {
            Log.w(TAG, "加密存储不可用，API Key 回退普通存储", e)
            prefs
        }
    }

    /** astrometry.net API Key（到 https://nova.astrometry.net/api_help 免费注册获取） */
    var apiKey: String
        get() {
            val fromSecure = securePrefs.getString(KEY_API_KEY, "") ?: ""
            if (fromSecure.isNotEmpty()) return fromSecure
            // 迁移：旧版本明文存的 key 读出来改写进加密存储，并清掉明文项
            val legacy = prefs.getString(KEY_API_KEY, "") ?: ""
            if (legacy.isNotEmpty() && securePrefs !== prefs) {
                securePrefs.edit().putString(KEY_API_KEY, legacy).apply()
                prefs.edit().remove(KEY_API_KEY).apply()
            }
            return legacy
        }
        set(value) {
            securePrefs.edit().putString(KEY_API_KEY, value.trim()).apply()
            if (securePrefs !== prefs) prefs.edit().remove(KEY_API_KEY).apply()
        }

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

    /**
     * 应用语言选择：跟随系统（默认）/ 简体中文 / 英文。
     * 启动时同步至 [com.starcam.astro.ui.theme.LocaleState]，切换即时生效。
     */
    var appLanguage: AppLanguage
        get() = AppLanguage.entries.firstOrNull {
            it.key == prefs.getString(KEY_APP_LANGUAGE, AppLanguage.FOLLOW_SYSTEM.key)
        } ?: AppLanguage.FOLLOW_SYSTEM
        set(value) = prefs.edit().putString(KEY_APP_LANGUAGE, value.key).apply()

    /**
     * 传感器辅助粗定标（陀螺仪/重力/指南针 + 定位）：默认开启。
     */
    var sensorAssistedPointing: Boolean
        get() = prefs.getBoolean(KEY_SENSOR_POINTING, true)
        set(value) = prefs.edit().putBoolean(KEY_SENSOR_POINTING, value).apply()

    /**
     * AR 实时星图（§0.49）：相机取景页传感器驱动星空叠加，默认开启。
     */
    var arLiveStarMap: Boolean
        get() = prefs.getBoolean(KEY_AR_LIVE, true)
        set(value) = prefs.edit().putBoolean(KEY_AR_LIVE, value).apply()

    /**
     * AR 星图水平视场（度，40~90）：不同手机主摄 FOV 不同，滑块校准对齐。
     */
    var arFovDeg: Float
        get() = prefs.getFloat(KEY_AR_FOV, 62f)
        set(value) = prefs.edit().putFloat(KEY_AR_FOV, value.coerceIn(40f, 90f)).apply()

    companion object {
        private const val TAG = "SettingsRepository"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_SERVER = "server_url"
        private const val KEY_ENGINE_MODE = "engine_mode"
        private const val KEY_APP_THEME = "app_theme"
        private const val KEY_APP_LANGUAGE = "app_language"
        private const val KEY_SENSOR_POINTING = "sensor_pointing"
        private const val KEY_AR_LIVE = "ar_live_star_map"
        private const val KEY_AR_FOV = "ar_fov_deg"
        const val DEFAULT_SERVER = "https://nova.astrometry.net/api"
    }
}
