package com.starcam.astro

import android.content.Context
import android.util.Log
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.text.font.FontFamily
import com.starcam.astro.astro.StarSolver
import com.starcam.astro.astro.StellarSolverNative
import com.starcam.astro.data.SettingsRepository
import com.starcam.astro.ui.theme.StarCamTheme
import com.starcam.astro.util.ImageUtils
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 真机识别效率自动化测试页（仅 debug 构建注册）。
 *
 * 由 adb 直接拉起（不受后台启动限制，进程全程前台存活）：
 * ```
 * adb shell am start -n com.starcam.astro/.AutoTestActivity \
 *   --es dir /sdcard/Android/data/com.starcam.astro/files/test12
 * ```
 * 对目录内每张 jpg 走完整三层引擎链路，屏幕实时显示进度，
 * 同步输出 logcat（tag=STARCAM_TEST）。
 */
@OptIn(ExperimentalMaterial3Api::class)
class AutoTestActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val dir = intent.getStringExtra("dir")
        val url = intent.getStringExtra("url")
        setContent {
            StarCamTheme {
                val context = this
                var lines by remember { mutableStateOf(listOf<String>()) }
                var finished by remember { mutableStateOf(false) }
                var granted by remember { mutableStateOf(false) }
                val permLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { ok ->
                    granted = ok
                    if (!ok) lines = lines + "⚠ 未授予图片读取权限：相册库与共享目录将不可见"
                }
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    val perm = if (android.os.Build.VERSION.SDK_INT >= 33)
                        Manifest.permission.READ_MEDIA_IMAGES
                    else Manifest.permission.READ_EXTERNAL_STORAGE
                    val has = context.checkSelfPermission(perm) ==
                        PackageManager.PERMISSION_GRANTED
                    if (has) granted = true else permLauncher.launch(perm)
                }
                LaunchedEffect(granted) {
                    if (!granted) return@LaunchedEffect
                    if (url != null) {
                        lines = AutoTest.runFromUrl(context, url) { line ->
                            lines = lines + line
                        }
                        finished = true
                        return@LaunchedEffect
                    }
                    lines = lines + "dir 参数=$dir"
                    // 数据源候选：①MediaStore 相册库（Pictures/test12/，最可靠）
                    // ②硬编码路径 File 探测 ③getExternalFilesDir 探测
                    val media = AutoTest.mediaStoreSources(context, "Pictures/test12")
                    lines = lines + "MediaStore(Pictures/test12) 命中=${media.size}"
                    val ext = context.getExternalFilesDir(null)
                    lines = lines + "extFiles=${ext?.absolutePath}"
                    val candidates = listOfNotNull(
                        File(dir),
                        ext?.let { File(it, "test12") },
                    )
                    var fileSrc: File? = null
                    for (c in candidates) {
                        val n = c.listFiles()?.size ?: -1
                        lines = lines + "探测 ${c.absolutePath} exists=${c.exists()} files=$n"
                        if ((fileSrc == null) && n > 0) fileSrc = c
                    }
                    lines = lines + "使用=${if (media.isNotEmpty()) "MediaStore(${media.size}张)" else (fileSrc?.absolutePath ?: "无可用数据源")}"
                    lines = AutoTest.run(context, media, fileSrc) { line ->
                        lines = lines + line
                    }
                    finished = true
                }
                Scaffold(
                    topBar = {
                        TopAppBar(title = { Text("识别效率测试（debug）") })
                    },
                ) { padding ->
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(horizontal = 12.dp),
                    ) {
                        Text(
                            if (finished) "✓ 全部完成" else "识别中…请保持屏幕常亮",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        LazyColumn {
                            items(lines) { line ->
                                Text(
                                    line,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 测试执行逻辑（logcat + 屏幕回调双通道输出） */
object AutoTest {

    const val TAG = "STARCAM_TEST"

    /**
     * URL 模式：从服务器拉 list.json（文件名数组），逐张下载识别。
     * 绕开一切存储权限/路径/相册库差异（手机只需能上网）。
     */
    suspend fun runFromUrl(
        context: Context,
        baseUrl: String,
        onLog: (String) -> Unit,
    ): List<String> {
        val out = mutableListOf<String>()
        val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        log(out, onLog, "URL=$base")
        val names: List<String> = try {
            withContext(Dispatchers.IO) {
                val conn = java.net.URL(base + "list.json").openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 15000; conn.readTimeout = 15000
                conn.inputStream.bufferedReader().readText()
            }
        } catch (e: Exception) {
            log(out, onLog, "清单获取失败: ${e.message}")
            log(out, onLog, "ALL-DONE total=0 solved=0")
            return out
        }.removeSurrounding("[", "]")
            .split(",")
            .map { it.trim().removeSurrounding("\"") }
            .filter { it.isNotEmpty() }
        log(out, onLog, "清单=${names.size}张")
        if (names.isEmpty()) {
            log(out, onLog, "ALL-DONE total=0 solved=0")
            return out
        }
        val settings = SettingsRepository(context)
        var solved = 0
        var totalMs = 0L
        withContext(Dispatchers.IO) {
            for (name in names) {
                val t0 = System.currentTimeMillis()
                val line: String = try {
                    val bmp = withContext(Dispatchers.IO) {
                        val conn = java.net.URL(base + name).openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 20000; conn.readTimeout = 60000
                        conn.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
                    }?.let { bmp0 ->
                        val k = maxOf(bmp0.width, bmp0.height) / 2200.0
                        if (k > 1.0) {
                            android.graphics.Bitmap.createScaledBitmap(
                                bmp0,
                                (bmp0.width / k).toInt().coerceAtLeast(1),
                                (bmp0.height / k).toInt().coerceAtLeast(1),
                                true,
                            )
                        } else bmp0
                    }
                    if (bmp == null) {
                        "$name FAILED nstars=0 (download/decode-fail)"
                    } else {
                        val result = StarSolver.solve(context, bmp, "/http/$name", settings) { }
                        if (result != null) {
                            solved++
                            val s = result.solve
                            "${name} SOLVED nstars=${s.nStars ?: -1} ra=%.2f dec=%.2f fov=%.1f".format(
                                s.raDeg, s.decDeg, s.fieldWidthDeg,
                            ) + " engine=${result.detail ?: result.engine?.name ?: "?"}"
                        } else {
                            "$name FAILED nstars=${StellarSolverNative.lastFailNStars ?: -1}" +
                                " rc=${StellarSolverNative.lastFailRc ?: -1}" +
                                " gmean=%.1f gmax=%.1f".format(
                                    StellarSolverNative.lastFailGMean ?: -1.0,
                                    StellarSolverNative.lastFailGMax ?: -1.0,
                                )
                        }
                    }
                } catch (e: Exception) {
                    "$name FAILED (${e.message ?: "exception"})"
                }
                val ms = System.currentTimeMillis() - t0
                totalMs += ms
                log(out, onLog, "$line timeMs=$ms")
            }
        }
        val summary = "ALL-DONE total=${names.size} solved=$solved avgMs=${totalMs / names.size}"
        log(out, onLog, summary)
        return out
    }

    /** 数据源：名字 + 打开解码位图 */
    data class Source(val name: String, val open: () -> android.graphics.Bitmap?)

    /** MediaStore 相册库扫描（RELATIVE_PATH 前缀匹配，按添加时间排序） */
    fun mediaStoreSources(
        context: Context,
        relPathPrefix: String,
    ): List<Source> {
        val out = mutableListOf<Source>()
        val collection = android.provider.MediaStore.Images.Media.getContentUri(
            android.provider.MediaStore.VOLUME_EXTERNAL,
        )
        val proj = arrayOf(
            android.provider.MediaStore.Images.Media._ID,
            android.provider.MediaStore.Images.Media.DISPLAY_NAME,
        )
        try {
            context.contentResolver.query(
                collection,
                proj,
                "${android.provider.MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
                arrayOf("$relPathPrefix%"),
                "${android.provider.MediaStore.Images.Media.DATE_ADDED} ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val name = c.getString(1) ?: "img$id"
                    val uri = android.net.Uri.parse(
                        "content://media/external/images/media/$id",
                    )
                    out.add(Source(name) {
                        context.contentResolver.openInputStream(uri)?.use { s ->
                            android.graphics.BitmapFactory.decodeStream(s)
                        }
                    })
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    /** 逐张执行完整识别链路；onLog 为屏幕实时回调；返回全部输出行 */
    suspend fun run(
        context: Context,
        mediaSources: List<Source>,
        fileDir: File?,
        onLog: (String) -> Unit,
    ): List<String> {
        val out = mutableListOf<String>()
        val media = mediaSources
        val files = fileDir?.listFiles { f ->
            f.extension.lowercase() in setOf("jpg", "jpeg")
        }?.sorted() ?: emptyList()
        log(out, onLog, "数据源: MediaStore=${media.size}张 File=${files.size}张")
        if (media.isEmpty() && files.isEmpty()) {
            val s = "ALL-DONE total=0 solved=0"
            log(out, onLog, s)
            return out
        }
        // 统一为 (名字, 打开函数) 序列：MediaStore 优先，File 兜底（去重按名字跳过）
        val seq = mutableListOf<Source>()
        val seen = mutableSetOf<String>()
        seq.addAll(media)
        seen.addAll(media.map { it.name })
        for (f in files) {
            if (f.name in seen) continue
            seen.add(f.name)
            seq.add(Source(f.name) {
                android.graphics.BitmapFactory.decodeStream(java.io.FileInputStream(f))
            })
        }
        val settings = SettingsRepository(context)
        var solved = 0
        var totalMs = 0L
        withContext(Dispatchers.IO) {
            for ((idx, srcItem) in seq.withIndex()) {
                val t0 = System.currentTimeMillis()
                val line: String = try {
                    val bmp = srcItem.open()?.let { bmp0 ->
                        // 长边 2200 采样（与手动识别一致）
                        val k = maxOf(bmp0.width, bmp0.height) / 2200.0
                        if (k > 1.0) {
                            android.graphics.Bitmap.createScaledBitmap(
                                bmp0,
                                (bmp0.width / k).toInt().coerceAtLeast(1),
                                (bmp0.height / k).toInt().coerceAtLeast(1),
                                true,
                            )
                        } else bmp0
                    }
                    if (bmp == null) {
                        "${srcItem.name} FAILED nstars=0 (decode-fail)"
                    } else {
                        val result = StarSolver.solve(context, bmp, "/batch/${srcItem.name}", settings) { }
                        if (result != null) {
                            solved++
                            val s = result.solve
                            "${srcItem.name} SOLVED nstars=${s.nStars ?: -1} ra=%.2f dec=%.2f fov=%.1f".format(
                                s.raDeg, s.decDeg, s.fieldWidthDeg,
                            ) + " engine=${result.detail ?: result.engine?.name ?: "?"}"
                        } else {
                            "${srcItem.name} FAILED nstars=${StellarSolverNative.lastFailNStars ?: -1}" +
                                " rc=${StellarSolverNative.lastFailRc ?: -1}" +
                                " gmean=%.1f gmax=%.1f".format(
                                    StellarSolverNative.lastFailGMean ?: -1.0,
                                    StellarSolverNative.lastFailGMax ?: -1.0,
                                )
                        }
                    }
                } catch (e: Exception) {
                    "${srcItem.name} FAILED (${e.message ?: "exception"})"
                }
                val ms = System.currentTimeMillis() - t0
                totalMs += ms
                log(out, onLog, "$line timeMs=$ms")
            }
        }
        val summary = "ALL-DONE total=${seq.size} solved=$solved avgMs=${if (seq.isEmpty()) 0 else totalMs / seq.size}"
        log(out, onLog, summary)
        return out
    }

    private fun log(out: MutableList<String>, onLog: (String) -> Unit, line: String) {
        Log.i(TAG, line)
        out.add(line)
        onLog(line)
    }
}
