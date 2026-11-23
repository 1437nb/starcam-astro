package com.starcam.astro.astro.steps

import android.graphics.Bitmap
import com.starcam.astro.astro.CompactStarDetector
import com.starcam.astro.astro.EngineResult
import com.starcam.astro.astro.ExifPriors
import com.starcam.astro.astro.ExifPriorsReader
import com.starcam.astro.astro.FovEstimate
import com.starcam.astro.astro.NativePreprocess
import com.starcam.astro.astro.PointingHintStore
import com.starcam.astro.astro.SolveEngine
import com.starcam.astro.astro.SolveResult
import com.starcam.astro.astro.SolverPlanning
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

        if (!com.starcam.astro.astro.NativeEngineProbe.available) {
            ctx.log.line(ctx.context, "官方引擎：当前设备架构不支持原生引擎库，跳过")
            return StepOutcome.Skipped("当前架构不支持原生引擎")
        }

        if (ctx.isBudgetDepleted) {
            ctx.log.line(ctx.context, "官方引擎：全局时间预算耗尽，跳过")
            return StepOutcome.Skipped("全局时间预算耗尽")
        }

        val isEn = LocaleState.isEnglish
        ctx.onPhase(com.starcam.astro.astro.SolvePhase.NATIVE)
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
        val tGray0 = System.currentTimeMillis()
        val grayBuffer = ctx.pixels.nativeCopy()
        val grayMs = System.currentTimeMillis() - tGray0
        val tIdx0 = System.currentTimeMillis()
        val nativeIndexes = com.starcam.astro.astro.NativeSolverSupport.ensureIndexes(
            ctx.context, StellarSolverNative.BUNDLED_INDEX_NAMES,
        )
        val idxPrepMs = System.currentTimeMillis() - tIdx0
        ctx.log.line(
            ctx.context,
            "官方引擎准备：grayMs=${grayMs} idxPrepMs=${idxPrepMs} idxCount=${nativeIndexes.size} " +
                "remSec=${"%.1f".format(ctx.remainingSec)} fov=${effFov?.let { "%.1f°".format(it) } ?: "无EXIF"}",
        )

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
                val effectiveTimeLimit = minOf(8.0, StarSolver.NATIVE_TIME_LIMIT_KNOWN_FOV * 0.4, remSec * 0.35)
                val tSeg0 = System.currentTimeMillis()
                nativeSolve = try {
                    StellarSolverNative.solveBitmapPriors(
                        ctx.context, ctx.display, lo, hi,
                        sensorHint.raDeg, sensorHint.decDeg, sensorHint.radiusDeg,
                        effectiveTimeLimit,
                        gray = grayBuffer,
                        token = ctx.token,
                    )
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    null
                }
                val segMs = System.currentTimeMillis() - tSeg0
                ctx.log.line(
                    ctx.context,
                    "官方引擎-传感器先验：limit=${"%.1f".format(effectiveTimeLimit)}s elapsed=${segMs}ms " +
                        "ok=${nativeSolve != null} nstars=${StellarSolverNative.lastFailNStars ?: nativeSolve?.nStars ?: -1}",
                )
                if (nativeSolve != null) {
                    nativeDetail = if (isEn) "Sensor Prior (within %.0f°) · FOV %.1f°–%.1f°".format(sensorHint.radiusDeg, lo, hi)
                    else "传感器粗定标（%.0f° 内）· 视场 %.1f°–%.1f°".format(sensorHint.radiusDeg, lo, hi)
                }
            }
        }

        // 2) 天顶天区先验（注意：非 EXIF 的当前定位兜底在历史/异地照片上可能错误，限时收紧）
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
                val effectiveTimeLimit = when {
                    priors.locationIsFallback -> minOf(2.5, remSec * 0.22)
                    remSec <= 15.0 -> minOf(2.5, remSec * 0.22)
                    else -> minOf(7.0, StarSolver.NATIVE_TIME_LIMIT_KNOWN_FOV * 0.40, remSec * 0.35)
                }
                val tSeg0 = System.currentTimeMillis()
                nativeSolve = try {
                    StellarSolverNative.solveBitmapPriors(
                        ctx.context, ctx.display, lo, hi,
                        zenithRa, zenithDec, radius,
                        effectiveTimeLimit,
                        gray = grayBuffer,
                        token = ctx.token,
                    )
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    null
                }
                val segMs = System.currentTimeMillis() - tSeg0
                ctx.log.line(
                    ctx.context,
                    "官方引擎-天顶先验(fallback=${priors.locationIsFallback})：limit=${"%.1f".format(effectiveTimeLimit)}s " +
                        "elapsed=${segMs}ms ok=${nativeSolve != null} nstars=${StellarSolverNative.lastFailNStars ?: nativeSolve?.nStars ?: -1}",
                )
                if (nativeSolve != null) {
                    val srcZh = if (priors.locationIsFallback) "当前定位" else "EXIF 定位"
                    val srcEn = if (priors.locationIsFallback) "current location" else "EXIF GPS"
                    nativeDetail = if (isEn) "Sky prior via %s (zenith ±%.0f°) · FOV %.1f°–%.1f°".format(srcEn, radius, lo, hi)
                    else "天区先验（%s · 天顶 %.0f° 内）· 视场 %.1f°–%.1f°".format(srcZh, radius, lo, hi)
                }
            }
        }

        var lastObservedNStars: Int? = StellarSolverNative.lastFailNStars?.takeIf { it > 0 }

        // 3) 分段盲解 / 已知 scale 求解
        if (nativeSolve == null) {
            val retryReserveSec = if (ctx.remainingSec >= 8.0) 5.5 else 0.0
            val nativePlan = if (effFov != null) {
                val (lo, hi) = FovEstimate.fovRange(effFov)
                listOf(Triple(lo, hi, StarSolver.NATIVE_TIME_LIMIT_KNOWN_FOV))
            } else {
                SolverPlanning.nativeBlindSegments()
            }
            for ((index, seg) in nativePlan.withIndex()) {
                coroutineContext.ensureActive()
                ctx.token.check()
                val usableRem = (ctx.remainingSec - retryReserveSec).coerceAtLeast(0.0)
                if (usableRem <= 0.8) break
                val (lo, hi, _) = seg
                val rawLimit = SolverPlanning
                    .nativeSegmentTimeLimitSec(index, nativePlan, usableRem)
                val effectiveTimeLimit = if (retryReserveSec > 0.0 && index == 0) {
                    minOf(rawLimit, if (nativePlan.size > 1) 3.2 else 4.0)
                } else {
                    rawLimit
                }
                if (effectiveTimeLimit < 0.5) break
                ctx.onProgress(
                    if (isEn) "Astrometry engine solving (FOV %.1f°–%.1f°, segment %d/%d, ~%.0fs)…"
                        .format(lo, hi, index + 1, nativePlan.size, effectiveTimeLimit)
                    else "官方引擎求解中（视场 %.1f°–%.1f°，第 %d/%d 段，约 %.0f 秒）…"
                        .format(lo, hi, index + 1, nativePlan.size, effectiveTimeLimit),
                )
                val tSeg0 = System.currentTimeMillis()
                nativeSolve = try {
                    StellarSolverNative.solveBitmap(
                        ctx.context, ctx.display, lo, hi, effectiveTimeLimit,
                        gray = grayBuffer,
                        token = ctx.token,
                    )
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    null
                }
                val segMs = System.currentTimeMillis() - tSeg0
                StellarSolverNative.lastFailNStars?.takeIf { it > 0 }?.let { lastObservedNStars = it }
                ctx.log.line(
                    ctx.context,
                    "官方引擎-分段[${index + 1}/${nativePlan.size}] %.1f°..%.1f°：limit=%.1fs elapsed=${segMs}ms ok=${nativeSolve != null} nstars=${StellarSolverNative.lastFailNStars ?: nativeSolve?.nStars ?: -1}"
                        .format(lo, hi, effectiveTimeLimit),
                )
                if (nativeSolve != null) {
                    nativeDetail = if (isEn) "FOV %.1f°–%.1f°".format(lo, hi)
                    else "视场 %.1f°–%.1f°".format(lo, hi)
                    break
                }
            }
        }

        // 3.5) §0.144 P3 预算内单因子预处理重试（先天空裁剪避开前景，后局部背景扣除+降噪重提）
        if (nativeSolve == null && ctx.remainingSec >= 2.2) {
            coroutineContext.ensureActive()
            ctx.token.check()
            val origW = ctx.display.width
            val origH = ctx.display.height
            val quickStars = try {
                CompactStarDetector.detect(origW, origH, grayBuffer, ctx.token)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                emptyList()
            }

            // 3A) 前景感知天空裁剪重试
            val cropPlan = NativePreprocess.detectSkyCrop(quickStars, grayBuffer, origW, origH)
            if (cropPlan != null && ctx.remainingSec >= 2.0) {
                val (edge, crop) = cropPlan
                val cropBmp = try {
                    Bitmap.createBitmap(ctx.display, crop.x0, crop.y0, crop.width, crop.height)
                } catch (_: Throwable) {
                    null
                }
                if (cropBmp != null) {
                    try {
                        val cropGray = NativePreprocess.cropGray(grayBuffer, origW, origH, crop)
                        val fovScale = crop.width.toDouble() / origW.coerceAtLeast(1)
                        val (cLo, cHi) = if (effFov != null) {
                            FovEstimate.fovRange(effFov * fovScale)
                        } else {
                            (15.0 * fovScale).coerceAtLeast(1.5) to (115.0 * fovScale).coerceAtMost(140.0)
                        }
                        val limit = minOf(3.5, ctx.remainingSec * 0.55)
                        if (limit >= 0.8) {
                            ctx.onProgress(
                                if (isEn) "Retrying sky crop away from foreground…"
                                else "官方引擎避开前景裁剪天空重试中…",
                            )
                            val t0 = System.currentTimeMillis()
                            val solvedCrop = try {
                                StellarSolverNative.solveBitmap(
                                    ctx.context, cropBmp, cLo, cHi, limit,
                                    gray = cropGray,
                                    token = ctx.token,
                                )
                            } catch (e: Throwable) {
                                if (e is CancellationException) throw e
                                null
                            }
                            val elapsed = System.currentTimeMillis() - t0
                            StellarSolverNative.lastFailNStars?.takeIf { it > 0 }?.let { lastObservedNStars = it }
                            ctx.log.line(
                                ctx.context,
                                "官方引擎-天空裁剪重试(${edge.name} ${crop.width}x${crop.height})：FOV %.1f°..%.1f° limit=%.1fs elapsed=${elapsed}ms ok=${solvedCrop != null} nstars=${StellarSolverNative.lastFailNStars ?: solvedCrop?.nStars ?: -1}"
                                    .format(cLo, cHi, limit),
                            )
                            if (solvedCrop != null) {
                                nativeSolve = NativePreprocess.remapCroppedSolve(solvedCrop, crop, origW, origH)
                                nativeDetail = if (isEn) "Sky crop (${edge.name}, FOV %.1f°–%.1f°)".format(cLo, cHi)
                                else "天空裁剪(${edge.name} · 视场 %.1f°–%.1f°)".format(cLo, cHi)
                            }
                        }
                    } finally {
                        cropBmp.recycle()
                    }
                }
            }

            // 3B) 局部背景扣除 + 热点抑制 + 自适应 plim 重试（针对光害/月晕/稠密噪点）
            if (nativeSolve == null && ctx.remainingSec >= 1.8 &&
                (lastObservedNStars == null || lastObservedNStars!! >= 1500)
            ) {
                coroutineContext.ensureActive()
                ctx.token.check()
                val tPrep0 = System.currentTimeMillis()
                val cleanGray = NativePreprocess.cleanBackgroundAndSpots(grayBuffer, origW, origH)
                val prepMs = System.currentTimeMillis() - tPrep0
                val plim = NativePreprocess.recommendedPlim(lastObservedNStars)
                val (bLo, bHi) = if (effFov != null) FovEstimate.fovRange(effFov) else 1.5 to 95.0
                val limit = minOf(3.8, ctx.remainingSec * 0.80)
                if (limit >= 0.8) {
                    ctx.onProgress(
                        if (isEn) "Retrying with skyglow background subtraction…"
                        else "官方引擎正在进行去背景与噪点抑制重试…",
                    )
                    val t0 = System.currentTimeMillis()
                    nativeSolve = try {
                        StellarSolverNative.solveBitmap(
                            ctx.context, ctx.display, bLo, bHi, limit,
                            gray = cleanGray,
                            token = ctx.token,
                            plimOverride = plim,
                        )
                    } catch (e: Throwable) {
                        if (e is CancellationException) throw e
                        null
                    }
                    val elapsed = System.currentTimeMillis() - t0
                    ctx.log.line(
                        ctx.context,
                        "官方引擎-去背景降噪重试(prep=${prepMs}ms plim=%.1f)：FOV %.1f°..%.1f° limit=%.1fs elapsed=${elapsed}ms ok=${nativeSolve != null} nstars=${StellarSolverNative.lastFailNStars ?: nativeSolve?.nStars ?: -1}"
                            .format(plim, bLo, bHi, limit),
                    )
                    if (nativeSolve != null) {
                        nativeDetail = if (isEn) "Skyglow cleaned (FOV %.1f°–%.1f°)".format(bLo, bHi)
                        else "去背景降噪(视场 %.1f°–%.1f°)".format(bLo, bHi)
                    }
                }
            }

            // 3C) EXIF 窄视场估算偏差兜底（例如竖拍宽边 EXIF 导致真实水平视场超出 ±35%）
            if (nativeSolve == null && effFov != null && ctx.remainingSec >= 2.2) {
                coroutineContext.ensureActive()
                ctx.token.check()
                val wLo = (effFov * 0.55).coerceAtLeast(1.5)
                val wHi = (effFov * 1.90).coerceAtMost(120.0)
                val limit = minOf(3.2, ctx.remainingSec * 0.85)
                if (limit >= 0.8) {
                    ctx.onProgress(
                        if (isEn) "Retrying widened FOV fallback…"
                        else "官方引擎正在进行宽视场范围兜底重试…",
                    )
                    val t0 = System.currentTimeMillis()
                    nativeSolve = try {
                        StellarSolverNative.solveBitmap(
                            ctx.context, ctx.display, wLo, wHi, limit,
                            gray = grayBuffer,
                            token = ctx.token,
                        )
                    } catch (e: Throwable) {
                        if (e is CancellationException) throw e
                        null
                    }
                    val elapsed = System.currentTimeMillis() - t0
                    ctx.log.line(
                        ctx.context,
                        "官方引擎-宽视场兜底重试：FOV %.1f°..%.1f° limit=%.1fs elapsed=${elapsed}ms ok=${nativeSolve != null} nstars=${StellarSolverNative.lastFailNStars ?: nativeSolve?.nStars ?: -1}"
                            .format(wLo, wHi, limit),
                    )
                    if (nativeSolve != null) {
                        nativeDetail = if (isEn) "Widened FOV fallback (%.1f°–%.1f°)".format(wLo, wHi)
                        else "宽视场兜底(%.1f°–%.1f°)".format(wLo, wHi)
                    }
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
                    val scaledGray = StellarSolverNative.bitmapToGray(scaled)
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
                            StellarSolverNative.solveBitmap(
                                ctx.context, scaled, lo, hi, effectiveTimeLimit,
                                gray = scaledGray,
                                token = ctx.token,
                            )
                        } catch (e: Throwable) {
                            if (e is CancellationException) throw e
                            null
                        }
                        if (nativeSolve != null) {
                            nativeSolve = StarSolver.rescaleSolveFor(nativeSolve, scaled.width, scaled.height, origW, origH)
                            nativeDetail = if (isEn) "Downsampled retry (FOV %.1f°–%.1f°)".format(lo, hi)
                            else "降采样重试（视场 %.1f°–%.1f°）".format(lo, hi)
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
                nativeSolve.nMatch?.let { append(if (isEn) " · $it stars matched" else " · 匹配 $it 星") }
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
            if (ctx.recordSuccess) StarSolver.writeSuccessLog(ctx.context, ctx.report, ctx.imagePath, "官方引擎", detail)
            return StepOutcome.Success(EngineResult(nativeSolve, engine, detail, ctx.display))
        } else {
            val diag = "nstars=${StellarSolverNative.lastFailNStars} rc=${StellarSolverNative.lastFailRc} gmean=${StellarSolverNative.lastFailGMean}"
            ctx.log.line(ctx.context, "官方引擎失败（所有轮次）：$diag")
            SolveLogStore.addStep(ctx.report, "官方引擎", "unsolved", diag, System.currentTimeMillis() - ctx.logT0)
            return StepOutcome.Failure("官方引擎未命中：$diag")
        }
    }
}
