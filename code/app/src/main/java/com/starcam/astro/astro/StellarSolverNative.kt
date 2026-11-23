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
     * §0.72 SEP 检出点的边界剔除带宽（像素）。
     * simplexy 的背景估计在图像边缘失效，会把边界噪声判成峰值；实测失败样本
     * 73% 的"星点"落在 30px 边界带内（该带面积仅占 6.3%，富集 11.6 倍），
     * 而正常样本只有 1~5%。取 16px 折中：足以清掉伪影带，又不误杀真实边缘星
     * （广角照片即便有星贴边，剔掉也无碍——投票仍需 ≥5 颗且只用画面内部星）。
     */
    private const val EDGE_MARGIN_PX = 16f

    /**
     * §0.86 喂给匹配器的星点上限。
     *
     * 与"检出上限"是两件事：本值只约束**喂进投票轮的清单长度**，
     * 而"这张图到底检出了多少"由 [SepStars.totalPeaks] 单独回报，不受它截断。
     * 解耦前两者共用一个 200，导致 UI 上永远显示 ~197（见 §0.86）。
     *
     * 取值沿用历史行为 200。注意 [com.starcam.astro.astro.LocalStarMatcher]
     * 记录了「n≥150 时 apod5 会被噪声星凑出假解」——本值已高于那条标定线；
     * 收窄到 150 是独立待验证项，需跑宽场回归确认内点无损后再定。
     */
    const val SEP_MATCH_MAX = 200

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
        token: SolveToken?,
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
        token: SolveToken?,
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

    /** 归还进程级索引缓存（§0.67）。原生侧在「有超时 detach 线程可能仍在使用
     *  索引」时会拒绝释放并返回 0。 */
    @JvmStatic
    private external fun releaseIndexes(): Int

    /** 诊断：索引缓存状态 JSON（cached / loads / leakedThreads）。 */
    @JvmStatic
    private external fun indexCacheStats(): String

    /**
     * 系统内存紧张时归还索引占用的 ~11MB（§0.67）。
     * 返回实际释放的档数（0 = 未释放：有泄漏线程在跑，或本来就未加载）。
     * 释放后下次求解会重新加载，首次多花一次磁盘读取。
     */
    fun trimIndexCache(): Int = try {
        releaseIndexes()
    } catch (e: Throwable) {
        0
    }

    /** 索引缓存状态（诊断用；原生库不可用时返回 null）。 */
    fun indexCacheInfo(): String? = try {
        indexCacheStats()
    } catch (e: Throwable) {
        null
    }

    /**
     * §0.86 SEP 提星结果：星点列表 + **未截断**的真实检出总数。
     *
     * 为什么要分开：[stars] 受 [sepDetectStars] 的 maxStars 截断（喂匹配器的量
     * 有安全上限），而 [totalPeaks] 是 simplexy 在本幅图上实际检出的峰值总数。
     * 真实星空照片两者常差一个数量级（截断后 UI 永远显示 ~197，见 §0.86）。
     */
    class SepStars(
        val stars: List<DetectedStar>,
        /** simplexy 检出峰值总数（未截断）；UI 用它显示"真实检出量" */
        val totalPeaks: Int,
    )

    /**
     * 用 SEP 提取星点（不求解）。返回 null 表示原生库不可用或提取失败。
     *
     * §0.72：[thresholdBgMultiple] 是「背景 sigma 倍数」的真实语义（C 层换算成
     * simplexy 的 plim：plim = N × 2√π × dpsf）。历史上该值被直接当作 plim
     * 使用，2.0 实际只相当于 0.56σ，导致检出限低于噪声、提星结果全是边界伪影
     * （用户 2026-09-17 原图识别失败根因，见 astro_bridge.c §0.72）。
     *
     * [lastDiag] 回填提星诊断（供识别日志记录"提星过程"）：总数、边界过滤数、
     * flux 范围、边缘占比。
     */
    /**
     * 将 Bitmap 转换为灰度浮点数组（0..255），供原生求解器与提星器复用。
     */
    fun bitmapToGray(bitmap: Bitmap): FloatArray = LocalStarMatcher.bitmapToGray(bitmap)

    fun sepDetectStars(
        bitmap: Bitmap,
        thresholdBgMultiple: Double = 2.0,
        maxStars: Int = SEP_MATCH_MAX,
        lastDiag: StringBuilder? = null,
        grayInput: FloatArray? = null,
    ): SepStars? {
        val w = bitmap.width
        val h = bitmap.height
        val gray = grayInput?.also { require(it.size == w * h) }?.copyOf() ?: bitmapToGray(bitmap)
        val json = try {
            extractStars(gray, w, h, thresholdBgMultiple, maxStars)
        } catch (_: Throwable) {
            lastDiag?.append("sep=${thresholdBgMultiple}σ 原生库不可用；")
            return null
        }
        return NativeSolverSupport.parseSepStarsJson(json, w, h, EDGE_MARGIN_PX, thresholdBgMultiple, lastDiag)
    }

    /** 从文件图片求解（自动缩放到 2200 长边内） */
    fun solveImage(
        context: Context,
        imagePath: String,
        fovLoDeg: Double,
        fovHiDeg: Double,
        timeLimitSec: Double = 45.0,
    ): SolveResult? {
        val bitmap = NativeSolverSupport.decodeSampled(imagePath) ?: return null
        return solveBitmap(context, bitmap, fovLoDeg, fovHiDeg, timeLimitSec)
    }

    /** 从 Bitmap 求解（可选传入已预先计算的灰度缓冲 [gray] 与峰值显著度覆盖 [plimOverride]） */
    fun solveBitmap(
        context: Context,
        bitmap: Bitmap,
        fovLoDeg: Double,
        fovHiDeg: Double,
        timeLimitSec: Double = 45.0,
        gray: FloatArray? = null,
        token: SolveToken? = null,
        plimOverride: Double = 0.0,
    ): SolveResult? {
        return solveBitmapImpl(context, bitmap, fovLoDeg, fovHiDeg, timeLimitSec,
            raDeg = NO_PRIOR, decDeg = NO_PRIOR, radiusDeg = -1.0,
            plimOverride = plimOverride, gray = gray, token = token)
    }

    /**
     * 从 Bitmap 求解（带天区先验）。
     * [raDeg]/[decDeg] 为天球中心（度），[radiusDeg] 为搜索半径（度）；
     * 传 [NO_PRIOR] 表示无天区先验（等价 [solveBitmap]）。
     * 可选传入已预先计算的灰度缓冲 [gray]。
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
        gray: FloatArray? = null,
        token: SolveToken? = null,
    ): SolveResult? {
        return solveBitmapImpl(context, bitmap, fovLoDeg, fovHiDeg, timeLimitSec,
            raDeg, decDeg, radiusDeg, gray = gray, token = token)
    }

    /** 无天区先验哨兵值 */
    const val NO_PRIOR: Double = -999.0

    private data class FailDiag(
        val nStars: Int? = null,
        val rc: Int? = null,
        val gMean: Double? = null,
        val gMax: Double? = null,
    )

    private val threadFailDiag = ThreadLocal<FailDiag?>()

    /** 最近一次求解失败时的提星数（当前线程隔离，真机诊断用，AutoTest 读取；null=未知） */
    val lastFailNStars: Int? get() = threadFailDiag.get()?.nStars

    /**
     * §0.64 诊断：把"本地匹配器实际吃到的像素"与它检出的星点落盘（仅 debug 构建）。
     */
    fun dumpLocalFail(
        context: Context,
        bitmap: Bitmap,
        stars: List<DetectedStar>,
        note: String,
    ) = NativeSolverSupport.dumpLocalFail(context, bitmap, stars, note)

    /** 最近一次提星失败的诊断：simplexy 返回码 / 输入灰度均值 / 灰度最大值（线程隔离） */
    val lastFailRc: Int? get() = threadFailDiag.get()?.rc

    val lastFailGMean: Double? get() = threadFailDiag.get()?.gMean

    val lastFailGMax: Double? get() = threadFailDiag.get()?.gMax

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
        gray: FloatArray? = null,
        token: SolveToken? = null,
    ): SolveResult? {
        token?.check()
        threadFailDiag.remove()
        val w = bitmap.width
        val h = bitmap.height
        val effectiveGray = if (gray != null && gray.size == w * h) {
            gray
        } else {
            bitmapToGray(bitmap)
        }
        val indexes = NativeSolverSupport.ensureIndexes(context, BUNDLED_INDEX_NAMES)

        // 首轮原生求解传 extStars = null：C 层 simplexy 提星成功率 >99.9% 且自备提星。
        // 杜绝每轮盲解均提前执行耗时 100~250ms 的 JVM 提星与大数组分配。
        val tAttempt0Nanos = System.nanoTime()
        var extStars: FloatArray? = null
        var json = try {
            if (raDeg > NO_PRIOR / 2.0) {
                solvePriors(effectiveGray, w, h, indexes, fovLoDeg, fovHiDeg,
                    raDeg, decDeg, radiusDeg, plimOverride, timeLimitSec, extStars, token)
            } else {
                solve(effectiveGray, w, h, indexes, fovLoDeg, fovHiDeg, plimOverride, timeLimitSec, extStars, token)
            }
        } catch (e: UnsatisfiedLinkError) {
            return null
        } catch (e: Throwable) {
            if (e is java.util.concurrent.CancellationException) throw e
            return null
        }
        token?.check()
        var parsed = NativeSolverSupport.parseJson(json, w, h)
        // 官方解质量门槛（服务器 12 张标定：真解 logodds 26~907、nmatch≥15；
        // 噪声星点下的随机假解 logodds<10、nmatch=1）——宁可 FAILED 也不报错误天区
        if (parsed != null && parsed.logodds != null &&
            (parsed.logodds < 25.0 || (parsed.nMatch ?: 99) < 2)
        ) {
            parsed = null
        }

        // 仅在必要失败时（simplexy 提取失败或星点不足 4 颗）重试补充 JVM 外部星点
        if (parsed == null) {
            var needRetryWithExtStars = false
            try {
                val o = JSONObject(json)
                val err = o.optString("error", "")
                val nstars = o.optInt("nstars", 999)
                if (err == "star-extraction-failed" || err == "too-few-stars" || nstars < 4) {
                    needRetryWithExtStars = true
                }
            } catch (_: Exception) {
            }

            val elapsedSec = (System.nanoTime() - tAttempt0Nanos) / 1e9
            val remainingLimitSec = timeLimitSec - elapsedSec
            if (needRetryWithExtStars && remainingLimitSec >= 0.5) {
                token?.check()
                extStars = try {
                    com.starcam.astro.astro.LocalStarMatcher.detectStarsGray(w, h, effectiveGray, 200)
                        .takeIf { it.size >= 4 }?.let { stars ->
                            FloatArray(1 + stars.size * 3).also { a ->
                                a[0] = stars.size.toFloat()
                                stars.forEachIndexed { i, d ->
                                    a[1 + i * 3] = d.x
                                    a[2 + i * 3] = d.y
                                    a[3 + i * 3] = d.brightness
                                }
                            }
                        }
                } catch (_: Throwable) {
                    null
                }

                if (extStars != null) {
                    val retryLimitSec = (timeLimitSec - (System.nanoTime() - tAttempt0Nanos) / 1e9)
                        .coerceAtLeast(0.5)
                    json = try {
                        if (raDeg > NO_PRIOR / 2.0) {
                            solvePriors(
                                effectiveGray, w, h, indexes, fovLoDeg, fovHiDeg,
                                raDeg, decDeg, radiusDeg, plimOverride, retryLimitSec, extStars, token,
                            )
                        } else {
                            solve(
                                effectiveGray, w, h, indexes, fovLoDeg, fovHiDeg,
                                plimOverride, retryLimitSec, extStars, token,
                            )
                        }
                    } catch (_: Throwable) {
                        json
                    }
                    token?.check()
                    parsed = NativeSolverSupport.parseJson(json, w, h)
                    if (parsed != null && parsed.logodds != null &&
                        (parsed.logodds < 25.0 || (parsed.nMatch ?: 99) < 2)
                    ) {
                        parsed = null
                    }
                }
            }
        }

        if (parsed == null) {
            NativeSolverSupport.dumpNativeGray(context, w, h, effectiveGray)
            try {
                val o = JSONObject(json)
                threadFailDiag.set(
                    FailDiag(
                        nStars = if (o.has("nstars")) o.getInt("nstars") else null,
                        rc = if (o.has("rc")) o.getInt("rc") else null,
                        gMean = if (o.has("gmean")) o.getDouble("gmean") else null,
                        gMax = if (o.has("gmax")) o.getDouble("gmax") else null,
                    ),
                )
            } catch (_: Exception) {
            }
        }
        return parsed
    }

    internal fun isValidFitsIndex(file: File): Boolean = FitsIndexValidator.isValidFitsIndex(file)
}
