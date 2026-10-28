package com.starcam.astro.astro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 在线求解客户端安全策略与文件去重测试（纯 JVM）。
 */
class PlateSolveClientTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

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
}
