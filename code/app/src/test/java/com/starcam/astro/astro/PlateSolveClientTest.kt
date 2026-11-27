package com.starcam.astro.astro

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.hypot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 在线求解客户端安全策略、文件去重与完整 Nova API 协议端到端测试（纯 JVM）。
 */
class PlateSolveClientTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun withMockNovaServer(
        handler: (path: String, body: String) -> Triple<Int, String, Map<String, String>>,
        block: suspend (baseUrl: String) -> Unit,
    ) = runBlocking {
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val worker = Thread {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: SocketException) {
                    break
                } catch (_: Exception) {
                    break
                }
                socket.use { s ->
                    s.soTimeout = 5_000
                    val input = BufferedInputStream(s.getInputStream())
                    val headerBytes = ByteArrayOutputStream()
                    var prev = 0
                    var state = 0
                    while (true) {
                        val b = input.read()
                        if (b == -1) return@use
                        headerBytes.write(b)
                        state = when {
                            b == '\r'.code && (state == 0 || state == 2) -> state + 1
                            b == '\n'.code && (state == 1 || state == 3) -> state + 1
                            b == '\r'.code -> 1
                            else -> 0
                        }
                        prev = b
                        if (state == 4) break
                    }
                    val headerText = headerBytes.toString(Charsets.UTF_8.name())
                    val headerLines = headerText.split("\r\n")
                    val reqLine = headerLines.firstOrNull().orEmpty()
                    val fullPath = reqLine.split(" ").getOrNull(1).orEmpty()
                    val contentLength = headerLines
                        .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                        ?.substringAfter(":")?.trim()?.toIntOrNull() ?: 0
                    val bodyBytes = ByteArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = input.read(bodyBytes, read, contentLength - read)
                        if (n <= 0) break
                        read += n
                    }
                    val reqBody = String(bodyBytes, 0, read, Charsets.UTF_8)
                    val relPath = fullPath.removePrefix("/api")
                    val (code, respBody, headers) = handler(relPath, reqBody)
                    val respBytes = respBody.toByteArray(Charsets.UTF_8)
                    val out = s.getOutputStream()
                    val statusText = when (code) {
                        200 -> "OK"
                        429 -> "Too Many Requests"
                        503 -> "Service Unavailable"
                        else -> "Error"
                    }
                    val respHeader = buildString {
                        append("HTTP/1.1 $code $statusText\r\n")
                        append("Content-Type: application/json\r\n")
                        append("Content-Length: ${respBytes.size}\r\n")
                        append("Connection: close\r\n")
                        headers.forEach { (k, v) -> append("$k: $v\r\n") }
                        append("\r\n")
                    }
                    out.write(respHeader.toByteArray(Charsets.UTF_8))
                    out.write(respBytes)
                    out.flush()
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            block("http://127.0.0.1:${server.localPort}/api")
        } finally {
            server.close()
            worker.join(1_000)
        }
    }

    @Test
    fun isSecureServerUrl_enforcesHttpsExceptLoopback() {
        // HTTPS 地址合法
        assertTrue(PlateSolveClient.isSecureServerUrl("https://nova.astrometry.net/api"))
        assertTrue(PlateSolveClient.isSecureServerUrl("HTTPS://EXAMPLE.COM/API"))

        // 本地回环合法
        assertTrue(PlateSolveClient.isSecureServerUrl("http://127.0.0.1:8000/api"))
        assertTrue(PlateSolveClient.isSecureServerUrl("http://localhost:8080/api"))

        // 公网明文 HTTP 严格拒绝（防止明文外泄用户照片与 API Key）
        assertFalse(PlateSolveClient.isSecureServerUrl("http://nova.astrometry.net/api"))
        assertFalse(PlateSolveClient.isSecureServerUrl("http://192.168.1.100/api"))
        assertFalse(PlateSolveClient.isSecureServerUrl("ftp://example.com"))
    }

    @Test
    fun computeFileKey_stableAndIncludesSizeAndHash() {
        val f = tempFolder.newFile("sample.jpg")
        f.writeBytes(ByteArray(1024) { 0x42 })
        val key1 = PlateSolveClient.computeFileKey(f)
        val key2 = PlateSolveClient.computeFileKey(f)
        assertEquals("相同文件的去重键必须确定一致", key1, key2)
        assertTrue("去重键必须包含文件大小", key1.startsWith("1024_"))
    }

    @Test
    fun loopbackExceptionRejectsLookalikeHostsAndUserInfo() {
        for (url in listOf(
            "http://localhost.example.com/api", "http://127.0.0.1.example.com/api",
            "http://localhost@example.com/api", "https://user:password@example.com/api", "https://",
        )) assertFalse(url, PlateSolveClient.isSecureServerUrl(url))
        assertFalse(PlateSolveClient.isSecureServerUrl("http://[::1]:8080/api"))
    }

    @Test
    fun uploadDedupDistinguishesEqualHeadersAndDifferentImagePayloads() {
        val a = tempFolder.newFile("a.jpg")
        val b = tempFolder.newFile("b.jpg")
        a.writeBytes(ByteArray(32768))
        b.writeBytes(ByteArray(32768).also { it[20000] = 1 })
        assertFalse(PlateSolveClient.computeFileKey(a) == PlateSolveClient.computeFileKey(b))
    }

    @Test
    fun solve_fullNovaProtocol_parsesRealCalibrationWithoutStatusAndProjectsAccurately() {
        val subCalls = AtomicInteger(0)
        val jobCalls = AtomicInteger(0)
        val img = tempFolder.newFile("leo_triplet.jpg").apply { writeBytes(ByteArray(128) { 1 }) }

        // 使用真实 nova.astrometry.net/api/jobs/1493115/calibration 返回结构（无 status 字段！）
        val realCalibrationJson = """
            {
              "ra": 169.96633791366915,
              "dec": 13.221011585315143,
              "width_arcsec": 5027.99363323464,
              "height_arcsec": 2964.443968575217,
              "radius": 0.8106715896625917,
              "pixscale": 1.0906710701159739,
              "orientation": 74.25057920908068,
              "parity": 1.0
            }
        """.trimIndent()

        withMockNovaServer(
            handler = { path, body ->
                when (path) {
                    "/login" -> {
                        assertTrue("login 请求应携带 request-json", body.contains("dummy-key"))
                        Triple(200, """{"status":"success","session":"sess-abc"}""", emptyMap())
                    }
                    "/upload" -> {
                        assertTrue("upload 应标记 publicly_visible=n", body.contains("publicly_visible"))
                        assertTrue("upload 应携带 downsample_factor", body.contains("downsample_factor"))
                        assertTrue("提供视场先验时应携带 degwidth 约束", body.contains("degwidth"))
                        assertTrue("提供视场先验时应携带 scale_lower", body.contains("scale_lower"))
                        Triple(200, """{"status":"success","subid":777}""", emptyMap())
                    }
                    "/submissions/777" -> {
                        val n = subCalls.incrementAndGet()
                        val resp = if (n == 1) """{"jobs":[null]}""" else """{"jobs":[1493115]}"""
                        Triple(200, resp, emptyMap())
                    }
                    "/jobs/1493115" -> {
                        val n = jobCalls.incrementAndGet()
                        val resp = if (n == 1) """{"status":"solving"}""" else """{"status":"success"}"""
                        Triple(200, resp, emptyMap())
                    }
                    "/jobs/1493115/calibration" -> Triple(200, realCalibrationJson, emptyMap())
                    else -> Triple(404, """{"error":"unknown path $path"}""", emptyMap())
                }
            },
        ) { baseUrl ->
            val progress = mutableListOf<String>()
            val client = PlateSolveClient(baseUrl, pollInitialMs = 10L, pollMaxMs = 50L)
            val result = client.solve(img, "dummy-key", timeoutMs = 10_000L, fovWidthRangeDeg = 1.0 to 2.0) { progress += it }

            assertEquals(169.96633791366915, result.raDeg, 1e-6)
            assertEquals(13.221011585315143, result.decDeg, 1e-6)
            assertEquals(1.0906710701159739, result.pixScaleArcsec, 1e-6)
            assertEquals(74.25057920908068, result.orientationDeg, 1e-6)
            assertEquals(1, result.parity)
            assertEquals(1493115L, result.subId)
            // radius 从度换算为角分（0.81067° * 60 = 48.64'）
            assertEquals(0.8106715896625917 * 60.0, result.fieldRadiusArcmin!!, 1e-5)
            // JVM 无法用 BitmapFactory 解码假字节时，正确回退 width_arcsec / height_arcsec
            assertEquals(4610, result.imageWidth)
            assertEquals(2718, result.imageHeight)
            assertNotNull("应构建出有效 WCS", result.wcs)

            // 真值校验：nova.astrometry.net/api/jobs/1493115/annotations 给出的 M65 & M66 像素坐标
            val m65 = result.wcs!!.skyToScreen(169.73317, 13.09222, result.imageWidth, result.imageHeight)
            val m66 = result.wcs!!.skyToScreen(170.06254, 12.99153, result.imageWidth, result.imageHeight)
            val errM65 = hypot((m65[0] - 2917.28f).toDouble(), (m65[1] - 752.63f).toDouble())
            val errM66 = hypot((m66[0] - 2950.80f).toDouble(), (m66[1] - 1861.46f).toDouble())
            assertTrue("M65 投影误差应 < 3 px，实际 $errM65 px", errM65 < 3.0)
            assertTrue("M66 投影误差应 < 3 px，实际 $errM66 px", errM66 < 3.0)
            assertTrue("应有完整进度回调", progress.size >= 4)
        }
    }

    @Test
    fun verifyApiKey_reportsServerErrormessageAndSucceedsOnValidKey() {
        withMockNovaServer(
            handler = { path, body ->
                assertEquals("/login", path)
                if (body.contains("valid-key")) {
                    Triple(200, """{"status":"success","session":"s-ok"}""", emptyMap())
                } else {
                    Triple(200, """{"status":"error","errormessage":"bad apikey"}""", emptyMap())
                }
            },
        ) { baseUrl ->
            val client = PlateSolveClient(baseUrl, pollInitialMs = 10L, pollMaxMs = 50L)
            assertEquals("s-ok", client.verifyApiKey("valid-key"))

            try {
                client.verifyApiKey("wrong-key")
                fail("无效 Key 应抛出 PlateSolveException")
            } catch (e: PlateSolveException) {
                assertEquals(PlateSolveException.Kind.AUTH, e.kind)
                assertTrue("应透传服务端 errormessage，实际：${e.message}", e.message!!.contains("bad apikey"))
            }
        }
    }

    @Test
    fun solve_retriesHttp429AndHandlesJobFailureAndCalibrationError() {
        val loginAttempts = AtomicInteger(0)
        val img = tempFolder.newFile("retry.jpg").apply { writeBytes(ByteArray(64) { 7 }) }

        withMockNovaServer(
            handler = { path, _ ->
                when (path) {
                    "/login" -> {
                        if (loginAttempts.incrementAndGet() == 1) {
                            Triple(429, """{"error":"rate limited"}""", mapOf("Retry-After" to "1"))
                        } else {
                            Triple(200, """{"status":"success","session":"s2"}""", emptyMap())
                        }
                    }
                    "/upload" -> Triple(200, """{"status":"success","subid":88}""", emptyMap())
                    "/submissions/88" -> Triple(200, """{"jobs":[99]}""", emptyMap())
                    "/jobs/99" -> Triple(200, """{"status":"failure"}""", emptyMap())
                    else -> Triple(404, "{}", emptyMap())
                }
            },
        ) { baseUrl ->
            val client = PlateSolveClient(baseUrl, pollInitialMs = 10L, pollMaxMs = 20L)
            try {
                client.solve(img, "k", timeoutMs = 5_000L) {}
                fail("failure 状态应抛出 NO_MATCH")
            } catch (e: PlateSolveException) {
                assertEquals(PlateSolveException.Kind.NO_MATCH, e.kind)
                assertEquals("HTTP 429 应自动重试登录", 2, loginAttempts.get())
            }
        }

        // calibration 返回 {"error": "..."} 时应明确报错
        val img2 = tempFolder.newFile("calerr.jpg").apply { writeBytes(ByteArray(64) { 9 }) }
        withMockNovaServer(
            handler = { path, _ ->
                when (path) {
                    "/login" -> Triple(200, """{"status":"success","session":"s3"}""", emptyMap())
                    "/upload" -> Triple(200, """{"status":"success","subid":89}""", emptyMap())
                    "/submissions/89" -> Triple(200, """{"jobs":[100]}""", emptyMap())
                    "/jobs/100" -> Triple(200, """{"status":"success"}""", emptyMap())
                    "/jobs/100/calibration" -> Triple(200, """{"error":"no calibration data available for job 100"}""", emptyMap())
                    else -> Triple(404, "{}", emptyMap())
                }
            },
        ) { baseUrl ->
            val client = PlateSolveClient(baseUrl, pollInitialMs = 10L, pollMaxMs = 20L)
            try {
                client.solve(img2, "k", timeoutMs = 5_000L) {}
                fail("calibration error 应抛出 SOLVE_ERROR")
            } catch (e: PlateSolveException) {
                assertEquals(PlateSolveException.Kind.SOLVE_ERROR, e.kind)
                assertTrue(e.message!!.contains("no calibration data"))
            }
        }
    }
}
