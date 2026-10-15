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

    /**
     * 造一个**结构化合法**的最小 JPEG（SOI + 真标记 + SOS + 熵编码 + EOI）。
     *
     * 注意：结构合法 ≠ 能解码 —— 像素解码是 Android BitmapFactory 的事，这里用不到；
     * 而 RawPreview 的校验按 JPEG 语法走查（§0.110），所以夹具必须语法正确。
     */
    private fun fakeJpeg(payloadSize: Int, seed: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))                       // SOI
        out.write(byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10))           // APP0 len=16
        out.write(ByteArray(14) { 0x4A })                                          // APP0 载荷
        out.write(byteArrayOf(0xFF.toByte(), 0xC0.toByte(), 0x00, 0x11, 0x08))     // SOF0 len=17
        out.write(ByteArray(14) { 0x08 })                                          // SOF0 载荷
        out.write(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x08))           // SOS len=8
        out.write(ByteArray(6) { 0x01 })                                           // SOS 载荷
        var v = seed
        val body = ByteArray(payloadSize)
        for (k in body.indices) {
            v = v * 1103515245 + 12345
            body[k] = (v ushr 16).toByte()
            // 熵编码数据里 FF 必须转义（FF00），否则会被当成标记
            if ((body[k].toInt() and 0xFF) == 0xFF) body[k] = 0x11
        }
        out.write(body)
        out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))                       // EOI
        return out.toByteArray()
    }

    /**
     * IFD 条目描述（tag/type/count/值或偏移）。偏移在**条目数定下来之后**统一回填 ——
     * 早期版本先算 previewOff 再追加条目，指针落进 IFD 中间，提取必然失败（测试自查抓到的）。
     */
    private data class Ent(val tag: Int, val type: Int, val count: Long, var value: Long)

    private fun entries(le: Boolean, list: List<Ent>) = list

    /** 序列化一个 IFD（条目数 + 条目 + nextIFD=0）。 */
    private fun writeIfd(le: Boolean, list: List<Ent>): ByteArray {
        val order = if (le) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val bb = ByteBuffer.allocate(2 + list.size * 12 + 4).order(order)
        bb.putShort(list.size.toShort())
        for (e in list) {
            bb.putShort(e.tag.toShort())
            bb.putShort(e.type.toShort())
            bb.putInt(e.count.toInt())
            if (e.type == 3) bb.putShort(e.value.toShort()).putShort(0) else bb.putInt(e.value.toInt())
        }
        bb.putInt(0)
        return bb.array()
    }

    private fun ifdSize(n: Int) = 2 + n * 12 + 4

    /** 组装一个 TIFF 容器（模式：IFD0 直指 / SubIFD / 条带）。 */
    private fun tiff(le: Boolean, jpeg: ByteArray, putInSubIfd: Boolean, stripMode: Boolean = false): ByteArray {
        val order = if (le) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val out = ByteArrayOutputStream()
        val head = ByteBuffer.allocate(8).order(order)
        head.put(if (le) 'I'.code.toByte() else 'M'.code.toByte())
        head.put(if (le) 'I'.code.toByte() else 'M'.code.toByte())
        head.putShort(42)
        head.putInt(8)
        out.write(head.array())

        val base = listOf(Ent(0x0100, 4, 1, 100), Ent(0x0101, 4, 1, 100))
        if (putInSubIfd) {
            val ifd0N = base.size + 1
            val subOff = 8 + ifdSize(ifd0N)
            val subN = 3
            val prevOff = subOff + ifdSize(subN)
            val ifd0 = base + Ent(0x014A, 4, 1, subOff.toLong())
            val sub = listOf(
                Ent(0x0100, 4, 1, 100), Ent(0x0101, 4, 1, 100),
                Ent(0x0201, 4, 1, prevOff.toLong()),
            )
            out.write(writeIfd(le, ifd0))
            out.write(writeIfd(le, sub))
            out.write(jpeg)
        } else {
            val tail = if (stripMode) {
                listOf(Ent(0x0103, 3, 1, 7), Ent(0x0111, 4, 1, 0), Ent(0x0117, 4, 1, jpeg.size.toLong()))
            } else {
                listOf(Ent(0x0201, 4, 1, 0), Ent(0x0202, 4, 1, jpeg.size.toLong()))
            }
            val all = base + tail
            val prevOff = 8 + ifdSize(all.size)
            for (e in all) if (e.tag == 0x0201 || e.tag == 0x0111) e.value = prevOff.toLong()
            out.write(writeIfd(le, all))
            out.write(jpeg)
        }
        return out.toByteArray()
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
        // 外层：SOI + APP1（**段长按实际缩略图长度填写** —— 真 EXIF 缩略图就是这样嵌在
        // APP1 里的；段长不匹配 = 语法非法，会被 §0.110 的走查器正确拒绝）+ 主体 + EOI
        val outer = ByteArrayOutputStream().apply {
            write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
            val app1Len = 2 + thumb.size
            write(byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (app1Len shr 8).toByte(), (app1Len and 0xFF).toByte()))
            write(thumb)
            // 熵编码数据必须出现在 SOS **之后**（之前的字节是标记区，出现裸字节即语法非法）
            write(byteArrayOf(0xFF.toByte(), 0xC0.toByte(), 0x00, 0x11, 0x08))
            write(ByteArray(14) { 0x08 })
            write(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x08))
            write(ByteArray(6) { 0x01 })
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

    /**
     * §0.110 回归（真机 Canon 450D CR2 实测）：原始数据里偶然出现的 `FFD8…FFD9`
     * 不得被当成预览 —— 候选必须按 JPEG 语法走查验证，只挑**合法**的。
     */
    @Test
    fun `伪造的大跨度不得胜出（CR2 真机形态）`() {
        val real = fakeJpeg(300, seed = 5)            // 合法的小预览（IFD0 指向它）
        val container = tiff(le = true, jpeg = real, putInSubIfd = false).toMutableList()
        // 尾部追加"原始数据"：伪 SOI + 大量垃圾 + 远处一个 EOI（比真预览大得多）
        container.addAll(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xC4.toByte(), 0x00, 0x42).toList())
        repeat(200_000) { container.add(0x11) }
        container.addAll(byteArrayOf(0xFF.toByte(), 0xD9.toByte()).toList())
        val got = RawPreview.extractJpegBytes(container.toByteArray())
        assertEquals("应选中合法的小预览，而不是伪大跨度", real.toList(), got!!.toList())
    }

    @Test
    fun `走查器拒绝段长非法与越界`() {
        val bad = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x00, 0xFF.toByte(), 0xD9.toByte())
        assertEquals(-1, RawPreview.walkJpegEnd(bad, 0, bad.size))
        val over = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertEquals(-1, RawPreview.walkJpegEnd(over, 0, over.size))
        val noEoi = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x04, 0x01, 0x02)
        assertEquals(-1, RawPreview.walkJpegEnd(noEoi, 0, noEoi.size))
        val good = fakeJpeg(8)
        assertEquals("合法结构应走查到文件末尾的 EOI", good.size, RawPreview.walkJpegEnd(good, 0, good.size))
    }

    /** §0.110：SOF3（无损）不是可显示预览 —— CR2 的原始数据就是 lossless JPEG。 */
    @Test
    fun `无损 JPEG 必须被拒（CR2 原始数据形态）`() {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        out.write(byteArrayOf(0xFF.toByte(), 0xC3.toByte(), 0x00, 0x0B, 0x08))   // SOF3（无损）
        out.write(ByteArray(8) { 0x08 })
        out.write(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x08))          // SOS
        out.write(ByteArray(6) { 0x01 })
        out.write(ByteArray(64) { 0x22 })
        out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        val lossless = out.toByteArray()
        assertEquals(-1, RawPreview.walkJpegEnd(lossless, 0, lossless.size))
        assertNull("无损 JPEG 不得作为预览返回", RawPreview.extractJpegBytes(lossless))
    }

    /** §0.110：渐进式（SOF2，多段 SOS）是合法预览，必须能走查通过。 */
    @Test
    fun `渐进式 JPEG 可被接受`() {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        out.write(byteArrayOf(0xFF.toByte(), 0xC2.toByte(), 0x00, 0x0B, 0x08))   // SOF2（渐进）
        out.write(ByteArray(8) { 0x08 })
        repeat(2) {
            out.write(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x08))      // 两段 SOS
            out.write(ByteArray(6) { 0x01 })
            out.write(ByteArray(32) { 0x33 })
        }
        out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        val prog = out.toByteArray()
        assertEquals("渐进式应走查到 EOI", prog.size, RawPreview.walkJpegEnd(prog, 0, prog.size))
    }

    /** §0.110：TIFF 声明优先 —— 扫描能找到更大的伪跨度也不得取代声明的预览。 */
    @Test
    fun `TIFF 声明的预览优先于扫描候选`() {
        val real = fakeJpeg(200, seed = 9)
        val container = tiff(le = true, jpeg = real, putInSubIfd = false).toMutableList()
        // 尾部塞一段"更大的合法 JPEG"（模拟 CR2 的 lossless 原始数据位置）
        val bigger = fakeJpeg(5000, seed = 11)
        container.addAll(bigger.toList())
        val got = RawPreview.extractJpegBytes(container.toByteArray())
        assertEquals("应取 TIFF 声明的那张", real.toList(), got!!.toList())
    }
}
