package com.starcam.astro.astro

import android.graphics.BitmapFactory
import com.starcam.astro.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
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
    private val serverUrl: String = SettingsRepository.DEFAULT_SERVER,
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()

    /** 上传并求解；onProgress 回调进度文案。返回与 [image] 像素尺寸一致的定标结果。 */
    suspend fun solve(
        image: File,
        apiKey: String,
        onProgress: (String) -> Unit,
    ): SolveResult = withContext(Dispatchers.IO) {
        onProgress("正在登录识别服务…")
        val session = login(apiKey)

        onProgress("正在上传照片…")
        val subId = upload(session, image)

        onProgress("排队等待解算…")
        val jobId = waitForJob(subId)

        val deadline = System.currentTimeMillis() + SOLVE_TIMEOUT_MS
        var lastStatus = ""
        while (true) {
            if (System.currentTimeMillis() > deadline) {
                throw PlateSolveException(
                    "识别超时（${SOLVE_TIMEOUT_MS / 1000} 秒）。排队较久可稍后重试。",
                    PlateSolveException.Kind.TIMEOUT,
                )
            }
            val status = jobStatus(jobId)
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
            delay(POLL_INTERVAL_MS)
        }

        onProgress("解算成功，正在生成星座标注…")
        fetchCalibration(jobId, image)
    }

    private fun progressText(status: String): String = when (status) {
        "queued" -> "排队等待解算…"
        "solving" -> "正在解算星场（通常需要 10–60 秒）…"
        "success" -> "解算成功"
        "failure" -> "识别失败"
        else -> "处理中（$status）…"
    }

    private fun postJson(url: String, body: RequestBody): JSONObject {
        val req = Request.Builder().url(url).post(body).build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw PlateSolveException("请求失败（HTTP ${resp.code}）", PlateSolveException.Kind.NETWORK)
            }
            return JSONObject(text)
        }
    }

    private fun login(apiKey: String): String {
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

    private fun upload(session: String, image: File): Long {
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
    private suspend fun waitForJob(subId: Long): Long {
        val deadline = System.currentTimeMillis() + 120_000L
        while (true) {
            if (System.currentTimeMillis() > deadline) {
                throw PlateSolveException("任务未进入解算队列，请稍后重试", PlateSolveException.Kind.TIMEOUT)
            }
            val obj = postJson("$serverUrl/submissions/$subId", emptyBody())
            val jobs = obj.optJSONArray("jobs")
            if (jobs != null && jobs.length() > 0) {
                for (i in 0 until jobs.length()) {
                    if (!jobs.isNull(i)) return jobs.getLong(i)
                }
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private fun jobStatus(jobId: Long): String {
        val obj = postJson("$serverUrl/jobs/$jobId", emptyBody())
        return obj.optString("status", "processing")
    }

    private fun fetchCalibration(jobId: Long, image: File): SolveResult {
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
        private const val SOLVE_TIMEOUT_MS = 240_000L
        private const val POLL_INTERVAL_MS = 4_000L
    }
}
