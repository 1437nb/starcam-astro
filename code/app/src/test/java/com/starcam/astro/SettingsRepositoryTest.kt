package com.starcam.astro

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.starcam.astro.astro.EngineMode
import com.starcam.astro.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §0.142 验证 [SettingsRepository] 跨实例共享底层存储与 `apiKey` 内存缓存。
 */
class SettingsRepositoryTest {

    private class InMemoryPrefs : SharedPreferences {
        private val map = HashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = HashMap(map)
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (map[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = HashMap<String, Any?>()
            private var clearAll = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = values?.toSet()
            }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }
            override fun remove(key: String?): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = REMOVED
            }
            override fun clear(): SharedPreferences.Editor = apply { clearAll = true }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (clearAll) map.clear()
                for ((k, v) in pending) {
                    if (v === REMOVED || v == null) map.remove(k) else map[k] = v
                }
            }
        }

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private companion object {
            val REMOVED = Any()
        }
    }

    private class FakeAppContext : ContextWrapper(null) {
        private val prefsByName = HashMap<String, SharedPreferences>()
        var getSharedPreferencesCalls = 0

        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            getSharedPreferencesCalls++
            return prefsByName.getOrPut(name) { InMemoryPrefs() }
        }
    }

    @Test
    fun separateInstancesShareBackingStoreAndApiKeyCache() {
        val appCtx = FakeAppContext()
        val activityA = object : ContextWrapper(appCtx) {
            override fun getApplicationContext(): Context = appCtx
        }
        val activityB = object : ContextWrapper(appCtx) {
            override fun getApplicationContext(): Context = appCtx
        }

        // 模拟 StarCamApplication 预热线程先创建实例并读取 hasApiKey（填充空缓存并完成一次加密初始化尝试）
        val prewarmRepo = SettingsRepository(activityA)
        assertFalse(prewarmRepo.hasApiKey)
        assertEquals("", prewarmRepo.apiKey)
        val callsAfterPrewarm = appCtx.getSharedPreferencesCalls
        assertTrue(callsAfterPrewarm >= 1)

        // 模拟 StarCamApp / SettingsScreen 创建新实例：底层 SharedStore 复用，不再重复打开 SharedPreferences 或初始化 Tink
        val uiRepo = SettingsRepository(activityB)
        assertFalse(uiRepo.hasApiKey)
        assertEquals(callsAfterPrewarm, appCtx.getSharedPreferencesCalls)

        // 新实例更新 API Key 后，已缓存过空串的原预热实例立即读取到新值（共享同一 apiKeyCache 与加密实例）
        uiRepo.apiKey = "  example-nova-key  "
        assertEquals("example-nova-key", prewarmRepo.apiKey)
        assertTrue(prewarmRepo.hasApiKey)
        assertTrue(uiRepo.isApiKeyStorageEncrypted())
        assertEquals(prewarmRepo.isApiKeyStorageEncrypted(), uiRepo.isApiKeyStorageEncrypted())
    }

    @Test
    fun settingsClampingAndNormalizationWorkAcrossInstances() {
        val appCtx = FakeAppContext()
        val repo1 = SettingsRepository(appCtx)
        val repo2 = SettingsRepository.getInstance(appCtx)

        repo1.serverUrl = " https://nova.astrometry.net/api/ "
        assertEquals("https://nova.astrometry.net/api", repo2.serverUrl)

        repo1.arFovDeg = 120f
        assertEquals(90f, repo2.arFovDeg, 1e-4f)
        repo1.arFovDeg = 15f
        assertEquals(40f, repo2.arFovDeg, 1e-4f)

        repo1.solveBudgetSec = 3
        assertEquals(10, repo2.solveBudgetSec)
        repo1.solveBudgetSec = 500
        assertEquals(120, repo2.solveBudgetSec)

        repo1.engineMode = EngineMode.OFFLINE_ONLY
        assertEquals(EngineMode.OFFLINE_ONLY, repo2.engineMode)
    }
}
