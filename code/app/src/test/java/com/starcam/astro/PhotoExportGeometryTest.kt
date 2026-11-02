package com.starcam.astro

import com.starcam.astro.astro.PhotoExportGeometry
import com.starcam.astro.astro.PhotoOrientation
import com.starcam.astro.astro.SolveResult
import com.starcam.astro.astro.WcsTransform
import com.starcam.astro.astro.LayeredRenderer
import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoExportGeometryTest {
    @Test fun lineGapsScaleWithOriginalResolution() {
        val preview = FloatArray(4)
        val original = FloatArray(4)
        LayeredRenderer.shrinkInto(preview, 0f, 0f, 1.0, 200f, 100f, 2.0)
        LayeredRenderer.shrinkInto(original, 0f, 0f, 1.0, 600f, 300f, 2.0, gapScale = 3f)
        for (i in preview.indices) assertEquals(preview[i] * 3, original[i], .0001f)
    }
    private fun solve(w: Int, h: Int, parity: Int = -1): SolveResult = SolveResult(
        120.0, 22.0, 36.0, 15.0, parity, w, h,
        wcs = WcsTransform(w / 2.0 + .5, h / 2.0 + .5, 120.0, 22.0, parity * .01, .002, .003, .01),
    )

    private fun skyAt(solve: SolveResult, x: Float, y: Float): DoubleArray =
        solve.wcs!!.fitsPixelToSky(x + 1.0, solve.imageHeight - y.toDouble())

    private fun projected(g: PhotoExportGeometry, solve: SolveResult, sky: DoubleArray): FloatArray {
        val p = g.wcs(solve).skyToScreen(sky[0], sky[1], g.basisWidth, g.basisHeight)
        return g.outputPoint(p[0], p[1])
    }

    @Test fun allExifOrientationsAndInverseMatchKnownPixelPositions() {
        val expected = listOf(2f to 1f, 4f to 1f, 4f to 3f, 2f to 3f, 1f to 2f, 3f to 2f, 3f to 4f, 1f to 4f)
        for (value in 1..8) {
            val o = PhotoOrientation(value)
            val p = o.forward(2f, 1f, 7, 5)
            assertEquals(expected[value - 1].first, p[0], 0f)
            assertEquals(expected[value - 1].second, p[1], 0f)
            val original = o.inverse(p[0], p[1], 7, 5)
            assertEquals(2f, original[0], 0f); assertEquals(1f, original[1], 0f)
        }
    }

    @Test fun bitmapCanvasAndAnnotationCoordinatesSharePixelCenters() {
        for (value in 1..8) {
            val o = PhotoOrientation(value)
            val m = o.canvasMatrix(7, 5)
            for ((x, y) in listOf(0f to 0f, 6f to 0f, 0f to 4f, 6f to 4f)) {
                val expected = o.forward(x, y, 7, 5)
                val mappedX = m[0] * (x + .5f) + m[1] * (y + .5f) + m[2]
                val mappedY = m[3] * (x + .5f) + m[4] * (y + .5f) + m[5]
                assertEquals(expected[0] + .5f, mappedX, 0f)
                assertEquals(expected[1] + .5f, mappedY, 0f)
            }
        }
    }

    @Test fun offlineFullSizeProjectionKeepsRotatedAndMirroredStarsAligned() {
        for (parity in listOf(-1, 1)) {
            val solve = solve(1200, 800, parity)
            val sky = skyAt(solve, 97.3f, 131.7f)
            for (value in 1..8) {
                val orientation = PhotoOrientation(value)
                val g = PhotoExportGeometry(6000, 4000, PhotoOrientation(), orientation)
                val p = projected(g, solve, sky)
                val expected = orientation.forward((97.3f + .5f) * 5 - .5f, (131.7f + .5f) * 5 - .5f, 6000, 4000)
                assertEquals(expected[0], p[0], .002f)
                assertEquals(expected[1], p[1], .002f)
            }
        }
    }

    @Test fun onlinePortraitProjectionIsNotRotatedTwice() {
        val solve = solve(600, 900)
        val g = PhotoExportGeometry(6000, 4000, PhotoOrientation(6), PhotoOrientation(6))
        val p = projected(g, solve, skyAt(solve, 240f, 315f))
        assertEquals(4000, g.width); assertEquals(6000, g.height)
        assertEquals((240.5f * (4000f / 600)) - .5f, p[0], .002f)
        assertEquals((315.5f * (6000f / 900)) - .5f, p[1], .002f)
    }

    @Test fun online180DegreePhotoUsesUploadedPixelBasis() {
        val solve = solve(1200, 800)
        val g = PhotoExportGeometry(6000, 4000, PhotoOrientation(3), PhotoOrientation(3))
        val p = projected(g, solve, skyAt(solve, 197f, 231f))
        assertEquals(197.5f * 5 - .5f, p[0], .002f)
        assertEquals(231.5f * 5 - .5f, p[1], .002f)
    }

    @Test fun fitsCroppedRemainderDoesNotStretchOrShiftAnnotations() {
        val solve = solve(2000, 1333)
        val g = PhotoExportGeometry(6001, 4000, PhotoOrientation(), PhotoOrientation())
        val sky = skyAt(solve, 100.25f, 200.5f)
        val p = g.wcs(solve, 6000, 3999).skyToScreen(sky[0], sky[1], 6001, 4000)
        assertEquals(100.75f * 3 - .5f, p[0], .002f)
        assertEquals(201f * 3 - .5f, p[1], .002f)
    }

    @Test fun rawPreviewCanUndoUploadRotationWithoutRotatingPreviewAgain() {
        val g = PhotoExportGeometry(6000, 4000, PhotoOrientation(6), PhotoOrientation())
        val p = g.outputPoint(1000f, 2000f)
        assertEquals(2000f, p[0], 0f); assertEquals(2999f, p[1], 0f)
        assertEquals(1, PhotoOrientation(7).uploadOrientation().value)
    }
}
