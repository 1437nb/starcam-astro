package com.starcam.astro.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.starcam.astro.astro.EngineMode
import com.starcam.astro.astro.NativeEngineProbe
import com.starcam.astro.data.AppLanguage
import com.starcam.astro.data.SettingsRepository
import com.starcam.astro.data.SolveLogStore
import com.starcam.astro.ui.AppIcons
import com.starcam.astro.ui.I18n
import com.starcam.astro.ui.theme.AppThemeMode
import com.starcam.astro.ui.theme.LocaleState
import com.starcam.astro.ui.theme.ThemeState
import com.starcam.astro.ui.theme.onSuccessContainer
import com.starcam.astro.ui.theme.successContainer
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页（§0.143 Material 3 五大分组卡片重构）：
 * 1. 外观与语言（2×2 真实配色色板选择卡 + 语言切换芯片）
 * 2. 识别引擎与时间预算（引擎选择卡 + 时间总预算滑块与实时秒数徽标 + 双引擎并发竞速）
 * 3. 相机与 AR 实时星图（传感器粗定标 + AR 星图开关 + AR 视场校准滑块与一键重置 62°）
 * 4. 在线服务（API Key 密码显隐切换 + 硬件加密状态胶囊 + 服务器地址 + 保存反馈 + 隐私说明）
 * 5. 识别诊断日志与关于（日志开关 + 统计面板 + 导出/清空二次确认 + 版本徽标）
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: SettingsRepository,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var apiKey by remember { mutableStateOf(settings.apiKey) }
    var showApiKey by rememberSaveable { mutableStateOf(false) }
    var serverUrl by remember { mutableStateOf(settings.serverUrl) }
    var engineMode by remember { mutableStateOf(settings.engineMode) }
    var solveBudgetSec by remember { mutableIntStateOf(settings.solveBudgetSec) }
    var themeMode by remember { mutableStateOf(ThemeState.mode) }
    var appLanguage by remember { mutableStateOf(LocaleState.language) }
    var sensorPointing by remember { mutableStateOf(settings.sensorAssistedPointing) }
    var arLiveStarMap by remember { mutableStateOf(settings.arLiveStarMap) }
    var arFovDeg by remember { mutableFloatStateOf(settings.arFovDeg) }
    var parallelRacing by remember { mutableStateOf(settings.parallelEngineRacing) }
    var saved by remember { mutableStateOf(false) }
    var confirmClearLogs by remember { mutableStateOf(false) }

    // §0.70 识别日志
    var logEnabled by remember {
        mutableStateOf(SolveLogStore.isEnabled(context))
    }
    // §0.81：日志统计与加密探测涉及文件遍历和 Keystore 初始化，统一在 IO 线程异步加载
    var logStats by remember { mutableStateOf("") }
    var logDirPath by remember { mutableStateOf("") }
    var encryptedStorage by remember { mutableStateOf<Boolean?>(null) }
    var nativeEngine by remember { mutableStateOf<Boolean?>(null) }
    var appVersion by remember { mutableStateOf("") }

    val isEn = LocaleState.isEnglish
    val scope = rememberCoroutineScope()

    suspend fun loadLogInfo() {
        withContext(Dispatchers.IO) {
            logStats = SolveLogStore.stats(context, LocaleState.isEnglish)
            logDirPath = SolveLogStore.logDir(context).absolutePath
            encryptedStorage = settings.isApiKeyStorageEncrypted()
            nativeEngine = NativeEngineProbe.available
            appVersion = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
            }.getOrDefault("")
        }
    }

    fun refreshStats() {
        scope.launch { loadLogInfo() }
    }

    LaunchedEffect(isEn) { loadLogInfo() }

    // SAF 导出诊断日志（免额外权限、无需 FileProvider）
    val exportLogLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { targetUri ->
        if (targetUri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val logFile = SolveLogStore.logFile(context)
                    val failDirs = SolveLogStore.listFailDirs(context).take(5)
                    val payload = buildString {
                        appendLine("# StarCam Diagnostic Log Export")
                        if (appVersion.isNotEmpty()) appendLine("# Version: $appVersion")
                        appendLine("# Stats: ${SolveLogStore.stats(context, isEnglish = true)}")
                        appendLine()
                        if (logFile.exists()) {
                            appendLine("=== starcam-log.txt ===")
                            appendLine(logFile.readText())
                        } else {
                            appendLine("(starcam-log.txt is currently empty)")
                        }
                        for (dir in failDirs) {
                            val reportFile = File(dir, "report.json")
                            if (reportFile.exists()) {
                                appendLine()
                                appendLine("=== ${dir.name}/report.json ===")
                                appendLine(reportFile.readText())
                            }
                        }
                    }
                    context.contentResolver.openOutputStream(targetUri)?.use { out ->
                        out.write(payload.toByteArray(Charsets.UTF_8))
                    } != null
                }.getOrDefault(false)
            }
            val msg = if (ok) {
                if (isEn) "Diagnostic log exported" else "诊断日志已导出"
            } else {
                if (isEn) "Failed to export diagnostic log" else "导出诊断日志失败"
            }
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    if (confirmClearLogs) {
        AlertDialog(
            onDismissRequest = { confirmClearLogs = false },
            title = {
                Text(if (isEn) "Clear diagnostic logs?" else "清空识别诊断日志？")
            },
            text = {
                Text(
                    if (isEn) {
                        "This will permanently delete all local solve trace logs and failure dumps" +
                            (if (logStats.isNotEmpty()) " ($logStats)" else "") +
                            ". Saved entries in Recognition History will not be affected."
                    } else {
                        "将永久清空本机保存的全部识别过程日志与失败现场数据" +
                            (if (logStats.isNotEmpty()) "（$logStats）" else "") +
                            "，「识别历史」中的成功记录不受影响。"
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClearLogs = false
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                SolveLogStore.clearAll(context)
                            }
                            refreshStats()
                            Toast.makeText(
                                context,
                                if (isEn) "Logs cleared" else "诊断日志已清空",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                ) {
                    Text(I18n.Settings.logClear, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearLogs = false }) {
                    Text(I18n.cancel)
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                // §0.99b：标题栏下常显版本号
                title = {
                    Column {
                        Text(I18n.Settings.title)
                        if (appVersion.isNotEmpty()) {
                            Text(
                                I18n.Settings.appVersion(appVersion),
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
                            contentDescription = I18n.back,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── 卡片 1：外观与语言 ──────────────────────────────────────────────
            SettingsGroupCard(
                icon = AppIcons.Palette,
                title = if (isEn) "Appearance & Language" else "外观与语言",
            ) {
                Text(
                    text = I18n.Settings.appearanceSection,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = I18n.Settings.appearanceDesc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                // 2×2 真实配色色板选择卡
                val themeRows = remember { AppThemeMode.entries.chunked(2) }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (rowModes in themeRows) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            for (mode in rowModes) {
                                ThemeSwatchCard(
                                    mode = mode,
                                    selected = themeMode == mode,
                                    isEn = isEn,
                                    isCurrentNightRed = themeMode == AppThemeMode.NIGHT_RED,
                                    onClick = {
                                        themeMode = mode
                                        settings.appTheme = mode
                                        ThemeState.mode = mode
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(14.dp))

                Text(
                    text = I18n.Settings.languageSection,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = I18n.Settings.languageDesc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AppLanguage.entries.forEach { lang ->
                        val selected = appLanguage == lang
                        FilterChip(
                            selected = selected,
                            onClick = {
                                appLanguage = lang
                                settings.appLanguage = lang
                                LocaleState.language = lang
                            },
                            label = { Text(lang.label(isEn)) },
                            leadingIcon = if (selected) {
                                {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
            }

            // ── 卡片 2：识别引擎与时间预算 ──────────────────────────────────────
            SettingsGroupCard(
                icon = AppIcons.Engine,
                title = if (isEn) "Solving Engine & Time Budget" else "识别引擎与时间预算",
            ) {
                Text(
                    text = I18n.Settings.engineDesc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    EngineMode.entries.forEach { mode ->
                        val selected = engineMode == mode
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selected,
                                    onClick = {
                                        engineMode = mode
                                        settings.engineMode = mode
                                    },
                                    role = Role.RadioButton,
                                ),
                            shape = MaterialTheme.shapes.medium,
                            color = if (selected) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainer
                            },
                            border = BorderStroke(
                                width = if (selected) 1.5.dp else 1.dp,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                },
                            ),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = selected,
                                    onClick = null,
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = mode.label(isEn),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.onSecondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = mode.description(isEn),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(14.dp))

                // 识别时间总预算滑块 + 实时秒数徽标
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = if (isEn) "Global Solve Time Budget" else "识别时间总预算",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (isEn) {
                                "Max total time per photo across local & online solvers (10–120s)"
                            } else {
                                "单张照片本地与在线引擎的总耗时上限（10～120 秒）"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    ValueBadge(text = "${solveBudgetSec}s")
                }

                Spacer(Modifier.height(4.dp))
                Slider(
                    value = solveBudgetSec.toFloat(),
                    onValueChange = { raw ->
                        val v = raw.roundToInt().coerceIn(10, 120)
                        solveBudgetSec = v
                        settings.solveBudgetSec = v
                    },
                    valueRange = 10f..120f,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = if (isEn) "10s (Fast) — 120s (Deep)" else "10s（快速）— 120s（深解）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (solveBudgetSec != 30) {
                        TextButton(
                            onClick = {
                                solveBudgetSec = 30
                                settings.solveBudgetSec = 30
                            },
                        ) {
                            Text(if (isEn) "Reset 30s" else "重置 30s")
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(10.dp))

                SettingsSwitchRow(
                    title = I18n.Settings.parallelRacingTitle,
                    description = I18n.Settings.parallelRacingDesc,
                    checked = parallelRacing,
                    onCheckedChange = { checked ->
                        parallelRacing = checked
                        settings.parallelEngineRacing = checked
                    },
                )
            }

            // ── 卡片 3：相机与 AR 实时星图 ──────────────────────────────────────
            SettingsGroupCard(
                icon = AppIcons.Compass,
                title = if (isEn) "Camera & AR Live Star Map" else "相机与 AR 实时星图",
            ) {
                SettingsSwitchRow(
                    title = I18n.Settings.sensorTitle,
                    description = I18n.Settings.sensorDesc,
                    checked = sensorPointing,
                    onCheckedChange = { checked ->
                        sensorPointing = checked
                        settings.sensorAssistedPointing = checked
                    },
                )

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(12.dp))

                SettingsSwitchRow(
                    title = I18n.Ar.toggleDesc,
                    description = if (isEn) {
                        "Project real-time constellations, bright stars, horizon compass, and target finder onto the camera viewfinder."
                    } else {
                        "在相机取景画面实时投影真实星空连线、亮星、地平线方位标尺与找星导航引导。"
                    },
                    checked = arLiveStarMap,
                    onCheckedChange = { checked ->
                        arLiveStarMap = checked
                        settings.arLiveStarMap = checked
                    },
                )

                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(14.dp))

                // AR 相机视场角滑块 + 实时度数徽标 + 一键重置 62°
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = if (isEn) "AR Camera Horizontal FOV" else "AR 相机水平视场角",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (isEn) {
                                "Align live AR star overlay with your phone's main camera lens (40°–90°)"
                            } else {
                                "校准实时星图投影比例以贴合手机主摄实际视场（40°～90°）"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    ValueBadge(text = "${arFovDeg.roundToInt()}°")
                }

                Spacer(Modifier.height(4.dp))
                Slider(
                    value = arFovDeg,
                    onValueChange = { raw ->
                        val v = raw.roundToInt().toFloat().coerceIn(40f, 90f)
                        arFovDeg = v
                        settings.arFovDeg = v
                    },
                    valueRange = 40f..90f,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = if (isEn) "40° (Tele) — 90° (Ultra-wide)" else "40°（窄角）— 90°（超广角）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = {
                            arFovDeg = 62f
                            settings.arFovDeg = 62f
                        },
                        enabled = abs(arFovDeg - 62f) >= 0.5f,
                    ) {
                        Text(if (isEn) "Reset 62°" else "重置 62°")
                    }
                }
            }

            // ── 卡片 4：在线服务 ────────────────────────────────────────────────
            SettingsGroupCard(
                icon = AppIcons.Cloud,
                title = I18n.Settings.onlineSection,
            ) {
                // 加密存储状态胶囊（§0.94）
                encryptedStorage?.let { isEncrypted ->
                    if (isEncrypted) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.successContainer,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = AppIcons.Lock,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSuccessContainer,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = if (isEn) {
                                        "AES-256 Hardware-Backed Encrypted Storage"
                                    } else {
                                        "AES-256 加密存储保护中"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSuccessContainer,
                                )
                            }
                        }
                    } else {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.errorContainer,
                        ) {
                            Text(
                                text = I18n.Settings.apiKeyPlaintextWarning,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }

                Text(
                    text = I18n.Settings.onlineDesc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = {
                        apiKey = it
                        saved = false
                    },
                    label = { Text("API Key") },
                    placeholder = { Text(I18n.Settings.apiKeyPlaceholder) },
                    singleLine = true,
                    visualTransformation = if (showApiKey) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showApiKey = !showApiKey }) {
                            Icon(
                                imageVector = if (showApiKey) AppIcons.VisibilityOff else AppIcons.Visibility,
                                contentDescription = if (showApiKey) {
                                    if (isEn) "Hide API Key" else "隐藏 API Key"
                                } else {
                                    if (isEn) "Show API Key" else "显示 API Key"
                                },
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = I18n.Settings.apiKeyHelp,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        runCatching {
                            val intent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://nova.astrometry.net/signin"),
                            )
                            context.startActivity(intent)
                        }
                    },
                ) {
                    Text(I18n.Settings.openWebsiteButton)
                }

                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(14.dp))

                Text(
                    text = I18n.Settings.serverSection,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = {
                        serverUrl = it
                        saved = false
                    },
                    label = { Text(I18n.Settings.serverLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = I18n.Settings.serverHelp,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        settings.apiKey = apiKey
                        settings.serverUrl = serverUrl
                        saved = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Text(
                        text = I18n.save,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                AnimatedVisibility(
                    visible = saved,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Surface(
                        modifier = Modifier
                            .padding(top = 10.dp)
                            .fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.successContainer,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSuccessContainer,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = I18n.savedToast,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSuccessContainer,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            text = I18n.Settings.privacySection,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = I18n.Settings.privacyContent,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ── 卡片 5：识别诊断日志与关于 ──────────────────────────────────────
            SettingsGroupCard(
                icon = AppIcons.Diagnostics,
                title = if (isEn) "Diagnostics & About" else "识别诊断日志与关于",
                trailingBadge = if (appVersion.isNotEmpty()) "v$appVersion" else null,
            ) {
                SettingsSwitchRow(
                    title = I18n.Settings.logTitle,
                    description = I18n.Settings.logDesc,
                    checked = logEnabled,
                    onCheckedChange = { checked ->
                        logEnabled = checked
                        SolveLogStore.setEnabled(context, checked)
                        refreshStats()
                    },
                )

                Spacer(Modifier.height(12.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (logStats.isNotEmpty()) {
                            Text(
                                text = logStats,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        if (logDirPath.isNotEmpty()) {
                            Text(
                                text = I18n.Settings.logDirLabel(logDirPath),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        nativeEngine?.let { available ->
                            Text(
                                text = if (available) {
                                    I18n.Settings.nativeEngineOk
                                } else {
                                    I18n.Settings.nativeEngineMissing
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (available) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                        }
                        if (appVersion.isNotEmpty()) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Text(
                                text = "${I18n.Settings.appVersion(appVersion)} · GPL-3.0-or-later",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = { refreshStats() }) {
                        Text(I18n.Settings.logRefresh)
                    }
                    OutlinedButton(
                        onClick = { exportLogLauncher.launch("starcam-log.txt") },
                    ) {
                        Text(if (isEn) "Export Log" else "导出日志")
                    }
                    OutlinedButton(
                        onClick = { confirmClearLogs = true },
                    ) {
                        Text(
                            text = I18n.Settings.logClear,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

/** 统一 Material 3 分组设置卡片（surfaceContainerLow + shapes.large + 1dp outlineVariant 描边） */
@Composable
private fun SettingsGroupCard(
    icon: ImageVector,
    title: String,
    trailingBadge: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.size(34.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (trailingBadge != null) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            text = trailingBadge,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

/** 2×2 主题色板预览选择卡片：展示各主题真实背景底色与主强调色圆点 */
@Composable
private fun ThemeSwatchCard(
    mode: AppThemeMode,
    selected: Boolean,
    isEn: Boolean,
    isCurrentNightRed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 展示各主题真实背景色与主强调色；在夜视红激活时适度压低纯白预览亮度以保护暗适应视力
    val (swatchBg, swatchPrimary, swatchOutline) = when (mode) {
        AppThemeMode.DEEP_SKY -> Triple(
            Color(0xFF0B1026),
            Color(0xFFE3C567),
            Color(0xFF3A4470),
        )
        AppThemeMode.PURE_BLACK -> Triple(
            Color(0xFF000000),
            Color(0xFFE3C567),
            Color(0xFF424242),
        )
        AppThemeMode.PURE_WHITE -> Triple(
            if (isCurrentNightRed) Color(0xFFD6C2BE) else Color(0xFFFFFFFF),
            if (isCurrentNightRed) Color(0xFF7A2822) else Color(0xFF005AC1),
            if (isCurrentNightRed) Color(0xFF8A4A44) else Color(0xFFB5BAC6),
        )
        AppThemeMode.NIGHT_RED -> Triple(
            Color(0xFF000000),
            Color(0xFFFF5252),
            Color(0xFF6E2C28),
        )
    }

    val (shortTitle, subTag) = when (mode) {
        AppThemeMode.DEEP_SKY -> if (isEn) "Deep Sky" to "Default" else "深空蓝" to "默认"
        AppThemeMode.PURE_BLACK -> if (isEn) "Pure Black" to "AMOLED" else "纯黑" to "AMOLED"
        AppThemeMode.PURE_WHITE -> if (isEn) "Pure White" to "Light" else "纯白" to "浅色模式"
        AppThemeMode.NIGHT_RED -> if (isEn) "Night Red" to "Dark Adapt" else "夜视红" to "暗适应"
    }

    Surface(
        modifier = modifier.selectable(
            selected = selected,
            onClick = onClick,
            role = Role.RadioButton,
        ),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 双层同心色板圆点：外圈为主题背景色，内芯为主题主色
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(swatchBg, CircleShape)
                    .border(1.dp, swatchOutline, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(swatchPrimary, CircleShape),
                )
            }
            Spacer(Modifier.width(9.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = shortTitle,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                )
                Text(
                    text = subTag,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                )
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** 通用设置开关行（整行可切换，无障碍 Role.Switch） */
@Composable
private fun SettingsSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                onValueChange = onCheckedChange,
                role = Role.Switch,
            )
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
        )
    }
}

/** 滑块实时数值徽标 */
@Composable
private fun ValueBadge(text: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
