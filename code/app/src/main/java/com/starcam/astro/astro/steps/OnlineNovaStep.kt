package com.starcam.astro.astro.steps

import com.starcam.astro.astro.EngineResult
import com.starcam.astro.astro.PlateSolveClient
import com.starcam.astro.astro.PlateSolveException
import com.starcam.astro.astro.SolveEngine
import com.starcam.astro.astro.StarSolver
import com.starcam.astro.data.SolveLogStore
import com.starcam.astro.ui.theme.LocaleState
import com.starcam.astro.util.ImageUtils
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 在线 nova.astrometry.net 求解步骤（§0.114 解耦抽离）。
 */
class OnlineNovaStep : SolveStep {
    override val engine = SolveEngine.ONLINE_NOVA

    override suspend fun execute(ctx: StepContext): StepOutcome {
        coroutineContext.ensureActive()
        ctx.token.check()

        val isEn = LocaleState.isEnglish
        val key = ctx.settings.apiKey
        if (key.isBlank()) {
            return StepOutcome.Skipped(if (isEn) "API Key not configured" else "未配置 API Key")
        }

        if (ctx.isBudgetDepleted) {
            ctx.log.line(ctx.context, "在线识别：全局时间预算耗尽，跳过")
            return StepOutcome.Skipped(if (isEn) "Global time budget exhausted" else "全局时间预算耗尽")
        }

        val isOnlineOnly = ctx.scheduledSteps == listOf(SolveEngine.ONLINE_NOVA)
        ctx.onPhase(com.starcam.astro.astro.SolvePhase.ONLINE)
        ctx.onProgress(
            if (isOnlineOnly) {
                if (isEn) "Preparing photo for online solve…" else "正在准备照片进行在线求解…"
            } else {
                if (isEn) "Local engines unmatched, uploading for online solve…"
                else "本地引擎未匹配，正在上传在线求解…"
            },
        )

        val upload = File.createTempFile("starcam-upload-", ".jpg", ctx.context.cacheDir)
        var onlineDisplay: android.graphics.Bitmap? = null
        var delivered = false
        try {
            val solve = try {
                val ok = ImageUtils.compressForUpload(ctx.imagePath, upload) {
                    ctx.token.check()
                } != null
                if (!ok) {
                    val msg = if (isEn) "Failed to compress image for upload" else "图片压缩上传失败"
                    SolveLogStore.addStep(
                        ctx.report, "在线识别", "failed", msg,
                        System.currentTimeMillis() - ctx.logT0,
                    )
                    return StepOutcome.Failure(msg)
                }
                ctx.token.check()

                onlineDisplay = ImageUtils.decodeSampledBitmap(upload.absolutePath, 2200) {
                    ctx.token.check()
                } ?: run {
                    val msg = if (isEn) "Failed to decode uploaded image copy" else "无法解码上传图片副本"
                    SolveLogStore.addStep(
                        ctx.report, "在线识别", "failed", msg,
                        System.currentTimeMillis() - ctx.logT0,
                    )
                    return StepOutcome.Failure(msg)
                }

                val fovLongDeg = ctx.fovOverride ?: ctx.fovDeg
                val fovWidthRange = fovLongDeg?.takeIf { it.isFinite() && it > 0.0 }?.let { longDeg ->
                    val w = onlineDisplay.width
                    val h = onlineDisplay.height
                    // readFovDeg 为传感器长边视场，Nova scale_units=degwidth 为水平宽度视场
                    val widthDeg = if (w > 0 && h > 0 && w < h) {
                        longDeg * w.toDouble() / h.toDouble()
                    } else {
                        longDeg
                    }
                    val lo = (widthDeg * 0.65).coerceAtLeast(0.1)
                    val hi = (widthDeg * 1.45).coerceAtMost(180.0).coerceAtLeast(lo + 0.5)
                    lo to hi
                }

                val remainingMs = (ctx.remainingSec * 1000).toLong().coerceAtLeast(1000L)
                kotlinx.coroutines.withTimeout(remainingMs) {
                    val client = PlateSolveClient(ctx.settings.serverUrl)
                    client.solve(upload, key, remainingMs, fovWidthRange) { msg ->
                        ctx.token.check()
                        ctx.onProgress(msg)
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                ctx.onProgress(if (isEn) "Online solve timed out" else "在线识别超时")
                val reason = if (isEn) "Online solve exceeded global time budget" else "在线识别超过全局时间预算"
                SolveLogStore.addStep(
                    ctx.report, "在线识别", "timeout", reason,
                    System.currentTimeMillis() - ctx.logT0,
                )
                return StepOutcome.Failure(reason)
            } catch (e: PlateSolveException) {
                ctx.onProgress(e.message ?: if (isEn) "Online solve unavailable" else "在线识别不可用")
                val reason = e.message ?: if (isEn) "Online solve failed" else "在线求解失败"
                SolveLogStore.addStep(
                    ctx.report, "在线识别", "failed", reason,
                    System.currentTimeMillis() - ctx.logT0,
                )
                return StepOutcome.Failure(reason)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                val reason = if (isEn) "Network/IO error: ${e.message}" else "网络异常：${e.message}"
                SolveLogStore.addStep(
                    ctx.report, "在线识别", "failed", reason,
                    System.currentTimeMillis() - ctx.logT0,
                )
                return StepOutcome.Failure(reason)
            }

            val detail = listOfNotNull(
                solve.subId?.let { if (isEn) "Sub #$it" else "任务 #$it" },
                if (isEn) "Online plate solve" else "在线定标",
            ).joinToString(" · ")

            ctx.log.line(
                ctx.context,
                "在线识别成功：$detail 耗时=${System.currentTimeMillis() - ctx.logT0}ms",
            )
            SolveLogStore.addStep(
                ctx.report, "在线识别", "solved", detail,
                System.currentTimeMillis() - ctx.logT0,
            )
            StarSolver.writeSuccessLog(ctx.context, ctx.report, ctx.imagePath, "在线识别", detail)
            ctx.token.check()
            coroutineContext.ensureActive()
            delivered = true
            return StepOutcome.Success(EngineResult(solve, engine, detail, onlineDisplay))
        } finally {
            if (!delivered) onlineDisplay?.takeUnless { it.isRecycled }?.recycle()
            upload.delete()
        }
    }
}
