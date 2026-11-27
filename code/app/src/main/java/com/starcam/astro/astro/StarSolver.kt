package com.starcam.astro.astro

import android.content.Context
import android.graphics.Bitmap
import com.starcam.astro.astro.steps.LocalMatcherStep
import com.starcam.astro.astro.steps.NativeSolveStep
import com.starcam.astro.astro.steps.OnlineNovaStep
import com.starcam.astro.astro.steps.SolveStep
import com.starcam.astro.astro.steps.StepContext
import com.starcam.astro.astro.steps.StepOutcome
import com.starcam.astro.data.SettingsRepository
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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

    /** 在线 nova.astrometry.net 最低独立时间预算（秒），避免默认 30s 本地预算掐断云端排队与解算 */
    const val ONLINE_MIN_BUDGET_SEC = 90

    /** 盲解（无 EXIF 先验）分段试探参数：窄-中场段（度，限时秒） */
    const val NATIVE_BLIND_SEG_LO_DEG = 3.0
    const val NATIVE_BLIND_SEG_MID_DEG = 40.0
    const val NATIVE_BLIND_SEG_MID_TIME = 12.0

    /** 盲解（无 EXIF 先验）分段试探参数：中-宽场段（度，限时秒） */
    const val NATIVE_BLIND_SEG_WIDE_DEG = 120.0
    const val NATIVE_BLIND_SEG_WIDE_TIME = 16.0

    /** 无 EXIF 视场先验时的官方引擎分段试探方案（实现见 [SolverPlanning.blindSegments]）。 */
    fun blindSegments(): List<Triple<Double, Double, Double>> = SolverPlanning.blindSegments()

    /** 降采样重试轮：目标长边像素 */
    const val NATIVE_DOWNSAMPLE_RETRY_LONG_EDGE = 1100

    /** 降采样重试轮：单段限时（秒，比首轮盲解段收紧，避免总预算失控） */
    const val NATIVE_DOWNSAMPLE_RETRY_TIME = 10.0

    /** 本地解官方复核（§0.32）：内点达到该值即视为高置信，跳过复核（省时） */
    const val CROSS_CHECK_MIN_INLIERS = 18

    /** 本地解官方复核：单次求解限时（秒，只在低内点时触发一次） */
    const val CROSS_CHECK_TIME_LIMIT_SEC = 10.0

    /** 天球角距（度） */
    internal fun angularSepDeg(ra1: Double, dec1: Double, ra2: Double, dec2: Double): Double =
        SolverPlanning.angularSepDeg(ra1, dec1, ra2, dec2)

    internal fun crossCheckAccept(
        localRaDeg: Double,
        localDecDeg: Double,
        localFovDeg: Double,
        localInliers: Int,
        native: SolveResult?,
    ): Boolean = SolverPlanning.crossCheckAccept(localRaDeg, localDecDeg, localFovDeg, localInliers, native)

    fun downsampleRetryPlan(
        fovDeg: Double?,
        longEdgePx: Int,
    ): Pair<Int, List<Triple<Double, Double, Double>>>? =
        SolverPlanning.downsampleRetryPlan(fovDeg, longEdgePx)

    fun rescaleSolveFor(
        solve: SolveResult,
        fromW: Int,
        fromH: Int,
        toW: Int,
        toH: Int,
    ): SolveResult = SolverPlanning.rescaleSolveFor(solve, fromW, fromH, toW, toH)

    fun planSteps(
        fovDeg: Double?,
        mode: EngineMode,
        hasApiKey: Boolean,
        nativeAvailable: Boolean = true,
    ): List<SolveEngine> = SolverPlanning.planSteps(fovDeg, mode, hasApiKey, nativeAvailable)

    internal sealed class LocalAcceptVerdict {
        object AcceptLocal : LocalAcceptVerdict()
        data class AdoptNative(val solve: SolveResult) : LocalAcceptVerdict()
        object Rejected : LocalAcceptVerdict()
    }

    /**
     * 本地解是否可呈现：高内点直接接受；低内点用官方引擎复核（见 [crossCheckAccept]）。
     */
    internal fun evaluateLocalSolve(
        context: Context,
        display: Bitmap,
        matched: LocalMatchResult,
        token: SolveToken? = null,
        remainingSec: Double = CROSS_CHECK_TIME_LIMIT_SEC,
    ): LocalAcceptVerdict {
        token?.check()
        if (matched.inlierCount >= CROSS_CHECK_MIN_INLIERS) return LocalAcceptVerdict.AcceptLocal
        if (remainingSec <= 0.5) return LocalAcceptVerdict.AcceptLocal
        val fov = maxOf(matched.solve.fieldWidthDeg, matched.solve.fieldHeightDeg)
        val (lo, hi) = FovEstimate.fovRange(fov)
        val native = try {
            StellarSolverNative.solveBitmap(context, display, lo, hi,
                minOf(CROSS_CHECK_TIME_LIMIT_SEC, remainingSec), token = token)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            null
        }
        if (native == null) {
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

    fun readFovDeg(imagePath: String): Double? = SolverPlanning.readFovDeg(imagePath)

    internal fun demoPhotoFov(fileName: String): Double? = SolverPlanning.demoPhotoFov(fileName)

    fun canRace(
        isLowRam: Boolean,
        maxMemoryBytes: Long,
        isNativeAvailable: Boolean,
        fovDeg: Double?,
        steps: List<SolveEngine>,
    ): Boolean = SolverPlanning.canRace(isLowRam, maxMemoryBytes, isNativeAvailable, fovDeg, steps)

    fun canRace(context: Context, fovDeg: Double?, steps: List<SolveEngine>): Boolean =
        SolverPlanning.canRace(context, fovDeg, steps)

    /**
     * 双引擎并发竞速（§0.114）：
     * 同时运行 [LocalMatcherStep] 与 [NativeSolveStep]，任一引擎成功即刻熔断并取消另一引擎，
     * 求解耗时缩短至 min(t_local, t_native)。
     */
    internal suspend fun raceSteps(
        ctx: StepContext,
        localStep: SolveStep,
        nativeStep: SolveStep,
    ): StepOutcome {
        val localReport = org.json.JSONObject(ctx.report.toString())
        val nativeReport = org.json.JSONObject(ctx.report.toString())
        val baseSteps = ctx.report.optJSONArray("steps")?.length() ?: 0
        val prefix = if (com.starcam.astro.ui.theme.LocaleState.isEnglish) "Parallel engine solving · " else "双引擎并发识别中 · "
        suspend fun execute(step: SolveStep, token: SolveToken, report: org.json.JSONObject): StepOutcome =
            step.execute(ctx.copy(
                token = token, report = report, recordSuccess = false,
                stepBudgetSec = null, isRacing = true,
                onProgress = { if (!token.isCancelled) ctx.onProgress("$prefix$it") },
                onPhase = { if (!token.isCancelled) ctx.onPhase(SolvePhase.RACING) },
                onStarsDetected = { stars, total -> if (!token.isCancelled) ctx.onStarsDetected(stars, total) },
            ))
        val outcome = try {
            raceEngineOutcomes(ctx.token,
                { execute(localStep, it, localReport) },
                { execute(nativeStep, it, nativeReport) }, sharedBitmap = ctx.display)
        } finally {
            val steps = ctx.report.optJSONArray("steps") ?: org.json.JSONArray().also { ctx.report.put("steps", it) }
            for (report in listOf(localReport, nativeReport)) {
                report.optJSONArray("steps")?.let { array ->
                    for (i in baseSteps until array.length()) steps.put(array.get(i))
                }
            }
            for (key in listOf("stars", "starCount")) {
                if (localReport.has(key)) ctx.report.put(key, localReport.get(key))
            }
        }
        if (outcome is StepOutcome.Success) {
            writeSuccessLog(ctx.context, ctx.report, ctx.imagePath, outcome.result.engine.labelZh, outcome.result.detail)
        }
        return outcome
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
        onPhase: (SolvePhase) -> Unit = {},
        onProgress: (String) -> Unit,
    ): EngineResult? {
        val localBudgetSec = settings.solveBudgetSec.toDouble()
        val onlineBudgetSec = SolverPlanning.onlineStepBudgetSec(settings.solveBudgetSec)
        val hasOnlinePotential = settings.hasApiKey && settings.engineMode != EngineMode.OFFLINE_ONLY
        val overallBudgetMs = when {
            settings.engineMode == EngineMode.ONLINE_ONLY -> (onlineBudgetSec * 1000.0).toLong()
            hasOnlinePotential -> ((localBudgetSec + onlineBudgetSec) * 1000.0).toLong()
            else -> (localBudgetSec * 1000.0).toLong()
        }
        val operationToken = SolveToken(token, overallBudgetMs)
        return withContext(Dispatchers.IO) {
            withSolveCancellation(operationToken) {
                solveInternal(context, display, imagePath, settings, operationToken,
                    onDiagnostics, onStarsDetected, onPhase, onProgress)
            }
        }
    }

    private suspend fun solveInternal(
        context: Context,
        display: Bitmap,
        imagePath: String,
        settings: SettingsRepository,
        token: SolveToken,
        onDiagnostics: (SolveDiagnostics) -> Unit,
        onStarsDetected: (List<SolveDiagnostics.DiagStar>, Int) -> Unit,
        onPhase: (SolvePhase) -> Unit,
        onProgress: (String) -> Unit,
    ): EngineResult? = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        token.check()

        val fov = readFovDeg(imagePath)
        val steps = planSteps(
            fovDeg = fov,
            mode = settings.engineMode,
            hasApiKey = settings.hasApiKey,
            nativeAvailable = NativeEngineProbe.available,
        )
        if (steps.isEmpty()) return@withContext null

        val budgetSec = settings.solveBudgetSec.toDouble()
        val onlineBudgetSec = SolverPlanning.onlineStepBudgetSec(settings.solveBudgetSec)
        val hasOnlineStep = steps.contains(SolveEngine.ONLINE_NOVA)
        val localStepsOnly = steps.filter { it != SolveEngine.ONLINE_NOVA }
        val localDeadlineMs = System.nanoTime() / 1_000_000 + (budgetSec * 1000).toLong()
        val localToken = SolveToken(token, (budgetSec * 1000).toLong())

        val enginesTried = ArrayList<String>()
        var lastLocalStars: List<DetectedStar>? = null
        var lastLocalStarTotal = 0
        var lastDetectedDiagStars: List<SolveDiagnostics.DiagStar>? = null
        var lastOnlineFailure: String? = null
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
                "App=v$appVer 本地预算=${budgetSec.toInt()}s" +
                (if (hasOnlineStep) " 在线预算=${onlineBudgetSec.toInt()}s" else ""),
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
            token = localToken,
            deadlineMs = localDeadlineMs,
            report = report,
            log = log,
            logT0 = logT0,
            onProgress = onProgress,
            onStarsDetected = { stars, total ->
                lastDetectedDiagStars = stars
                if (total > 0) lastLocalStarTotal = total
                onStarsDetected(stars, total)
            },
            onPhase = onPhase,
        )

        val shouldRace = settings.parallelEngineRacing &&
            settings.engineMode == EngineMode.AUTO &&
            canRace(context, fov, steps)

        var budgetExceededError: SolveBudgetExceeded? = null
        try {
            if (localStepsOnly.isNotEmpty()) {
                try {
                    if (shouldRace) {
                        log.line(context, "双引擎并发竞速（LocalMatcher + Native）开启")
                        enginesTried.add("内置星表")
                        enginesTried.add("官方引擎")

                        val raceTimeout = (localDeadlineMs - System.nanoTime() / 1_000_000).coerceAtLeast(1L)
                        val raceOutcome = kotlinx.coroutines.withTimeoutOrNull(raceTimeout) {
                            raceSteps(ctx, localStep, nativeStep)
                        } ?: run {
                            log.line(context, "双引擎并发竞速超时（耗尽本地预算 ${raceTimeout}ms）")
                            StepOutcome.Failure(reason = "竞速超时")
                        }
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
                    } else {
                        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                        val isLowRam = am?.isLowRamDevice == true
                        val maxMemMb = Runtime.getRuntime().maxMemory() / (1024 * 1024)
                        log.line(
                            context,
                            "串行调度（racing=${settings.parallelEngineRacing}, canRace=false, " +
                                "mode=${settings.engineMode.key}, maxMem=${maxMemMb}MB, lowRam=$isLowRam, native=${NativeEngineProbe.available}）",
                        )
                        for ((stepIdx, engine) in localStepsOnly.withIndex()) {
                            coroutineContext.ensureActive()
                            localToken.check()

                            if (ctx.isBudgetDepleted) {
                                log.line(context, "本地时间预算耗尽（上限 ${budgetSec.toInt()}s），终止后续本地引擎尝试")
                                break
                            }

                            val step = stepMap[engine] ?: continue
                            enginesTried.add(engine.labelZh)

                            val hasNativeFollowUp = (stepIdx + 1 until localStepsOnly.size).any {
                                localStepsOnly[it] == SolveEngine.ASTROMETRY_NATIVE
                            }
                            val allocatedBudgetSec = if (engine == SolveEngine.LOCAL_MATCHER && hasNativeFollowUp) {
                                SolverPlanning.localStepBudgetSec(budgetSec, fov, hasNativeFollowUp = true)
                            } else {
                                null
                            }
                            val stepStartMs = System.currentTimeMillis() - logT0
                            log.line(
                                context,
                                "调度：开始 ${engine.labelZh}（offset=${stepStartMs}ms，本地剩余=${"%.1f".format(ctx.remainingSec)}s" +
                                    (allocatedBudgetSec?.let { "，步骤上限=${"%.1f".format(it)}s" } ?: "") + "）",
                            )

                            val stepTimeout = (localDeadlineMs - System.nanoTime() / 1_000_000).coerceAtLeast(1L)
                            val outcome = kotlinx.coroutines.withTimeoutOrNull(stepTimeout) {
                                step.execute(
                                    ctx.copy(
                                        fovOverride = fovOverride,
                                        stepBudgetSec = allocatedBudgetSec,
                                        isRacing = false,
                                    ),
                                )
                            } ?: run {
                                log.line(context, "${engine.labelZh}：单步执行超时（耗尽本地预算 ${stepTimeout}ms），跳过")
                                StepOutcome.Failure(reason = "单步超过预算")
                            }
                            val stepElapsedMs = (System.currentTimeMillis() - logT0) - stepStartMs
                            val outcomeTag = when (outcome) {
                                is StepOutcome.Success -> "success"
                                is StepOutcome.Failure -> "failure(${outcome.reason.take(40)})"
                                is StepOutcome.Skipped -> "skipped(${outcome.reason})"
                            }
                            log.line(
                                context,
                                "调度：结束 ${engine.labelZh}（耗时=${stepElapsedMs}ms，本地剩余=${"%.1f".format(ctx.remainingSec)}s，结果=$outcomeTag）",
                            )
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
                } catch (e: SolveBudgetExceeded) {
                    if (hasOnlineStep) {
                        log.line(context, "本地引擎耗尽 ${budgetSec.toInt()}s 预算，转入在线识别兜底（独立预算 ${onlineBudgetSec.toInt()}s）")
                    } else {
                        throw e
                    }
                }
            }

            if (hasOnlineStep) {
                coroutineContext.ensureActive()
                token.check()
                val onlineBudgetMs = (onlineBudgetSec * 1000.0).toLong().coerceAtLeast(1000L)
                val onlineDeadlineMs = System.nanoTime() / 1_000_000 + onlineBudgetMs
                val onlineToken = SolveToken(token, onlineBudgetMs)
                val stepStartMs = System.currentTimeMillis() - logT0
                enginesTried.add(SolveEngine.ONLINE_NOVA.labelZh)
                log.line(
                    context,
                    "调度：开始 ${SolveEngine.ONLINE_NOVA.labelZh}（offset=${stepStartMs}ms，在线独立预算=${onlineBudgetSec.toInt()}s）",
                )
                val onlineOutcome = kotlinx.coroutines.withTimeoutOrNull(onlineBudgetMs) {
                    onlineStep.execute(
                        ctx.copy(
                            token = onlineToken,
                            deadlineMs = onlineDeadlineMs,
                            fovOverride = fovOverride,
                            stepBudgetSec = onlineBudgetSec,
                            isRacing = false,
                        ),
                    )
                } ?: run {
                    val timeoutMsg = if (com.starcam.astro.ui.theme.LocaleState.isEnglish) {
                        "Online solve timed out (${onlineBudgetSec.toInt()}s)"
                    } else {
                        "在线识别超时（${onlineBudgetSec.toInt()} 秒）"
                    }
                    log.line(context, "在线识别超时（耗尽在线预算 ${onlineBudgetMs}ms）")
                    StepOutcome.Failure(reason = timeoutMsg)
                }
                val stepElapsedMs = (System.currentTimeMillis() - logT0) - stepStartMs
                val outcomeTag = when (onlineOutcome) {
                    is StepOutcome.Success -> "success"
                    is StepOutcome.Failure -> "failure(${onlineOutcome.reason.take(60)})"
                    is StepOutcome.Skipped -> "skipped(${onlineOutcome.reason})"
                }
                log.line(
                    context,
                    "调度：结束 ${SolveEngine.ONLINE_NOVA.labelZh}（耗时=${stepElapsedMs}ms，结果=$outcomeTag）",
                )
                when (onlineOutcome) {
                    is StepOutcome.Success -> return@withContext onlineOutcome.result
                    is StepOutcome.Failure -> lastOnlineFailure = onlineOutcome.reason
                    is StepOutcome.Skipped -> lastOnlineFailure = onlineOutcome.reason
                }
            }
        } catch (e: SolveBudgetExceeded) {
            budgetExceededError = e
            log.line(context, "全局时间预算耗尽（上限 ${budgetSec.toInt()}s），收集失败诊断与现场")
        }

        val last = lastLocalStars
        val maxB = last?.maxOfOrNull { it.brightness } ?: 0f
        val diagStarList = last?.map { s ->
            SolveDiagnostics.DiagStar(
                x = s.x / display.width.toFloat(),
                y = s.y / display.height.toFloat(),
                brightness01 = if (maxB > 0f) {
                    (s.brightness / maxB).coerceIn(0f, 1f)
                } else 0.5f,
            )
        } ?: lastDetectedDiagStars ?: emptyList()
        val diagStarCount = when {
            lastLocalStarTotal > 0 -> lastLocalStarTotal
            last != null -> last.size
            else -> diagStarList.size
        }
        onDiagnostics(
            SolveDiagnostics(
                starCount = diagStarCount,
                stars = diagStarList,
                enginesTried = enginesTried,
                onlineFailureReason = lastOnlineFailure,
            ),
        )

        val failVerdict = SolveDiagnostics(
            starCount = diagStarCount,
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
        budgetExceededError?.let { throw it }
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
