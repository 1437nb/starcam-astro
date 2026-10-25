package com.starcam.astro.astro.steps

import android.content.Context
import android.graphics.Bitmap
import com.starcam.astro.astro.DetectedStar
import com.starcam.astro.astro.EngineResult
import com.starcam.astro.astro.SolveDiagnostics
import com.starcam.astro.astro.SolveEngine
import com.starcam.astro.astro.SolveToken
import com.starcam.astro.data.SettingsRepository
import com.starcam.astro.data.SolveLogStore

/**
 * 单个求解步骤的执行上下文。
 */
data class StepContext(
    val context: Context,
    val display: Bitmap,
    val imagePath: String,
    val settings: SettingsRepository,
    val fovDeg: Double?,
    val fovOverride: Double? = null,
    val scheduledSteps: List<SolveEngine> = emptyList(),
    val token: SolveToken,
    val deadlineMs: Long,
    val report: org.json.JSONObject,
    val log: SolveLogStore.Session,
    val logT0: Long,
    val onProgress: (String) -> Unit,
    val onStarsDetected: (List<SolveDiagnostics.DiagStar>, Int) -> Unit = { _, _ -> },
) {
    /** 距离全局死线剩余秒数 */
    val remainingSec: Double
        get() = (deadlineMs - System.currentTimeMillis()) / 1000.0

    /** 是否已接近预算耗尽（剩余时间 ≤ 0.5 秒） */
    val isBudgetDepleted: Boolean
        get() = remainingSec <= 0.5
}

/**
 * 单个求解步骤的执行产出。
 */
sealed interface StepOutcome {
    /** 步骤成功解出结果 */
    data class Success(val result: EngineResult) : StepOutcome

    /** 步骤执行失败或未命中 */
    data class Failure(
        val reason: String,
        val fovOverride: Double? = null,
        val diagStars: List<DetectedStar>? = null,
        val diagStarTotal: Int = 0,
        val graySnapshot: FloatArray? = null,
        val grayW: Int = 0,
        val grayH: Int = 0,
    ) : StepOutcome

    /** 步骤因前置条件不满足被跳过（如无 Key 或预算耗尽） */
    data class Skipped(val reason: String) : StepOutcome
}

/**
 * 统一求解步骤接口（§0.114 架构重构）。
 */
interface SolveStep {
    val engine: SolveEngine

    /**
     * 执行具体步骤的求解逻辑。
     * @param ctx 步骤执行上下文
     * @return 求解产出结果 [StepOutcome]
     */
    suspend fun execute(ctx: StepContext): StepOutcome
}
