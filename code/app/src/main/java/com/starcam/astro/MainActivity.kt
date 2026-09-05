package com.starcam.astro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.starcam.astro.data.SettingsRepository
import com.starcam.astro.ui.StarCamApp
import com.starcam.astro.ui.theme.LocaleState
import com.starcam.astro.ui.theme.StarCamTheme
import com.starcam.astro.ui.theme.ThemeState

/** 主 Activity：承载 Compose 界面 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = SettingsRepository(this)
        // 恢复用户主题与语言偏好（设置页修改通过状态即时生效，无需重建 Activity）
        ThemeState.mode = settings.appTheme
        LocaleState.language = settings.appLanguage

        setContent {
            StarCamTheme {
                StarCamApp()
            }
        }
    }
}
