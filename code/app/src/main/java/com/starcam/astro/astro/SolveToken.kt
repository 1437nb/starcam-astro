package com.starcam.astro.astro

import java.util.concurrent.CancellationException

/**
 * 求解取消令牌（用于跨协程、调度器与底层算法循环传递取消信号）。
 *
 * 解决问题：
 * 1. 用户按返回键或点击取消时，仅取消协程外壳无法中止底层密集计算循环；
 * 2. 计算期间占用 [LocalStarMatcher.solveLock] 导致下一张照片排队阻塞；
 * 3. 通过周期性调用 [check] 快速抛出 [CancellationException]，实现毫秒级终止并释放锁资源。
 */
class SolveToken {

    @Volatile
    var isCancelled: Boolean = false
        private set

    /** 请求取消求解 */
    fun cancel() {
        isCancelled = true
    }

    /** 检查是否已被取消，若是则抛出 [CancellationException] */
    fun check() {
        if (isCancelled) {
            throw CancellationException("Plate solve was cancelled")
        }
    }
}
