package com.starcam.astro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import com.starcam.astro.data.SettingsRepository
import com.starcam.astro.ui.AppViewModel
import com.starcam.astro.ui.Screen
import com.starcam.astro.ui.StarCamApp
import com.starcam.astro.ui.theme.LocaleState
import com.starcam.astro.ui.theme.StarCamTheme
import com.starcam.astro.ui.theme.ThemeState
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 仅 Debug 的 UI 验收入口；只读随包公开照片，不接受外部路径、URL 或命令。 */
class DeviceAcceptanceActivity : ComponentActivity() {
    private val app by viewModels<AppViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = SettingsRepository(this)
        ThemeState.mode = settings.appTheme
        LocaleState.language = settings.appLanguage
        setContent {
            StarCamTheme {
                LaunchedEffect(Unit) {
                    if (app.screen == Screen.Home) {
                        val paths = withContext(Dispatchers.IO) {
                            val directory = File(filesDir, "device-acceptance-ui/${UUID.randomUUID()}").apply { mkdirs() }
                            listOf("photo4963.jpg", "photo4974.jpg").map { name ->
                                File(directory, name).apply {
                                    assets.open("offline_photos/$name").use { input -> outputStream().use { input.copyTo(it) } }
                                }.path
                            }
                        }
                        app.navigate(Screen.Batch(paths))
                    }
                }
                StarCamApp(app)
            }
        }
    }
}
