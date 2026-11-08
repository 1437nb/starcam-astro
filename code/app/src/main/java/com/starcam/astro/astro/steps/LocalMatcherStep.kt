package com.starcam.astro.astro.steps

import com.starcam.astro.astro.CatalogDomain
import com.starcam.astro.astro.AdaptiveStarDetector
import com.starcam.astro.astro.DetectedStar
import com.starcam.astro.astro.DetectorSelection
import com.starcam.astro.astro.EngineResult
import com.starcam.astro.astro.LocalStarMatcher
import com.starcam.astro.astro.PointingHintStore
import com.starcam.astro.astro.SolveDiagnostics
import com.starcam.astro.astro.SolveEngine
import com.starcam.astro.astro.StarCatalogData
import com.starcam.astro.astro.StarSolver
import com.starcam.astro.astro.SolveToken
import com.starcam.astro.astro.SolveBudgetExceeded
import com.starcam.astro.astro.StellarSolverNative
import com.starcam.astro.data.SolveLogStore
import java.util.concurrent.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 内置星表匹配求解步骤（§0.114 解耦抽离）。
 */
class LocalMatcherStep : SolveStep {
    override val engine = SolveEngine.LOCAL_MATCHER

    override suspend fun execute(ctx: StepContext): StepOutcome {
        coroutineContext.ensureActive()
        ctx.token.check()

        if (ctx.isBudgetDepleted) {
            ctx.log.line(ctx.context, "内置星表：全局时间预算耗尽，跳过")
            return StepOutcome.Skipped("全局时间预算耗尽")
        }

        val isEn = com.starcam.astro.ui.theme.LocaleState.isEnglish
        ctx.onPhase(com.starcam.astro.astro.SolvePhase.DETECTING)
        ctx.onProgress(
            if (isEn) "Matching local star catalog (${StarCatalogData.stars.size} bright stars)…"
            else "本地星表识别中（内置 ${StarCatalogData.stars.size} 颗亮星）…"
        )

        var sepListForFallback: List<DetectedStar>? = null
        var previewStars: List<DetectedStar>? = null
        var lastLocalStarTotal = 0
        var lastGraySnapshot: FloatArray? = null
        var lastGrayW = 0
        var lastGrayH = 0
        var recoveryStars: List<DetectedStar>? = null

        val stars = try {
            val extractDiag = StringBuilder()
            fun sepStars(sigma: Double): StellarSolverNative.SepStars? = try {
                StellarSolverNative.sepDetectStars(
                    ctx.display, sigma,
                    StellarSolverNative.SEP_MATCH_MAX, extractDiag, grayInput = ctx.pixels.gray,
                )
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                extractDiag.append("sep=${sigma}σ 异常(${e.javaClass.simpleName})；")
                null
            }

            var sr = sepStars(2.0)
            if (sr == null || sr.stars.size < 5) {
                val looser = sepStars(1.5)
                if (looser != null && looser.stars.size > (sr?.stars?.size ?: 0)) sr = looser
            }
            val sepList: List<DetectedStar>? = sr?.stars
            val detectedTotal0 = sr?.totalPeaks ?: 0

            val sepTrustworthy = sepList != null && sepList.size >= 5
            val bb = if (sepTrustworthy) {
                try {
                    LocalStarMatcher.detectStarsGray(ctx.display.width, ctx.display.height, ctx.pixels.gray, maxStars = 100)
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    null
                }
            } else {
                null
            }

            val plan = DetectorSelection.plan(
                boxBlur = bb,
                sep = sepList,
                sepTrustworthy = sepTrustworthy,
                detectedTotal = detectedTotal0,
                boxBlurRetry = {
                    try {
                        LocalStarMatcher.detectStarsGray(ctx.display.width, ctx.display.height, ctx.pixels.gray, maxStars = 100)
                    } catch (e: Throwable) {
                        if (e is CancellationException) throw e
                        null
                    }
                },
            )
            extractDiag.append(plan.note)
            lastLocalStarTotal = plan.detectedTotal
            previewStars = plan.preview
            sepListForFallback = plan.fallback
            ctx.log.line(ctx.context, "提星：$extractDiag")

            previewStars?.takeIf { it.isNotEmpty() }?.let { det ->
                val maxB = det.maxOfOrNull { it.brightness } ?: 0f
                ctx.onStarsDetected(
                    det.map { st ->
                        SolveDiagnostics.DiagStar(
                            x = st.x / ctx.display.width.toFloat(),
                            y = st.y / ctx.display.height.toFloat(),
                            brightness01 = if (maxB > 0f) {
                                (st.brightness / maxB).coerceIn(0f, 1f)
                            } else 0.5f,
                        )
                    },
                    lastLocalStarTotal,
                )
            }
            plan.primary
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            null
        }

        ctx.token.check()

        val hint = if (ctx.settings.sensorAssistedPointing) {
            PointingHintStore.get(ctx.imagePath)
        } else null

        ctx.onPhase(com.starcam.astro.astro.SolvePhase.MATCHING)
        val allowDeep = (ctx.fovDeg != null && ctx.fovDeg <= LocalStarMatcher.DEEP_PATH_MAX_FOV_DEG) ||
            !ctx.scheduledSteps.contains(SolveEngine.ASTROMETRY_NATIVE)
        val recoveryAllowed = ctx.fovDeg == null || ctx.fovDeg >= 10.0
        // Reserve part of the existing global budget; otherwise a contaminated primary
        // list can exhaust it before the independent detector ever gets a chance.
        val primaryToken = if (recoveryAllowed) SolveToken(ctx.token,
            timeoutMillis = (ctx.remainingSec * 600).toLong().coerceAtLeast(1)) else ctx.token
        val primaryAttempt = try {
            LocalStarMatcher.matchWithDiagnostics(
                stars ?: emptyList(), ctx.display.width, ctx.display.height, hint,
                allowDeepRetry = allowDeep, fallbackDetected = sepListForFallback,
                fovHintDeg = ctx.fovDeg, token = primaryToken,
            )
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            ctx.token.check()
            if (e is SolveBudgetExceeded) ctx.log.line(ctx.context, "内置星表主路径时间片耗尽，保留余量给复杂星空补救")
            null
        }
        ctx.token.check()
        val attempt = if (primaryAttempt?.result != null || !recoveryAllowed || ctx.isBudgetDepleted) primaryAttempt else {
            val retry = try {
                LocalStarMatcher.matchWithDiagnostics(
                    emptyList(), ctx.display.width, ctx.display.height, hint,
                    allowDeepRetry = allowDeep,
                    fovHintDeg = ctx.fovDeg,
                    token = ctx.token,
                    recoveryDetected = { recoveryToken ->
                        ctx.onPhase(com.starcam.astro.astro.SolvePhase.DETECTING)
                        ctx.onProgress(if (isEn) "Recovering stars against uneven sky background…"
                            else "复杂星空补救：分析局部背景与星点形态…")
                        AdaptiveStarDetector.detect(ctx.display.width, ctx.display.height,
                            ctx.pixels.gray, token = recoveryToken).also {
                            recoveryStars = it
                            ctx.onPhase(com.starcam.astro.astro.SolvePhase.MATCHING)
                        }
                    },
                )
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                ctx.token.check()
                null
            }
            if (retry?.result != null || primaryAttempt == null) retry else primaryAttempt.copy(
                diagnostics = primaryAttempt.diagnostics.copy(recoveryDetail = retry?.diagnostics?.recoveryDetail))
        }
        ctx.token.check()

        val matched = attempt?.result
        val diag = attempt?.diagnostics
        val deepRejectReason = diag?.deepRejectedReason
        diag?.recoveryDetail?.let { ctx.log.line(ctx.context, "内置星表补救：$it") }
        val matchedStars = if (diag?.usedRecovery == true) recoveryStars else stars
        if (diag?.usedRecovery == true) {
            previewStars = recoveryStars
            lastLocalStarTotal = recoveryStars?.size ?: 0
        }

        if (matched != null && diag?.usedFallback == true) {
            ctx.log.line(
                ctx.context,
                "内置星表：box-blur 表(${stars?.size ?: 0}颗)浅域未匹配，" +
                    "改用 SEP 备用星表(${sepListForFallback?.size ?: 0}颗)命中",
            )
        }

        if (matchedStars != null) {
            SolveLogStore.setStars(ctx.report, matchedStars, lastLocalStarTotal)
            try {
                lastGraySnapshot = ctx.pixels.gray
                lastGrayW = ctx.display.width
                lastGrayH = ctx.display.height
            } catch (_: Throwable) {
            }
        }

        if (matched != null) {
            ctx.onPhase(com.starcam.astro.astro.SolvePhase.VERIFYING)
            val verdict = StarSolver.evaluateLocalSolve(ctx.context, ctx.display, matched, ctx.token, ctx.remainingSec)
            ctx.token.check()
            when (verdict) {
                is StarSolver.LocalAcceptVerdict.AcceptLocal -> {
                    val domainTag = if (matched.catalogDomain == CatalogDomain.DEEP) {
                        if (isEn) " · Deep fallback" else " · 深域兜底"
                    } else ""
                    val recoveryTag = if (diag?.usedRecovery == true) {
                        if (isEn) " · Adaptive detection" else " · 复杂星空补救"
                    } else ""
                    val detail = if (isEn) "Built-in catalog · ${matched.inlierCount} inliers$domainTag$recoveryTag"
                    else "内置星表 · 内点 ${matched.inlierCount} 颗$domainTag$recoveryTag"
                    ctx.log.line(
                        ctx.context,
                        "内置星表成功：$detail scale=%.2f\" 耗时=%dms"
                            .format(matched.solve.pixScaleArcsec, System.currentTimeMillis() - ctx.logT0),
                    )
                    SolveLogStore.addStep(
                        ctx.report, "内置星表", "solved",
                        ("内点=${matched.inlierCount} scale=%.2f$domainTag$recoveryTag").format(matched.solve.pixScaleArcsec),
                        System.currentTimeMillis() - ctx.logT0,
                    )
                    if (ctx.recordSuccess) StarSolver.writeSuccessLog(ctx.context, ctx.report, ctx.imagePath, "内置星表", detail)
                    return StepOutcome.Success(EngineResult(matched.solve, engine, detail, ctx.display))
                }
                is StarSolver.LocalAcceptVerdict.AdoptNative -> {
                    val nativeSolve = verdict.solve
                    val detail = buildString {
                        append(if (isEn) "Reviewed & corrected · FOV %.1f°".format(nativeSolve.fieldWidthDeg)
                        else "官方复核修正 · 视场 %.1f°".format(nativeSolve.fieldWidthDeg))
                        nativeSolve.indexId?.let { append(" · index-$it") }
                        nativeSolve.nMatch?.let { append(if (isEn) " · $it stars matched" else " · 匹配 $it 星") }
                    }
                    ctx.log.line(
                        ctx.context,
                        "内置星表低内点(${matched.inlierCount})复核时官方引擎给出更高置信解，直接采纳：$detail " +
                            "耗时=${System.currentTimeMillis() - ctx.logT0}ms",
                    )
                    SolveLogStore.addStep(
                        ctx.report, "官方引擎", "solved",
                        "复核采纳 index-${nativeSolve.indexId} nmatch=${nativeSolve.nMatch} " +
                            "logodds=${nativeSolve.logodds} $detail",
                        System.currentTimeMillis() - ctx.logT0,
                    )
                    if (ctx.recordSuccess) StarSolver.writeSuccessLog(ctx.context, ctx.report, ctx.imagePath, "官方引擎", detail)
                    return StepOutcome.Success(
                        EngineResult(nativeSolve, SolveEngine.ASTROMETRY_NATIVE, detail, ctx.display),
                    )
                }
                is StarSolver.LocalAcceptVerdict.Rejected -> {
                    // 官方复核未通过，记录降级视场继续尝试
                }
            }
        }

        val why = when {
            stars == null -> "提星失败（原生与回退检测器均未给出星点）"
            stars.size < 5 -> "星点太少（${stars.size} 颗，匹配需 ≥5）"
            matched == null -> buildString {
                append("星点 ${stars.size} 颗但未匹配")
                append("；投票候选=${diag?.shallowVoteStats ?: diag?.voteStats}")
                append("；打分轮=${diag?.shallowScoredStats ?: diag?.scoredStats}")
                append("（best=${diag?.shallowScoredBest ?: diag?.scoredBest}")
                append(" inFrame=${diag?.shallowScoredInFrame ?: diag?.scoredInFrame}）")
                deepRejectReason?.let { append("；深域：").append(it) }
            }
            else -> "有解但内点仅 ${matched.inlierCount} 颗，被官方复核拒绝"
        }

        val failureReason = diag?.recoveryDetail?.let { "$why；补救：$it" } ?: why
        ctx.log.line(ctx.context, "内置星表失败：$failureReason")
        SolveLogStore.addStep(ctx.report, "内置星表", "unsolved", failureReason, System.currentTimeMillis() - ctx.logT0)

        if (stars != null) {
            try {
                StellarSolverNative.dumpLocalFail(
                    ctx.context, ctx.display, stars,
                    if (matched == null) "local-unsolved" else "local-inliers-${matched.inlierCount}",
                )
            } catch (_: LinkageError) {
                // Unsupported ABIs still use the Kotlin engine. A failed optional JNI
                // class initializer must not turn a normal unsolved result into a crash.
            }
        }

        val fovOverride = if (matched != null) {
            maxOf(matched.solve.fieldWidthDeg, matched.solve.fieldHeightDeg)
        } else null

        return StepOutcome.Failure(
            reason = failureReason,
            fovOverride = fovOverride,
            diagStars = previewStars ?: stars,
            diagStarTotal = lastLocalStarTotal,
            graySnapshot = lastGraySnapshot,
            grayW = lastGrayW,
            grayH = lastGrayH,
        )
    }
}
