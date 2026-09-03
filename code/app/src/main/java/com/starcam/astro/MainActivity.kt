package com.starcam.astro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.starcam.astro.data.SettingsRepository
import com.starcam.astro.ui.StarCamApp
import com.starcam.astro.ui.theme.StarCamTheme
import com.starcam.astro.ui.theme.ThemeState

/** 主 Activity：承载 Compose 界面 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 恢复用户主题（§0.33）；之后设置页修改通过 ThemeState 即时生效
        ThemeState.mode = SettingsRepository(this).appTheme
        setContent {
            StarCamTheme {
                StarCamApp()
            }
        }
    }
}
