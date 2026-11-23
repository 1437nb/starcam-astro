package com.starcam.astro

import com.starcam.astro.astro.SolveBudgetExceeded
import com.starcam.astro.astro.SolveToken
import com.starcam.astro.astro.SolverPlanning
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §0.144 P1 步骤预算切分、加权盲解分段与子令牌超时隔离单元测试。
 */
class StepBudgetSchedulingTest {

    @Test
    fun `localStepBudgetSec returns full budget without native followup`() {
        assertEquals(30.0, SolverPlanning.localStepBudgetSec(30.0, null, hasNativeFollowUp = false), 1e-6)
        assertEquals(30.0, SolverPlanning.localStepBudgetSec(30.0, 8.0, hasNativeFollowUp = false), 1e-6)
    }

    @Test
    fun `localStepBudgetSec reserves adequate native window for blind and narrow FOV`() {
        val blind30 = SolverPlanning.localStepBudgetSec(30.0, null, hasNativeFollowUp = true)
        assertEquals(16.5, blind30, 1e-6)
        assertTrue(30.0 - blind30 >= 10.0)

        val narrow30 = SolverPlanning.localStepBudgetSec(30.0, 10.0, hasNativeFollowUp = true)
        assertEquals(10.5, narrow30, 1e-6)
        assertTrue(30.0 - narrow30 >= 19.0)

        val smallBudget = SolverPlanning.localStepBudgetSec(8.0, null, hasNativeFollowUp = true)
        assertEquals(4.0, smallBudget, 1e-6)
    }

    @Test
    fun `nativeBlindSegments cover overlapping vertical phone and narrow-mid bands`() {
        val segs = SolverPlanning.nativeBlindSegments()
        assertEquals(2, segs.size)
        assertEquals(30.0, segs[0].first, 1e-6)
        assertEquals(120.0, segs[0].second, 1e-6)
        assertEquals(1.5, segs[1].first, 1e-6)
        assertEquals(42.0, segs[1].second, 1e-6)
        // Overlap between [30..120] and [1.5..42] avoids boundary misses around 30~42 deg
        assertTrue(segs[1].second > segs[0].first)
    }

    @Test
    fun `nativeSegmentTimeLimitSec prevents first segment from starving narrow segment`() {
        val segs = SolverPlanning.nativeBlindSegments()
        val remTotal = 13.5
        val seg0Limit = SolverPlanning.nativeSegmentTimeLimitSec(0, segs, remTotal)
        // Remaining budget after seg0 runs to its full limit
        val remAfterSeg0 = remTotal - seg0Limit
        val seg1Limit = SolverPlanning.nativeSegmentTimeLimitSec(1, segs, remAfterSeg0)

        assertTrue("seg0 should not consume almost all remaining budget", seg0Limit < remTotal * 0.6)
        assertTrue("seg1 must keep meaningful budget", seg1Limit >= 4.0)
        assertEquals(0.0, SolverPlanning.nativeSegmentTimeLimitSec(2, segs, 10.0), 1e-6)
        assertEquals(0.0, SolverPlanning.nativeSegmentTimeLimitSec(0, segs, 0.4), 1e-6)
    }

    @Test
    fun `child SolveToken timeout isolates parent from budget exceeded`() {
        val parent = SolveToken(timeoutMillis = 5_000L)
        val child = SolveToken(parent, timeoutMillis = 30L)
        Thread.sleep(60L)

        var caughtBudgetExceeded = false
        try {
            child.check()
        } catch (_: SolveBudgetExceeded) {
            caughtBudgetExceeded = true
        }
        assertTrue(caughtBudgetExceeded)
        assertTrue(child.isBudgetExceeded)
        assertTrue(child.isCancelled)

        assertFalse("parent must stay uncancelled when only child expires", parent.isBudgetExceeded)
        assertFalse(parent.isCancelled)
        parent.check()
    }

    @Test
    fun `parent cancel propagates CancellationException to child SolveToken`() {
        val parent = SolveToken(timeoutMillis = 5_000L)
        val child = SolveToken(parent, timeoutMillis = 5_000L)
        parent.cancel()

        assertTrue(parent.isCancelled)
        assertTrue(child.isCancelled)

        var caughtCancel = false
        try {
            child.check()
        } catch (_: CancellationException) {
            caughtCancel = true
        }
        assertTrue(caughtCancel)
    }
}
