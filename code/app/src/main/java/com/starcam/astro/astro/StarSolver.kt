package com.starcam.astro.astro

import android.content.Context
import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import com.starcam.astro.data.SettingsRepository
import com.starcam.astro.util.ImageUtils
import java.io.File
import kotlin.math.abs
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 三层识别引擎调度器 —— 拍照认星主入口。
 *
 * 引擎优先级（按用户配置，默认 AUTO）：
 *  1. [SolveEngine.LOCAL_MATCHER]   广角 Hipparcos 亮星表匹配
 *     （内置 919 颗 Hipparcos 亮星（mag≤4.5），三角形投票 + RANSAC，
 *     约 8°~180° 广角场景秒级出结果，适合手机广角星空照片）
 *  2. [SolveEngine.ASTROMETRY_NATIVE]  astrometry.net 官方盲求解器
 *     （NDK libstellar_solver.so，4100 系列索引 index-4112~4119，
 *     quad 直径 2.0′~2000′，可解 0.1°~180° 视场，含手机广角照片，
 *     亮星表未命中时的精解引擎；无 EXIF 先验时按分段盲解试探）
 *  3. [SolveEngine.ONLINE_NOVA]        在线 nova.astrometry.net（需 API Key）兜底
 *
 * AUTO 模式：广角（≥8°）先 Hipparcos 亮星表快匹配，未命中才用官方引擎精解；
 * 窄场（<8°，望远镜等）亮星表自动跳过直接官方引擎；最后在线兜底。
 * NATIVE_FIRST 模式保留官方引擎优先的旧策略。
 */
enum class SolveEngine(val labelZh: String, val labelEn: String) {
    ASTROMETRY_NATIVE("astrometry.net 本地引擎", "astrometry.net Native"),
    LOCAL_MATCHER("内置星表匹配", "Local Catalog Matcher"),
    ONLINE_NOVA("在线 nova.astrometry.net", "Online nova.astrometry.net");

    val label: String get() = labelZh
    fun label(isEnglish: Boolean = false): String = if (isEnglish) labelEn else labelZh
}

/** 识别引擎模式（设置页可切换） */
enum class EngineMode(
    val key: String,
    val labelZh: String,
    val labelEn: String,
    val descriptionZh: String,
    val descriptionEn: String,
) {
    AUTO(
        "auto", "自动（推荐）", "Auto (Recommended)",
        "广角照片先 Hipparcos 亮星表秒级匹配，失败后用 astrometry.net 官方引擎精解，最后在线兜底",
        "Fast wide-field catalog matching first, official engine next, online fallback last",
    ),
    NATIVE_FIRST(
        "native_first", "官方引擎优先", "Official Engine First",
        "始终先试 astrometry.net 官方引擎（0.1°~35°），再内置星表，最后在线",
        "Always try official astrometry.net engine first, then local catalog, online last",
    ),
    OFFLINE_ONLY(
        "offline_only", "仅离线", "Offline Only",
        "只用本地引擎（官方 + 内置星表），不上传照片",
        "Use local engines only (official + local catalog), never upload photos",
    ),
    ONLINE_ONLY(
        "online_only", "仅在线", "Online Only",
        "只用 astrometry.net 在线服务求解（需 API Key）",
        "Only use online astrometry.net service (requires API Key)",
    );

    val label: String get() = labelZh
    val description: String get() = descriptionZh

    fun label(isEnglish: Boolean = false): String = if (isEnglish) labelEn else labelZh
    fun description(isEnglish: Boolean = false): String = if (isEnglish) descriptionEn else descriptionZh
}

/** 一次成功识别的结果（含引擎标识与详情） */
data class EngineResult(
    val solve: SolveResult,
    val engine: SolveEngine,
    val detail: String,
    /** 渲染基准位图（在线路径会基于上传图重新解码，与求解像素一一对应） */
    val displayBitmap: Bitmap,
)

object StarSolver {

