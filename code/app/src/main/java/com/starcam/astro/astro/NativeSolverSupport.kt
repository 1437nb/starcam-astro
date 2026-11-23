package com.starcam.astro.astro

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import com.starcam.astro.util.ImageUtils
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * §0.95 原生引擎可用性探测（缺陷报告 S3 的「能力上报」那一半）。
 *
 * 原生库只编译了 arm64-v8a，`build.gradle.kts` 也没有声明 `abiFilters` —— 于是 32 位
 * 与 x86 设备**能装上包**，但 [StellarSolverNative] 的 `System.loadLibrary` 会失败，
 * 官方引擎与 SEP 提星整体不可用，而此前只在调用点静默 catch，用户与支持者都看不到。
 *
 * 之所以单独放一个对象：库缺失时 [StellarSolverNative] 的 `init` 会抛
 * `ExceptionInInitializerError`，**该对象本身不可用** —— 探测必须独立于它。
 * `loadLibrary` 幂等，重复加载无副作用；结果缓存（成败不会随运行变化）。
 */
object NativeEngineProbe {
    val available: Boolean by lazy {
        try {
            System.loadLibrary("stellar_solver")
            true
        } catch (e: Throwable) {
            false
        }
    }
}

/**
 * §0.123 修复 P2-1：FITS 索引完整性校验器（纯 JVM，不触发 System.loadLibrary）。
 */
object FitsIndexValidator {
    /**
     * 校验 FITS 索引文件完整性。
     * FITS 标准强制规范：
     * 1. 文件长度必须 ≥2880 字节且严格为 2880 字节的整数倍；
     * 2. 首个 Header 卡片（80 字节 ASCII）必须以 "SIMPLE  =" 起始。
     */
    fun isValidFitsIndex(file: File): Boolean {
        if (!file.exists() || !file.isFile) return false
        val len = file.length()
        if (len < 2880L || len % 2880L != 0L) return false
        return try {
            file.inputStream().use { input ->
                val header = ByteArray(80)
                val read = input.read(header)
                if (read < 80) false
                else String(header, 0, 80, Charsets.US_ASCII).startsWith("SIMPLE  =")
            }
        } catch (_: Exception) {
            false
        }
    }
}

/**
 * §0.142 原生求解器支撑工具：FITS 索引解压自愈、JSON 解算结果解析与 debug 灰度现场落盘。
 * 与 [StellarSolverNative] 的 JNI `external` 入口解耦，不触发 `System.loadLibrary`。
 */
internal object NativeSolverSupport {

