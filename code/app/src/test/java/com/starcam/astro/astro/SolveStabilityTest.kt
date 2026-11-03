package com.starcam.astro.astro

import com.starcam.astro.astro.steps.StepOutcome
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SolveStabilityTest {
    private fun block(token: SolveToken): Nothing { while (true) { token.check(); Thread.sleep(2) } }
    private fun success() = StepOutcome.Success(EngineResult(
        SolveResult(66.0, 20.0, 280.0, 0.0, -1, 400, 250), SolveEngine.LOCAL_MATCHER, "test"))

    @Test fun childCancellationDoesNotNeedThePollingCoroutine() {
        val parent = SolveToken(); val child = SolveToken(parent)
        parent.cancel()
        assertTrue(child.isCancelled)
        try { child.check(); fail("Expected cancellation") } catch (_: CancellationException) { }
    }

    @Test fun exhaustedBudgetIsAnErrorRatherThanSilentUserCancellation() {
        val parent = SolveToken(timeoutMillis = 0); val child = SolveToken(parent)
        assertTrue(child.isCancelled)
        try { child.check(); fail("Expected deadline") } catch (_: SolveBudgetExceeded) { }
        assertEquals(java.lang.Boolean.TYPE, child.javaClass.getMethod("isCancelled").returnType)
    }

    @Test fun cancellationReachesSynchronousEngineBeforeItReturns() = runBlocking {
        val token = SolveToken()
        val started = CompletableDeferred<Unit>()
        val ended = AtomicBoolean()
        val worker = launch(Dispatchers.Default) {
            withSolveCancellation(token) {
                started.complete(Unit)
                try { block(token) }
                finally { ended.set(true) }
            }
        }
        withTimeout(2000) { started.await() }
        withTimeout(2000) { worker.cancelAndJoin() }
        assertTrue(token.isCancelled); assertTrue(ended.get())
    }

    @Test fun firstSuccessCancelsBlockingLoserAndWaitsForItsCleanup() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val ended = AtomicBoolean()
        var result: StepOutcome? = null
        val elapsed = measureTimeMillis {
            withTimeout(2000) {
                result = raceEngineOutcomes(SolveToken(),
                    { started.await(); success() },
                    { token -> started.complete(Unit)
                        try { block(token) }
                        finally { ended.set(true) }
                    })
            }
        }
        assertTrue(result is StepOutcome.Success); assertTrue(ended.get()); assertTrue(elapsed < 2000)
    }

    @Test fun parentTokenAloneTerminatesTheRace() = runBlocking {
        val parent = SolveToken(); val started = CompletableDeferred<Unit>()
        val ended = AtomicBoolean()
        val worker = async {
            raceEngineOutcomes(parent,
                { token -> started.complete(Unit); try { block(token) } finally { ended.set(true) } },
                { delay(5000); StepOutcome.Skipped("unused") })
        }
        started.await(); parent.cancel()
        try { withTimeout(2000) { worker.await() }; fail("Expected cancellation") }
        catch (_: CancellationException) { }
        assertTrue(ended.get())
    }

    @Test fun timeoutStopsBothChildren() = runBlocking {
        val ended = java.util.concurrent.atomic.AtomicInteger()
        val blocking: suspend (SolveToken) -> StepOutcome = { token ->
            try { block(token) }
            finally { ended.incrementAndGet() }
        }
        assertNull(withTimeoutOrNull(100) { raceEngineOutcomes(SolveToken(), blocking, blocking) })
        assertEquals(2, ended.get())
    }

    @Test fun bothFailureOrdersPreserveReplayAndAllEngineReasons() = runBlocking {
        val gray = floatArrayOf(1f, 2f)
        val local = StepOutcome.Failure("local", diagStars = listOf(DetectedStar(1f, 2f, 3f)),
            diagStarTotal = 20, graySnapshot = gray, grayW = 2, grayH = 1)
        val native = StepOutcome.Failure("native")
        for (order in listOf(local to native, native to local)) {
            val result = raceEngineOutcomes(SolveToken(), { order.first }, { delay(20); order.second }) as StepOutcome.Failure
            assertTrue(result.reason.contains("local") && result.reason.contains("native"))
            assertSame(gray, result.graySnapshot); assertEquals(20, result.diagStarTotal)
            assertEquals(2, result.grayW); assertEquals(1, result.grayH)
        }
    }
}
