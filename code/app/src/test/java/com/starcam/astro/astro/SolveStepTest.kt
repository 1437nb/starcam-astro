package com.starcam.astro.astro

import com.starcam.astro.astro.steps.StepOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 求解步骤与执行上下文测试（纯 JVM）。
 */
class SolveStepTest {

    @Test
    fun stepOutcome_variants_behaveCorrectly() {
        val successSolve = SolveResult(
            raDeg = 10.0,
            decDeg = 20.0,
            pixScaleArcsec = 1.5,
            orientationDeg = 0.0,
            parity = 1,
            imageWidth = 1000,
            imageHeight = 1000,
            fieldRadiusArcmin = 60.0,
        )
        val engineResult = EngineResult(
            solve = successSolve,
            engine = SolveEngine.LOCAL_MATCHER,
            detail = "test-detail",
            displayBitmap = null,
        )

        val outcomeSuccess: StepOutcome = StepOutcome.Success(engineResult)
        assertTrue(outcomeSuccess is StepOutcome.Success)
        assertEquals(SolveEngine.LOCAL_MATCHER, (outcomeSuccess as StepOutcome.Success).result.engine)

        val outcomeFailure: StepOutcome = StepOutcome.Failure(
            reason = "unmatched",
            fovOverride = 45.0,
        )
        assertTrue(outcomeFailure is StepOutcome.Failure)
        assertEquals("unmatched", (outcomeFailure as StepOutcome.Failure).reason)
        assertEquals(45.0, outcomeFailure.fovOverride!!, 0.001)

        val outcomeSkipped: StepOutcome = StepOutcome.Skipped("budget depleted")
        assertTrue(outcomeSkipped is StepOutcome.Skipped)
        assertEquals("budget depleted", (outcomeSkipped as StepOutcome.Skipped).reason)
    }

    @Test
    fun budgetRemaining_calculation() {
        val now = System.currentTimeMillis()
        val deadlineMs = now + 10_000L
        val remainingSec = (deadlineMs - System.currentTimeMillis()) / 1000.0
        assertTrue(remainingSec > 8.0)
        assertFalse(remainingSec <= 0.5)

        val depletedDeadline = now + 200L
        val depletedRemaining = (depletedDeadline - System.currentTimeMillis()) / 1000.0
        assertTrue(depletedRemaining <= 0.5)
    }
}
