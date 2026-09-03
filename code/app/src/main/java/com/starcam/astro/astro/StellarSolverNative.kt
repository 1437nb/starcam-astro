package com.starcam.astro.astro

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONObject
import java.io.File

/**
 * StellarSolver（本地 astrometry 引擎 + SEP 星点提取）的 JNI 桥封装。
 *
 * 链路：Kotlin 提供灰度像素 → 原生 SEP 提取星点 → astrometry 盲解
 * （blind solver，加载官方 4100 系列索引）→ 返回 WCS 解算结果。
 *
 * 可解视场：约 0.1° ~ 180°（4100 系列索引 quad 直径 2.0′~2000′；
 * quad 仅占图像一部分，官方 demo 即以 index-4115~4119 求解 45° 视场照片，
 * 手机广角照片同样适用；180° 为 engine maxwidth 硬上限）。
 * 与 LocalStarMatcher 构成双引擎分层：
 *   StellarSolver：0.1°~180°（高精度，含广角）
 *   LocalStarMatcher：超宽场（>180° 或官方未命中时）补充
 *   PlateSolveClient：在线 nova 兜底
 */
object StellarSolverNative {

    /** 支持的 4100 系列索引（quad 直径档位，角分）：4119 最宽（33.3°） */
    // 索引文件与 quad 直径对应（来自 data.astrometry.net/4100/README 官方表格）：
    // 4119: 1400–2000′, 4118: 1000–1400′, 4117: 680–1000′, 4116: 480–680′,
    // 4115: 340–480′, 4114: 240–340′, 4113: 170–240′, 4112: 120–170′
    // （quad 只占图像的一部分——可解视场远超 quad 直径，广角照片同样适用）
    // 打包进 APK 的索引（assets/indexes/），需在 assets 中手工添加 fit 文件
    val BUNDLED_INDEX_NAMES = listOf(
        "index-4119.fits",
        "index-4118.fits",
        "index-4117.fits",
        "index-4116.fits",
        "index-4115.fits",
        "index-4114.fits",
        "index-4113.fits",
        "index-4112.fits",
    )

    // 原生库（由 native/CMakeLists.txt 构建，libstellar_solver.so）
    init {
        System.loadLibrary("stellar_solver")
    }

    /**
     * 求解入口（原生实现）。[gray] 为灰度 float 像素（0..255），
     * [indexPaths] 为索引 fits 完整路径列表，[fovLoDeg]/[fovHiDeg] 为
     * 视场宽度估计范围（度），[timeLimitSec] 为求解时间上限。
     * 返回 JSON：{"ok":true,"ra":..,"dec":..,"fieldw_arcmin":..,...,"indexid":..}
     * 或 {"ok":false,"error":"..."}
     */
    @JvmStatic
    private external fun solve(
        gray: FloatArray,
        w: Int,
        h: Int,
        indexPaths: Array<String>,
        fovLoDeg: Double,
        fovHiDeg: Double,
        plimOverride: Double,
        timeLimitSec: Double,
        extStars: FloatArray?,
    ): String

    /**
     * 带天区先验的求解入口（原生实现，native 库 0.2 版起）。
     * 相比 [solve] 多出 [raDeg]/[decDeg]/[radiusDeg]：以该天球坐标为中心、
     * 指定半径（度）的球冠内搜索（等价官方 solve-field 的 --ra/--dec/--radius），
     * 把全天空盲解缩小到拍摄时刻的可见天区。
     * 无天区先验时传 ra=dec=-999.0、radius=-1.0（等价 [solve]）。
     */
    @JvmStatic
    private external fun solvePriors(
        gray: FloatArray,
        w: Int,
        h: Int,
        indexPaths: Array<String>,
        fovLoDeg: Double,
        fovHiDeg: Double,
        raDeg: Double,
        decDeg: Double,
        radiusDeg: Double,
        plimOverride: Double,
        timeLimitSec: Double,
        extStars: FloatArray?,
    ): String

    /**
     * SEP 星点提取（不求解，供 LocalStarMatcher 检测增强）。
     * 返回 {"ok":true,"n":N,"x":[...],"y":[...],"flux":[...]}（坐标 0 起始，
     * y 向下，与 detectStarsGray 约定一致）或 {"ok":false,"error":"..."}。
     * [thresholdBgMultiple] 提取阈值 = multiple × 背景 sigma（默认 2.0，越大越少）。
     */
    @JvmStatic
    private external fun extractStars(
        gray: FloatArray,
        w: Int,
        h: Int,
        thresholdBgMultiple: Double,
        maxStars: Int,
    ): String

