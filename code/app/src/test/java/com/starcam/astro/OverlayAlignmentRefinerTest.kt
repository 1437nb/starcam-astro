package com.starcam.astro

import com.starcam.astro.astro.OverlayAlignmentRefiner
import com.starcam.astro.astro.OverlayAlignmentRefiner.StarMatchPair
import com.starcam.astro.astro.SolveResult
import com.starcam.astro.astro.StarFieldGeometry
import com.starcam.astro.astro.WcsTransform
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * §0.144 P4 全图叠加精度客观评估与保守全图微调单元测试。
 */
class OverlayAlignmentRefinerTest {

    @Test
    fun `fitConservativeAffineTan eliminates global edge drift on holdout stars`() {
        val w = 2200
        val h = 1466
        val crpix1 = w / 2.0 + 0.5
        val crpix2 = h / 2.0 + 0.5
        val ra0 = 247.35
        val dec0 = -26.43

        // Ground-truth TAN WCS (~66 deg wide field, scale ~108"/px)
        val trueScale = 0.030
        val rot = Math.toRadians(12.5)
        val trueWcs = WcsTransform(
            crpix1 = crpix1,
            crpix2 = crpix2,
            crval1 = ra0,
            crval2 = dec0,
            cd11 = -trueScale * cos(rot),
            cd12 = trueScale * sin(rot),
            cd21 = trueScale * sin(rot),
            cd22 = trueScale * cos(rot),
        )

        // Initial biased WCS: slight 2.2% scale underestimate + 2.4 deg rotation error (causes ~70-120px edge drift)
        val badScale = trueScale * 0.978
        val badRot = Math.toRadians(14.9)
        val badWcs = WcsTransform(
            crpix1 = crpix1 + 4.0,
            crpix2 = crpix2 - 3.0,
            crval1 = ra0 + 0.04,
            crval2 = dec0 - 0.03,
            cd11 = -badScale * cos(badRot),
            cd12 = badScale * sin(badRot),
            cd21 = badScale * sin(badRot),
            cd22 = badScale * cos(badRot),
        )
        val badSolve = SolveResult(
            raDeg = ra0 + 0.04,
            decDeg = dec0 - 0.03,
            pixScaleArcsec = badScale * 3600.0,
            orientationDeg = 14.9,
            parity = 1,
            imageWidth = w,
            imageHeight = h,
            wcs = badWcs,
        )

        // Generate 24 synthetic whole-frame stars projected from trueWcs
        val pairs = ArrayList<StarMatchPair>()
        for (ry in 0 until 4) {
            for (rx in 0 until 6) {
                val sx = 120.0 + rx * 390.0
                val sy = 100.0 + ry * 410.0
                val sky = trueWcs.fitsPixelToSky(sx + 1.0, h - sy)
                pairs.add(StarMatchPair(sx, sy, sky[0], sky[1], mag = 2.0 + 0.1 * (ry * 6 + rx), hip = 1000 + ry * 6 + rx))
            }
        }

        val (fit, holdout) = OverlayAlignmentRefiner.splitFitAndHoldout(pairs)
        val before = OverlayAlignmentRefiner.computeMetrics(badWcs, holdout, pairs.size, w, h)
        assertTrue("baseline holdout P90 error must be high (got ${before.p90Px})", before.p90Px > 30.0)

        val refitted = OverlayAlignmentRefiner.fitConservativeAffineTan(badSolve, badWcs, fit, w, h)
        assertNotNull(refitted)
        val refitWcs = refitted!!.wcs!!
        assertTrue(OverlayAlignmentRefiner.isWellShapedTransform(badWcs, refitWcs, badSolve, refitted, w, h))

        val after = OverlayAlignmentRefiner.computeMetrics(refitWcs, holdout, pairs.size, w, h)
        assertTrue("holdout P90 must collapse to < 2px (got ${after.p90Px})", after.p90Px < 2.0)
        assertTrue("holdout max must collapse to < 3px (got ${after.maxPx})", after.maxPx < 3.0)
    }

