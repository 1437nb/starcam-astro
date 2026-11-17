package com.starcam.astro.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.starcam.astro.astro.EngineMode
import com.starcam.astro.ui.theme.AppThemeMode
import java.util.WeakHashMap

/** 应用语言选择：跟随系统 / 简体中文 / 英文 */
enum class AppLanguage(val key: String, val labelZh: String, val labelEn: String) {
    FOLLOW_SYSTEM("system", "跟随系统", "Follow System"),
    ZH("zh", "简体中文", "Simplified Chinese"),
    EN("en", "English", "English");

    fun label(isEnglish: Boolean = false): String = if (isEnglish) labelEn else labelZh
}

/**
 * 应用设置（普通项 SharedPreferences；API Key 走加密存储）。
 *
 * §0.142：底层存储按 `applicationContext` 共享单例 [SharedStore]，消除
 * `StarCamApplication` 后台预热、`MainActivity`、`StarCamApp` 与 `CameraScreen`
 * 各自 new 实例导致的 Keystore 重复初始化与 `apiKeyCache` 内存缓存割裂。
 */
class SettingsRepository(context: Context) {

    private val store: SharedStore = storeFor(context)
    private val prefs: SharedPreferences get() = store.prefs

    /**
     * §0.94：加密存储是否**真正**可用。
     *
     * 加密初始化失败会静默回退明文 prefs（见 [SharedStore.securePrefs]）—— 这本身是刻意的
     * 可用性取舍，但此前只在 logcat 留一行警告，**用户完全无感知**：可计费的
     * API Key 在降级设备上以明文落盘，与 README 的隐私承诺不符。
     * 设置页据此明示「当前为明文存储」。
     *
     * 首次调用会触发加密存储初始化（§0.81 的后台预热线程通常已初始化过）—— 建议在 IO 线程调用。
     */
    fun isApiKeyStorageEncrypted(): Boolean = store.isApiKeyStorageEncrypted()

    /** astrometry.net API Key（到 https://nova.astrometry.net/api_help 免费注册获取） */
    var apiKey: String
        get() = store.getApiKey()
        set(value) = store.setApiKey(value)

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
     * 主题模式（§0.33）：深空蓝（默认）/ 纯黑（AMOLED极简）/ 纯白（浅色模式）/ 夜视红（暗适应）。
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

    /**
     * 求解全局时间总预算（秒，10~120）：默认 30 秒。
     */
    var solveBudgetSec: Int
        get() = prefs.getInt(KEY_SOLVE_BUDGET, 30)
        set(value) = prefs.edit().putInt(KEY_SOLVE_BUDGET, value.coerceIn(10, 120)).apply()

    /**
     * 双引擎并发竞速（§0.114）：未知视场时本地星表与官方引擎并发加速，取最先命中者。
     * 仅在非低内存设备生效，默认开启。
     */
    var parallelEngineRacing: Boolean
        get() = prefs.getBoolean(KEY_PARALLEL_RACING, true)
        set(value) = prefs.edit().putBoolean(KEY_PARALLEL_RACING, value).apply()

    private class SharedStore(private val appContext: Context) {
        val prefs: SharedPreferences = appContext
            .getSharedPreferences("starcam_settings", Context.MODE_PRIVATE)

        @Volatile
        private var secureOk = false

        val securePrefs: SharedPreferences by lazy {
            try {
                val masterKey = MasterKey.Builder(appContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                val created = EncryptedSharedPreferences.create(
                    appContext,
                    "starcam_secure_settings",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
                secureOk = true
                created
            } catch (e: Throwable) {
                Log.w(TAG, "加密存储不可用，API Key 回退普通存储", e)
                secureOk = false
                prefs
            }
        }

        /**
         * §0.81：apiKey 的内存缓存（跨全进程所有 [SettingsRepository] 引用共享）。
         */
        @Volatile
        private var apiKeyCache: String? = null

        fun isApiKeyStorageEncrypted(): Boolean {
            securePrefs
            return secureOk
        }

        fun getApiKey(): String {
            apiKeyCache?.let { return it }
            val fromSecure = securePrefs.getString(KEY_API_KEY, "") ?: ""
            val value = if (fromSecure.isNotEmpty()) {
                fromSecure
            } else {
                val legacy = prefs.getString(KEY_API_KEY, "") ?: ""
                if (legacy.isNotEmpty() && securePrefs !== prefs) {
                    securePrefs.edit().putString(KEY_API_KEY, legacy).apply()
                    prefs.edit().remove(KEY_API_KEY).apply()
                }
                legacy
            }
            apiKeyCache = value
            return value
        }

        fun setApiKey(value: String) {
            val v = value.trim()
            securePrefs.edit().putString(KEY_API_KEY, v).apply()
            if (securePrefs !== prefs) prefs.edit().remove(KEY_API_KEY).apply()
            apiKeyCache = v
        }
    }

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
        private const val KEY_SOLVE_BUDGET = "solve_budget_sec"
        private const val KEY_PARALLEL_RACING = "parallel_engine_racing"
        const val DEFAULT_SERVER = "https://nova.astrometry.net/api"

        private val stores = WeakHashMap<Context, SharedStore>()

        private fun storeFor(context: Context): SharedStore {
            val key = context.applicationContext ?: context
            return synchronized(stores) {
                stores.getOrPut(key) { SharedStore(key) }
            }
        }

        fun getInstance(context: Context): SettingsRepository = SettingsRepository(context)
    }
}