    fun isDebugBuild(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * §0.64 诊断：把"本地匹配器实际吃到的像素"与检出星点落盘（仅 debug 构建）。
     */
    fun dumpLocalFail(
        context: Context,
        bitmap: Bitmap,
        stars: List<DetectedStar>,
        note: String,
    ) {
        if (!isDebugBuild(context)) return
        try {
            val dir = context.getExternalFilesDir(null) ?: return
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            val grayFile = File(dir, "local_fail_latest.gray")
            DataOutputStream(FileOutputStream(grayFile).buffered(128 * 1024)).use { o ->
                o.writeInt(w)
                o.writeInt(h)
                for (c in pixels) {
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    o.writeFloat(0.299f * r + 0.587f * g + 0.114f * b)
                }
            }
            File(dir, "local_fail_latest.txt").writeText(
                buildString {
                    append("note=").append(note).append('\n')
                    append("size=").append(w).append('x').append(h).append('\n')
                    append("detected=").append(stars.size).append('\n')
                    for ((i, d) in stars.withIndex()) {
                        append("star[").append(i).append("] x=").append(d.x)
                            .append(" y=").append(d.y)
                            .append(" b=").append(d.brightness).append('\n')
                    }
                },
            )
        } catch (_: Throwable) {
        }
    }

    /** 官方求解失败时转储灰度输入（仅 debug 构建，单文件轮换）。 */
    fun dumpNativeGray(context: Context, w: Int, h: Int, effectiveGray: FloatArray) {
        if (!isDebugBuild(context)) return
        try {
            val dump = File(context.getExternalFilesDir(null), "gray_dump_latest.gray")
            DataOutputStream(FileOutputStream(dump).buffered(128 * 1024)).use { o ->
                o.writeInt(w)
                o.writeInt(h)
                for (v in effectiveGray) o.writeFloat(v)
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * 确保索引文件在 `filesDir/indexes`（首次从 assets 解压，带临时文件原子替换与 FITS 完整性自愈）。
     */
    /** 与 C 层 `g_index_cache[16]` 匹配的原生索引总数上限。 */
    internal const val MAX_NATIVE_INDEX_CACHE = 16

    @Synchronized
    fun ensureIndexes(context: Context, indexNames: List<String>): Array<String> {
        val dir = File(context.filesDir, "indexes").apply { mkdirs() }
        val result = ArrayList<String>()
        val seenNames = HashSet<String>()
        for (name in indexNames) {
            if (result.size >= MAX_NATIVE_INDEX_CACHE) break
            val f = File(dir, name)
            if (!FitsIndexValidator.isValidFitsIndex(f)) {
                val tmp = File(dir, "$name.tmp")
                try {
                    context.assets.open("indexes/$name").use { input ->
                        tmp.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (FitsIndexValidator.isValidFitsIndex(tmp)) {
                        if (f.exists()) f.delete()
                        tmp.renameTo(f)
                    } else {
                        tmp.delete()
                    }
                } catch (_: Exception) {
                    tmp.delete()
                    continue
                }
            }
            if (FitsIndexValidator.isValidFitsIndex(f) && seenNames.add(name)) {
                result.add(f.absolutePath)
            }
        }
        appendValidatedExtraIndexes(File(dir, "extra"), seenNames, result, MAX_NATIVE_INDEX_CACHE)
        return result.toTypedArray()
    }

    /**
     * §0.144 P2：从 `files/indexes/extra/` 按文件名排序挂载已校验的补充 FITS 索引，
     * 过滤非法文件并去重，同时严格限制总数不超过原生缓存上限 [maxTotal]。
     */
    internal fun appendValidatedExtraIndexes(
        extraDir: File,
        seenNames: MutableSet<String>,
        outPaths: MutableList<String>,
        maxTotal: Int = MAX_NATIVE_INDEX_CACHE,
    ) {
        if (!extraDir.isDirectory || outPaths.size >= maxTotal) return
        val candidates = extraDir.listFiles { f ->
            f.isFile && f.name.startsWith("index-") && f.name.endsWith(".fits")
        }?.sortedBy { it.name } ?: return
        for (f in candidates) {
            if (outPaths.size >= maxTotal) break
            if (!seenNames.add(f.name)) continue
            if (FitsIndexValidator.isValidFitsIndex(f)) {
                outPaths.add(f.absolutePath)
            }
        }
    }

    fun decodeSampled(imagePath: String): Bitmap? =
        try {
            ImageUtils.decodeSampledBitmap(imagePath, 2200)
        } catch (_: Exception) {
            null
        }

    /** 解析 SEP `extractStars` 返回的 JSON 并执行边界伪影过滤（§0.72 / §0.86）。 */
    fun parseSepStarsJson(
        json: String,
        w: Int,
        h: Int,
        edgeMarginPx: Float,
        thresholdBgMultiple: Double,
        lastDiag: StringBuilder?,
    ): StellarSolverNative.SepStars? {
        return try {
            val o = JSONObject(json)
            if (!o.optBoolean("ok", false)) {
                lastDiag?.append("sep=${thresholdBgMultiple}σ 失败(${o.optString("error")})；")
                return null
            }
            val n = o.optInt("n", 0)
            val totalPeaks = o.optInt("npeaks", n)
            if (n <= 0) {
                lastDiag?.append("sep=${thresholdBgMultiple}σ 0 颗；")
                return null
            }
            val xs = o.getJSONArray("x")
            val ys = o.getJSONArray("y")
            val fs = o.getJSONArray("flux")
            val raw = ArrayList<DetectedStar>(n)
            for (i in 0 until n) {
                raw.add(DetectedStar(xs.getDouble(i).toFloat(), ys.getDouble(i).toFloat(), fs.getDouble(i).toFloat()))
            }
            val out = raw.filter {
                it.x >= edgeMarginPx && it.y >= edgeMarginPx && it.x <= w - edgeMarginPx && it.y <= h - edgeMarginPx
            }
            if (lastDiag != null) {
                val border = raw.size - out.size
                val fl = raw.map { it.brightness }.sortedDescending()
                lastDiag.append(
                    "sep=${thresholdBgMultiple}σ 全图检出${totalPeaks}颗/取最亮${raw.size}颗" +
                        "（边界剔除${border}颗→${out.size}颗）" +
                        " flux=${"%.1f".format(fl.lastOrNull() ?: 0f)}~${"%.1f".format(fl.firstOrNull() ?: 0f)}" +
                        " 边缘占比=${"%.0f".format(100.0 * border / raw.size)}%；",
                )
            }
            out.takeIf { it.size >= 5 }?.let { StellarSolverNative.SepStars(it, totalPeaks) }
        } catch (_: Exception) {
            null
        }
    }

    /** 解析原生求解返回的 WCS JSON。 */
    fun parseJson(json: String, w: Int, h: Int): SolveResult? {
        return try {
            val o = JSONObject(json)
            if (!o.optBoolean("ok", false)) return null
            val ra = o.getDouble("ra")
            val dec = o.getDouble("dec")
            val fieldwArcmin = o.getDouble("fieldw_arcmin")
            val fieldhArcmin = o.getDouble("fieldh_arcmin")
            val orient = o.getDouble("orient")
            val pixscale = o.getDouble("pixscale")
            val parity = o.getInt("parity")
            val crpix1 = o.optDouble("crpix1", w / 2.0 + 0.5)
            val crpix2 = o.optDouble("crpix2", h / 2.0 + 0.5)
            val wcs = if (o.has("cd00")) {
                WcsTransform(
                    crpix1, crpix2, ra, dec,
                    o.getDouble("cd00"), o.getDouble("cd01"),
                    o.getDouble("cd10"), o.getDouble("cd11"),
                )
            } else {
                val s = pixscale / 3600.0
                val th = Math.toRadians(orient)
                val cosTh = cos(th)
                val sinTh = sin(th)
                if (parity > 0) {
                    WcsTransform(crpix1, crpix2, ra, dec,
                        s * cosTh, s * sinTh, -s * sinTh, s * cosTh)
                } else {
                    WcsTransform(crpix1, crpix2, ra, dec,
                        -s * cosTh, s * sinTh, s * sinTh, s * cosTh)
                }
            }
            val center = wcs.fitsPixelToSky(w / 2.0 + 0.5, h / 2.0 + 0.5)
            SolveResult(
                raDeg = center[0],
                decDeg = center[1],
                pixScaleArcsec = pixscale,
                orientationDeg = orient,
                parity = parity,
                imageWidth = w,
                imageHeight = h,
                fieldRadiusArcmin = hypot(fieldwArcmin, fieldhArcmin) / 2.0,
                nMatch = if (o.has("nmatch")) o.getInt("nmatch") else null,
                nStars = if (o.has("nstars")) o.getInt("nstars") else null,
                indexId = if (o.has("indexid")) o.getInt("indexid") else null,
                logodds = if (o.has("logodds")) o.getDouble("logodds") else null,
                wcs = wcs,
            )
        } catch (_: Exception) {
            null
        }
    }
}