    /**
     * 官方引擎可用视场范围（度）。
     * 下限 0.1°：索引 4112（quad 直径 2.0′）能支撑的最窄场；
     * 上限 180°：astrometry engine 的 maxwidth 硬上限（也是鱼眼/半球视场极限）。
     * 注意：这远宽于"索引 quad 直径上限 2000′≈33°"——quad 只占图像的一小部分，
     * 官方 demo 即以 index-4115~4119 求解 45° 视场照片，手机广角同样适用。
     */
    const val NATIVE_FOV_MIN_DEG = 0.1
    const val NATIVE_FOV_MAX_DEG = 180.0

    /** 自研匹配器经验适用范围（度） */
    const val LOCAL_FOV_MIN_DEG = 8.0
    const val LOCAL_FOV_MAX_DEG = 180.0

    /** 官方引擎单次求解时间上限（秒）：fov 已知时给足，未知盲解时收紧 */
    const val NATIVE_TIME_LIMIT_KNOWN_FOV = 30.0

    /** 盲解（无 EXIF 先验）分段试探参数：窄-中场段（度，限时秒） */
    const val NATIVE_BLIND_SEG_LO_DEG = 3.0
    const val NATIVE_BLIND_SEG_MID_DEG = 40.0
    const val NATIVE_BLIND_SEG_MID_TIME = 12.0

    /** 盲解（无 EXIF 先验）分段试探参数：中-宽场段（度，限时秒） */
    const val NATIVE_BLIND_SEG_WIDE_DEG = 120.0
    const val NATIVE_BLIND_SEG_WIDE_TIME = 16.0

    /**
     * 无 EXIF 视场先验时的官方引擎分段试探方案。
     * 实测（12 张真实手机照片 + 手机预算模拟 §0.15）：11/12 均由
     * 40°~120° 宽场段命中（手机主摄/广角普遍 40°+），故**宽场段优先**，
     * 让广角照片少等一段；窄场照片（望远镜/变焦）随后 3°~40° 段兜底。
     * 每段限时不变：宽场 16s、窄中 12s（合计 28s ≤ 已知视场单段 30s）。
     */
    fun blindSegments(): List<Triple<Double, Double, Double>> = listOf(
        Triple(NATIVE_BLIND_SEG_MID_DEG, NATIVE_BLIND_SEG_WIDE_DEG, NATIVE_BLIND_SEG_WIDE_TIME),
        Triple(NATIVE_BLIND_SEG_LO_DEG, NATIVE_BLIND_SEG_MID_DEG, NATIVE_BLIND_SEG_MID_TIME),
    )

    /** 降采样重试轮：目标长边像素 */
    const val NATIVE_DOWNSAMPLE_RETRY_LONG_EDGE = 1100

    /** 降采样重试轮：单段限时（秒，比首轮盲解段收紧，避免总预算失控） */
    const val NATIVE_DOWNSAMPLE_RETRY_TIME = 10.0

    /** 本地解官方复核（§0.32）：内点达到该值即视为高置信，跳过复核（省时） */
    const val CROSS_CHECK_MIN_INLIERS = 18

    /** 本地解官方复核：单次求解限时（秒，只在低内点时触发一次） */
    const val CROSS_CHECK_TIME_LIMIT_SEC = 10.0

    /**
     * 本地解官方复核决策（§0.29 遗留项落地，§0.32）：
     * - 内点 ≥ [CROSS_CHECK_MIN_INLIERS]：自研解高置信，直接接受；
     * - 内点不足时用官方引擎（自带 logodds/nMatch 质量门槛）在自研视场
     *   ±35% 范围内复核：
     *   - 官方无解（复核失败/引擎不可用）→ 不拦截（宁放过不误杀）；
     *   - 官方有解且天区/视场与自研一致（天区 Δ<3°、视场比 0.5~2）→ 接受；
     *   - 官方有解但不一致 → 拒绝（防"宽场误判窄场特写"类假解，§0.28/§0.30）。
     */
    internal fun crossCheckAccept(
        localRaDeg: Double,
        localDecDeg: Double,
        localFovDeg: Double,
        localInliers: Int,
        native: SolveResult?,
    ): Boolean {
        if (localInliers >= CROSS_CHECK_MIN_INLIERS) return true
        if (native == null) return true
        val dra = abs(localRaDeg - native.raDeg).let { min(it, 360.0 - it) }
        val ddec = abs(localDecDeg - native.decDeg)
        val fovRatio = localFovDeg / native.fieldWidthDeg
        return dra < 3.0 && ddec < 3.0 && fovRatio in 0.5..2.0
    }

