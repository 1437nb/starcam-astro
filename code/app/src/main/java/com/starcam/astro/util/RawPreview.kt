package com.starcam.astro.util

/**
 * 相机 RAW 的**内嵌预览图**提取（纯 Kotlin，不依赖 Android / 第三方库）。
 *
 * ## 为什么取内嵌预览，而不是解传感器原始数据
 *
 * 解 RAW 传感器数据要 libraw/rawspeed 那类本地库（交叉编译 + 授权 + 体积），
 * 而各家 RAW 都会在文件里**内嵌一张完整或近全尺寸的 JPEG 预览**（相机 ISP 渲染过的），
 * 它的星点质量足以做识别 —— 本 APP 的既有链路（JPEG → BitmapFactory → 提星 → 匹配）
 * 正好直接吃它。代价：损失线性度与位深（对"认出是哪片天"无影响；将来若要测光/叠图
 * 再引 libraw 不迟）。
 *
 * ## 覆盖范围
 *
 * TIFF 系（CR2 / NEF / ARW / DNG / ORF / RW2 / PEF / SRW / RAF 的 TIFF 变体）：
 * 走 IFD 走查，按三类线索找预览：
 *   1. `JPEGInterchangeFormat(0x0201)` + `Length(0x0202)`；
 *   2. `Compression(0x0103)=6/7` 时的 `StripOffsets(0x0111)` + `StripByteCounts(0x0117)`（多段拼接）；
 *   3. `SubIFDs(0x014A)` 递归（很多机型的预览在子 IFD 里）。
 *
 * 都找不到时**兜底全文件扫描**：单遍线性扫描所有 SOI…EOI 对（带嵌套栈，
 * 因此"JPEG 里嵌 EXIF 缩略图"的嵌套情形也能正确取外层大图），取最大的那个。
 *
 * ## 不做什么
 *
 * 不保证拿到"最大"预览（只保证拿到一个能用的、最大的候选）：`PreviewImage` 一类的
 * MakerNote 私有字段各厂不同，属私有协议，不猜。
 */
object RawPreview {

    /** 常见相机 RAW 扩展名（TIFF 系 + 少量非 TIFF 也走兜底扫描）。 */
    private val RAW_EXTS = setOf(
        "cr2", "cr3", "crw", "nef", "nrw", "arw", "srf", "sr2", "dng", "orf", "rw2", "raf",
        "pef", "srw", "3fr", "fff", "iiq", "mos", "mrw", "x3f",
    )

    fun isRawPath(path: String): Boolean =
        path.trim().substringAfterLast('.', "").lowercase().trim() in RAW_EXTS

    /** 读文件并提取最大的内嵌 JPEG；失败返回 null。 */
    fun extractJpeg(path: String): ByteArray? = try {
        extractJpegBytes(java.io.File(path).readBytes())
    } catch (e: Exception) {
        null
    }

    internal fun extractJpegBytes(bytes: ByteArray): ByteArray? {
        // §0.110：**TIFF 元数据优先**。扫描兜底只能在容器解析不出预览时使用 ——
        // 否则"取最大"会输给原始数据（Canon CR2 的 12.8MB lossless JPEG 就藏在
        // StripOffsets 里，扫描/贪婪策略都会选中它，而真正的预览是 IFD0 那 362KB）。
        return findJpegByTiff(bytes) ?: findJpegByScan(bytes)
    }

    // ------------------------------------------------------------------ TIFF 走查

