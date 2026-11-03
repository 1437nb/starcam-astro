package com.starcam.astro.util

/**
 * FITS 图像解码（**纯 Kotlin**，不依赖 Android / 第三方库）。
 *
 * ## 为什么自研
 *
 * 天文摄影与望远镜导出的图基本都是 FITS（`.fit` / `.fits` / `.fts`）；这是本 APP
 * 目标用户手里最"专业"的一类素材。FITS 的结构非常简单（2880 字节一块的 ASCII 头
 * + 大端数据），自研解码器：不引依赖、离线可用、可 JVM 单测（不像 Android 的
 * BitmapFactory 那样只能跑在设备上）。
 *
 * ## 与既有真值台的口径一致（关键）
 *
 * `tools/convert_narrowfield.py` 是本仓库把 FITS 转成回归台 `.gray` 的参考实现，
 * 窄场/中场真值台（`NarrowFieldRegressionTest` / `TieredCatalogTest`）用的就是它。
 * 本解码器**逐条对齐**它的语义，否则同一张 FITS 在设备上与在回归台上会给出不同灰度：
 *   1. 数据按**大端**读（`>f4` 等）；
 *   2. 行序**自下而上** → 翻转成自上而下（否则星场上下颠倒，匹配必失败）；
 *   3. NaN/±Inf → 0；
 *   4. 拉伸：取 1.0% 与 99.5% 分位，线性映射到 0..255 后截断；
 *   5. BITPIX 支持 8/16/32/-32/-64，且应用 BSCALE/BZERO。
 *
 * ## 支持范围（有意为之的取舍）
 *
 *  - 只取**二维图像** HDU：主 HDU 没有数据（NAXIS=0）时，向后扫描第一个 NAXIS=2 的
 *    扩展 HDU（很多望远镜产品把图像放扩展里）；
 *  - 不支持 BINTABLE（表格）与压缩 FITS（`.fits.fz`）—— 前者不是图像，后者要
 *    Rice/HCOMPRESS 解码，属独立工程项（真遇到了会让上层明确报"读不出图"）；
 *  - 超过 [maxDim] 时按整数倍**面积平均**降采样（与 BitmapFactory 的 inSampleSize
 *    同语义，既省内存又不放大噪声）。
 */
object FitsImage {

    /** 解码结果：灰度已拉伸到 0..255（仍是 FloatArray，保留亚像素精度）。 */
    data class Decoded(
        val w: Int,
        val h: Int,
        val gray: FloatArray,
        val meta: FitsMeta,
    )

    /** 头部里对求解/先验有用的信息（缺项为 null，不猜）。 */
    data class FitsMeta(
        val bitpix: Int,
        val bscale: Double,
        val bzero: Double,
        val naxis1: Int,
        val naxis2: Int,
        /** WCS 中心（度）——有它就能给求解器一个天区先验（窄场秒解）。 */
        val crval1: Double?,
        val crval2: Double?,
        /** WCS 每像素尺度（度/像素）——视场 = cdelt × NAXIS。 */
        val cdelt1: Double?,
        val cdelt2: Double?,
        /** 观测时刻（DATE-OBS，UTC，FITS 标准写法 `2024-01-01T12:00:00`）。 */
        val dateObs: String?,
        /** 观测站纬度/经度（FITS 常见 SITELAT/SITELONG 或 LATITUDE/LONGITUD，度）。 */
        val siteLat: Double?,
        val siteLon: Double?,
        val objectName: String?,
    ) {
        /** 长边视场（度）；缺 WCS 时为 null。 */
        val fovDeg: Double?
            get() {
                val px = cdelt1 ?: cdelt2 ?: return null
                val pxAbs = kotlin.math.abs(px)
                return if (pxAbs > 0.0) pxAbs * maxOf(naxis1, naxis2) else null
            }
    }

    private const val BLOCK = 2880
    private const val CARD = 80

    /** 扩展名判定（大小写不敏感）。 */
    fun isFitsPath(path: String): Boolean {
        val ext = path.trim().substringAfterLast('.', "").lowercase().trim()
        return ext == "fit" || ext == "fits" || ext == "fts"
    }

