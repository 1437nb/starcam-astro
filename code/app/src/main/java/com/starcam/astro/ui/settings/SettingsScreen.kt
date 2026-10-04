package com.starcam.astro.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    // §0.70 识别日志
    var logEnabled by remember {
        mutableStateOf(com.starcam.astro.data.SolveLogStore.isEnabled(context))
    }
    // §0.81：日志统计会 `walkTopDown` 遍历整个日志目录并累加文件大小，logDir 还会
    // mkdirs —— 两者都不能放在组合期（每次进设置页都会卡一下主线程）。
    // 改为异步取，初值给空串，由 LaunchedEffect 触发首次加载。
    var logStats by remember { mutableStateOf("") }
    var logDirPath by remember { mutableStateOf("") }
    // §0.94：加密存储是否可用（null = 尚未探测）。探测要初始化 Keystore，
    // 属 IO，故与日志信息一并放在 LaunchedEffect + IO 里取。
    var encryptedStorage by remember { mutableStateOf<Boolean?>(null) }
    // §0.95 原生引擎可用性（null = 尚未探测；loadLibrary 可能较慢，故同样放 IO）
    var nativeEngine by remember { mutableStateOf<Boolean?>(null) }
    // §0.99 版本号：排查"我装的到底是哪一版"时最直接的依据
    var appVersion by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    suspend fun loadLogInfo() {
        withContext(Dispatchers.IO) {
            logStats = com.starcam.astro.data.SolveLogStore.stats(context)
            logDirPath = com.starcam.astro.data.SolveLogStore.logDir(context).absolutePath
            encryptedStorage = settings.isApiKeyStorageEncrypted()
            nativeEngine = com.starcam.astro.astro.NativeEngineProbe.available
            appVersion = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
            }.getOrDefault("")
        }
    }
    fun refreshStats() {
        scope.launch { loadLogInfo() }
    }
    LaunchedEffect(Unit) { loadLogInfo() }
    val isEn = LocaleState.isEnglish

    Scaffold(
        topBar = {
            TopAppBar(
                // §0.99b：标题栏下常显版本号 —— 放在页面顶部，不需要滚动到日志区、
                // 也不依赖任何折叠状态（原先放在"识别日志"区里，用户按「底部」去找会落空）
                title = {
                    Column {
                        Text(if (isEn) "Settings" else "设置")
                        if (appVersion.isNotEmpty()) {
                            Text(
                                com.starcam.astro.ui.I18n.Settings.appVersion(appVersion),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
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
            Text(if (isEn) "Language" else "语言", style = MaterialTheme.typography.titleMedium)
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
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(20.dp))

            // 外观（§0.33）：夜视红为暗适应模式——红光不破坏夜间视力，建议观星时使用
            Text(if (isEn) "Appearance" else "外观", style = MaterialTheme.typography.titleMedium)
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
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(20.dp))

            // 传感器辅助粗定标
            Text(com.starcam.astro.ui.I18n.Settings.sensorSection, style = MaterialTheme.typography.titleMedium)
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
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(20.dp))

            // §0.70 识别日志：用户报告「识别不了」时，这里是取证据的入口
            Text("识别日志", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("记录识别过程", fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "记录每次识别的引擎轨迹与失败原因；识别不出的照片会额外保存" +
                            "现场数据。只存本机、不上传，可随时清空。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(16.dp))
                Switch(
                    checked = logEnabled,
                    onCheckedChange = { checked ->
                        logEnabled = checked
                        com.starcam.astro.data.SolveLogStore.setEnabled(context, checked)
                        refreshStats()
                    },
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                logStats,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { refreshStats() }) { Text("刷新统计") }
                OutlinedButton(
                    onClick = {
                        // §0.81：clearAll 会 deleteRecursively 整个日志目录，不能放主线程
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                com.starcam.astro.data.SolveLogStore.clearAll(context)
                            }
                            refreshStats()
                        }
                    },
                ) { Text("清空日志") }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "日志目录：$logDirPath",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // §0.99：版本号（便于确认设备上跑的是哪一版）
            if (appVersion.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    com.starcam.astro.ui.I18n.Settings.appVersion(appVersion),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // §0.95：原生引擎可用性上报（缺陷报告 S3）。原生库只编了 arm64-v8a，
            // 32 位 / x86 设备能装上包但原生引擎整体不可用，此前用户与支持者都看不到。
            nativeEngine?.let { available ->
                Spacer(Modifier.height(6.dp))
                Text(
                    if (available) {
                        com.starcam.astro.ui.I18n.Settings.nativeEngineOk
                    } else {
                        com.starcam.astro.ui.I18n.Settings.nativeEngineMissing
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (available) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(20.dp))

            Text(com.starcam.astro.ui.I18n.Settings.engineSection, style = MaterialTheme.typography.titleMedium)
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
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(20.dp))

            Text(com.starcam.astro.ui.I18n.Settings.onlineSection, style = MaterialTheme.typography.titleMedium)
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
            // §0.94：加密存储降级必须让用户看得见 —— 否则「可计费凭据以明文落盘」
            // 只在 logcat 留痕，与 README 的隐私承诺不符（仅当探测到降级才显示）。
            if (encryptedStorage == false) {
                Spacer(Modifier.height(8.dp))
                Text(
                    com.starcam.astro.ui.I18n.Settings.apiKeyPlaintextWarning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
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
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(20.dp))

            Text(com.starcam.astro.ui.I18n.Settings.serverSection, style = MaterialTheme.typography.titleMedium)
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
                Text(com.starcam.astro.ui.I18n.save, style = MaterialTheme.typography.bodyLarge)
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
