package com.starcam.astro.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 识别日志（§0.70）：把每次识别的**完整过程**记下来，失败时给出可解释的原因，
 * 并允许用户导出交给开发者精确重放。
 *
 * 为什么需要它：历史上多次出现「用户说识别不了、开发机复现不出」——
 * 此前唯一的手段是 §0.64 的调试转储，但它**只在 debug 构建生效**
 * （`isDebugBuild` 判断），而用户装的是 release 包，所以一直没派上用场。
 * 本模块在 release 也工作，这是它与旧转储的关键区别。
 *
 * 两类产物（都在 `getExternalFilesDir("logs")`，免权限、可被文件管理器取走）：
 *  - `starcam-log.txt`     人类可读的滚动日志（成功也记一行摘要，便于看成功率）
 *  - `fail-<时间戳>/`       失败现场：`report.json`（结构化）+ `input.gray.gz`
 *                          （gzip 后的原始像素，开发机解压即可精确重放）
 *
 * 隐私：只写应用自己的外部文件目录；不上传、不联网。用户可一键清空。
 * 日志内的照片路径只保留文件名，不写完整路径。
 */
object SolveLogStore {

    private const val TAG = "SolveLog"

    /** 人类可读日志文件名 */
    private const val LOG_NAME = "starcam-log.txt"

    /** 滚动上限：超过则只保留末尾这么多字符 */
    private const val LOG_MAX_CHARS = 512 * 1024

    /** 失败现场最多保留的目录数（旧的自动删除） */
    private const val MAX_FAIL_DIRS = 10

    /** 单条记录最多保留的星点数（够诊断，又不至于文件过大） */
    private const val MAX_STARS_IN_REPORT = 200

    private const val PREF = "starcam_solve_log"
    private const val KEY_ENABLED = "enabled"

