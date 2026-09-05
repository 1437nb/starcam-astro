package com.starcam.astro.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.starcam.astro.astro.EngineMode
import com.starcam.astro.data.AppLanguage
import com.starcam.astro.data.SettingsRepository
import com.starcam.astro.ui.theme.AppThemeMode
import com.starcam.astro.ui.theme.LocaleState
import com.starcam.astro.ui.theme.ThemeState

/** 设置页：语言、主题、astrometry.net API Key 与服务器地址 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: SettingsRepository,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var apiKey by remember { mutableStateOf(settings.apiKey) }
    var serverUrl by remember { mutableStateOf(settings.serverUrl) }
    var engineMode by remember { mutableStateOf(settings.engineMode) }
    var themeMode by remember { mutableStateOf(ThemeState.mode) }
    var appLanguage by remember { mutableStateOf(LocaleState.language) }
    var sensorPointing by remember { mutableStateOf(settings.sensorAssistedPointing) }
    var saved by remember { mutableStateOf(false) }
    val isEn = LocaleState.isEnglish

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEn) "Settings" else "设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (isEn) "Back" else "返回",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            // 语言设置（支持跟随系统 / 简体中文 / English 即时生效）
            Text(if (isEn) "Language" else "语言", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                if (isEn) "Choose display language for UI, constellation names, star names, and Messier objects."
                else "选择界面、星座名称、恒星专名及梅西耶天体的显示语言。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            AppLanguage.entries.forEach { lang ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = appLanguage == lang,
                        onClick = {
                            appLanguage = lang
                            settings.appLanguage = lang
                            LocaleState.language = lang
                        },
                    )
                    Text(lang.label(isEn), fontWeight = FontWeight.Medium)
                }
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            Spacer(Modifier.height(20.dp))

            // 外观（§0.33）：夜视红为暗适应模式——红光不破坏夜间视力，建议观星时使用
            Text(if (isEn) "Appearance" else "外观", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                if (isEn) "Night Red is recommended for dark adaptation: pure black background + low-intensity red light."
                else "夜间观星建议开启「夜视红」：纯黑背景 + 低亮度红光，对暗适应的破坏最小，OLED 屏幕也更省电。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            AppThemeMode.entries.forEach { mode ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = themeMode == mode,
                        onClick = {
                            themeMode = mode
                            settings.appTheme = mode
                            ThemeState.mode = mode // 即时生效
                            saved = false
                        },
                    )
                    Text(
                        if (isEn) (if (mode == AppThemeMode.NIGHT_RED) "Night Red (Dark Adaptation)" else "Deep Sky Blue (Default)")
                        else mode.label,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            Spacer(Modifier.height(20.dp))

            // 传感器辅助粗定标
            Text(com.starcam.astro.ui.I18n.Settings.sensorSection, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(com.starcam.astro.ui.I18n.Settings.sensorTitle, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        com.starcam.astro.ui.I18n.Settings.sensorDesc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(16.dp))
                Switch(
                    checked = sensorPointing,
                    onCheckedChange = { checked ->
                        sensorPointing = checked
                        settings.sensorAssistedPointing = checked
                    },
                )
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            Spacer(Modifier.height(20.dp))

            Text(com.starcam.astro.ui.I18n.Settings.engineSection, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                com.starcam.astro.ui.I18n.Settings.engineDesc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            EngineMode.values().forEach { mode ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = engineMode == mode,
                        onClick = {
                            engineMode = mode
                            settings.engineMode = mode
                            saved = false
                        },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(mode.label(isEn), fontWeight = FontWeight.Medium)
                        Text(
                            mode.description(isEn),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            Spacer(Modifier.height(20.dp))

            Text(com.starcam.astro.ui.I18n.Settings.onlineSection, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                com.starcam.astro.ui.I18n.Settings.onlineDesc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it; saved = false },
                label = { Text("API Key") },
                placeholder = { Text(com.starcam.astro.ui.I18n.Settings.apiKeyPlaceholder) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                com.starcam.astro.ui.I18n.Settings.apiKeyHelp,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://nova.astrometry.net/signin"),
                    )
                    context.startActivity(intent)
                },
            ) {
                Text(com.starcam.astro.ui.I18n.Settings.openWebsiteButton)
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            Spacer(Modifier.height(20.dp))

            Text(com.starcam.astro.ui.I18n.Settings.serverSection, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it; saved = false },
                label = { Text(com.starcam.astro.ui.I18n.Settings.serverLabel) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                com.starcam.astro.ui.I18n.Settings.serverHelp,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    settings.apiKey = apiKey
                    settings.serverUrl = serverUrl
                    saved = true
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(com.starcam.astro.ui.I18n.save, fontSize = 16.sp)
            }

            if (saved) {
                Spacer(Modifier.height(12.dp))
                Text(
                    com.starcam.astro.ui.I18n.savedToast,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(24.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(com.starcam.astro.ui.I18n.Settings.privacySection, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        com.starcam.astro.ui.I18n.Settings.privacyContent,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