    /**
     * 官方引擎盲解（无 EXIF 视场先验）全段失败后的降采样重试决策。
     *
     * 服务器实测依据（2026-08-28）：12 张真实手机照片中，变焦/星点肥大照片
     * （5057，41° 视场）在原始分辨率下官方引擎解不出，加 `--downsample 2`
     * 后秒解（log-odds 248）；原因是大星点 + 噪声在提星阶段污染 quad 匹配，
     * 降采样后星点归一、形状更稳。
     *
     * 规则：仅"无视场先验的盲解"且图像长边仍偏大时触发；
     * 有 EXIF 焦距先验的路径已按 FOV 对准 scale，不需要（也省时间）。
     * 返回（目标长边像素, 重试分段），不满足条件返回 null。
     */
    fun downsampleRetryPlan(
        fovDeg: Double?,
        longEdgePx: Int,
    ): Pair<Int, List<Triple<Double, Double, Double>>>? {
        if (fovDeg != null) return null
        if (longEdgePx <= NATIVE_DOWNSAMPLE_RETRY_LONG_EDGE + 100) return null
        return NATIVE_DOWNSAMPLE_RETRY_LONG_EDGE to blindSegments()
    }

    /**
     * 把基于缩放图（[fromW]×[fromH]）定标的求解结果换算回原图（[toW]×[toH]）。
     *
     * astro_bridge 的 CRPIX 恒为图像中心（w/2+0.5），整体缩放时中心对齐：
     *  - crpix 按放大比例（to/from）外推；
     *  - CD 矩阵与 pixscale 按缩小比例（from/to）换算（同一天体尺度在更多像素上，度/像素变小）；
     *  - 天球坐标（crval/ra/dec）与 parity/orientation 不变；
     *  - fieldRadiusArcmin 按新尺寸与新比例尺重算。
     */
    fun rescaleSolveFor(
        solve: SolveResult,
        fromW: Int,
        fromH: Int,
        toW: Int,
        toH: Int,
    ): SolveResult {
        val ps = solve.pixScaleArcsec * fromW.toDouble() / toW
        val wcs = solve.wcs?.rescaledFor(fromW, fromH, toW, toH)
        return SolveResult(
            raDeg = solve.raDeg,
            decDeg = solve.decDeg,
            pixScaleArcsec = ps,
            orientationDeg = solve.orientationDeg,
            parity = solve.parity,
            imageWidth = toW,
            imageHeight = toH,
            subId = solve.subId,
            fieldRadiusArcmin = Math.hypot(toW.toDouble(), toH.toDouble()) * ps / (2.0 * 60.0),
            nMatch = solve.nMatch,
            indexId = solve.indexId,
            logodds = solve.logodds,
            wcs = wcs,
        )
    }

