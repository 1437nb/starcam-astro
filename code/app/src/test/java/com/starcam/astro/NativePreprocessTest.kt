package com.starcam.astro

import com.starcam.astro.astro.DetectedStar
import com.starcam.astro.astro.NativePreprocess
import com.starcam.astro.astro.SkyRegionRecovery
import com.starcam.astro.astro.SolveResult
import com.starcam.astro.astro.WcsTransform
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §0.144 P3 复杂星空原生预处理（天空裁剪 WCS 回映、前景色差保护与去背景降噪）单元测试。
 */
class NativePreprocessTest {

    @Test
    fun `remapCroppedSolve preserves skyToScreen coordinates across entire frame`() {
        val fullW = 2200
        val fullH = 1466
        val crop = NativePreprocess.CropRect(x0 = 0, y0 = 0, width = 2200, height = 952) // BOTTOM cropped

        // Simulated WCS solved on cropped bitmap around Orion
        val cropWcs = WcsTransform(
            crpix1 = 1100.5,
            crpix2 = 476.5,
            crval1 = 83.822,
            crval2 = -5.391,
            cd11 = -0.0085,
            cd12 = 0.0012,
            cd21 = 0.0012,
            cd22 = 0.0085,
        )
        val cropSolve = SolveResult(
            raDeg = 83.822,
            decDeg = -5.391,
            pixScaleArcsec = hypot(-0.0085, 0.0012) * 3600.0,
            orientationDeg = 8.0,
            parity = 1,
            imageWidth = crop.width,
            imageHeight = crop.height,
            fieldRadiusArcmin = 350.0,
            nMatch = 22,
            nStars = 180,
            indexId = 4116,
            logodds = 120.0,
            wcs = cropWcs,
        )

        val remapped = NativePreprocess.remapCroppedSolve(cropSolve, crop, fullW, fullH)
        val fullWcs = remapped.wcs
        assertNotNull(fullWcs)
        assertEquals(fullW, remapped.imageWidth)
        assertEquals(fullH, remapped.imageHeight)

        // Check corners + center + off-crop points: sky -> fullScreen must equal sky -> cropScreen + (x0, y0)
        val testPoints = listOf(
            83.822 to -5.391,
            80.1 to -3.2,
            87.5 to -7.4,
            81.0 to -8.1,
            86.2 to -2.5,
        )
        for ((ra, dec) in testPoints) {
            val cropPx = cropWcs.skyToScreen(ra, dec, crop.width, crop.height)
            val fullPx = fullWcs!!.skyToScreen(ra, dec, fullW, fullH)
            assertEquals("x mismatch for ($ra, $dec)", cropPx[0] + crop.x0, fullPx[0], 1e-4f)
            assertEquals("y mismatch for ($ra, $dec)", cropPx[1] + crop.y0, fullPx[1], 1e-4f)
        }
    }

    @Test
    fun `remapCroppedSolve preserves exact equivalence for TOP LEFT and RIGHT crops`() {
        val fullW = 1600
        val fullH = 2000
        val rects = listOf(
            NativePreprocess.buildCropRect(SkyRegionRecovery.Edge.TOP, fullW, fullH, 0.65),
            NativePreprocess.buildCropRect(SkyRegionRecovery.Edge.LEFT, fullW, fullH, 0.65),
            NativePreprocess.buildCropRect(SkyRegionRecovery.Edge.RIGHT, fullW, fullH, 0.65),
        )
        for (crop in rects) {
            val cropWcs = WcsTransform(
                crpix1 = crop.width * 0.5 + 0.5,
                crpix2 = crop.height * 0.5 + 0.5,
                crval1 = 210.0,
                crval2 = 45.0,
                cd11 = -0.006,
                cd12 = 0.0005,
                cd21 = 0.0005,
                cd22 = 0.006,
            )
            val solve = SolveResult(210.0, 45.0, 21.6, 5.0, 1, crop.width, crop.height, wcs = cropWcs)
            val full = NativePreprocess.remapCroppedSolve(solve, crop, fullW, fullH)
            val fullWcs = full.wcs!!

            val cPx = cropWcs.skyToScreen(211.2, 44.3, crop.width, crop.height)
            val fPx = fullWcs.skyToScreen(211.2, 44.3, fullW, fullH)
            assertEquals((cPx[0] + crop.x0).toDouble(), fPx[0].toDouble(), 1e-4)
            assertEquals((cPx[1] + crop.y0).toDouble(), fPx[1].toDouble(), 1e-4)
        }
    }

