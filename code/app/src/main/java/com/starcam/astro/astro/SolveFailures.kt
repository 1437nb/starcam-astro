package com.starcam.astro.astro

import com.starcam.astro.astro.steps.StepOutcome

/** A fast native failure must not discard the later local replay/diagnostics. */
internal fun mergeSolveFailures(a: StepOutcome.Failure?, b: StepOutcome.Failure): StepOutcome.Failure {
    if (a == null) return b
    val pixels = if (b.graySnapshot != null) b else a
    return StepOutcome.Failure(
        reason = listOf(a.reason, b.reason).distinct().joinToString("；"),
        fovOverride = b.fovOverride ?: a.fovOverride,
        diagStars = b.diagStars ?: a.diagStars,
        diagStarTotal = maxOf(a.diagStarTotal, b.diagStarTotal),
        graySnapshot = pixels.graySnapshot,
        grayW = pixels.grayW,
        grayH = pixels.grayH,
    )
}