    /**
     * 纯决策函数（JVM 可单测）：按模式与视场估计决定引擎执行顺序。
     *
     * - AUTO：广角 Hipparcos 亮星表优先 —— fov 已知且 ≥8°（星表适用范围）：
     *   先自研亮星表匹配，失败再官方引擎；窄场（<8°）亮星表自动跳过直接官方；
     *   >180° 仅亮星表；fov 未知 → 亮星表先试再官方（盲解分段限时）；
     *   有 Key 时最后追加在线。
     * - NATIVE_FIRST：官方 → 自研 → 在线（始终，保留旧策略可选）。
     * - OFFLINE_ONLY：AUTO 的本地部分（星表 + 官方），无在线。
     * - ONLINE_ONLY：仅在线（无 Key 时返回空列表，调用方给引导）。
     */
    fun planSteps(fovDeg: Double?, mode: EngineMode, hasApiKey: Boolean): List<SolveEngine> {
        val steps = ArrayList<SolveEngine>()
        fun localSteps() {
            val fov = fovDeg
            when {
                fov == null -> {
                    steps += SolveEngine.LOCAL_MATCHER
                    steps += SolveEngine.ASTROMETRY_NATIVE
                }
                fov in NATIVE_FOV_MIN_DEG..NATIVE_FOV_MAX_DEG -> {
                    if (fov >= LOCAL_FOV_MIN_DEG) steps += SolveEngine.LOCAL_MATCHER
                    steps += SolveEngine.ASTROMETRY_NATIVE
                }
                else -> steps += SolveEngine.LOCAL_MATCHER
            }
        }
        when (mode) {
            EngineMode.ONLINE_ONLY -> if (hasApiKey) steps += SolveEngine.ONLINE_NOVA
            EngineMode.OFFLINE_ONLY -> localSteps()
            EngineMode.NATIVE_FIRST -> {
                steps += SolveEngine.ASTROMETRY_NATIVE
                steps += SolveEngine.LOCAL_MATCHER
                if (hasApiKey) steps += SolveEngine.ONLINE_NOVA
            }
            EngineMode.AUTO -> {
                localSteps()
                if (hasApiKey) steps += SolveEngine.ONLINE_NOVA
            }
        }
        return steps
    }

    /**
     * 本地解是否可呈现：高内点直接接受；低内点用官方引擎复核（见
     * [crossCheckAccept]）。复核在自研视场 ±35% 内部署，官方引擎自身带
     * logodds≥25/nMatch≥2 门槛，10 秒上限，单次触发。
     */
    private fun acceptLocalSolve(
        context: Context,
        display: Bitmap,
        matched: LocalMatchResult,
    ): Boolean {
        if (matched.inlierCount >= CROSS_CHECK_MIN_INLIERS) return true
        val fov = maxOf(matched.solve.fieldWidthDeg, matched.solve.fieldHeightDeg)
        val (lo, hi) = FovEstimate.fovRange(fov)
        val native = try {
            StellarSolverNative.solveBitmap(context, display, lo, hi, CROSS_CHECK_TIME_LIMIT_SEC)
        } catch (t: Throwable) {
            null
        }
        return crossCheckAccept(
            matched.solve.raDeg, matched.solve.decDeg, fov, matched.inlierCount, native,
        )
    }

