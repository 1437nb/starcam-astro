package com.starcam.astro.astro

import java.util.concurrent.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 求解取消令牌（用于跨协程、调度器与底层算法循环传递取消信号）。
 *
 * 解决问题：
 * 1. 用户按返回键或点击取消时，仅取消协程外壳无法中止底层密集计算循环；
 * 2. 计算期间占用 [LocalStarMatcher.solveLock] 导致下一张照片排队阻塞；
 * 3. 通过周期性调用 [check] 快速抛出 [CancellationException]，实现毫秒级终止并释放锁资源。
 */
class SolveToken(
    private val parent: SolveToken? = null,
    timeoutMillis: Long? = null,
) {
    private val startedNanos = System.nanoTime()
    private val budgetNanos = timeoutMillis?.coerceIn(0, Long.MAX_VALUE / 1_000_000)?.times(1_000_000)

    @Volatile
    private var cancelled = false

    /** JNI also polls this getter; deadline uses a monotonic clock. */
    val isCancelled: Boolean
        get() = cancelled || parent?.isCancelled == true ||
            budgetNanos?.let { System.nanoTime() - startedNanos >= it } == true

    /** 请求取消求解 */
    fun cancel() {
        cancelled = true
    }

    /** 检查是否已被取消，若是则抛出 [CancellationException] */
    fun check() {
        if (cancelled) {
            throw CancellationException("Plate solve was cancelled")
        }
        parent?.check()
        if (budgetNanos?.let { System.nanoTime() - startedNanos >= it } == true) {
            throw SolveBudgetExceeded()
        }
    }
}

internal class SolveBudgetExceeded : RuntimeException("Plate solve time budget exhausted")

/** Cancellation handler runs inline even while the solve thread is in synchronous code. */
internal suspend fun <T> withSolveCancellation(token: SolveToken, action: suspend () -> T): T =
    kotlinx.coroutines.coroutineScope {
        val operation = this
        val guard = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            kotlinx.coroutines.suspendCancellableCoroutine<Nothing> { continuation ->
                continuation.invokeOnCancellation { token.cancel() }
            }
        }
        val watcher = launch {
            while (isActive) {
                try { token.check() }
                catch (e: CancellationException) { operation.cancel(e); return@launch }
                delay(20)
            }
        }
        try {
            token.check()
            action()
        } finally {
            watcher.cancel()
            guard.cancel()
        }
    }