    val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val dirFmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    /** 是否启用。默认开启（用户要求 release 也能记录）。 */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, on).apply()
    }

    /** 日志目录（应用外部目录，免权限；用户在设置页可查看路径） */
    fun logDir(context: Context): File =
        File(context.getExternalFilesDir(null), "logs").apply { mkdirs() }

    fun logFile(context: Context): File = File(logDir(context), LOG_NAME)

    /**
     * 一条人类可读日志。线程安全（synchronized 到本对象），滚动截断。
     * 任何异常都吞掉——日志本身绝不能影响识别主流程。
     */
    fun line(context: Context, msg: String) {
        if (!isEnabled(context)) return
        try {
            Log.i(TAG, msg)
            synchronized(this) {
                val f = logFile(context)
                f.appendText("${timeFmt.format(Date())}  $msg\n")
                if (f.length() > LOG_MAX_CHARS) {
                    // 滚动：只留末尾一段，避免无限增长
                    val keep = f.readText().takeLast(LOG_MAX_CHARS / 2)
                    f.writeText(keep)
                }
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * 写失败现场：结构化报告 + 可选原始像素。
     *
     * @param report   结构化诊断（引擎轨迹、星点数、各轮结果…）
     * @param gray     匹配器实际吃到的灰度（int32 w + int32 h + float32 数组），
     *                 可直接喂给本机测试台重放；为 null 则不落盘
     * @return 落盘的目录（供 UI 展示路径），失败返回 null
     */
    fun dumpFailure(
        context: Context,
        report: JSONObject,
        gray: FloatArray? = null,
        grayW: Int = 0,
        grayH: Int = 0,
    ): File? {
        if (!isEnabled(context)) return null
        return try {
            val dir = File(logDir(context), "fail-${dirFmt.format(Date())}")
            dir.mkdirs()
            File(dir, "report.json").writeText(report.toString(2))

            if (gray != null && grayW > 0 && grayH > 0 && gray.size == grayW * grayH) {
                // gzip 压缩后落盘：1649×2200 的原始 float 数组约 14.5MB，
                // 直接写会让 10 份现场占掉 145MB —— 对手机存储不可接受。
                // 星空图低频为主，实测可压到 1/5 左右，且**无损**，
                // 开发机解压后仍是逐字节相同的像素，重放结果不受影响。
                val f = File(dir, "input.gray.gz")
                java.io.DataOutputStream(
                    java.util.zip.GZIPOutputStream(
                        java.io.BufferedOutputStream(f.outputStream()),
                    ),
                ).use { o ->
                    o.writeInt(grayW)
                    o.writeInt(grayH)
                    for (v in gray) o.writeFloat(v)
                }
            }
            pruneFailDirs(context)
            dir
        } catch (e: Throwable) {
            Log.w(TAG, "写失败现场失败", e)
            null
        }
    }

    /** 失败现场目录，新的在前 */
    fun listFailDirs(context: Context): List<File> =
        logDir(context).listFiles { f -> f.isDirectory && f.name.startsWith("fail-") }
            ?.sortedByDescending { it.name } ?: emptyList()

    /** 只保留最近 [MAX_FAIL_DIRS] 个失败现场 */
    private fun pruneFailDirs(context: Context) {
        val dirs = listFailDirs(context)
        for (i in MAX_FAIL_DIRS until dirs.size) {
            dirs[i].deleteRecursively()
        }
    }

    /** 清空全部日志与失败现场 */
    fun clearAll(context: Context) {
        try {
            logDir(context).deleteRecursively()
            logDir(context).mkdirs()
            line(context, "日志已清空")
        } catch (_: Throwable) {
        }
    }

    /** 统计信息（设置页展示用） */
    fun stats(context: Context): String {
        val dir = logDir(context)
        val logs = dir.listFiles()?.size ?: 0
        val fails = listFailDirs(context).size
        val sizeKb = (dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }) / 1024
        return "日志文件 $logs 个 · 失败现场 $fails 份 · 共 ${sizeKb}KB"
    }

    // ==================== 结构化报告构造 ====================

    /** 建一份报告骨架（含设备与输入信息，便于对照） */
    fun newReport(context: Context, imagePath: String, w: Int, h: Int): JSONObject = JSONObject().apply {
        put("logVersion", 2)
        put("time", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        put("image", File(imagePath).name)          // 只留文件名，不写完整路径
        put("width", w)
        put("height", h)
        put("device", android.os.Build.MODEL ?: "?")
        put("android", android.os.Build.VERSION.RELEASE ?: "?")
        put("appVersion", appVersion(context))
        put("steps", JSONArray())
        put("stars", JSONArray())
    }

    /**
     * 应用版本号。不用 BuildConfig —— AGP 8 默认不生成它，
     * 为一个日志字段去开 `buildFeatures.buildConfig` 不划算，改走 PackageManager。
     */
    private fun appVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (_: Throwable) {
        "?"
    }

    /** 追加一步引擎尝试 */
    fun addStep(report: JSONObject, engine: String, outcome: String, detail: String? = null,
                costMs: Long? = null) {
        try {
            val arr = report.optJSONArray("steps") ?: JSONArray().also { report.put("steps", it) }
            arr.put(JSONObject().apply {
                put("engine", engine)
                put("outcome", outcome)
                detail?.let { put("detail", it) }
                costMs?.let { put("costMs", it) }
            })
        } catch (_: Throwable) {
        }
    }

    /** 写入检出星点（坐标 + 亮度），供开发机对照 */
    fun setStars(report: JSONObject, stars: List<com.starcam.astro.astro.DetectedStar>) {
        try {
            val arr = JSONArray()
            val n = minOf(stars.size, MAX_STARS_IN_REPORT)
            for (i in 0 until n) {
                val s = stars[i]
                arr.put(JSONArray().apply {
                    put(s.x.toDouble()); put(s.y.toDouble()); put(s.brightness.toDouble())
                })
            }
            report.put("stars", arr)
            report.put("starCount", stars.size)
        } catch (_: Throwable) {
        }
    }

    /** 把 report.json 渲染成人类可读的短摘要（失败页显示用） */
    fun renderSummary(report: JSONObject): String = buildString {
        appendLine("图像 ${report.optInt("width")}×${report.optInt("height")}")
        report.optInt("starCount", -1).takeIf { it >= 0 }?.let { appendLine("检出星点 $it 颗") }

        val steps = report.optJSONArray("steps")
        if (steps != null && steps.length() > 0) {
            appendLine("引擎尝试：")
            for (i in 0 until steps.length()) {
                val s = steps.optJSONObject(i) ?: continue
                val cost = s.optLong("costMs", -1).takeIf { it >= 0 }?.let { " (${it}ms)" } ?: ""
                val det = s.optString("detail", "").takeIf { it.isNotBlank() }?.let { "：$it" } ?: ""
                appendLine("  · ${s.optString("engine")} → ${s.optString("outcome")}$det$cost")
            }
        }
        report.optString("verdict", "").takeIf { it.isNotBlank() }?.let { appendLine("判定：$it") }
    }
}
