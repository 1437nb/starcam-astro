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

    fun decode(path: String, maxDim: Int = 2200): Decoded? = try {
        decodeBytes(java.io.File(path).readBytes(), maxDim)
    } catch (e: Exception) {
        null
    }

    /** 只读头部（先验用；不读像素，代价极小）。 */
    fun readMeta(path: String): FitsMeta? = try {
        readMetaBytes(java.io.File(path).readBytes())
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
        val gcount = cards["GCOUNT"]?.trim()?.toIntOrNull() ?: 1
        val pcount = cards["PCOUNT"]?.trim()?.toIntOrNull() ?: 0
        var dataLen = 0L
        if (naxis > 0) {
            var n = 1L
            for (k in 1..naxis) {
                n *= (cards["NAXIS$k"]?.trim()?.toLongOrNull() ?: 0L)
            }
            dataLen = (kotlin.math.abs(bitpix) / 8L) * gcount * (n + pcount)
            // 数据必须补齐到 2880 的整数倍（FITS 标准）
            dataLen = ((dataLen + BLOCK - 1) / BLOCK) * BLOCK
        }
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

    private fun readMetaBytes(bytes: ByteArray): FitsMeta? {
        val hdu = firstImageHdu(bytes) ?: return null
        return metaOf(hdu)
    }

    private fun metaOf(hdu: Hdu): FitsMeta = FitsMeta(
        bitpix = hdu.int("BITPIX"),
        bscale = hdu.dbl("BSCALE") ?: 1.0,
        bzero = hdu.dbl("BZERO") ?: 0.0,
        naxis1 = hdu.int("NAXIS1"),
        naxis2 = hdu.int("NAXIS2"),
        crval1 = hdu.dbl("CRVAL1"),
        crval2 = hdu.dbl("CRVAL2"),
        cdelt1 = hdu.dbl("CDELT1"),
        cdelt2 = hdu.dbl("CDELT2"),
        dateObs = hdu.str("DATE-OBS"),
        siteLat = hdu.dbl("SITELAT") ?: hdu.dbl("LATITUDE"),
        siteLon = hdu.dbl("SITELONG") ?: hdu.dbl("LONGITUD"),
        objectName = hdu.str("OBJECT"),
    )

    // ------------------------------------------------------------------ 数据

    /** 找到第一个含二维图像的 HDU（主 HDU 无数据时向后扫描扩展 HDU）。 */
    private fun firstImageHdu(bytes: ByteArray): Hdu? {
        var offset = 0
        var guard = 0
        while (offset + BLOCK <= bytes.size && guard++ < 64) {
            val hdu = parseHdu(bytes, offset) ?: return null
            if (hdu.int("NAXIS") == 2 && hdu.int("NAXIS1") > 0 && hdu.int("NAXIS2") > 0) {
                return hdu
            }
            if (hdu.dataBytes <= 0 && hdu.int("NAXIS") == 0 && guard > 1) return null
            offset += hdu.headerBytes + hdu.dataBytes.toInt()
        }
        return null
    }

    internal fun decodeBytes(bytes: ByteArray, maxDim: Int = 2200): Decoded? {
        var offset = 0
        var guard = 0
        while (offset + BLOCK <= bytes.size && guard++ < 64) {
            val hdu = parseHdu(bytes, offset) ?: return null
            val naxis = hdu.int("NAXIS")
            val w = hdu.int("NAXIS1")
            val h = hdu.int("NAXIS2")
            if (naxis == 2 && w > 0 && h > 0) {
                val gray = readGray(bytes, offset + hdu.headerBytes, hdu, w, h) ?: return null
                val meta = metaOf(hdu)
                return finish(gray, w, h, meta, maxDim)
            }
            offset += hdu.headerBytes + hdu.dataBytes.toInt()
        }
        return null
    }

    /** 读像素 → 物理值（BSCALE/BZERO）→ 翻转行序 → NaN 清零。 */
    private fun readGray(bytes: ByteArray, dataStart: Int, hdu: Hdu, w: Int, h: Int): FloatArray? {
        val bitpix = hdu.int("BITPIX")
        val n = w * h
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
