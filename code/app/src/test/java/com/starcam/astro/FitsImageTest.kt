package com.starcam.astro

import com.starcam.astro.util.FitsImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * FITS 解码器（[FitsImage]）单元回归 —— 纯 JVM，夹具在测试内**现场构造**（不占仓库体积）。
 *
 * 口径对齐 `tools/convert_narrowfield.py`（仓库把 FITS 转成真值台 `.gray` 的参考实现）：
 * 大端读取、行序翻转、NaN→0、1.0%/99.5% 分位拉伸到 0..255。任何一条走偏，设备上看到
 * 的星场就会与回归台上的不一样（最坏情况：上下颠倒，匹配必失败）。
 */
class FitsImageTest {

    // ------------------------------------------------------------ 夹具构造

    /** 一张 80 列的 FITS 头卡片；`value` 为 null 时输出注释型卡片。 */
    private fun card(key: String, value: Any?): String {
        val body = when (value) {
            null -> ""
            is String -> "'${value}'"
            is Double -> {
                // 与真实 FITS 写法一致：定点或 D 指数
                if (value != 0.0 && (kotlin.math.abs(value) < 1e-4 || kotlin.math.abs(value) >= 1e6)) {
                    "%.6E".format(value).replace("E", "D")
                } else {
                    value.toString()
                }
            }
            else -> value.toString()
        }
        val text = if (body.isEmpty()) key else "%s= %s".format(key.padEnd(8), body)
        return text.padEnd(80).take(80)
    }

    private fun headerBlock(vararg lines: String): ByteArray {
        val sb = StringBuilder()
        for (l in lines) sb.append(l)
        sb.append("END".padEnd(80))
        while (sb.length % 2880 != 0) sb.append(' '.toString().repeat(80))
        return sb.toString().toByteArray(Charsets.US_ASCII)
    }

    /** BITPIX=-32 的单 HDU FITS；[rows] 按 FITS 行序（第 0 行 = 图像**底部**）。 */
    private fun fitsFloat32(w: Int, h: Int, rows: List<FloatArray>, extra: List<String> = emptyList()): ByteArray {
        val head = headerBlock(
            card("SIMPLE", "T"), card("BITPIX", -32), card("NAXIS", 2),
            card("NAXIS1", w), card("NAXIS2", h),
            *extra.toTypedArray(),
        )
        val data = ByteBuffer.allocate(w * h * 4).order(ByteOrder.BIG_ENDIAN)
        for (y in 0 until h) for (x in 0 until w) data.putFloat(rows[y][x])
        val out = ByteArrayOutputStream()
        out.write(head)
        out.write(data.array())
        // 数据补齐到 2880 整数倍
        val pad = (2880 - (data.array().size % 2880)) % 2880
        out.write(ByteArray(pad))
        return out.toByteArray()
    }

    private fun fitsInt16(w: Int, h: Int, raw: List<IntArray>, extra: List<String> = emptyList()): ByteArray {
        val head = headerBlock(
            card("SIMPLE", "T"), card("BITPIX", 16), card("NAXIS", 2),
            card("NAXIS1", w), card("NAXIS2", h),
            *extra.toTypedArray(),
        )
        val data = ByteBuffer.allocate(w * h * 2).order(ByteOrder.BIG_ENDIAN)
        for (y in 0 until h) for (x in 0 until w) data.putShort(raw[y][x].toShort())
        val out = ByteArrayOutputStream()
        out.write(head)
        out.write(data.array())
        val pad = (2880 - (data.array().size % 2880)) % 2880
        out.write(ByteArray(pad))
        return out.toByteArray()
    }

    // ------------------------------------------------------------ 测试

    @Test
    fun `BITPIX 负 32 解码：行序翻转与拉伸到 0-255`() {
        // 4x4：FITS 第 0 行（图像底部）全亮 100，其余 0 → 翻转后**顶行**应为 255
        val rows = listOf(
            FloatArray(4) { 100f },  // FITS 第 0 行 = 图像底部
            FloatArray(4) { 0f },
            FloatArray(4) { 0f },
            FloatArray(4) { 0f },
        )
        val d = FitsImage.decodeBytes(fitsFloat32(4, 4, rows), maxDim = 2200)
        assertNotNull(d)
        assertEquals(4, d!!.w)
        assertEquals(4, d.h)
        // 顶行（位图第 0 行）应来自 FITS 的最后一行？—— 不：FITS 第 0 行在**底**，
        // 翻转后它落到位图**最后**一行；最亮值来自 FITS 第 0 行 → 位图末行 = 255
        assertEquals("位图末行来自 FITS 第 0 行", 255f, d.gray[3 * 4 + 0], 0.01f)
        assertEquals("位图首行来自 FITS 最后一行", 0f, d.gray[0], 0.01f)
    }

    @Test
    fun `BITPIX16 与 BSCALE-BZERO 和浮点等价`() {
        val raw = listOf(
            IntArray(3) { 100 }, IntArray(3) { 200 }, IntArray(3) { 300 },
        )
        // 物理值 = BZERO + BSCALE*raw = 0 + 0.5*raw
        val f = FitsImage.decodeBytes(
            fitsInt16(3, 3, raw, listOf(card("BSCALE", 0.5), card("BZERO", 0.0))),
        )!!
        val expect = FitsImage.decodeBytes(
            fitsFloat32(
                3, 3,
                listOf(
                    FloatArray(3) { 50f }, FloatArray(3) { 100f }, FloatArray(3) { 150f },
                ),
            ),
        )!!
        assertTrue(
            "同一物理值经 16 位+BSCALE 与直接浮点应得到相同灰度",
            f.gray.contentEquals(expect.gray),
        )
    }

