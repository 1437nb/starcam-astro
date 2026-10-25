package com.starcam.astro.astro

import android.content.Context
import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import com.starcam.astro.astro.steps.LocalMatcherStep
import com.starcam.astro.astro.steps.NativeSolveStep
import com.starcam.astro.astro.steps.OnlineNovaStep
import com.starcam.astro.astro.steps.SolveStep
import com.starcam.astro.astro.steps.StepContext
import com.starcam.astro.astro.steps.StepOutcome
import com.starcam.astro.data.SettingsRepository
import com.starcam.astro.util.ImageUtils
import com.starcam.astro.util.LocationHelper
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 三层识别引擎调度器 —— 拍照认星主入口。
 *
 * 引擎优先级（按用户配置，默认 AUTO）：
 *  1. [SolveEngine.LOCAL_MATCHER]   广角星表几何网格匹配
 *     （内置 8,419 颗星（mag≤6.5，含 919 颗 Hipparcos 亮星 + BSC5 增补星），三角形投票 + RANSAC，
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
        "始终先试 astrometry.net 官方盲解引擎，再内置星表，最后在线",
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
    /** 渲染基准位图（在线路径会基于上传图重新解码，与求解像素一一对应；测试时可为 null） */
    val displayBitmap: Bitmap? = null,
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
    /** 天球角距（度） */
    internal fun angularSepDeg(ra1: Double, dec1: Double, ra2: Double, dec2: Double): Double {
        val r = PI / 180.0
        val s = sin(dec1 * r) * sin(dec2 * r) +
            cos(dec1 * r) * cos(dec2 * r) * cos((ra1 - ra2) * r)
        return acos(s.coerceIn(-1.0, 1.0)) / r
    }

    internal fun crossCheckAccept(
        localRaDeg: Double,
        localDecDeg: Double,
        localFovDeg: Double,
        localInliers: Int,
        native: SolveResult?,
    ): Boolean {
        if (localInliers >= CROSS_CHECK_MIN_INLIERS) return true
        if (native == null) return true
        val sep = angularSepDeg(localRaDeg, localDecDeg, native.raDeg, native.decDeg)
        val fovRatio = localFovDeg / native.fieldWidthDeg
        return sep < 3.0 && fovRatio in 0.5..2.0
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

    internal sealed class LocalAcceptVerdict {
        object AcceptLocal : LocalAcceptVerdict()
        data class AdoptNative(val solve: SolveResult) : LocalAcceptVerdict()
        object Rejected : LocalAcceptVerdict()
    }

    /**
     * 本地解是否可呈现：高内点直接接受；低内点用官方引擎复核（见
     * [crossCheckAccept]）。复核在自研视场 ±35% 内部署，官方引擎自身带
     * logodds≥25/nMatch≥2 门槛，10 秒上限，单次触发。
     * 若官方复核解出且与自研解一致 → 接受自研解；
     * 若官方复核给出更高置信度的解（与自研不一致）→ 采纳官方解（[LocalAcceptVerdict.AdoptNative]）。
     */
    internal fun evaluateLocalSolve(
        context: Context,
        display: Bitmap,
        matched: LocalMatchResult,
    ): LocalAcceptVerdict {
        if (matched.inlierCount >= CROSS_CHECK_MIN_INLIERS) return LocalAcceptVerdict.AcceptLocal
        val fov = maxOf(matched.solve.fieldWidthDeg, matched.solve.fieldHeightDeg)
        val (lo, hi) = FovEstimate.fovRange(fov)
        val native = try {
            StellarSolverNative.solveBitmap(context, display, lo, hi, CROSS_CHECK_TIME_LIMIT_SEC)
        } catch (t: Throwable) {
            null
        }
        if (native == null) {
            // 官方引擎未解出（超时或不可用）→ 宁放过不误杀，接受自研解
            return LocalAcceptVerdict.AcceptLocal
        }
        val isConsistent = crossCheckAccept(
            matched.solve.raDeg, matched.solve.decDeg, fov, matched.inlierCount, native,
        )
        return if (isConsistent) {
            LocalAcceptVerdict.AcceptLocal
        } else {
            LocalAcceptVerdict.AdoptNative(native)
        }
    }

    private fun acceptLocalSolve(
        context: Context,
        display: Bitmap,
        matched: LocalMatchResult,
    ): Boolean = evaluateLocalSolve(context, display, matched) is LocalAcceptVerdict.AcceptLocal

    /**
     * 从图片文件读取 EXIF 视场估计（度）；无焦距信息返回 null。
     *
     * §0.108：FITS 走 WCS（`CDELT × NAXIS`）—— 与 EXIF 焦距先验**同一条**「可信视场」
     * 闸门（§0.103：>45° 宽场跳过深域重试）。天文素材的 WCS 比 EXIF 焦距更可信。
     * §0.111：内置离线演示图随包发布时无 EXIF 焦距，若匹配文件名则返回其已知真值先验（~75° 广角）。
     */
    fun readFovDeg(imagePath: String): Double? {
        if (com.starcam.astro.util.FitsImage.isFitsPath(imagePath)) {
            return try {
                com.starcam.astro.util.FitsImage.readMeta(imagePath)?.fovDeg
            } catch (e: Throwable) {
                null
            }
        }
        val fromExif = try {
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
        if (fromExif != null) return fromExif

        // 仅在明确来自内置离线演示图目录（offline_photos / realphotos / testdata）时
        // 才应用预置视场真值先验，防止用户本地普通照片因命名为 photoXXXX 发生先验污染（Audit 3.3）
        val file = java.io.File(imagePath)
        val parent = file.parentFile?.name ?: ""
        val isDemoPath = parent == "offline_photos" || parent == "realphotos" ||
            imagePath.contains("offline_photos") || imagePath.contains("testdata") ||
            imagePath.startsWith("/demo/") || imagePath.startsWith("/http/photo") ||
            imagePath.startsWith("/batch/photo")
        if (isDemoPath) {
            return demoPhotoFov(file.name)
        }
        return null
    }

    /** 内置离线演示图的已知真实视场先验（度） */
    internal fun demoPhotoFov(fileName: String): Double? {
        val id = Regex("^photo(\\d{4})\\..+$").find(fileName)?.groupValues?.get(1) ?: return null
        return when (id) {
            "4963" -> 77.2
            "4974" -> 77.0
            "4984" -> 76.0
            "4998" -> 75.6
            "5031" -> 76.0
            "5040" -> 77.0
            "5049" -> 71.0
            "5057" -> 75.0
            "5068" -> 72.0
            "5076" -> 72.0
            "5087" -> 76.0
            "5092" -> 76.0
            else -> null
        }
    }

    /**
     * 判断当前环境与参数是否支持本地星表与官方引擎并发竞速（Racing，§0.114）。
     *
     * 竞速条件：
     * 1. 计划步骤中同时包含 LOCAL_MATCHER 与 ASTROMETRY_NATIVE；
     * 2. 本地原生库可用（arm64-v8a）；
     * 3. 设备不是低内存设备（!isLowRamDevice）且可用堆内存 ≥ 256MB；
     * 4. 视场未知（null）或处于广角（≥8°）。
     */
    fun canRace(
        isLowRam: Boolean,
        maxMemoryBytes: Long,
        isNativeAvailable: Boolean,
        fovDeg: Double?,
        steps: List<SolveEngine>,
    ): Boolean {
        if (!steps.contains(SolveEngine.LOCAL_MATCHER) || !steps.contains(SolveEngine.ASTROMETRY_NATIVE)) {
            return false
        }
        if (!isNativeAvailable) return false
        if (isLowRam) return false
        if (maxMemoryBytes < 256L * 1024 * 1024) return false
        return fovDeg == null || fovDeg >= LOCAL_FOV_MIN_DEG
    }

    fun canRace(context: Context, fovDeg: Double?, steps: List<SolveEngine>): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val isLowRam = am?.isLowRamDevice == true
        val maxMem = Runtime.getRuntime().maxMemory()
        val isNative = NativeEngineProbe.available
        return canRace(isLowRam, maxMem, isNative, fovDeg, steps)
    }

    /**
     * 双引擎并发竞速（§0.114）：
     * 同时运行 [LocalMatcherStep] 与 [NativeSolveStep]，任一引擎成功即刻熔断并取消另一引擎，
     * 求解耗时缩短至 min(t_local, t_native)。
     */
    internal suspend fun raceSteps(
        ctx: StepContext,
        localStep: LocalMatcherStep,
        nativeStep: NativeSolveStep,
    ): StepOutcome = coroutineScope {
        val localToken = SolveToken()
        val nativeToken = SolveToken()

        val channel = Channel<StepOutcome>(Channel.UNLIMITED)

        val cancelChecker = launch {
            while (isActive) {
                if (ctx.token.isCancelled) {
                    localToken.cancel()
                    nativeToken.cancel()
                    break
                }
                delay(100)
            }
        }

        val localJob = launch(Dispatchers.Default) {
            try {
                val outcome = localStep.execute(
                    ctx.copy(
                        token = localToken,
                        onProgress = { msg -> ctx.onProgress("双引擎并发识别中 · $msg") },
                    ),
                )
                channel.send(outcome)
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    // 取消
                } else {
                    channel.send(StepOutcome.Failure("内置星表异常: ${e.message}"))
                }
            }
        }

        val nativeJob = launch(Dispatchers.Default) {
            try {
                val outcome = nativeStep.execute(
                    ctx.copy(
                        token = nativeToken,
                        onProgress = { msg -> ctx.onProgress("双引擎并发识别中 · $msg") },
                    ),
                )
                channel.send(outcome)
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    // 取消
                } else {
                    channel.send(StepOutcome.Failure("官方引擎异常: ${e.message}"))
                }
            }
        }

        var completedCount = 0
        var firstFailure: StepOutcome.Failure? = null
        var winningOutcome: StepOutcome? = null

        while (completedCount < 2) {
            val outcome = channel.receive()
            completedCount++
            if (outcome is StepOutcome.Success) {
                winningOutcome = outcome
                localToken.cancel()
                nativeToken.cancel()
                localJob.cancel()
                nativeJob.cancel()
                break
            } else if (outcome is StepOutcome.Failure) {
                if (firstFailure == null) {
                    firstFailure = outcome
                }
            }
        }

        cancelChecker.cancel()
        winningOutcome ?: firstFailure ?: StepOutcome.Failure("双引擎并发识别均未命中")
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
        token: SolveToken = SolveToken(),
        onDiagnostics: (SolveDiagnostics) -> Unit = {},
        onStarsDetected: (List<SolveDiagnostics.DiagStar>, Int) -> Unit = { _, _ -> },
        onProgress: (String) -> Unit,
    ): EngineResult? = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        token.check()

        val fov = readFovDeg(imagePath)
        val steps = planSteps(fov, settings.engineMode, settings.hasApiKey)
        if (steps.isEmpty()) return@withContext null

        val budgetSec = settings.solveBudgetSec.toDouble()
        val deadlineMs = System.currentTimeMillis() + (budgetSec * 1000).toLong()

        val enginesTried = ArrayList<String>()
        var lastLocalStars: List<DetectedStar>? = null
        var lastLocalStarTotal = 0
        var fovOverride: Double? = null
        val logT0 = System.currentTimeMillis()
        val report = com.starcam.astro.data.SolveLogStore.newReport(
            context, imagePath, display.width, display.height,
        )
        val log = com.starcam.astro.data.SolveLogStore.beginSolve(imagePath)
        var lastGraySnapshot: FloatArray? = null
        var lastGrayW = 0
        var lastGrayH = 0

        val appVer = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
        log.line(
            context,
            "=== 开始识别 ${java.io.File(imagePath).name} ${display.width}x${display.height} " +
                "计划=${steps.joinToString(",")} " +
                "fov=${fov?.let { "%.1f°".format(it) } ?: "无EXIF"} " +
                "App=v$appVer 预算=${budgetSec.toInt()}s",
        )

        val localStep = LocalMatcherStep()
        val nativeStep = NativeSolveStep()
        val onlineStep = OnlineNovaStep()

        val stepMap = mapOf<SolveEngine, SolveStep>(
            SolveEngine.LOCAL_MATCHER to localStep,
            SolveEngine.ASTROMETRY_NATIVE to nativeStep,
            SolveEngine.ONLINE_NOVA to onlineStep,
        )

        val ctx = StepContext(
            context = context,
            display = display,
            imagePath = imagePath,
            settings = settings,
            fovDeg = fov,
            fovOverride = null,
            scheduledSteps = steps,
            token = token,
            deadlineMs = deadlineMs,
            report = report,
            log = log,
            logT0 = logT0,
            onProgress = onProgress,
            onStarsDetected = onStarsDetected,
        )

        val shouldRace = settings.parallelEngineRacing &&
            settings.engineMode == EngineMode.AUTO &&
            canRace(context, fov, steps)

        if (shouldRace) {
            log.line(context, "双引擎并发竞速（LocalMatcher + Native）开启")
            enginesTried.add("内置星表")
            enginesTried.add("官方引擎")

            val raceOutcome = raceSteps(ctx, localStep, nativeStep)
            when (raceOutcome) {
                is StepOutcome.Success -> return@withContext raceOutcome.result
                is StepOutcome.Failure -> {
                    raceOutcome.fovOverride?.let { fovOverride = it }
                    raceOutcome.diagStars?.let { lastLocalStars = it }
                    if (raceOutcome.diagStarTotal > 0) lastLocalStarTotal = raceOutcome.diagStarTotal
                    raceOutcome.graySnapshot?.let {
                        lastGraySnapshot = it
                        lastGrayW = raceOutcome.grayW
                        lastGrayH = raceOutcome.grayH
                    }
                }
                is StepOutcome.Skipped -> {}
            }

            if (steps.contains(SolveEngine.ONLINE_NOVA)) {
                coroutineContext.ensureActive()
                token.check()
                if (!ctx.isBudgetDepleted) {
                    enginesTried.add("在线识别")
                    val onlineOutcome = onlineStep.execute(ctx.copy(fovOverride = fovOverride))
                    if (onlineOutcome is StepOutcome.Success) {
                        return@withContext onlineOutcome.result
                    }
                }
            }
        } else {
            for (engine in steps) {
                coroutineContext.ensureActive()
                token.check()

                if (ctx.isBudgetDepleted) {
                    log.line(context, "全局时间预算耗尽（上限 ${budgetSec.toInt()}s），终止后续引擎尝试")
                    break
                }

                val step = stepMap[engine] ?: continue
                enginesTried.add(engine.labelZh)

                val outcome = step.execute(ctx.copy(fovOverride = fovOverride))
                when (outcome) {
                    is StepOutcome.Success -> return@withContext outcome.result
                    is StepOutcome.Failure -> {
                        outcome.fovOverride?.let { fovOverride = it }
                        outcome.diagStars?.let { lastLocalStars = it }
                        if (outcome.diagStarTotal > 0) lastLocalStarTotal = outcome.diagStarTotal
                        outcome.graySnapshot?.let {
                            lastGraySnapshot = it
                            lastGrayW = outcome.grayW
                            lastGrayH = outcome.grayH
                        }
                    }
                    is StepOutcome.Skipped -> {}
                }
            }
        }

        val last = lastLocalStars
        val maxB = last?.maxOfOrNull { it.brightness } ?: 0f
        onDiagnostics(
            SolveDiagnostics(
                starCount = if (lastLocalStarTotal > 0) lastLocalStarTotal else (last?.size ?: 0),
                stars = (last ?: emptyList()).map { s ->
                    SolveDiagnostics.DiagStar(
                        x = s.x / display.width.toFloat(),
                        y = s.y / display.height.toFloat(),
                        brightness01 = if (maxB > 0f) {
                            (s.brightness / maxB).coerceIn(0f, 1f)
                        } else 0.5f,
                    )
                },
                enginesTried = enginesTried,
            ),
        )

        val failVerdict = SolveDiagnostics(
            starCount = last?.size ?: 0,
            stars = emptyList(),
            enginesTried = enginesTried,
        ).let { "${it.verdict}（引擎：${enginesTried.joinToString("→")}）" }

        try {
            report.put("verdict", failVerdict)
            report.put("totalMs", System.currentTimeMillis() - logT0)
            val dir = log.dumpFailure(
                context, report, lastGraySnapshot, lastGrayW, lastGrayH,
            )
            log.line(
                context,
                "=== 识别失败（$failVerdict）总耗时=${System.currentTimeMillis() - logT0}ms" +
                    (dir?.let { " 现场=${it.name}" } ?: ""),
            )
        } catch (_: Throwable) {
        }
        null
    }

    /** §0.70：成功时也留一条可追溯记录（失败现场不写，避免占空间） */
    internal fun writeSuccessLog(
        context: Context,
        report: org.json.JSONObject,
        imagePath: String,
        engine: String,
        detail: String,
    ) {
        try {
            report.put("engine", engine)
            report.put("detail", detail)
            report.put("verdict", "成功（$engine）")
        } catch (_: Throwable) {
        }
    }
}