    /** 用 SEP 提取星点（不求解）。返回 null 表示原生库不可用或提取失败。 */
    fun sepDetectStars(
        bitmap: Bitmap,
        thresholdBgMultiple: Double = 2.0,
        maxStars: Int = 200,
    ): List<DetectedStar>? {
        val w = bitmap.width
        val h = bitmap.height
        val gray = FloatArray(w * h)
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val c = pixels[i]
            gray[i] = 0.299f * ((c shr 16) and 0xFF) +
                0.587f * ((c shr 8) and 0xFF) +
                0.114f * (c and 0xFF)
        }
        val json = try {
            extractStars(gray, w, h, thresholdBgMultiple, maxStars)
        } catch (e: Throwable) {
            return null
        }
        return try {
            val o = JSONObject(json)
            if (!o.optBoolean("ok", false)) return null
            val n = o.optInt("n", 0)
            if (n <= 0) return null
            val xs = o.getJSONArray("x")
            val ys = o.getJSONArray("y")
            val fs = o.getJSONArray("flux")
            val out = ArrayList<DetectedStar>(n)
            for (i in 0 until n) {
                out.add(DetectedStar(xs.getDouble(i).toFloat(), ys.getDouble(i).toFloat(), fs.getDouble(i).toFloat()))
            }
            out
        } catch (e: Exception) {
            null
        }
    }

    /** 从文件图片求解（自动缩放到 2200 长边内） */
    fun solveImage(
        context: Context,
        imagePath: String,
        fovLoDeg: Double,
        fovHiDeg: Double,
        timeLimitSec: Double = 45.0,
    ): SolveResult? {
        val bitmap = decodeSampled(imagePath) ?: return null
        return solveBitmap(context, bitmap, fovLoDeg, fovHiDeg, timeLimitSec)
    }

    /** 从 Bitmap 求解 */
    fun solveBitmap(
        context: Context,
        bitmap: Bitmap,
        fovLoDeg: Double,
        fovHiDeg: Double,
        timeLimitSec: Double = 45.0,
    ): SolveResult? {
        return solveBitmapImpl(context, bitmap, fovLoDeg, fovHiDeg, timeLimitSec,
            raDeg = NO_PRIOR, decDeg = NO_PRIOR, radiusDeg = -1.0)
    }

    /**
     * 从 Bitmap 求解（带天区先验）。
     * [raDeg]/[decDeg] 为天球中心（度），[radiusDeg] 为搜索半径（度）；
     * 传 [NO_PRIOR] 表示无天区先验（等价 [solveBitmap]）。
     */
    fun solveBitmapPriors(
        context: Context,
        bitmap: Bitmap,
        fovLoDeg: Double,
        fovHiDeg: Double,
        raDeg: Double,
        decDeg: Double,
        radiusDeg: Double,
        timeLimitSec: Double = 45.0,
    ): SolveResult? {
        return solveBitmapImpl(context, bitmap, fovLoDeg, fovHiDeg, timeLimitSec,
            raDeg, decDeg, radiusDeg)
    }

    /** 无天区先验哨兵值 */
    const val NO_PRIOR: Double = -999.0

    /** 最近一次求解失败时的提星数（真机诊断用，AutoTest 读取；null=未知） */
    @Volatile
    var lastFailNStars: Int? = null
        private set

    private fun isDebugBuild(context: Context): Boolean =
        (context.applicationInfo.flags and
            android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** 最近一次提星失败的诊断：simplexy 返回码 / 输入灰度均值 / 灰度最大值 */
    @Volatile
    var lastFailRc: Int? = null
        private set

    @Volatile
    var lastFailGMean: Double? = null
        private set

    @Volatile
    var lastFailGMax: Double? = null
        private set

    private fun solveBitmapImpl(
        context: Context,
        bitmap: Bitmap,
        fovLoDeg: Double,
        fovHiDeg: Double,
        timeLimitSec: Double,
        raDeg: Double,
        decDeg: Double,
        radiusDeg: Double,
        plimOverride: Double = 0.0,
    ): SolveResult? {
        val w = bitmap.width
        val h = bitmap.height
        val gray = FloatArray(w * h)
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val c = pixels[i]
            gray[i] = 0.299f * ((c shr 16) and 0xFF) +
                0.587f * ((c shr 8) and 0xFF) +
                0.114f * (c and 0xFF)
        }
        val indexes = ensureIndexes(context)
        // 跨引擎星点复用：部分真机上 .so 内 simplexy（全部入口）提星 0 颗
        // （2026-08-30 真机五轮实测：同代码独立可执行正常 462 颗、APP 内 0 颗），
        // 而 JVM 提星（LocalStarMatcher.detectStars，纯 Kotlin box-blur）已被
        // 证明在同一台手机上工作（自研引擎 5 解即其输入）——用它喂官方引擎。
        val extStars = try {
            com.starcam.astro.astro.LocalStarMatcher.detectStars(bitmap, 40)
                .takeIf { it.size >= 4 }?.let { stars ->
                FloatArray(1 + stars.size * 3).also { a ->
                    a[0] = stars.size.toFloat()
                    stars.forEachIndexed { i, d ->
                        a[1 + i*3] = d.x
                        a[2 + i*3] = d.y
                        a[3 + i*3] = d.brightness
                    }
                }
            }
        } catch (_: Throwable) {
            null
        }
        val json = try {
            if (raDeg > NO_PRIOR / 2.0) {
                solvePriors(gray, w, h, indexes, fovLoDeg, fovHiDeg,
                    raDeg, decDeg, radiusDeg, plimOverride, timeLimitSec, extStars)
            } else {
                solve(gray, w, h, indexes, fovLoDeg, fovHiDeg, plimOverride, timeLimitSec, extStars)
            }
        } catch (e: UnsatisfiedLinkError) {
            return null
        } catch (e: Throwable) {
            return null
        }
        val parsed = parseJson(json, w, h)
        // 诊断：官方求解失败时转储灰度输入（仅 debug 构建，单文件轮换）
        // 供服务器端 diag_arm64_dyn 用同一条数据复现"提星 0 颗"之谜
        if (parsed == null && isDebugBuild(context)) {
            try {
                val dump = java.io.File(
                    context.getExternalFilesDir(null),
                    "gray_dump_latest.gray",
                )
                java.io.DataOutputStream(java.io.FileOutputStream(dump)).use { o ->
                    o.writeInt(w); o.writeInt(h)
                    for (v in gray) o.writeFloat(v)
                }
            } catch (_: Throwable) {
            }
        }
        // 官方解质量门槛（服务器 12 张标定：真解 logodds 26~907、nmatch≥15；
        // 噪声星点下的随机假解 logodds<10、nmatch=1）——宁可 FAILED 也不报错误天区
        if (parsed != null && parsed.logodds != null &&
            (parsed.logodds < 25.0 || (parsed.nMatch ?: 99) < 2)
        ) {
            return null
        }
        if (parsed == null) {
            try {
                val o = org.json.JSONObject(json)
                if (o.has("nstars")) lastFailNStars = o.getInt("nstars")
                if (o.has("rc")) lastFailRc = o.getInt("rc")
                if (o.has("gmean")) lastFailGMean = o.getDouble("gmean")
                if (o.has("gmax")) lastFailGMax = o.getDouble("gmax")
            } catch (_: Exception) {
            }
        }
        return parsed
    }

    /**
     * 确保索引文件在 filesDir（首次从 assets 解压）。返回完整路径列表，
     * 缺失的索引自动跳过（如窄场索引用户未下载时）。
     */
    private fun ensureIndexes(context: Context): Array<String> {
        val dir = File(context.filesDir, "indexes").apply { mkdirs() }
        val result = ArrayList<String>()
        for (name in BUNDLED_INDEX_NAMES) {
            val f = File(dir, name)
            if (!f.exists() || f.length() == 0L) {
                try {
                    context.assets.open("indexes/$name").use { input ->
                        f.outputStream().use { output -> input.copyTo(output) }
                    }
                } catch (e: Exception) {
                    continue // 未打包该索引：跳过
                }
            }
            if (f.length() > 0L) result.add(f.absolutePath)
        }
        return result.toTypedArray()
    }

    private fun decodeSampled(imagePath: String): Bitmap? =
        try {
            com.starcam.astro.util.ImageUtils.decodeSampledBitmap(imagePath, 2200)
        } catch (e: Exception) {
            null
        }

    /** 解析原生返回的 JSON */
    private fun parseJson(json: String, w: Int, h: Int): SolveResult? {
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
            val crpix1 = w / 2.0 + 0.5
            val crpix2 = h / 2.0 + 0.5
            // 原生库 0.2 起直出官方 CD 矩阵（tan_t.wcstan，度/像素），
            // 与官方求解器结果逐位一致；旧库无 cd 字段时回退 orient/parity 重建
            val wcs = if (o.has("cd00")) {
                WcsTransform(
                    crpix1, crpix2, ra, dec,
                    o.getDouble("cd00"), o.getDouble("cd01"),
                    o.getDouble("cd10"), o.getDouble("cd11"),
                )
            } else {
                val s = pixscale / 3600.0
                val th = Math.toRadians(orient)
                val cosTh = kotlin.math.cos(th)
                val sinTh = kotlin.math.sin(th)
                if (parity > 0) {
                    WcsTransform(crpix1, crpix2, ra, dec,
                        s * cosTh, s * sinTh, -s * sinTh, s * cosTh)
                } else {
                    WcsTransform(crpix1, crpix2, ra, dec,
                        -s * cosTh, s * sinTh, s * sinTh, s * cosTh)
                }
            }
            SolveResult(
                raDeg = ra,
                decDeg = dec,
                pixScaleArcsec = pixscale,
                orientationDeg = orient,
                parity = parity,
                imageWidth = w,
                imageHeight = h,
                fieldRadiusArcmin = kotlin.math.hypot(fieldwArcmin, fieldhArcmin) / 2.0,
                nMatch = if (o.has("nmatch")) o.getInt("nmatch") else null,
                nStars = if (o.has("nstars")) o.getInt("nstars") else null,
                indexId = if (o.has("indexid")) o.getInt("indexid") else null,
                logodds = if (o.has("logodds")) o.getDouble("logodds") else null,
                wcs = wcs,
            )
        } catch (e: Exception) {
            null
        }
    }
}