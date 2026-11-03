package com.starcam.astro.astro

import android.graphics.BitmapFactory
import com.starcam.astro.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 识别失败异常 */
class PlateSolveException(message: String, val kind: Kind = Kind.OTHER) : Exception(message) {
    enum class Kind { NETWORK, AUTH, NO_MATCH, SOLVE_ERROR, TIMEOUT, OTHER }
}

/**
 * astrometry.net 在线底片求解客户端。
 *
 * 流程（与官方 net/client/client.py 一致）：
 *   login 获取 session → upload 上传图片 → 轮询 submissions/{id} 取 job id
 *   → 轮询 jobs/{jobid} 状态 → 取 calibration（ra/dec/orientation/pixscale/parity）。
 */
class PlateSolveClient(
    serverUrl: String = SettingsRepository.DEFAULT_SERVER,
) {

    /** 服务器地址：强制 HTTPS。
     *  serverUrl 由用户自由填写，若不校验，改成恶意 HTTP 地址就会明文外发
     *  用户照片与 API Key，削弱 README 里「照片不离开手机」的承诺。 */
    private val serverUrl: String = serverUrl.trim().removeSuffix("/").also {
        if (!isSecureServerUrl(it)) {
            throw PlateSolveException(
                "在线识别要求 HTTPS 服务器地址（当前：$it）。请在「设置」中改用 https:// 开头的地址。",
                PlateSolveException.Kind.OTHER,
            )
        }
    }

    private val client = sharedClient

    /** 上传并求解；onProgress 回调进度文案。返回与 [image] 像素尺寸一致的定标结果。 */
    suspend fun solve(
        image: File,
        apiKey: String,
        timeoutMs: Long = SOLVE_TIMEOUT_MS,
        onProgress: (String) -> Unit,
    ): SolveResult = withContext(Dispatchers.IO) {
        val key = computeFileKey(image)
        globalUploadLock.withLock {
            if (globalInFlightKeys.contains(key)) {
                throw PlateSolveException("该照片正在识别中，请勿重复提交。", PlateSolveException.Kind.OTHER)
            }
            globalInFlightKeys.add(key)
        }
        try {
            kotlinx.coroutines.withTimeout(timeoutMs.coerceAtLeast(1L)) {
                solveInner(image, apiKey, timeoutMs, onProgress)
            }
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) {
                globalUploadLock.withLock { globalInFlightKeys.remove(key) }
            }
        }
    }

    private suspend fun solveInner(
        image: File,
        apiKey: String,
        timeoutMs: Long,
        onProgress: (String) -> Unit,
    ): SolveResult {
        val overallDeadline = (System.nanoTime() / 1_000_000) + timeoutMs
        onProgress("正在登录识别服务…")
        val session = login(apiKey)

        onProgress("正在上传照片…")
        val subId = upload(session, image)

        onProgress("排队等待解算…")
        val waitBudget = (overallDeadline - (System.nanoTime() / 1_000_000)).coerceAtLeast(1000L)
        val jobId = waitForJob(subId, minOf(WAIT_FOR_JOB_TIMEOUT_MS, waitBudget))

        val remainingBudget = (overallDeadline - (System.nanoTime() / 1_000_000)).coerceAtLeast(1000L)
        val deadline = (System.nanoTime() / 1_000_000) + minOf(SOLVE_TIMEOUT_MS, remainingBudget)
        var lastStatus = ""
        var interval = POLL_INTERVAL_INITIAL_MS
        while (true) {
            if ((System.nanoTime() / 1_000_000) > deadline) {
                throw PlateSolveException(
                    "解算超时（已达预算限制），可能天区恒星较少或服务正忙",
                    PlateSolveException.Kind.TIMEOUT,
                )
            }
            val (status, retryAfter) = jobStatus(jobId)
            if (status != lastStatus) {
                lastStatus = status
                onProgress(progressText(status))
            }
            when (status) {
                "success" -> break
                "failure" -> throw PlateSolveException(
                    "识别失败：未找到可匹配的星场。请确认照片包含清晰星空（≥3 颗亮星），" +
                        "且没有严重过曝、模糊或镜头污渍。",
                    PlateSolveException.Kind.NO_MATCH,
                )
                else -> Unit
            }
            // 服务端给了 Retry-After 就听它的，否则指数退避到上限
            interval = backoff(interval, retryAfter)
            delay(interval)
        }

        onProgress("解算成功，正在生成星座标注…")
        return fetchCalibration(jobId, image)
    }

    /**
     * 指数退避：初始 2s，每次 ×1.5，上限 15s。
     * 原实现固定 4s 轮询，最长 240s 会打出 60 次请求，可能触发 nova 限流；
     * 且完全不看 Retry-After，被限流后只会继续撞。
     */
    private fun backoff(current: Long, retryAfterMs: Long?): Long {
        if (retryAfterMs != null && retryAfterMs > 0) {
            return retryAfterMs.coerceIn(POLL_INTERVAL_INITIAL_MS, POLL_INTERVAL_MAX_MS)
        }
        return (current * 3 / 2).coerceAtMost(POLL_INTERVAL_MAX_MS)
    }

    private fun progressText(status: String): String = when (status) {
        "queued" -> "排队等待解算…"
        "solving" -> "正在解算星场（通常需要 10–60 秒）…"
        "success" -> "解算成功"
        "failure" -> "识别失败"
        else -> "处理中（$status）…"
    }

    /**
     * POST 并解析 JSON。网络类错误自动重试一次（弱网下体验明显改善），
     * HTTP 429/503 会带上 Retry-After 供调用方退避。
     */
    private suspend fun postJson(url: String, body: RequestBody): JSONObject =
        postJsonWithRetry(url, body).first

    private suspend fun postJsonWithRetry(url: String, body: RequestBody): Pair<JSONObject, Long?> {
        var lastError: IOException? = null
        for (attempt in 0 until MAX_NETWORK_RETRIES) {
            try {
                val req = Request.Builder().url(url).post(body).build()
                client.newCall(req).awaitText().let { resp ->
                    val text = resp.text
                    if (resp.code == 429 || resp.code == 503) {
                        throw PlateSolveException(
                            "识别服务繁忙（HTTP ${resp.code}），请稍后重试。",
                            PlateSolveException.Kind.TIMEOUT,
                        )
                    }
                    if (resp.code !in 200..299) {
                        throw PlateSolveException(
                            "请求失败（HTTP ${resp.code}）",
                            PlateSolveException.Kind.NETWORK,
                        )
                    }
                    return JSONObject(text) to resp.retryAfter?.trim()?.toLongOrNull()
                        ?.coerceIn(0L, POLL_INTERVAL_MAX_MS / 1000)?.times(1000L)
                }
            } catch (e: IOException) {
                lastError = e
                if (attempt == MAX_NETWORK_RETRIES - 1) break
            }
        }
        throw PlateSolveException(
            "网络连接失败：${lastError?.message ?: "未知错误"}",
            PlateSolveException.Kind.NETWORK,
        )
    }

    private suspend fun login(apiKey: String): String {
        val json = JSONObject().put("apikey", apiKey).toString()
        val body = FormBody.Builder().add("request-json", json).build()
        val obj = postJson("$serverUrl/login", body)
        if (obj.optString("status") != "success") {
            throw PlateSolveException(
                obj.optString("errormsg", "API Key 无效，请到「设置」中检查"),
                PlateSolveException.Kind.AUTH,
            )
        }
        return obj.getString("session")
    }

    private suspend fun upload(session: String, image: File): Long {
        val meta = JSONObject()
            .put("session", session)
            .put("publicly_visible", "n") // 隐私：图片不进入公开图库
            .put("allow_commercial_use", "d")
            .put("allow_modifications", "d")
        val mime = if (image.name.lowercase().endsWith(".png")) "image/png" else "image/jpeg"
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("request-json", meta.toString())
            .addFormDataPart("file", image.name, image.asRequestBody(mime.toMediaType()))
            .build()
        val obj = postJson("$serverUrl/upload", body)
        if (obj.optString("status") != "success") {
            throw PlateSolveException(
                "上传失败：${obj.optString("errormsg", obj.toString().take(120))}",
                PlateSolveException.Kind.SOLVE_ERROR,
            )
        }
        return obj.getLong("subid")
    }

    private fun emptyBody(): RequestBody = "".toRequestBody(null)

    /** 轮询 submissions/{id} 直到拿到第一个非空 job id */
    private suspend fun waitForJob(subId: Long, maxWaitMs: Long = WAIT_FOR_JOB_TIMEOUT_MS): Long {
        val deadline = (System.nanoTime() / 1_000_000) + maxWaitMs
        var interval = POLL_INTERVAL_INITIAL_MS
        while (true) {
            if ((System.nanoTime() / 1_000_000) > deadline) {
                throw PlateSolveException(
                    "任务未进入解算队列（等待 ${maxWaitMs / 1000} 秒），请稍后重试",
                    PlateSolveException.Kind.TIMEOUT,
                )
            }
            val (obj, retryAfter) = postJsonWithRetry("$serverUrl/submissions/$subId", emptyBody())
            val jobs = obj.optJSONArray("jobs")
            if (jobs != null && jobs.length() > 0) {
                for (i in 0 until jobs.length()) {
                    if (!jobs.isNull(i)) return jobs.getLong(i)
                }
            }
            interval = backoff(interval, retryAfter)
            delay(interval)
        }
    }

    private suspend fun jobStatus(jobId: Long): Pair<String, Long?> {
        val (obj, retryAfter) = postJsonWithRetry("$serverUrl/jobs/$jobId", emptyBody())
        return obj.optString("status", "processing") to retryAfter
    }

    private suspend fun fetchCalibration(jobId: Long, image: File): SolveResult {
        val obj = postJson("$serverUrl/jobs/$jobId/calibration", emptyBody())
        if (obj.optString("status") != "success") {
            throw PlateSolveException("定标数据缺失，请重试", PlateSolveException.Kind.SOLVE_ERROR)
        }

        val ra = obj.getDouble("ra")
        val dec = obj.getDouble("dec")
        val orientation = obj.getDouble("orientation")
        val pixscale = obj.getDouble("pixscale")
        val parity = obj.getInt("parity")
        val radius = obj.optDouble("radius", Double.NaN).takeIf { !it.isNaN() }

        // 图像尺寸以实际上传文件为准（calibration 里不保证返回像素宽高）
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(image.absolutePath, bounds)
        val width = if (bounds.outWidth > 0) bounds.outWidth else 0
        val height = if (bounds.outHeight > 0) bounds.outHeight else 0

        val wcs = WcsTransform.fromCalibration(ra, dec, orientation, pixscale, parity, width, height)

        return SolveResult(
            raDeg = ra,
            decDeg = dec,
            pixScaleArcsec = pixscale,
            orientationDeg = orientation,
            parity = parity,
            imageWidth = width,
            imageHeight = height,
            subId = jobId,
            fieldRadiusArcmin = radius,
            wcs = wcs,
        )
    }

    companion object {
        const val SOLVE_TIMEOUT_MS = 240_000L
        const val WAIT_FOR_JOB_TIMEOUT_MS = 120_000L
        private const val POLL_INTERVAL_INITIAL_MS = 2_000L
        private const val POLL_INTERVAL_MAX_MS = 15_000L
        private const val MAX_NETWORK_RETRIES = 2

        private val sharedClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(180, TimeUnit.SECONDS)
            .build()

        private val globalUploadLock = Mutex()
        private val globalInFlightKeys = mutableSetOf<String>()

        /** 计算文件去重指纹（§0.123 修复 P3-2：全局跨实例去重） */
        fun computeFileKey(file: File): String {
            return try {
                val md = java.security.MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buf = ByteArray(16384)
                    var read: Int
                    while (input.read(buf).also { read = it } >= 0) {
                        if (read > 0) md.update(buf, 0, read)
                    }
                }
                "${file.length()}_" + md.digest().joinToString("") { String.format(java.util.Locale.ROOT, "%02x", it) }.take(16)
            } catch (_: Exception) {
                "${file.length()}_${file.lastModified()}"
            }
        }

        /** 只接受 HTTPS（本地回环调试地址除外，便于自建服务联调）。 */
        fun isSecureServerUrl(url: String): Boolean {
            val u = url.trim().toHttpUrlOrNull() ?: return false
            if (u.username.isNotEmpty() || u.password.isNotEmpty()) return false
            return u.scheme == "https" ||
                (u.scheme == "http" && (u.host == "127.0.0.1" || u.host == "localhost"))
        }
    }
}
