package com.starcam.astro

import com.starcam.astro.astro.LocalStarMatcher
import com.starcam.astro.astro.DetectedStar
import com.starcam.astro.astro.SkyRegionCalibration
import com.starcam.astro.astro.SkyRegionRecovery
import com.starcam.astro.astro.SolveToken
import com.starcam.astro.astro.StarCatalogData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class SkyRegionRecoveryTest {
    @Test
    fun foregroundRecoveryKeepsStarsAlignedOnTheFullFrame() {
        val width = 900
        val height = 1500
        val sky = SyntheticSkyRenderer.render(30.0, 0.0, 65.0, width, 4.0)
        val gray = sky.gray.copyOf(width * height)
        for (i in sky.gray.indices) gray[i] *= 0.3f
        val cleanStars = LocalStarMatcher.detectStarsGray(width, width, sky.gray)
        assertNull(SkyRegionRecovery.foregroundEdge(cleanStars, width, width) { sky.gray })

        for (y in 975 until height - 8 step 17) {
            for (x in 8 until width - 8 step 21) {
                for (dy in 0..4) for (dx in 0..4) gray[(y + dy) * width + x + dx] = 210f
            }
        }
        val whole = LocalStarMatcher.detectStarsGray(width, height, gray, maxStars = 100)
        assertEquals(SkyRegionRecovery.Edge.BOTTOM, SkyRegionRecovery.foregroundEdge(whole, width, height) { gray })
        val result = SkyRegionRecovery.solve(gray, width, height, null, SolveToken())
        assertNotNull("The foreground fixture must recover from the upper sky", result)
        val solve = result!!.match.solve
        assertEquals(width, solve.imageWidth)
        assertEquals(height, solve.imageHeight)
        val wcs = solve.wcs!!
        val center = wcs.fitsPixelToSky(width / 2.0 + 0.5, height / 2.0 + 0.5)
        assertEquals(center[0], solve.raDeg, 1e-9)
        assertEquals(center[1], solve.decDeg, 1e-9)
        val reprojected = result.match.matchedPairs.count { (_, star) ->
            val expected = SyntheticSkyRenderer.project(star.ra, star.dec, 30.0, 0.0, 65.0 / width, width, width)
            val actual = wcs.skyToScreen(star.ra, star.dec, width, height)
            expected != null && hypot(actual[0] - expected.first, actual[1] - expected.second) <= 5.0
        }
        assertTrue("Catalog positions must still land on the original pixels", reprojected >= 8)
        val upperSkyErrors = StarCatalogData.stars.filter { it.mag <= 4.0 }.mapNotNull { star ->
            val expected = SyntheticSkyRenderer.project(star.ra, star.dec, 30.0, 0.0, 65.0 / width, width, width)
                ?.takeIf { it.second < width * 0.25 } ?: return@mapNotNull null
            val actual = wcs.skyToScreen(star.ra, star.dec, width, height)
            hypot(actual[0] - expected.first, actual[1] - expected.second)
        }
        assertTrue("The sky edge must align with independent synthetic ground truth",
            upperSkyErrors.size >= 3 && upperSkyErrors.all { it <= 5.0 })
        assertNull(SkyRegionRecovery.solve(gray, width, height, 20.0, SolveToken()))

        for (edge in listOf(SkyRegionRecovery.Edge.LEFT, SkyRegionRecovery.Edge.RIGHT, SkyRegionRecovery.Edge.TOP)) {
            val rotatedWidth = if (edge == SkyRegionRecovery.Edge.TOP) width else height
            val rotatedHeight = if (edge == SkyRegionRecovery.Edge.TOP) height else width
            val rotated = FloatArray(gray.size)
            for (y in 0 until height) for (x in 0 until width) {
                val index = when (edge) {
                    SkyRegionRecovery.Edge.LEFT -> x * rotatedWidth + (height - 1 - y)
                    SkyRegionRecovery.Edge.RIGHT -> (width - 1 - x) * rotatedWidth + y
                    else -> (height - 1 - y) * width + (width - 1 - x)
                }
                rotated[index] = gray[y * width + x]
            }
            val stars = LocalStarMatcher.detectStarsGray(rotatedWidth, rotatedHeight, rotated, maxStars = 100)
            assertEquals(edge, SkyRegionRecovery.foregroundEdge(stars, rotatedWidth, rotatedHeight) { rotated })
            val retry = SkyRegionRecovery.solve(rotated, rotatedWidth, rotatedHeight, null, SolveToken(), edge)
            assertNotNull("Must recover with foreground on $edge", retry)
            val calibrated = retry!!.match.solve
            assertEquals(rotatedWidth, calibrated.imageWidth)
            assertEquals(rotatedHeight, calibrated.imageHeight)
            assertEquals(solve.parity, calibrated.parity)
            val error = retry.match.matchedPairs.maxOf { (point, star) ->
                val (x, y) = wcs.skyToScreen(star.ra, star.dec, width, height)
                val (expectedX, expectedY) = when (edge) {
                    SkyRegionRecovery.Edge.LEFT -> height - 1 - y to x
                    SkyRegionRecovery.Edge.RIGHT -> y to width - 1 - x
                    else -> width - 1 - x to height - 1 - y
                }
                val (px, py) = calibrated.wcs!!.skyToScreen(star.ra, star.dec, rotatedWidth, rotatedHeight)
                assertTrue(hypot(point.x - px, point.y - py) <= 5.0)
                hypot(px - expectedX, py - expectedY)
            }
            assertTrue("WCS must rotate back to the source frame: $edge error=$error", error < 0.01)
        }
    }

    @Test
    fun rejectsCalibrationWithNoUpperSkyEvidence() {
        val width = 900
        val height = 900
        val center = doubleArrayOf(30.0, 0.0)
        val truth = StarCatalogData.stars.filter { it.mag <= 4.0 }.mapNotNull { star ->
            val (x, y) = SyntheticSkyRenderer.project(star.ra, star.dec, center[0], center[1], 65.0 / width, width, height)
                ?: return@mapNotNull null
            DetectedStar(x.toFloat(), y.toFloat(), 1f) to star
        }
        val anchors = truth.filter { it.first.y > height * 0.45 }.take(8)
        assertEquals(8, anchors.size)
        assertNotNull(SkyRegionCalibration.refine(
            width, height, height, center, 1, anchors, truth.map { it.first }, SolveToken(),
        ))
        val lowerDetections = truth.map { it.first }.filter { it.y > height * 0.4 }
        assertNull(SkyRegionCalibration.refine(
            width, height, height, center, 1, anchors, lowerDetections, SolveToken(),
        ))
    }
}