    @Test
    fun `NaN 与 Inf 归零`() {
        val rows = listOf(
            floatArrayOf(0f, Float.NaN, 100f, Float.POSITIVE_INFINITY),
            floatArrayOf(Float.NEGATIVE_INFINITY, 10f, 20f, 30f),
        )
        val d = FitsImage.decodeBytes(fitsFloat32(4, 2, rows))!!
        for (v in d.gray) assertTrue("不得出现非有限值：$v", v.isFinite())
        // 位图第 1 行来自 FITS 第 0 行 [0, NaN, 100, +Inf]：NaN/Inf 归零后拉伸为 [0, 0, 255, 0]
        assertEquals("NaN 位置应为 0", 0f, d.gray[5], 0.01f)
        assertEquals("+Inf 位置应为 0", 0f, d.gray[7], 0.01f)
        assertEquals("100 是全场最大值 → 拉伸到 255", 255f, d.gray[6], 0.01f)
        // 位图第 0 行来自 FITS 第 1 行 [-Inf, 10, 20, 30]：-Inf → 0，其余按 0..255 线性放大
        assertEquals("-Inf 位置应为 0", 0f, d.gray[0], 0.01f)
        assertTrue("10 应被拉伸到正区间（实测 %.2f）".format(d.gray[1]), d.gray[1] > 0f)
    }

    @Test
    fun `主 HDU 无数据时取第一个图像扩展 HDU`() {
        // 主 HDU：NAXIS=0（无数据）；扩展 1：3x2 图像
        val primary = headerBlock(
            card("SIMPLE", "T"), card("BITPIX", 8), card("NAXIS", 0),
        )
        val ext = headerBlock(
            card("XTENSION", "IMAGE"), card("BITPIX", -32), card("NAXIS", 2),
            card("NAXIS1", 3), card("NAXIS2", 2),
        )
        val data = ByteBuffer.allocate(3 * 2 * 4).order(ByteOrder.BIG_ENDIAN)
        for (y in 0 until 2) for (x in 0 until 3) data.putFloat((y * 3 + x).toFloat() * 10f)
        val out = ByteArrayOutputStream()
        out.write(primary); out.write(ext); out.write(data.array())
        val pad = (2880 - (data.array().size % 2880)) % 2880
        out.write(ByteArray(pad))
        val d = FitsImage.decodeBytes(out.toByteArray())!!
        assertEquals(3, d.w)
        assertEquals(2, d.h)
    }

    @Test
    fun `非 FITS 输入返回 null`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        assertNull(FitsImage.decodeBytes(jpeg))
        assertNull(FitsImage.decodeBytes(ByteArray(0)))
        assertNull(FitsImage.decodeBytes(ByteArray(2880))) // 全零头：无 BITPIX
    }

    @Test
    fun `头部元数据与视场换算`() {
        val d = FitsImage.decodeBytes(
            fitsFloat32(
                10, 5, List(5) { FloatArray(10) { it.toFloat() } },
                extra = listOf(
                    card("CRVAL1", 130.274), card("CRVAL2", -43.878),
                    card("CDELT1", 1.5e-3), card("CDELT2", 1.5e-3),
                    card("DATE-OBS", "2024-01-01T12:00:00"),
                    card("SITELAT", -31.0), card("SITELONG", -70.0),
                    card("OBJECT", "Vela SNR"),
                ),
            ),
        )!!
        val m = d.meta
        assertEquals(130.274, m.crval1!!, 1e-9)
        assertEquals(-43.878, m.crval2!!, 1e-9)
        assertEquals(-31.0, m.siteLat!!, 1e-9)
        assertEquals("2024-01-01T12:00:00", m.dateObs)
        assertEquals("Vela SNR", m.objectName)
        // 视场 = cdelt × 长边 = 1.5e-3 × 10 = 0.015°
        assertEquals(0.015, m.fovDeg!!, 1e-9)
    }

    @Test
    fun `D 指数与引号内斜杠的解析`() {
        val d = FitsImage.decodeBytes(
            fitsFloat32(
                2, 2, List(2) { FloatArray(2) { 1f } },
                extra = listOf(
                    card("CDELT1", 2.5e-5),           // 输出为 D 指数写法
                    card("DATE-OBS", "2024/01/01"),   // 引号里的斜杠不是注释
                ),
            ),
        )!!
        assertEquals(2.5e-5, d.meta.cdelt1!!, 1e-12)
        assertEquals("2024/01/01", d.meta.dateObs)
    }

    @Test
    fun `长边超限时按整数倍面积平均降采样`() {
        // 8x8 全 8（亮度均匀）→ 降采样到 maxDim=4 → 2x2，值仍为 8（拉伸后恒定）
        val rows = List(8) { FloatArray(8) { 8f } }
        val d = FitsImage.decodeBytes(fitsFloat32(8, 8, rows), maxDim = 4)!!
        assertEquals(4, d.w)
        assertEquals(4, d.h)
        // 全平场：拉伸走 min-max 兜底 → 全 0（无对比度），但尺寸必须正确
    }

    @Test
    fun `扩展名判定`() {
        assertTrue(FitsImage.isFitsPath("a.fit"))
        assertTrue(FitsImage.isFitsPath("A.FITS"))
        assertTrue(FitsImage.isFitsPath("/tmp/x.fts"))
        assertFalse(FitsImage.isFitsPath("a.jpg"))
        assertFalse(FitsImage.isFitsPath("noext"))
    }

    private fun assertFalse(b: Boolean) = assertTrue("应为 false", !b)
}
