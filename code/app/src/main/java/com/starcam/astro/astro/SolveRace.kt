package com.starcam.astro.astro

import com.starcam.astro.astro.steps.StepOutcome
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shared production race controller, also exercised with blocking test engines. */
internal suspend fun raceEngineOutcomes(
    parent: SolveToken,
    first: suspend (SolveToken) -> StepOutcome,
    second: suspend (SolveToken) -> StepOutcome,
    sharedBitmap: android.graphics.Bitmap? = null,
): StepOutcome = coroutineScope {
    val tokens = listOf(SolveToken(parent), SolveToken(parent))
    val results = Channel<StepOutcome>(Channel.UNLIMITED)
    var winner: StepOutcome.Success? = null
    fun discard(result: StepOutcome.Success) {
        result.result.displayBitmap?.takeIf {
            it !== sharedBitmap && it !== winner?.result?.displayBitmap && !it.isRecycled
        }?.recycle()
    }
    val workers = listOf(first, second).mapIndexed { i, execute ->
        launch(Dispatchers.Default) {
            try {
                val result = execute(tokens[i])
                if (results.trySend(result).isFailure && result is StepOutcome.Success) {
                    discard(result)
                }
            } catch (e: CancellationException) {
                results.close(e)
            } catch (e: Exception) {
                results.trySend(StepOutcome.Failure(e.message ?: "Engine failed"))
            }
        }
    }
    val watcher = launch {
        try {
            while (isActive) { parent.check(); delay(20) }
        } catch (e: Exception) { results.close(e) }
    }
    try {
        var failure: StepOutcome.Failure? = null
        repeat(2) {
            when (val result = results.receive()) {
                is StepOutcome.Success -> { winner = result; return@coroutineScope result }
                is StepOutcome.Failure -> failure = mergeSolveFailures(failure, result)
                is StepOutcome.Skipped -> Unit
            }
        }
        failure ?: StepOutcome.Failure("Parallel solve yielded no result")
    } finally {
        tokens.forEach { it.cancel() }
        workers.forEach { it.cancel() }
        watcher.cancel()
        // JNI may still be returning; it retains worker-owned solver data after a detach.
        withContext(NonCancellable) { workers.joinAll() }
        results.close()
        while (true) {
            val result = results.tryReceive().getOrNull() ?: break
            if (result is StepOutcome.Success && result !== winner) discard(result)
        }
    }
}