    fun decode(path: String, maxDim: Int = 2200, checkActive: () -> Unit = {}): Decoded? = try {
        val image = java.io.FileInputStream(path).use { findImageHdu(it, checkActive) }
        image?.let {
            val meta = metaOf(it.hdu, it.primary)
            java.io.RandomAccessFile(path, "r").use { pixels ->
                FitsPixels.read(pixels, it.offset, meta.naxis1, meta.naxis2, meta.bitpix,
                    meta.bscale, meta.bzero, maxDim, checkActive)?.let { gray ->
                    val factor = (maxOf(meta.naxis1, meta.naxis2).toLong() / (maxDim.toLong() + 1) + 1).toInt()
                    Decoded(meta.naxis1 / factor, meta.naxis2 / factor, gray, meta)
                }
            }
        }
    } catch (e: Exception) {
        if (e is java.util.concurrent.CancellationException || e is ImageMemoryLimitException) throw e
        null
    }

    /** 只读头部（先验用；按 2880 字节块流式读取，读到 END 即刻终止并关闭流，杜绝 100MB+ 大图 OOM）。 */
    fun readMeta(path: String): FitsMeta? = try {
        java.io.FileInputStream(path).use { stream ->
            readMetaStream(stream)
        }
    } catch (e: Exception) {
        null
    }

    internal fun readMetaBytes(bytes: ByteArray): FitsMeta? = try {
        java.io.ByteArrayInputStream(bytes).use { stream ->
            readMetaStream(stream)
        }
    } catch (e: Exception) {
        null
    }

    // ------------------------------------------------------------------ 头部