    /**
     * 从图片文件读取 EXIF 视场估计（度）；无焦距信息返回 null */
    fun readFovDeg(imagePath: String): Double? {
        return try {
            val exif = ExifInterface(imagePath)
            val focal35 = exif.getAttributeDouble(
                ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0.0,
            )
            val portrait = FovEstimate.isPortrait(
                exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL),
            )
            FovEstimate.fovDegFromFocal35(focal35, portrait)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 执行识别：按 [SettingsRepository.engineMode] 依次尝试各引擎，
     * 返回第一个成功的 [EngineResult]；全部失败返回 null 并通过
     * [onDiagnostics] 回调失败诊断（检出星点/引擎轨迹，§0.33）。
     * [onStarsDetected] 在本地提星完成后立即回调（成功/失败都会触发），
     * 供 UI 在加载阶段实时预览已检测到的星点（§0.36）。
     * [onProgress] 在 UI 线程回调进度文案。
     * 注：onDiagnostics/onStarsDetected 必须在 onProgress 之前（尾 lambda
     * 绑定最后一个函数类型参数，旧调用 AutoTest/Batch 的尾 lambda 是 onProgress）。
     */
    suspend fun solve(
        context: Context,
        display: Bitmap,
        imagePath: String,
        settings: SettingsRepository,
        onDiagnostics: (SolveDiagnostics) -> Unit = {},
        onStarsDetected: (List<SolveDiagnostics.DiagStar>) -> Unit = {},
        onProgress: (String) -> Unit,
    ): EngineResult? = withContext(Dispatchers.IO) {
        val fov = readFovDeg(imagePath)
        val steps = planSteps(fov, settings.engineMode, settings.hasApiKey)
        if (steps.isEmpty()) return@withContext null

        var currentDisplay = display
        val enginesTried = ArrayList<String>()
        var lastLocalStars: List<DetectedStar>? = null
        // 本地弱解被官方复核拒绝后，用自研视场作为官方流程的 scale 先验
        // （避免复核不一致后再走 12s+16s 盲解两段，§0.33.3 性能修复）
        var fovOverride: Double? = null
        for (engine in steps) {
            when (engine) {
                SolveEngine.ASTROMETRY_NATIVE -> {
                    enginesTried.add("官方引擎")
                    // 先验优化：EXIF 焦距 → scale 区间；EXIF GPS+时间 → 天顶 RA/Dec 天区
                    // （官方 solver_set_radec 通道，把全天空盲解缩小到可见天区）
                    val priors = try {
                        ExifPriorsReader.read(imagePath)
                    } catch (e: Throwable) {
                        ExifPriors(null, null, null, null, false)
                    }
                    var nativeSolve: SolveResult? = null
                    var nativeDetail = ""
                    // 自研弱解被复核拒绝后，官方流程直接用自研视场作 scale 先验
                    // （§0.33.3：避免复核不一致后再走 12s+16s 盲解两段）
                    val effFov = fovOverride ?: fov

                    // 1) 传感器粗定标先验轮（若开启且有姿态缓存）：以相机指向为中心 ±25° 快速求解
                    val sensorHint = if (settings.sensorAssistedPointing) {
                        PointingHintStore.get(imagePath)
                    } else null

                    if (sensorHint != null) {
                        val (lo, hi) = if (effFov != null) {
                            FovEstimate.fovRange(effFov)
                        } else {
                            0.1 to 180.0
                        }
                        val isEn = com.starcam.astro.ui.theme.LocaleState.isEnglish
                        onProgress(
                            if (isEn) {
                                "Astrometry engine solving (Sensor prior: within %.0f°)…".format(sensorHint.radiusDeg)
                            } else {
                                "官方引擎求解中（传感器粗定标：%.0f° 范围内）…".format(sensorHint.radiusDeg)
                            }
                        )
                        nativeSolve = try {
                            StellarSolverNative.solveBitmapPriors(
                                context, currentDisplay, lo, hi,
                                sensorHint.raDeg, sensorHint.decDeg, sensorHint.radiusDeg,
                                NATIVE_TIME_LIMIT_KNOWN_FOV * 0.7,
                            )
                        } catch (e: Throwable) {
                            null
                        }
                        if (nativeSolve != null) {
                            nativeDetail = if (isEn) {
                                "Sensor Prior (within %.0f°) · FOV %.1f°–%.1f°".format(sensorHint.radiusDeg, lo, hi)
                            } else {
                                "传感器粗定标（%.0f° 内）· 视场 %.1f°–%.1f°".format(sensorHint.radiusDeg, lo, hi)
                            }
                        }
                    }

                    // 2) 天区先验轮（无传感器先验但 GPS+时间齐全时）：天顶为中心 ± 半径，先试快解
                    val zenith = priors.zenith
                    if (nativeSolve == null && zenith != null) {
                        val zenithRa = zenith.first
                        val zenithDec = zenith.second
                        val radius = ExifPriorsReader.skyPriorRadiusDeg(effFov)
                        val (lo, hi) = if (effFov != null) {
                            FovEstimate.fovRange(effFov)
                        } else {
                            0.1 to 180.0 // 尺度未知：交给官方引擎全范围搜（天区已缩小）
                        }
                        onProgress(
                            "官方引擎求解中（天区先验：天顶 %.0f° 范围内）…".format(radius),
                        )
                        nativeSolve = try {
                            StellarSolverNative.solveBitmapPriors(
                                context, currentDisplay, lo, hi,
                                zenithRa, zenithDec, radius,
                                NATIVE_TIME_LIMIT_KNOWN_FOV * 0.7,
                            )
                        } catch (e: Throwable) {
                            null
                        }
                        if (nativeSolve != null) {
                            nativeDetail =
                                "天区先验（天顶 %.0f° 内）· 视场 %.1f°–%.1f°".format(
                                    radius, lo, hi,
                                )
                        }
                    }

                    // 3) 原有计划（scale 直传 / 分段盲解），保证成功率不降
                    if (nativeSolve == null) {
                        val nativePlan = if (effFov != null) {
                            val (lo, hi) = FovEstimate.fovRange(effFov)
                            listOf(Triple(lo, hi, NATIVE_TIME_LIMIT_KNOWN_FOV))
                        } else {
                            blindSegments()
                        }
                        for ((index, seg) in nativePlan.withIndex()) {
                            val (lo, hi, timeLimit) = seg
                            onProgress(
                                "官方引擎求解中（视场 %.1f°–%.1f°，第 %d/%d 段，约 %.0f 秒）…"
                                    .format(lo, hi, index + 1, nativePlan.size, timeLimit),
                            )
                            nativeSolve = try {
                                StellarSolverNative.solveBitmap(context, currentDisplay, lo, hi, timeLimit)
                            } catch (e: Throwable) {
                                null
                            }
                            if (nativeSolve != null) {
                                nativeDetail = "视场 %.1f°–%.1f°".format(lo, hi)
                                break
                            }
                        }
                    }

                    // 3) 降采样重试轮（仅盲解失败后）：服务器实测 5057 变焦图
                    //    需 --downsample 2 才解出——降采样归一化大星点，quad 更稳。
                    //    求解基于缩小图，成功后把 WCS 换算回原图坐标系供叠加渲染。
                    if (nativeSolve == null) {
                        val origW = currentDisplay.width
                        val origH = currentDisplay.height
                        val longEdge = maxOf(origW, origH)
                        val retry = downsampleRetryPlan(effFov, longEdge)
                        if (retry != null) {
                            val (edge, plan) = retry
                            val scaled = try {
                                val factor = edge.toFloat() / longEdge
                                Bitmap.createScaledBitmap(
                                    currentDisplay,
                                    maxOf(1, (origW * factor).toInt()),
                                    maxOf(1, (origH * factor).toInt()),
                                    true,
                                )
                            } catch (e: Throwable) {
                                null
                            }
                            if (scaled != null) {
                                onProgress(
                                    "官方引擎降采样重试（${scaled.width}px 长边）…",
                                )
                                for ((lo, hi, _) in plan) {
                                    nativeSolve = try {
                                        StellarSolverNative.solveBitmap(
                                            context, scaled, lo, hi, NATIVE_DOWNSAMPLE_RETRY_TIME,
                                        )
                                    } catch (e: Throwable) {
                                        null
                                    }
                                    if (nativeSolve != null) {
                                        nativeSolve = rescaleSolveFor(
                                            nativeSolve, scaled.width, scaled.height, origW, origH,
                                        )
                                        nativeDetail = "降采样重试（视场 %.1f°–%.1f°）".format(lo, hi)
                                        break
                                    }
                                }
                                scaled.recycle()
                            }
                        }
                    }
                    if (nativeSolve != null) {
                        val detail = buildString {
                            append(nativeDetail)
                            nativeSolve.indexId?.let { append(" · index-$it") }
                            nativeSolve.nMatch?.let { append(" · 匹配 $it 星") }
                        }
                        return@withContext EngineResult(nativeSolve, engine, detail, currentDisplay)
                    }
                }

                SolveEngine.LOCAL_MATCHER -> {
                    enginesTried.add("内置星表")
                    onProgress("本地星表识别中（内置 ${StarCatalogData.stars.size} 颗亮星）…")
                    val stars = try {
                        // SEP 弱星提取优先（分块背景估计+卷积滤波，检出能力更强），
                        // 2σ 阈值星太少时降级 1.5σ；原生库不可用回退 box-blur 检测器
                        fun sepStars(sigma: Double): List<DetectedStar>? = try {
                            StellarSolverNative.sepDetectStars(currentDisplay, sigma, 200)
                        } catch (e: Throwable) {
                            null
                        }
                        var s = sepStars(2.0)
                        if (s == null || s.size < 5) {
                            val looser = sepStars(1.5)
                            if (looser != null && looser.size > (s?.size ?: 0)) s = looser
                        }
                        if (s == null || s.size < 5) {
                            s = try {
                                LocalStarMatcher.detectStars(currentDisplay)
                            } catch (e: Throwable) {
                                null
                            }
                        }
                        s?.takeIf { it.isNotEmpty() }?.let { det ->
                            lastLocalStars = det
                            val maxB = det.maxOfOrNull { it.brightness } ?: 0f
                            onStarsDetected(
                                det.map { st ->
                                    SolveDiagnostics.DiagStar(
                                        x = st.x / currentDisplay.width.toFloat(),
                                        y = st.y / currentDisplay.height.toFloat(),
                                        brightness01 = if (maxB > 0f) {
                                            (st.brightness / maxB).coerceIn(0f, 1f)
                                        } else 0.5f,
                                    )
                                },
                            )
                            det // 保持 try 块返回 List<DetectedStar>?
                        }
                    } catch (e: Throwable) {
                        null
                    }
                    val matched = if (stars != null && stars.size >= 5) {
                        try {
                            val hint = if (settings.sensorAssistedPointing) {
                                PointingHintStore.get(imagePath)
                            } else null
                            LocalStarMatcher.match(stars, currentDisplay.width, currentDisplay.height, hint)
                        } catch (e: Throwable) {
                            null
                        }
                    } else null
                    if (matched != null && acceptLocalSolve(context, currentDisplay, matched)) {
                        val detail = "内置星表 · 内点 ${matched.inlierCount} 颗"
                        return@withContext EngineResult(matched.solve, engine, detail, currentDisplay)
                    }
                    // 低内点解被官方复核拒绝 → 不呈现；把自研视场传给官方流程作
                    // scale 先验（§0.33.3），避免盲解两段拖长等待
                    if (matched != null) {
                        fovOverride = maxOf(
                            matched.solve.fieldWidthDeg, matched.solve.fieldHeightDeg,
                        )
                    }
                }

                SolveEngine.ONLINE_NOVA -> {
                    val key = settings.apiKey
                    if (key.isBlank()) continue
                    enginesTried.add("在线识别")
                    onProgress("本地引擎未匹配，正在上传在线求解…")
                    val upload = File(context.cacheDir, "upload_${System.currentTimeMillis()}.jpg")
                    try {
                        val ok = ImageUtils.compressForUpload(imagePath, upload) != null
                        if (!ok) continue
                        // 用上传后的图片作为渲染基准，保证像素坐标与求解结果一致
                        val onlineDisplay = ImageUtils.decodeSampledBitmap(upload.absolutePath, 2200)
                            ?: continue
                        currentDisplay = onlineDisplay
                        val client = PlateSolveClient(settings.serverUrl)
                        val solve = client.solve(upload, key) { msg -> onProgress(msg) }
                        val detail = listOfNotNull(
                            solve.subId?.let { "任务 #$it" },
                            "在线定标",
                        ).joinToString(" · ")
                        return@withContext EngineResult(solve, engine, detail, currentDisplay)
                    } finally {
                        // 仅在内存中保留渲染基准；上传副本不应继续留在缓存目录。
                        upload.delete()
                    }
                }
            }

            // 三层引擎全部失败 → 输出失败诊断（§0.33）：检出星点 + 引擎轨迹，
            // 供结果页把"为什么没认出来"可视化给用户
            val diagDisplay = currentDisplay
            val last = lastLocalStars
            val maxB = last?.maxOfOrNull { it.brightness } ?: 0f
            onDiagnostics(
                SolveDiagnostics(
                    starCount = last?.size ?: 0,
                    stars = (last ?: emptyList()).map { s ->
                        SolveDiagnostics.DiagStar(
                            x = s.x / diagDisplay.width.toFloat(),
                            y = s.y / diagDisplay.height.toFloat(),
                            brightness01 = if (maxB > 0f) {
                                (s.brightness / maxB).coerceIn(0f, 1f)
                            } else 0.5f,
                        )
                    },
                    enginesTried = enginesTried,
                ),
            )
            null
        }
    }
