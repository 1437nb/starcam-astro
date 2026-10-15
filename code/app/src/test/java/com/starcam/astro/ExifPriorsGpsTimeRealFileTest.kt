package com.starcam.astro

import com.starcam.astro.astro.ExifPriorsReader
import com.starcam.astro.astro.LocationSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files.createTempDirectory

/**
 * §0.109（验证中发现）：**GPS 拍摄时刻必须真的被用上** —— 走完整 EXIF 读取链的回归。
 *
 * ## 为什么需要"真文件"测试
 *
 * `ExifPriorsTimeParseTest` 直接反射调 `parseUtcEpoch(date, time)` 并喂**原始分式形态**
 * （`"22/1,30/1,15/1"`）—— 那是解析器最初的假设，不是真机上的输入。真机上
 * `ExifPriorsReader.read()` 拿到的是 **androidx ExifInterface 格式化后的 `"HH:MM:SS"`**
 * （1.3.7 对 RATIONAL 三连的返回形态，已在真实照片上实测）。只认分式形态的解析器会
 * 把时分秒静默当成 0 → `epoch` 变成**当天 UTC 零点**（月亮标注可偏出 ~7°）。
 * 这条 bug 在 v1.5.76 上真实存在，本测试用**带真实 EXIF 的夹具文件**把它钉死。
 *
 * ## 夹具
 *
 * `src/test/resources/exif-probe/` 下的两张 jpg —— 由 `tools/make_exif_probe.py` 在公开演示图
 * （`assets/offline_photos/photo4963.jpg`）上注入 GPS EXIF 生成：
 *  - `105-a-single-digit-gpstime.jpg`：GPS 12:05:09（**分/秒单数位**）、无 DateTimeOriginal；
 *  - `105-b-two-digit-gpstime.jpg`：GPS 14:30:15（全两位）+ DateTimeOriginal（真实相机写法）。
 *
 * 期望值按 **GPS 的 UTC 时刻**手算，不依赖 `java.time` 现算：
 *  - 2024-11-01T12:05:09Z = 1730462709
 *  - 2024-11-05T14:30:15Z = 1730817015
 */
class ExifPriorsGpsTimeRealFileTest {

    private fun fixture(name: String): File {
        val dir = createTempDirectory("exif-probe").toFile()
        val out = File(dir, name)
        val res = javaClass.getResourceAsStream("/exif-probe/$name")
            ?: error("夹具缺失：/exif-probe/$name（用 tools/make_exif_probe.py 生成）")
        res.use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    /** 探针 A：单数位 GPS 时间 + 无 DateTimeOriginal → 必须用上 GPS 的时分秒。 */
    @Test
    fun `单数位 GPS 时间与无本地时间时用上 GPS 时刻`() {
        val p = ExifPriorsReader.read(fixture("105-a-single-digit-gpstime.jpg").absolutePath)
        assertTrue("GPS 时间存在时 timeIsUtc 应为真", p.timeIsUtc)
        assertEquals("应取 EXIF GPS 定位", LocationSource.EXIF, p.locationSource)
        assertEquals(39.9, p.latDeg!!, 1e-6)
        assertEquals(116.4, p.lonDeg!!, 1e-6)
        assertNotNull("必须解析出拍摄时刻（该文件没有 DateTimeOriginal 可回落）", p.epochSec)
        assertEquals(
            "时刻必须是 GPS 的 12:05:09Z，而不是当天零点（1730419200）",
            1730462709L, p.epochSec,
        )
    }

    /** 探针 B：常规写法 —— GPS 时刻优先于 DateTimeOriginal（本地时区解释）。 */
    @Test
    fun `常规写法下 GPS 时刻优先于本地时间`() {
        val p = ExifPriorsReader.read(fixture("105-b-two-digit-gpstime.jpg").absolutePath)
        assertTrue(p.timeIsUtc)
        assertNotNull(p.epochSec)
        assertEquals(
            "GPS 14:30:15Z 应胜出（DateTimeOriginal 是 22:30:15 本地，其解释随时区变化）",
            1730817015L, p.epochSec,
        )
    }

    /** 读不到 EXIF 的文件不得抛异常（演示图无 EXIF，走"全部未知"降级）。 */
    @Test
    fun `无 EXIF 的文件安全降级`() {
        val res = javaClass.getResourceAsStream("/exif-probe/105-a-single-digit-gpstime.jpg")
        res.use { input ->
            val tmp = File(createTempDirectory("noexif").toFile(), "plain.jpg")
            // 去掉 APP1 之后再读：直接截取 SOI 之后的原始 JPEG 头两字节即可构造"无 EXIF"文件
            val bytes = input!!.readBytes()
            val idx = bytes.indexOfEoiPlaceholder()
            tmp.writeBytes(bytes.copyOfRange(0, 2) + bytes.copyOfRange(idx, bytes.size))
            val p = ExifPriorsReader.read(tmp.absolutePath)
            assertNull("无 GPS 时间时 epoch 应为 null（由调用方降级）", p.epochSec)
            assertNull(p.latDeg)
        }
    }

    /** 找到 APP1 段之后（EXIF 之外）的起点：简单取第一个 APP1 长度后跳过它。 */
    private fun ByteArray.indexOfEoiPlaceholder(): Int {
        // 结构：FFD8 + FFE1 <len:2> <payload:len-2> + ...（生成器只插了一个 APP1）
        require(this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte())
        require(this[2] == 0xFF.toByte() && this[3] == 0xE1.toByte())
        val len = ((this[4].toInt() and 0xFF) shl 8) or (this[5].toInt() and 0xFF)
        return 2 + 2 + len
    }
}