    /** 一个 HDU 的位置与大小（用于在 HDU 之间跳转）。 */
    private class Hdu(
        val cards: Map<String, String>,
        val headerBytes: Int,
        val dataBytes: Long,
    ) {
        fun int(key: String, def: Int = 0): Int =
            cards[key]?.let { it.trim().toDoubleOrNull()?.toInt() ?: it.trim().toIntOrNull() } ?: def

        fun long(key: String, def: Long = 0L): Long =
            cards[key]?.trim()?.toLongOrNull() ?: def

        fun dbl(key: String): Double? = cards[key]?.let { parseDouble(it) }

        fun str(key: String): String? = cards[key]?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun parseDouble(raw: String): Double? {
        val v = raw.trim().replace('D', 'E').replace('d', 'e')
        return v.toDoubleOrNull()
    }

    private fun paddedDataBytes(cards: Map<String, String>): Long? { return try {
        val bitpix = cards["BITPIX"]?.trim()?.toLongOrNull() ?: return null
        val axes = cards["NAXIS"]?.trim()?.toIntOrNull() ?: return null
        if (axes !in 0..999 || bitpix !in setOf(8L, 16L, 32L, 64L, -32L, -64L)) return null
        val groups = cards["GCOUNT"]?.trim()?.toLongOrNull() ?: 1L
        val parameters = cards["PCOUNT"]?.trim()?.toLongOrNull() ?: 0L
        if (groups <= 0 || parameters < 0) return null
        if (axes == 0) return 0L
        var pixels = 1L
        for (axis in 1..axes) {
            val size = cards["NAXIS$axis"]?.trim()?.toLongOrNull() ?: return null
            if (size < 0) return null
            pixels = Math.multiplyExact(pixels, size)
        }
        val bytes = Math.multiplyExact(Math.multiplyExact(Math.addExact(pixels, parameters), groups), kotlin.math.abs(bitpix) / 8)
        Math.multiplyExact(Math.addExact(bytes, BLOCK - 1L) / BLOCK, BLOCK.toLong())
    } catch (_: ArithmeticException) { null } }

    /** 解析从 [start] 处开始的 HDU 头；非法返回 null。 */
    private fun parseHdu(bytes: ByteArray, start: Int): Hdu? {
        if (start + BLOCK > bytes.size) return null
        val cards = LinkedHashMap<String, String>()
        var blocks = 0
        var p = start
        outer@ while (p + BLOCK <= bytes.size) {
            blocks++
            var sawEnd = false
            for (i in 0 until BLOCK / CARD) {
                val cardStart = p + i * CARD
                val key = String(bytes, cardStart, 8, Charsets.US_ASCII).trim()
                if (key == "END") {
                    sawEnd = true
                    break
                }
                if (key.isEmpty() || key == "COMMENT" || key == "HISTORY" || key.startsWith("'")) {
                    continue
                }
                val eq = String(bytes, cardStart + 8, 2, Charsets.US_ASCII)
                if (eq != "= ") continue
                val rest = String(bytes, cardStart + 10, CARD - 10, Charsets.ISO_8859_1)
                cards[key] = parseValue(rest)
            }
            if (sawEnd) break@outer
            p += BLOCK
        }
        if (cards.isEmpty()) return null
        val headerBytes = blocks * BLOCK
        val bitpix = cards["BITPIX"]?.trim()?.toIntOrNull() ?: return null
        val naxis = cards["NAXIS"]?.trim()?.toIntOrNull() ?: 0
        val dataLen = paddedDataBytes(cards) ?: return null
        return Hdu(cards, headerBytes, dataLen)
    }

    /**
     * 拆出「值」部分：去掉注释、去引号。
     * 引号内的 `/` 不是注释起始（日期 `'2024-01-01T12:00:00'` 这种要完整保留）。
     */
    private fun parseValue(rest: String): String {
        val s = rest
        if (s.isNotEmpty() && s[0] == '\'') {
            var i = 1
            val sb = StringBuilder()
            while (i < s.length) {
                if (s[i] == '\'') {
                    // FITS 里单引号自身用 '' 转义
                    if (i + 1 < s.length && s[i + 1] == '\'') {
                        sb.append('\'')
                        i += 2
                        continue
                    }
                    break
                }
                sb.append(s[i])
                i++
            }
            return sb.toString().trim()
        }
        val noComment = if (s.contains('/')) s.substringBefore('/') else s
        return noComment.trim()
    }

    private data class ImageHdu(val hdu: Hdu, val primary: Hdu?, val offset: Long)

    private fun readMetaStream(stream: java.io.InputStream): FitsMeta? =
        findImageHdu(stream)?.let { metaOf(it.hdu, it.primary) }

    private fun findImageHdu(stream: java.io.InputStream, checkActive: () -> Unit = {}): ImageHdu? {
        val blockBuf = ByteArray(BLOCK)
        var offset = 0L
        var guard = 0
        var primaryHdu: Hdu? = null
        while (guard++ < 64) {
            val cards = LinkedHashMap<String, String>()
            var blocks = 0
            var sawEnd = false
            while (!sawEnd) {
                checkActive()
                if (blocks >= 200) return null
                if (!readFully(stream, blockBuf)) return null
                blocks++
                offset = Math.addExact(offset, BLOCK.toLong())
                for (i in 0 until BLOCK / CARD) {
                    val cardStart = i * CARD
                    val key = String(blockBuf, cardStart, 8, Charsets.US_ASCII).trim()
                    if (key == "END") {
                        sawEnd = true
                        break
                    }
                    if (key.isEmpty() || key == "COMMENT" || key == "HISTORY" || key.startsWith("'")) {
                        continue
                    }
                    val eq = String(blockBuf, cardStart + 8, 2, Charsets.US_ASCII)
                    if (eq != "= ") continue
                    val rest = String(blockBuf, cardStart + 10, CARD - 10, Charsets.ISO_8859_1)
                    cards[key] = parseValue(rest)
                }
            }
            if (cards.isEmpty()) return null
            val bitpix = cards["BITPIX"]?.trim()?.toIntOrNull() ?: return null
            val naxis = cards["NAXIS"]?.trim()?.toIntOrNull() ?: 0
            val naxis1 = cards["NAXIS1"]?.trim()?.toIntOrNull() ?: 0
            val naxis2 = cards["NAXIS2"]?.trim()?.toIntOrNull() ?: 0
            val headerBytes = blocks * BLOCK
            val dataLen = paddedDataBytes(cards) ?: return null
            val hdu = Hdu(cards, headerBytes, dataLen)
            if (guard == 1 && naxis == 0) {
                primaryHdu = hdu
            }
            val xtension = cards["XTENSION"]
            val isImageHdu = (xtension == null || xtension == "IMAGE")
            if (isImageHdu && naxis == 2 && naxis1 > 0 && naxis2 > 0) {
                return ImageHdu(hdu, primaryHdu, offset)
            }
            if (dataLen <= 0L && naxis == 0 && guard > 1) return null
            if (dataLen > 0L) {
                skipFully(stream, dataLen)
                offset = Math.addExact(offset, dataLen)
            }
        }
        return null
    }

    private fun readFully(stream: java.io.InputStream, buf: ByteArray): Boolean {
        var offset = 0
        while (offset < buf.size) {
            val count = stream.read(buf, offset, buf.size - offset)
            if (count < 0) return false
            offset += count
        }
        return true
    }

    private fun skipFully(stream: java.io.InputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip
        val discard = ByteArray(minOf(remaining, 8192L).toInt())
        while (remaining > 0L) {
            val skipped = stream.skip(remaining)
            if (skipped <= 0L) {
                val toRead = minOf(remaining, discard.size.toLong()).toInt()
                val read = stream.read(discard, 0, toRead)
                if (read < 0) break
                remaining -= read
            } else {
                remaining -= skipped
            }
        }
    }

    private fun metaOf(hdu: Hdu, primaryHdu: Hdu? = null): FitsMeta = FitsMeta(
        bitpix = hdu.int("BITPIX"),
        bscale = hdu.dbl("BSCALE") ?: primaryHdu?.dbl("BSCALE") ?: 1.0,
        bzero = hdu.dbl("BZERO") ?: primaryHdu?.dbl("BZERO") ?: 0.0,
        naxis1 = hdu.int("NAXIS1"),
        naxis2 = hdu.int("NAXIS2"),
        crval1 = hdu.dbl("CRVAL1") ?: primaryHdu?.dbl("CRVAL1"),
        crval2 = hdu.dbl("CRVAL2") ?: primaryHdu?.dbl("CRVAL2"),
        cdelt1 = hdu.dbl("CDELT1") ?: primaryHdu?.dbl("CDELT1"),
        cdelt2 = hdu.dbl("CDELT2") ?: primaryHdu?.dbl("CDELT2"),
        dateObs = hdu.str("DATE-OBS") ?: primaryHdu?.str("DATE-OBS"),
        siteLat = hdu.dbl("SITELAT") ?: hdu.dbl("LATITUDE") ?: primaryHdu?.dbl("SITELAT") ?: primaryHdu?.dbl("LATITUDE"),
        siteLon = hdu.dbl("SITELONG") ?: hdu.dbl("LONGITUD") ?: primaryHdu?.dbl("SITELONG") ?: primaryHdu?.dbl("LONGITUD"),
        objectName = hdu.str("OBJECT") ?: primaryHdu?.str("OBJECT"),
    )

    // ------------------------------------------------------------------ 数据

    internal fun decodeBytes(bytes: ByteArray, maxDim: Int = 2200): Decoded? {
        var offset = 0
        var guard = 0
        var primaryHdu: Hdu? = null
        while (offset + BLOCK <= bytes.size && guard++ < 64) {
            val hdu = parseHdu(bytes, offset) ?: return null
            val naxis = hdu.int("NAXIS")
            val w = hdu.int("NAXIS1")
            val h = hdu.int("NAXIS2")
            if (guard == 1 && naxis == 0) {
                primaryHdu = hdu
            }
            val xtension = hdu.str("XTENSION")
            val isImageHdu = (xtension == null || xtension == "IMAGE")
            if (isImageHdu && naxis == 2 && w > 0 && h > 0) {
                val gray = readGray(bytes, offset + hdu.headerBytes, hdu, w, h) ?: return null
                val meta = metaOf(hdu, primaryHdu)
                return finish(gray, w, h, meta, maxDim)
            }
            offset += hdu.headerBytes + hdu.dataBytes.toInt()
        }
        return null
    }

    /** 读像素 → 物理值（BSCALE/BZERO）→ 翻转行序 → NaN 清零。 */
    private fun readGray(bytes: ByteArray, dataStart: Int, hdu: Hdu, w: Int, h: Int): FloatArray? {
        val bitpix = hdu.int("BITPIX")
        val count = w.toLong() * h
        if (count <= 0 || count > Int.MAX_VALUE) return null
        val n = count.toInt()
        val bytesPer = kotlin.math.abs(bitpix) / 8
        if (bytesPer <= 0 || dataStart + n.toLong() * bytesPer > bytes.size) return null
        val raw = FloatArray(n)
        val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.BIG_ENDIAN)
        when (bitpix) {
            8 -> for (i in 0 until n) raw[i] = (bytes[dataStart + i].toInt() and 0xFF).toFloat()
            16 -> for (i in 0 until n) raw[i] = bb.getShort(dataStart + i * 2).toFloat()
            32 -> for (i in 0 until n) raw[i] = bb.getInt(dataStart + i * 4).toFloat()
            -32 -> for (i in 0 until n) raw[i] = bb.getFloat(dataStart + i * 4)
            -64 -> for (i in 0 until n) raw[i] = bb.getDouble(dataStart + i * 8).toFloat()
            else -> return null
        }
        val bscale = hdu.dbl("BSCALE") ?: 1.0
        val bzero = hdu.dbl("BZERO") ?: 0.0
        if (bscale != 1.0 || bzero != 0.0) {
            val s = bscale.toFloat()
            val z = bzero.toFloat()
            for (i in 0 until n) raw[i] = z + s * raw[i]
        }
        // 行序翻转：FITS 自下而上，位图自上而下（与 convert_narrowfield.py 一致）
        val flipped = FloatArray(n)
        for (y in 0 until h) {
            val src = (h - 1 - y) * w
            System.arraycopy(raw, src, flipped, y * w, w)
        }
        for (i in 0 until n) {
            val v = flipped[i]
            if (!v.isFinite()) flipped[i] = 0f
        }
        return flipped
    }

