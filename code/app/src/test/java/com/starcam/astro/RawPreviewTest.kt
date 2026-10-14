package com.starcam.astro

import com.starcam.astro.util.RawPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * RAW 内嵌预览提取（[RawPreview]）单元回归 —— 纯 JVM，TIFF 夹具**现场构造**。
 *
 * 覆盖三类真实布局 + 一条兜底路径：
 *  1. `JPEGInterchangeFormat` 直指预览（CR2/ARW/NEF 常见）；
 *  2. `SubIFD` 里的预览（不少机型如此）；
 *  3. `Compression=7` 的条带拼接（部分 DNG/ORF）；
 *  4. 非 TIFF 的裸容器 → 全文件扫描兜底；并验证**嵌套**（JPEG 里含 EXIF 缩略图）
 *     时取的是外层大图。
 */
class RawPreviewTest {

    // ---------------------------------------------------------- 夹具

    /** 造一个最小合法 JPEG（结构上 SOI + 若干标记字节 + EOI）—— 只测提取，不解码。 */
    private fun fakeJpeg(payloadSize: Int, seed: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))          // SOI
        out.write(byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10)) // APP0
        var v = seed
        val body = ByteArray(payloadSize)
        for (i in body.indices) {
            v = v * 1103515245 + 12345
            body[i] = (v ushr 16).toByte()
            // 避免在 body 里意外造出 SOI/EOI 序列
            if ((body[i].toInt() and 0xFF) == 0xFF) body[i] = 0x11
        }
        out.write(body)
        out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))          // EOI
        return out.toByteArray()
    }

    /** 造一个 IFD 条目（12 字节）。 */
    private fun entry(le: Boolean, tag: Int, type: Int, count: Long, value: Long): ByteArray {
        val bb = ByteBuffer.allocate(12).order(if (le) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        bb.putShort(tag.toShort())
        bb.putShort(type.toShort())
        bb.putInt(count.toInt())
        when (type) {
            3 -> bb.putShort(value.toShort()).putShort(0)   // SHORT 值左对齐占 4 字节
            else -> bb.putInt(value.toInt())                // LONG / 偏移
        }
        return bb.array()
    }

    /** 一个完整 TIFF：IFD0（带给定条目） + 预览数据 + 可选 SubIFD。 */
    private fun tiff(
        le: Boolean,
        jpeg: ByteArray,
        putInSubIfd: Boolean,
        stripMode: Boolean = false,
    ): ByteArray {
        val order = if (le) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val out = ByteArrayOutputStream()
        // 头：II/MM + 42 + IFD0 offset=8
        val head = ByteBuffer.allocate(8).order(order)
        head.put(if (le) 'I'.code.toByte() else 'M'.code.toByte())
        head.put(if (le) 'I'.code.toByte() else 'M'.code.toByte())
        head.putShort(42)
        head.putInt(8)
        out.write(head.array())

        // IFD0：两条基本条目（宽/高） + 预览线索
        val ifd0Entries = ArrayList<ByteArray>()
        ifd0Entries.add(entry(le, 0x0100, 4, 1, 100))  // Width
        ifd0Entries.add(entry(le, 0x0101, 4, 1, 100))  // Height
        val ifd0Size = 2 + ifd0Entries.size * 12 + 4
        val previewOff = 8 + ifd0Size
        if (putInSubIfd) {
            val subIfdOff = previewOff + jpeg.size
            ifd0Entries.add(entry(le, 0x014A, 4, 1, subIfdOff.toLong()))
            val e = entry(le, 0x0201, 4, 1, subIfdOff + 2 + 12 + 4L)
            out.write(writeIfd(le, ifd0Entries))
            out.write(jpeg)                                  // 占位数据（真实文件里是原始数据）
            out.write(writeIfd(le, listOf(e)))
            out.write(jpeg)
        } else {
            if (stripMode) {
                ifd0Entries.add(entry(le, 0x0103, 3, 1, 7))            // Compression = JPEG
                ifd0Entries.add(entry(le, 0x0111, 4, 1, previewOff.toLong()))
                ifd0Entries.add(entry(le, 0x0117, 4, 1, jpeg.size.toLong()))
            } else {
                ifd0Entries.add(entry(le, 0x0201, 4, 1, previewOff.toLong()))
                ifd0Entries.add(entry(le, 0x0202, 4, 1, jpeg.size.toLong()))
            }
            out.write(writeIfd(le, ifd0Entries))
            out.write(jpeg)
        }
        return out.toByteArray()
    }

    /** 写一个 IFD（条目数 + 条目 + nextIFD=0）。 */
    private fun writeIfd(le: Boolean, entries: List<ByteArray>): ByteArray {
        val order = if (le) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val bb = ByteBuffer.allocate(2 + entries.size * 12 + 4).order(order)
        bb.putShort(entries.size.toShort())
        for (e in entries) bb.put(e)
        bb.putInt(0)
        return bb.array()
    }

    // ---------------------------------------------------------- 测试

    @Test
    fun `小端 TIFF 的 JPEGInterchangeFormat 预览可提取`() {
        val jpeg = fakeJpeg(500)
        val got = RawPreview.extractJpegBytes(tiff(le = true, jpeg = jpeg, putInSubIfd = false))
        assertEquals("提取的字节应与内嵌预览逐字节一致", jpeg.toList(), got!!.toList())
    }

    @Test
    fun `大端 TIFF 同样可提取`() {
        val jpeg = fakeJpeg(300, seed = 7)
        val got = RawPreview.extractJpegBytes(tiff(le = false, jpeg = jpeg, putInSubIfd = false))
        assertEquals(jpeg.toList(), got!!.toList())
    }

    @Test
    fun `SubIFD 里的预览可提取`() {
        val jpeg = fakeJpeg(400, seed = 3)
        val got = RawPreview.extractJpegBytes(tiff(le = true, jpeg = jpeg, putInSubIfd = true))
        assertEquals(jpeg.toList(), got!!.toList())
    }

    @Test
    fun `Compression=7 的条带预览可提取`() {
        val jpeg = fakeJpeg(250, seed = 5)
        val got = RawPreview.extractJpegBytes(
            tiff(le = true, jpeg = jpeg, putInSubIfd = false, stripMode = true),
        )
        assertEquals(jpeg.toList(), got!!.toList())
    }

    @Test
    fun `非 TIFF 容器走全文件扫描`() {
        val jpeg = fakeJpeg(600, seed = 9)
        val blob = ByteArrayOutputStream().apply {
            write("PK\u0003\u0004zip-like-header".toByteArray())
            write(ByteArray(37) { 0x5A })
            write(jpeg)
            write(ByteArray(11) { 0x00 })
        }.toByteArray()
        val got = RawPreview.extractJpegBytes(blob)
        assertEquals(jpeg.toList(), got!!.toList())
    }

    @Test
    fun `嵌套时取外层大图（EXIF 缩略图场景）`() {
        val thumb = fakeJpeg(80, seed = 2)
        // 外层：SOI + APP1（内含缩略图）+ 主体 + EOI
        val outer = ByteArrayOutputStream().apply {
            write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
            write(byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0x00, 0x20))
            write(thumb)
            write(ByteArray(700) { 0x33 })
            write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        }.toByteArray()
        val got = RawPreview.findJpegByScan(outer)
        assertEquals("应取外层（大的那张）", outer.size, got!!.size)
        assertEquals(outer.toList(), got.toList())
    }

    @Test
    fun `没有 JPEG 的文件返回 null`() {
        assertNull(RawPreview.extractJpegBytes(ByteArray(256) { 0x11 }))
        assertNull(RawPreview.extractJpegBytes(ByteArray(0)))
        // 只有 SOI 没有 EOI：不成对，取不到
        assertNull(RawPreview.findJpegByScan(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)))
    }

    @Test
    fun `扩展名判定`() {
        assertTrue(RawPreview.isRawPath("IMG_0001.CR2"))
        assertTrue(RawPreview.isRawPath("a/b/ DSC_1.NEF "))
        assertTrue(RawPreview.isRawPath("x.arw"))
        assertTrue(RawPreview.isRawPath("x.dng"))
        assertTrue(RawPreview.isRawPath("x.raf"))
        assertTrue(!RawPreview.isRawPath("x.jpg"))
        assertTrue(!RawPreview.isRawPath("x.fits"))
    }
}
