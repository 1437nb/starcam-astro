package com.starcam.astro.astro.steps

import android.graphics.Bitmap
import com.starcam.astro.astro.EngineResult
import com.starcam.astro.astro.ExifPriors
import com.starcam.astro.astro.ExifPriorsReader
import com.starcam.astro.astro.FovEstimate
import com.starcam.astro.astro.PointingHintStore
import com.starcam.astro.astro.SolveEngine
import com.starcam.astro.astro.SolveResult
import com.starcam.astro.astro.StarSolver
import com.starcam.astro.astro.StellarSolverNative
import com.starcam.astro.data.SolveLogStore
import com.starcam.astro.ui.theme.LocaleState
import com.starcam.astro.util.LocationHelper
import java.util.concurrent.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/**
 * astrometry.net 官方原生盲求解步骤（§0.114 解耦抽离）。
 */
class NativeSolveStep : SolveStep {
    override val engine = SolveEngine.ASTROMETRY_NATIVE

    override suspend fun execute(ctx: StepContext): StepOutcome {
        coroutineContext.ensureActive()
        ctx.token.check()

        if (ctx.isBudgetDepleted) {
            ctx.log.line(ctx.context, "官方引擎：全局时间预算耗尽，跳过")
            return StepOutcome.Skipped("全局时间预算耗尽")
        }

        val isEn = LocaleState.isEnglish
        var priors = try {
            ExifPriorsReader.read(ctx.imagePath)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            ExifPriors(null, null, null, null, false)
        }

        if (priors.epochSec != null && (priors.latDeg == null || priors.lonDeg == null)) {
            val fallback = try {
                LocationHelper(ctx.context).getBestLocation()
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                null
            }
            if (fallback != null) {
                priors = priors.withFallbackLocation(fallback.latitude, fallback.longitude)
            }
        }

        var nativeSolve: SolveResult? = null
        var nativeDetail = ""
        val effFov = ctx.fovOverride ?: ctx.fovDeg

        // 1) 传感器先验
        val sensorHint = if (ctx.settings.sensorAssistedPointing) {
            PointingHintStore.get(ctx.imagePath)
        } else null

        if (sensorHint != null) {
            val (lo, hi) = if (effFov != null) FovEstimate.fovRange(effFov) else 0.1 to 180.0
            ctx.onProgress(
                if (isEn) "Astrometry engine solving (Sensor prior: within %.0f°)…".format(sensorHint.radiusDeg)
                else "官方引擎求解中（传感器粗定标：%.0f° 范围内）…".format(sensorHint.radiusDeg),
            )
            val remSec = ctx.remainingSec
            if (remSec > 0.5) {
                val effectiveTimeLimit = minOf(StarSolver.NATIVE_TIME_LIMIT_KNOWN_FOV * 0.7, remSec * 0.9)
                nativeSolve = try {
                    StellarSolverNative.solveBitmapPriors(
                        ctx.context, ctx.display, lo, hi,
                        sensorHint.raDeg, sensorHint.decDeg, sensorHint.radiusDeg,
                        effectiveTimeLimit,
                    )
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    null
                }
                if (nativeSolve != null) {
                    nativeDetail = if (isEn) "Sensor Prior (within %.0f°) · FOV %.1f°–%.1f°".format(sensorHint.radiusDeg, lo, hi)
                    else "传感器粗定标（%.0f° 内）· 视场 %.1f°–%.1f°".format(sensorHint.radiusDeg, lo, hi)
                }
            }
        }

        // 2) 天顶天区先验
        val zenith = priors.zenith
        if (nativeSolve == null && zenith != null) {
            val (zenithRa, zenithDec) = zenith
            val radius = ExifPriorsReader.skyPriorRadiusDeg(effFov)
            val (lo, hi) = if (effFov != null) FovEstimate.fovRange(effFov) else 0.1 to 180.0
            val priorZh = if (priors.locationIsFallback) "当前定位先验" else "天区先验"
            val priorEn = if (priors.locationIsFallback) "current-location prior" else "sky prior"
            ctx.onProgress(
                if (isEn) "Astrometry engine solving (%s: zenith within %.0f°)…".format(priorEn, radius)
                else "官方引擎求解中（%s：天顶 %.0f° 范围内）…".format(priorZh, radius),
            )
            val remSec = ctx.remainingSec
            if (remSec > 0.5) {
                val effectiveTimeLimit = minOf(StarSolver.NATIVE_TIME_LIMIT_KNOWN_FOV * 0.7, remSec * 0.9)
                nativeSolve = try {
                    StellarSolverNative.solveBitmapPriors(
                        ctx.context, ctx.display, lo, hi,
                        zenithRa, zenithDec, radius,
                        effectiveTimeLimit,
                    )
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    null
                }
                if (nativeSolve != null) {
                    val srcZh = if (priors.locationIsFallback) "当前定位" else "EXIF 定位"
                    val srcEn = if (priors.locationIsFallback) "current location" else "EXIF GPS"
                    nativeDetail = if (isEn) "Sky prior via %s (zenith ±%.0f°) · FOV %.1f°–%.1f°".format(srcEn, radius, lo, hi)
                    else "天区先验（%s · 天顶 %.0f° 内）· 视场 %.1f°–%.1f°".format(srcZh, radius, lo, hi)
                }
            }
        }

        // 3) 分段盲解 / 已知 scale 求解
        if (nativeSolve == null) {
            val nativePlan = if (effFov != null) {
                val (lo, hi) = FovEstimate.fovRange(effFov)
                listOf(Triple(lo, hi, StarSolver.NATIVE_TIME_LIMIT_KNOWN_FOV))
            } else {
                StarSolver.blindSegments()
            }
            for ((index, seg) in nativePlan.withIndex()) {
                coroutineContext.ensureActive()
                ctx.token.check()
                val remSec = ctx.remainingSec
                if (remSec <= 0.5) break
                val (lo, hi, timeLimit) = seg
                val effectiveTimeLimit = minOf(timeLimit, remSec * 0.9)
                ctx.onProgress(
                    if (isEn) "Astrometry engine solving (FOV %.1f°–%.1f°, segment %d/%d, ~%.0fs)…"
                        .format(lo, hi, index + 1, nativePlan.size, effectiveTimeLimit)
                    else "官方引擎求解中（视场 %.1f°–%.1f°，第 %d/%d 段，约 %.0f 秒）…"
                        .format(lo, hi, index + 1, nativePlan.size, effectiveTimeLimit),
                )
                nativeSolve = try {
                    StellarSolverNative.solveBitmap(ctx.context, ctx.display, lo, hi, effectiveTimeLimit)
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    null
                }
                if (nativeSolve != null) {
                    nativeDetail = "视场 %.1f°–%.1f°".format(lo, hi)
                    break
                }
            }
        }

        // 4) 降采样重试轮
        if (nativeSolve == null) {
            coroutineContext.ensureActive()
            ctx.token.check()
            val origW = ctx.display.width
            val origH = ctx.display.height
            val longEdge = maxOf(origW, origH)
            val retry = StarSolver.downsampleRetryPlan(effFov, longEdge)
            if (retry != null) {
                val (edge, plan) = retry
                val scaled = try {
                    val factor = edge.toFloat() / longEdge
                    Bitmap.createScaledBitmap(
                        ctx.display,
                        maxOf(1, (origW * factor).toInt()),
                        maxOf(1, (origH * factor).toInt()),
                        true,
                    )
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    null
                }
                if (scaled != null) {
                    ctx.onProgress(
                        if (isEn) "Astrometry downsample retry (${scaled.width}px)…"
                        else "官方引擎降采样重试（${scaled.width}px 长边）…",
                    )
                    for ((lo, hi, _) in plan) {
                        coroutineContext.ensureActive()
                        ctx.token.check()
                        val remSec = ctx.remainingSec
                        if (remSec <= 0.5) break
                        val effectiveTimeLimit = minOf(StarSolver.NATIVE_DOWNSAMPLE_RETRY_TIME, remSec * 0.9)
                        nativeSolve = try {
                            StellarSolverNative.solveBitmap(ctx.context, scaled, lo, hi, effectiveTimeLimit)
                        } catch (e: Throwable) {
                            if (e is CancellationException) throw e
                            null
                        }
                        if (nativeSolve != null) {
                            nativeSolve = StarSolver.rescaleSolveFor(nativeSolve, scaled.width, scaled.height, origW, origH)
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
            ctx.log.line(
                ctx.context,
                "官方引擎成功：$detail logodds=${nativeSolve.logodds} 耗时=${System.currentTimeMillis() - ctx.logT0}ms",
            )
            SolveLogStore.addStep(
                ctx.report, "官方引擎", "solved",
                "index-${nativeSolve.indexId} nmatch=${nativeSolve.nMatch} logodds=${nativeSolve.logodds} $nativeDetail",
                System.currentTimeMillis() - ctx.logT0,
            )
            StarSolver.writeSuccessLog(ctx.context, ctx.report, ctx.imagePath, "官方引擎", detail)
            return StepOutcome.Success(EngineResult(nativeSolve, engine, detail, ctx.display))
        } else {
            val diag = "nstars=${StellarSolverNative.lastFailNStars} rc=${StellarSolverNative.lastFailRc} gmean=${StellarSolverNative.lastFailGMean}"
            ctx.log.line(ctx.context, "官方引擎失败（所有轮次）：$diag")
            SolveLogStore.addStep(ctx.report, "官方引擎", "unsolved", diag, System.currentTimeMillis() - ctx.logT0)
            return StepOutcome.Failure("官方引擎未命中：$diag")
        }
    }
}