    private class Tiff(val le: Boolean) {
        fun u16(b: ByteArray, o: Int): Int = if (o + 2 > b.size) -1 else
            if (le) (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
            else ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

        fun u32(b: ByteArray, o: Int): Long = if (o + 4 > b.size) -1 else {
            var v = 0L
            if (le) {
                for (i in 3 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
            } else {
                for (i in 0..3) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
            }
            v
        }
    }

    /** 候选预览：按"看起来像完整 JPEG"校验过。 */
    private class Candidate(val off: Int, val len: Int)

    private fun findJpegByTiff(bytes: ByteArray): ByteArray? {
        if (bytes.size < 8) return null
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        val le = when {
            b0 == 'I'.code && b1 == 'I'.code -> true
            b0 == 'M'.code && b1 == 'M'.code -> false
            else -> return null
        }
        val t = Tiff(le)
        // 魔数：标准 TIFF 42；少数 RAW 变体（ORF 等）用别的值，只要 IFD0 偏移合理就继续
        val ifd0 = t.u32(bytes, 4)
        if (ifd0 <= 0) return null
        val candidates = ArrayList<Candidate>()
        val visited = HashSet<Long>()
        val queue = ArrayDeque<Long>()
        queue.add(ifd0)
        var guard = 0
        while (queue.isNotEmpty() && guard++ < 64) {
            val off = queue.removeFirst()
            if (off in visited || off < 8 || off + 2 > bytes.size) continue
            visited.add(off)
            val n = t.u16(bytes, off.toInt())
            if (n <= 0 || n > 4096) continue
            val base = off.toInt() + 2
            if (base + n * 12 + 4 > bytes.size) continue
            var jpegOff = -1L
            var jpegLen = -1L
            var compression = -1
            var stripOff = -1L
            var stripCount = -1L
            for (i in 0 until n) {
                val e = base + i * 12
                val tag = t.u16(bytes, e)
                val type = t.u16(bytes, e + 2)
                val count = t.u32(bytes, e + 4)
                val valueOff = e + 8
                when (tag) {
                    0x0103 -> compression = t.u16(bytes, valueOff)
                    0x0201 -> jpegOff = t.u32(bytes, valueOff)
                    0x0202 -> jpegLen = t.u32(bytes, valueOff)
                    0x0111 -> stripOff = firstLongValue(bytes, t, type, count, valueOff)
                    0x0117 -> stripCount = firstLongValue(bytes, t, type, count, valueOff)
                    0x014A -> { // SubIFDs：LONG 数组（值在别处时读偏移表）
                        val arr = longArrayValue(bytes, t, type, count, valueOff)
                        for (v in arr) if (v > 0) queue.add(v)
                    }
                }
            }
            if (jpegOff > 0 && jpegLen > 0) {
                candidates.add(Candidate(jpegOff.toInt(), jpegLen.toInt()))
            }
            if (stripOff > 0 && stripCount > 0 && (compression == 6 || compression == 7)) {
                candidates.add(Candidate(stripOff.toInt(), stripCount.toInt()))
            }
            val next = t.u32(bytes, base + n * 12)
            if (next > 0) queue.add(next)
        }
        var best: ByteArray? = null
        for (c in candidates) {
            val cut = jpegSlice(bytes, c.off, c.len) ?: continue
            if (best == null || cut.size > best.size) best = cut
        }
        return best
    }

    /** LONG/SHORT 且只有 1 个值（StripOffsets 单段时的常见形态）。 */
    private fun firstLongValue(bytes: ByteArray, t: Tiff, type: Int, count: Long, valueOff: Int): Long {
        if (count != 1L) return -1
        return when (type) {
            3 -> t.u16(bytes, valueOff).toLong()     // SHORT
            4 -> t.u32(bytes, valueOff)              // LONG
            else -> -1
        }
    }

    /** LONG/SHORT 数组（count>1 时值区是偏移）。 */
    private fun longArrayValue(bytes: ByteArray, t: Tiff, type: Int, count: Long, valueOff: Int): List<Long> {
        if (count <= 0 || count > 64) return emptyList()
        val unit = if (type == 3) 2 else 4
        val inline = unit * count <= 4
        val start = if (inline) valueOff else {
            val p = t.u32(bytes, valueOff)
            if (p <= 0 || p + unit * count > bytes.size) return emptyList()
            p.toInt()
        }
        val out = ArrayList<Long>(count.toInt())
        for (i in 0 until count.toInt()) {
            val p = start + i * unit
            out.add(if (type == 3) t.u16(bytes, p).toLong() else t.u32(bytes, p))
        }
        return out
    }

    /**
     * 按 [off]/[len] 切一段并**结构化校验**是 JPEG（§0.110）。
     *
     * 为什么不能只查"SOI 开头 + 末尾有 EOI"：真机 CR2 实测（Canon 450D）里，
     * 原始传感器数据中会**偶然出现** `FFD8 … FFD9` 字节串；贪婪地"取最大候选"
     * 会选中一段 12.8MB 的伪跨度（PIL 直接判定不是图像）。所以必须按 JPEG 语法
     * 逐个标记走查到 EOI：段长非法、标记不认识、越过边界 —— 一律不算预览。
     */
    private fun jpegSlice(bytes: ByteArray, off: Int, len: Int): ByteArray? {
        if (off <= 0 || len <= 4 || off + len > bytes.size) return null
        if ((bytes[off].toInt() and 0xFF) != 0xFF || (bytes[off + 1].toInt() and 0xFF) != 0xD8) return null
        val end = walkJpegEnd(bytes, off, off + len)
        if (end < 0) return null
        return bytes.copyOfRange(off, end)
    }

    /**
     * 从 [off]（必须是 SOI）按 JPEG 标记语法走查，返回 EOI 之后的下标；不合法返回 -1。
     *
     * 只做**结构**校验（段长、标记序列、SOS 之后的熵编码数据用 stuffing/restart 规则跳过），
     * 不解码像素 —— 因此纯 JVM 可测，也不依赖 Android 的 BitmapFactory。
     */
    internal fun walkJpegEnd(bytes: ByteArray, off: Int, limit: Int): Int {
        if (off + 2 > limit) return -1
        if ((bytes[off].toInt() and 0xFF) != 0xFF || (bytes[off + 1].toInt() and 0xFF) != 0xD8) return -1
        var i = off + 2
        var sofType = -1
        var sawSos = false
        var guard = 0
        while (i + 1 < limit && guard++ < 1 shl 20) {
            if ((bytes[i].toInt() and 0xFF) != 0xFF) return -1      // 标记区必须 FF 开头
            var j = i + 1
            var m = bytes[j].toInt() and 0xFF
            while (m == 0xFF && j + 1 < limit) {                    // 允许 FF 填充
                j++
                m = bytes[j].toInt() and 0xFF
            }
            when {
                m == 0xD9 -> {
                    // §0.110：必须是**可显示的** JPEG —— SOF0/1/2（基线/扩展顺序/渐进）。
                    // SOF3/5~7/9~11/13~15 是无损/算术/差分编码：相机把**原始传感器数据**
                    // 也存成 JPEG（Canon CR2 实测：12.8MB 的 lossless JPEG），
                    // 它不是给人看的预览，解出来也是花屏。
                    val viewable = sofType == 0xC0 || sofType == 0xC1 || sofType == 0xC2
                    return if (viewable && sawSos) j + 1 else -1
                }
                m == 0xD8 || m == 0x01 || (m in 0xD0..0xD7) -> i = j + 1
                m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC -> {  // SOFn
                    sofType = m
                    val n = readSegLen(bytes, j, limit) ?: return -1
                    i = j + 1 + n
                }
                m == 0xDA -> {                                           // SOS
                    val n = readSegLen(bytes, j, limit) ?: return -1
                    if (n < 6) return -1                                 // SOS 至少 6 字节载荷
                    sawSos = true
                    var k = j + 1 + n
                    // 熵编码段：只允许 FF00（填充）与 RSTn；DNL 允许一次；其余 FFxx 出现即非法
                    var done = false
                    while (k + 1 < limit) {
                        if ((bytes[k].toInt() and 0xFF) != 0xFF) { k++; continue }
                        val nx = bytes[k + 1].toInt() and 0xFF
                        when {
                            nx == 0x00 || nx in 0xD0..0xD7 -> k += 2
                            nx == 0xDC -> {                              // DNL：跳过后继续
                                val d = readSegLen(bytes, k + 1, limit) ?: return -1
                                k += 1 + 1 + d
                            }
                            nx == 0xDA -> {                              // 渐进式：又一段 SOS，跳过其段头继续
                                val n2 = readSegLen(bytes, k + 1, limit) ?: return -1
                                k += 2 + n2
                            }
                            nx == 0xD9 -> {                              // EOI
                                // §0.110：这里也要过 SOF 类型检查 —— 正常 JPEG 的 EOI
                                // 就是在熵编码扫描里遇到的；只在"标记区"检查等于没检查
                                // （Canon CR2 的 12.8MB lossless 原始数据因此曾被放行）
                                if (!(sofType == 0xC0 || sofType == 0xC1 || sofType == 0xC2)) return -1
                                k += 2; done = true
                            }
                            else -> return -1
                        }
                        if (done) break
                    }
                    if (!done) return -1
                    return k
                }
                m == 0x00 -> return -1                                    // 段外 FF00：非法
                else -> {
                    val n = readSegLen(bytes, j, limit) ?: return -1
                    i = j + 1 + n
                }
            }
        }
        return -1
    }

    /** 读 [j] 处标记的长度字段（2 字节，含自身）；非法返回 null。 */
    private fun readSegLen(bytes: ByteArray, j: Int, limit: Int): Int? {
        if (j + 2 >= limit) return null
        val n = ((bytes[j + 1].toInt() and 0xFF) shl 8) or (bytes[j + 2].toInt() and 0xFF)
        if (n < 2 || j + 1 + n > limit) return null
        return n
    }

    // ------------------------------------------------------------------ 兜底扫描

    /**
     * 单遍扫描所有 SOI…EOI 对（嵌套用栈配对，因此"JPEG 内含 EXIF 缩略图"也能取到外层），
     * 返回最大的完整 JPEG。熵编码数据里 `FF` 只会后跟 `00` 或标记，故按标记扫描是安全的。
     */
    internal fun findJpegByScan(bytes: ByteArray): ByteArray? {
        val stack = ArrayDeque<Int>()
        var best: ByteArray? = null
        var i = 0
        val n = bytes.size
        while (i < n - 1) {
            if ((bytes[i].toInt() and 0xFF) == 0xFF) {
                when (bytes[i + 1].toInt() and 0xFF) {
                    0xD8 -> stack.addLast(i)
                    0xD9 -> if (stack.isNotEmpty()) {
                        val s = stack.removeLast()
                        if (stack.isEmpty()) {
                            // §0.110：只收**结构化合法**的跨度（真机 CR2 的原始数据里
                            // 偶然出现 FFD8…FFD9，贪婪取最大会选中 12.8MB 的伪跨度）
                            val end = walkJpegEnd(bytes, s, i + 2)
                            if (end > 0) {
                                val cut = bytes.copyOfRange(s, end)
                                if (best == null || cut.size > best.size) best = cut
                            }
                        }
                    }
                }
            }
            i++
        }
        return best
    }
}
