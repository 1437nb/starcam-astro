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

        val key = ctx.settings.apiKey
        if (key.isBlank()) {
            return StepOutcome.Skipped("未配置 API Key")
        }

        if (ctx.isBudgetDepleted) {
            ctx.log.line(ctx.context, "在线识别：全局时间预算耗尽，跳过")
            return StepOutcome.Skipped("全局时间预算耗尽")
        }

        val isEn = LocaleState.isEnglish
        ctx.onProgress(
            if (isEn) "Local engines unmatched, uploading for online solve…"
            else "本地引擎未匹配，正在上传在线求解…",
        )

        val upload = File(ctx.context.cacheDir, "upload_${System.currentTimeMillis()}.jpg")
        try {
            val ok = ImageUtils.compressForUpload(ctx.imagePath, upload) != null
            if (!ok) return StepOutcome.Failure("图片压缩上传失败")
            ctx.token.check()

            val onlineDisplay = ImageUtils.decodeSampledBitmap(upload.absolutePath, 2200)
                ?: return StepOutcome.Failure("无法解码上传图片副本")

            val solve = try {
                val client = PlateSolveClient(ctx.settings.serverUrl)
                client.solve(upload, key) { msg ->
                    ctx.token.check()
                    ctx.onProgress(msg)
                }
            } catch (e: PlateSolveException) {
                ctx.onProgress(e.message ?: if (isEn) "Online solve unavailable" else "在线识别不可用")
                return StepOutcome.Failure(e.message ?: "在线求解失败")
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                return StepOutcome.Failure("网络异常：${e.message}")
            }

            val detail = listOfNotNull(
                solve.subId?.let { "任务 #$it" },
                "在线定标",
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
            return StepOutcome.Success(EngineResult(solve, engine, detail, onlineDisplay))
        } finally {
            upload.delete()
        }
    }
}
