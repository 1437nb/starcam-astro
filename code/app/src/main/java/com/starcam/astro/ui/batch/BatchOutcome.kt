package com.starcam.astro.ui.batch

/**
 * 单张批处理的**强类型结果**（P0-2.2，外部审计 2026-10-06）。
 *
 * 背景：原实现把 UI 文案当控制流契约 ——
 * `val ok = !note.startsWith("未能") && !note.startsWith("无法") && …`。
 * 改一句文案、切英文界面、加标点即**静默错判**。现在控制流只看类型，
 * [note] 只负责显示，不参与任何判断。
 *
 * 拆分依据是**失败发生在哪一步**（决定用户该做什么）：
 *  - [Saved]：识别 + 存相册都成功；
 *  - [SolvedButSaveFailed]：识别成功、存相册失败（成果在，但没落到相册）；
 *  - [SolveFailed]：识别本身没解出来（亮星不足 / 视场不支持）；
 *  - [ProcessingFailed]：识别之前/之外的处理失败（解码、渲染、异常）。
 */
sealed interface BatchOutcome {
    /** 展示文案。**仅供界面显示**，不得用于控制流判断。 */
    val note: String

    /** 已识别且已导出到相册。 */
    data class Saved(override val note: String) : BatchOutcome

    /** 已识别但保存相册失败（[note] 说明失败表现）。 */
    data class SolvedButSaveFailed(override val note: String) : BatchOutcome

    /** 识别失败（未解出天区）。 */
    data class SolveFailed(override val note: String) : BatchOutcome

    /** 处理失败（解码 / 渲染 / 未知异常）。 */
    data class ProcessingFailed(override val note: String) : BatchOutcome

    /** 是否识别成功（[Saved] 与 [SolvedButSaveFailed] 都算）。 */
    val solved: Boolean get() = this is Saved || this is SolvedButSaveFailed

    /** 是否已成功导出到相册。 */
    val exported: Boolean get() = this is Saved
}

/**
 * 批处理计数（P0-2.3，外部审计 2026-10-06）。
 *
 * 原实现只有**一个** `saved` 计数器且**无条件自增**：相册没存成也计入"已导出"，
 * 汇总文案因此在失败时说了假话。现拆三个计数器：
 *  - [solved]：识别成功（含已识别未导出）；
 *  - [saved]：成功导出到相册；
 *  - [failed]：识别或处理失败。
 *
 * 两个 worker 并发调用 [record]，故用原子计数。
 */
class BatchTally {
    private val solvedC = java.util.concurrent.atomic.AtomicInteger()
    private val savedC = java.util.concurrent.atomic.AtomicInteger()
    private val failedC = java.util.concurrent.atomic.AtomicInteger()

    /** 记一张的结果。线程安全（两个 worker 并发调用）。 */
    fun record(outcome: BatchOutcome) {
        when (outcome) {
            is BatchOutcome.Saved -> {
                solvedC.incrementAndGet()
                savedC.incrementAndGet()
            }

            is BatchOutcome.SolvedButSaveFailed -> solvedC.incrementAndGet()
            is BatchOutcome.SolveFailed,
            is BatchOutcome.ProcessingFailed,
            -> failedC.incrementAndGet()
        }
    }

    /** 识别成功张数（含未导出）。 */
    val solved: Int get() = solvedC.get()

    /** 成功导出张数。 */
    val saved: Int get() = savedC.get()

    /** 失败张数（识别失败 + 处理失败）。 */
    val failed: Int get() = failedC.get()

    /** 已处理完的张数（成功 + 失败），用于进度显示。 */
    val done: Int get() = solvedC.get() + failedC.get()

    /** 原子读取三个计数的快照（供界面状态更新使用）。 */
    fun snapshot(): BatchCounts = BatchCounts(solved, saved, failed)
}

/** 批处理计数快照（UI 展示用）。 */
data class BatchCounts(val solved: Int, val saved: Int, val failed: Int) {
    /** 已处理完的张数（成功 + 失败）。 */
    val done: Int get() = solved + failed
}
