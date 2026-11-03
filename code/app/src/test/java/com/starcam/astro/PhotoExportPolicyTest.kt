package com.starcam.astro

import com.starcam.astro.util.PhotoExportPolicy
import com.starcam.astro.util.PhotoSourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoExportPolicyTest {
    @Test fun rotationAccountsForBothFullSizeBitmaps() {
        val regular = PhotoExportPolicy.estimateBytes(6000, 4000, PhotoSourceKind.IMAGE, 5000000, false)
        val rotated = PhotoExportPolicy.estimateBytes(6000, 4000, PhotoSourceKind.IMAGE, 5000000, true)
        assertEquals(6000L * 4000 * 4, rotated - regular)
        assertEquals(6000L * 4000 * 4 + PhotoExportPolicy.RESERVE, regular)
    }

    @Test fun fitsAndRawBudgetIncludeTheirDecodingIntermediates() {
        val image = PhotoExportPolicy.estimateBytes(4000, 3000, PhotoSourceKind.IMAGE, 24000000, false)
        val fits = PhotoExportPolicy.estimateBytes(4000, 3000, PhotoSourceKind.FITS, 24000000, false)
        val raw = PhotoExportPolicy.estimateBytes(4000, 3000, PhotoSourceKind.RAW_PREVIEW, 24000000, false)
        assertEquals(4000L * 3000 * 12 + PhotoExportPolicy.RESERVE, fits)
        assertEquals(image + 24000000L * 3, raw)
    }

    @Test fun oversizedAndOverflowingInputsAreRejectedBeforeAllocating() {
        for ((w, h) in listOf(0 to 100, -1 to 100, 70000 to 10, 65500 to 65500)) {
            assertEquals(Long.MAX_VALUE, PhotoExportPolicy.estimateBytes(w, h, PhotoSourceKind.IMAGE, 0, false))
        }
        assertEquals(Long.MAX_VALUE, PhotoExportPolicy.estimateBytes(1, 1, PhotoSourceKind.RAW_PREVIEW, Long.MAX_VALUE, false))
    }

    @Test fun budgetRespectsHeapSystemMemoryAndFixedCapWithoutOverflow() {
        assertEquals(75L, PhotoExportPolicy.budget(100, 4000))
        assertEquals(25L, PhotoExportPolicy.budget(4000, 100))
        assertEquals(0L, PhotoExportPolicy.budget(-1, 4000))
        assertEquals(PhotoExportPolicy.MAX_BUDGET, PhotoExportPolicy.budget(Long.MAX_VALUE, Long.MAX_VALUE))
    }

    @Test fun fitsFootprintMatchesIntegerAreaSamplingWithRemainders() {
        assertEquals(6000 to 3999, PhotoExportPolicy.fitsFootprint(6001, 4000, 2200))
        assertEquals(6600 to 4000, PhotoExportPolicy.fitsFootprint(6600, 4000, 2000))
        assertEquals(100 to 101, PhotoExportPolicy.fitsFootprint(100, 101, 2200))
    }
}
