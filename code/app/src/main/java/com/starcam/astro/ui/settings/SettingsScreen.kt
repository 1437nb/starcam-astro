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
import com.starcam.astro.data.SettingsRepository
import com.starcam.astro.ui.theme.AppThemeMode
import com.starcam.astro.ui.theme.ThemeState

/** 设置页：astrometry.net API Key 与服务器地址 */
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
    var saved by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
            // 外观（§0.33）：夜视红为暗适应模式——红光不破坏夜间视力，建议观星时使用
            Text("外观", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                "夜间观星建议开启「夜视红」：纯黑背景 + 低亮度红光，" +
                    "对暗适应的破坏最小，OLED 屏幕也更省电。",
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
                    Text(mode.label, fontWeight = FontWeight.Medium)
                }
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            Spacer(Modifier.height(20.dp))

            Text("识别引擎", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                "拍照认星使用三层引擎：\n" +
                    "① 内置 Hipparcos 919 颗亮星广角匹配（约 8°~180°，秒级出结果）\n" +
                    "② astrometry.net 官方盲求解引擎（本地，0.1°~180° 视场，含广角）\n" +
                    "③ 在线 nova.astrometry.net 兜底（需 API Key）\n" +
                    "自动模式：广角照片先亮星表快匹配，失败后用官方引擎精解；\n" +
                    "窄场照片（<8°，如望远镜接拍）自动跳过亮星表直接官方引擎。",
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
                        Text(mode.label, fontWeight = FontWeight.Medium)
                        Text(
                            mode.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            Spacer(Modifier.height(20.dp))

            Text("在线识别（astrometry.net）", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                "「拍照认星」使用 astrometry.net 开源底片求解服务识别照片天区。\n" +
                    "该服务免费，需注册获取 API Key。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it; saved = false },
                label = { Text("API Key") },
                placeholder = { Text("例如：AbCdEf123456…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "API Key 是一串字母数字（无标点），注册登录后在网站右上角" +
                    "「My Profile」页可见；粘贴时注意不要带上多余空格。",
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
                Text("打开 nova.astrometry.net（注册后在 My Profile 查看 Key）↗")
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            Spacer(Modifier.height(20.dp))

            Text("高级：服务器地址", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it; saved = false },
                label = { Text("服务器地址") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "默认 https://nova.astrometry.net/api；自建 astrometry.net 服务时可修改。",
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
                Text("保存", fontSize = 16.sp)
            }

            if (saved) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "✓ 已保存",
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
                    Text("隐私说明", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "• 在线识别时，照片会上传到所选服务器（默认 astrometry.net）。\n" +
                            "• 上传前照片会被重新编码：拍摄位置（GPS）、时间、相机参数等\n" +
                            "  EXIF 信息已剥离，仅上传像素内容（§0.36 审计确认）。\n" +
                            "• 上传默认标记为「不公开」，不会被加入公开星图库。\n" +
                            "• 位置信息仅在本地用于天区先验（缩小搜索范围），绝不上传。\n" +
                            "• 无需网络时可使用主页「离线演示」功能完整体验。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