    /** 拉伸（1.0% / 99.5% 分位 → 0..255，截断）→ 可选降采样。 */
    private fun finish(
        gray: FloatArray, w: Int, h: Int, meta: FitsMeta, maxDim: Int,
    ): Decoded {
        // 分位数：全量排序（1~4M 像素量级，一次约百毫秒）与参考实现同口径
        val sorted = gray.clone()
        java.util.Arrays.sort(sorted)
        val lo = percentile(sorted, 1.0)
        val hi = percentile(sorted, 99.5)
        val span = hi - lo
        val stretched = if (span > 0f) {
            FloatArray(gray.size) { i ->
                (((gray[i] - lo) * (255f / span))).coerceIn(0f, 255f)
            }
        } else {
            // 全平场（比如全 0 的坏图）：退化为 min-max，避免整幅变 0
            val mn = sorted.first()
            val mx = sorted.last()
            val s2 = mx - mn
            if (s2 > 0f) FloatArray(gray.size) { i -> ((gray[i] - mn) * (255f / s2)).coerceIn(0f, 255f) }
            else FloatArray(gray.size)
        }
        val side = maxOf(w, h)
        if (side <= maxDim) return Decoded(w, h, stretched, meta)
        // 整数倍面积平均（与 BitmapFactory 的 inSampleSize 同语义）
        var n = 1
        while (side / n > maxDim) n++
        val ow = w / n
        val oh = h / n
        if (ow <= 0 || oh <= 0) return Decoded(w, h, stretched, meta)
        val out = FloatArray(ow * oh)
        for (y in 0 until oh) {
            for (x in 0 until ow) {
                var sum = 0f
                for (dy in 0 until n) {
                    val row = (y * n + dy) * w
                    for (dx in 0 until n) sum += stretched[row + x * n + dx]
                }
                out[y * ow + x] = sum / (n * n)
            }
        }
        return Decoded(ow, oh, out, meta)
    }

    /** 线性插值分位数（p ∈ [0,100]，已排序数组）。与 numpy.percentile 默认口径一致。 */
    private fun percentile(sorted: FloatArray, p: Double): Float {
        if (sorted.isEmpty()) return 0f
        if (sorted.size == 1) return sorted[0]
        val rank = (p / 100.0) * (sorted.size - 1)
        val loIdx = kotlin.math.floor(rank).toInt()
        val hiIdx = kotlin.math.ceil(rank).toInt()
        if (loIdx == hiIdx) return sorted[loIdx]
        val frac = (rank - loIdx).toFloat()
        return sorted[loIdx] * (1f - frac) + sorted[hiIdx] * frac
    }
}
