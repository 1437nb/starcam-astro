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
        val fromTiff = findJpegByTiff(bytes)
        val fromScan = findJpegByScan(bytes)
        return when {
            fromTiff == null -> fromScan
            fromScan == null -> fromTiff
            else -> if (fromTiff.size >= fromScan.size) fromTiff else fromScan
        }
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

    /** 按 [off]/[len] 切一段并校验是 JPEG（SOI 开头、内部能找到 EOI）。 */
    private fun jpegSlice(bytes: ByteArray, off: Int, len: Int): ByteArray? {
        if (off <= 0 || len <= 4 || off + len > bytes.size) return null
        if ((bytes[off].toInt() and 0xFF) != 0xFF || (bytes[off + 1].toInt() and 0xFF) != 0xD8) return null
        // 长度字段可能含填充：截到最后一个 EOI
        var eoi = -1
        var i = off + len - 2
        while (i > off) {
            if ((bytes[i].toInt() and 0xFF) == 0xFF && (bytes[i + 1].toInt() and 0xFF) == 0xD9) {
                eoi = i
                break
            }
            i--
        }
        if (eoi < 0) return null
        return bytes.copyOfRange(off, eoi + 2)
    }

    // ------------------------------------------------------------------ 兜底扫描

    /**
     * 单遍扫描所有 SOI…EOI 对（嵌套用栈配对，因此"JPEG 内含 EXIF 缩略图"也能取到外层），
     * 返回最大的完整 JPEG。熵编码数据里 `FF` 只会后跟 `00` 或标记，故按标记扫描是安全的。
     */
    internal fun findJpegByScan(bytes: ByteArray): ByteArray? {
        val stack = ArrayDeque<Int>()
        var bestStart = -1
        var bestEnd = -1
        var i = 0
        val n = bytes.size
        while (i < n - 1) {
            if ((bytes[i].toInt() and 0xFF) == 0xFF) {
                when (bytes[i + 1].toInt() and 0xFF) {
                    0xD8 -> stack.addLast(i)
                    0xD9 -> if (stack.isNotEmpty()) {
                        val s = stack.removeLast()
                        if (stack.isEmpty()) {
                            val e = i + 2
                            if (bestStart < 0 || e - s > bestEnd - bestStart) {
                                bestStart = s; bestEnd = e
                            }
                        }
                    }
                }
            }
            i++
        }
        if (bestStart < 0) return null
        return bytes.copyOfRange(bestStart, bestEnd)
    }
}