    @Test
    fun `isWellShapedTransform rejects skewed or excessive scale and center drift`() {
        val w = 2200
        val h = 1466
        val baseWcs = WcsTransform(1100.5, 733.5, 100.0, 20.0, -0.02, 0.0, 0.0, 0.02)
        val baseSolve = SolveResult(100.0, 20.0, 72.0, 0.0, 1, w, h, wcs = baseWcs)

        // 1) Excessive center shift > 0.35 deg
        val shiftedWcs = WcsTransform(1100.5, 733.5, 101.2, 20.0, -0.02, 0.0, 0.0, 0.02)
        val shiftedSolve = baseSolve.copy(raDeg = 101.2, wcs = shiftedWcs)
        assertFalse(OverlayAlignmentRefiner.isWellShapedTransform(baseWcs, shiftedWcs, baseSolve, shiftedSolve, w, h))

        // 2) Severe anisotropy / shear
        val shearedWcs = WcsTransform(1100.5, 733.5, 100.0, 20.0, -0.025, 0.008, 0.0, 0.02)
        val shearedSolve = baseSolve.copy(wcs = shearedWcs)
        assertFalse(OverlayAlignmentRefiner.isWellShapedTransform(baseWcs, shearedWcs, baseSolve, shearedSolve, w, h))

        // 3) Inverted parity
        val flippedWcs = WcsTransform(1100.5, 733.5, 100.0, 20.0, 0.02, 0.0, 0.0, 0.02)
        val flippedSolve = baseSolve.copy(wcs = flippedWcs)
        assertFalse(OverlayAlignmentRefiner.isWellShapedTransform(baseWcs, flippedWcs, baseSolve, flippedSolve, w, h))
    }

    @Test
    fun `suburban skyglow scorpius edge drift converges under whole-frame refit`() {
        val candidates = listOf(
            File("build/complex-sky-20261123-v1/recognition-v1/suburban-skyglow-天蝎校正-v1/校正数据-v1.json"),
            File("app/build/complex-sky-20261123-v1/recognition-v1/suburban-skyglow-天蝎校正-v1/校正数据-v1.json"),
        )
        val jsonFile = candidates.firstOrNull { it.isFile }
        assumeTrue("suburban-skyglow reference corpus only tested when local build stage exists", jsonFile != null)

        val root = JSONObject(jsonFile!!.readText(Charsets.UTF_8))
        val srcSize = root.getJSONArray("source_size")
        val w = srcSize.getInt(0)
        val h = srcSize.getInt(1)
        val oldWcsJson = root.getJSONObject("old_wcs_scaled_to_source")
        val crpix = oldWcsJson.getJSONArray("crpix")
        val crval = oldWcsJson.getJSONArray("crval")
        val cd = oldWcsJson.getJSONArray("cd")
        val cd0 = cd.getJSONArray(0)
        val cd1 = cd.getJSONArray(1)
        val oldWcs = WcsTransform(
            crpix1 = crpix.getDouble(0),
            crpix2 = crpix.getDouble(1),
            crval1 = crval.getDouble(0),
            crval2 = crval.getDouble(1),
            cd11 = cd0.getDouble(0),
            cd12 = cd0.getDouble(1),
            cd21 = cd1.getDouble(0),
            cd22 = cd1.getDouble(1),
        )
        val oldScale = hypot(oldWcs.cd11, oldWcs.cd21) * 3600.0
        val oldSolve = SolveResult(oldWcs.crval1, oldWcs.crval2, oldScale, 0.0, 1, w, h, wcs = oldWcs)

        val starsArr = root.getJSONArray("control_stars")
        val pairs = ArrayList<StarMatchPair>()
        for (i in 0 until starsArr.length()) {
            val s = starsArr.getJSONObject(i)
            val obs = s.getJSONArray("obs")
            pairs.add(
                StarMatchPair(
                    screenX = obs.getDouble(0),
                    screenY = obs.getDouble(1),
                    raDeg = s.getDouble("ra"),
                    decDeg = s.getDouble("dec"),
                    mag = 2.0,
                    hip = 5000 + i,
                ),
            )
        }
        val (fit, holdout) = OverlayAlignmentRefiner.splitFitAndHoldout(pairs)
        val before = OverlayAlignmentRefiner.computeMetrics(oldWcs, holdout, pairs.size, w, h)
        assertTrue("Scorpius old WCS edge drift expected > 30px (got ${before.p90Px})", before.p90Px > 30.0)

        val refitted = OverlayAlignmentRefiner.fitConservativeAffineTan(oldSolve, oldWcs, fit, w, h)
        assertNotNull(refitted)
        val after = OverlayAlignmentRefiner.computeMetrics(refitted!!.wcs!!, holdout, pairs.size, w, h)
        assertTrue(
            "Scorpius holdout max error should drop below 6px after refit (before=${before.maxPx}, after=${after.maxPx})",
            after.maxPx < 6.0 && after.p90Px < before.p90Px * 0.25,
        )
    }
}