    @Test
    fun `detectSkyCrop removes noisy bottom lights and flips dark bottom silhouette correctly`() {
        val w = 400
        val h = 400
        // Case 1: bottom bright street/harbor lights flooding bottom 3/8
        val noisyBottomGray = FloatArray(w * h) { idx ->
            val y = idx / w
            if (y >= h * 5 / 8) 90f else 15f
        }
        val bottomStars = (0 until 30).map { i ->
            DetectedStar(x = (i * 11 + 20).toFloat(), y = if (i < 26) 320f else 80f, brightness = 100f)
        }
        val res1 = NativePreprocess.detectSkyCrop(bottomStars, noisyBottomGray, w, h)
        assertNotNull(res1)
        assertEquals(SkyRegionRecovery.Edge.BOTTOM, res1!!.first)
        assertEquals(0, res1.second.y0)
        assertEquals((h * 0.65).toInt(), res1.second.height)

        // Case 2: uniform all-sky stars -> no crop triggered
        val uniformStars = (0 until 32).map { i ->
            DetectedStar(x = ((i % 8) * 45 + 20).toFloat(), y = ((i / 8) * 90 + 40).toFloat(), brightness = 100f)
        }
        assertNull(NativePreprocess.detectSkyCrop(uniformStars, FloatArray(w * h) { 20f }, w, h))
    }

    @Test
    fun `cleanBackgroundAndSpots flattens gradient and suppresses isolated hot pixels`() {
        val w = 144
        val h = 144
        val gray = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                // Strong moonlight/skyglow gradient from 120 down to 15
                gray[y * w + x] = 15f + 105f * (y.toFloat() / (h - 1))
            }
        }
        // Inject 3x3 real PSF star at (40, 40)
        for (dy in -1..1) for (dx in -1..1) {
            val weight = if (dx == 0 && dy == 0) 90f else 45f
            gray[(40 + dy) * w + (40 + dx)] += weight
        }
        // Inject 1-pixel sensor hot spot at (90, 90)
        gray[90 * w + 90] += 140f

        val cleaned = NativePreprocess.cleanBackgroundAndSpots(gray, w, h, tileSize = 24)
        assertEquals(gray.size, cleaned.size)

        // Gradient at dark vs bright sky background should now be flattened close to ~10
        val bgTop = cleaned[10 * w + 10]
        val bgBottom = cleaned[130 * w + 130]
        assertTrue("background gradient should be flattened (top=$bgTop bottom=$bgBottom)", abs(bgBottom - bgTop) < 12f)

        // Real 3x3 star peak must remain prominent above background
        val starPeak = cleaned[40 * w + 40]
        assertTrue("real PSF star peak ($starPeak) must survive", starPeak > 75f)

        // Isolated single-pixel hot spot must be suppressed down near background
        val hotSpot = cleaned[90 * w + 90]
        assertTrue("isolated hot pixel ($hotSpot) must be suppressed", hotSpot < 25f)

        // Adaptive plim recommendation check
        assertEquals(0.0, NativePreprocess.recommendedPlim(800), 1e-6)
        assertEquals(11.5, NativePreprocess.recommendedPlim(1800), 1e-6)
        assertEquals(13.5, NativePreprocess.recommendedPlim(3000), 1e-6)
        assertEquals(16.0, NativePreprocess.recommendedPlim(7000), 1e-6)
    }
}
