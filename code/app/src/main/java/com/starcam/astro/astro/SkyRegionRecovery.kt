package com.starcam.astro.astro

import kotlin.math.abs
import kotlin.math.hypot

/** Retry a foreground-dominated photo using two overlapping upper-sky regions. */
internal object SkyRegionRecovery {
    data class Result(val match: LocalMatchResult, val stars: List<DetectedStar>)

    fun shouldTry(stars: List<DetectedStar>?, height: Int): Boolean {
        if (stars == null || stars.size < 20) return false
        return stars.count { it.y >= height * 5 / 8 } * 5 >= stars.size * 4
    }

    fun solve(
        gray: FloatArray,
        width: Int,
        height: Int,
        fovDeg: Double?,
        token: SolveToken,
    ): Result? {
        fun detectAndMatch(cropHeight: Int, minInliers: Int): Result? {
            token.check()
            val crop = gray.copyOf(width * cropHeight)
            val stars = LocalStarMatcher.detectStarsGray(width, cropHeight, crop, maxStars = 64)
            if (stars.size < minInliers) return null
            val match = LocalStarMatcher.match(stars, width, cropHeight, allowDeepRetry = false, token = token)
                ?: return null
            return if (match.inlierCount >= minInliers) Result(match, stars) else null
        }

        val largerHeight = height * 5 / 8
        val smallerHeight = height * 3 / 5
        if (smallerHeight < 64 || smallerHeight >= largerHeight) return null
        val larger = detectAndMatch(largerHeight, minInliers = 8) ?: return null
        val candidate = larger.match.solve
        val smaller = detectAndMatch(smallerHeight, minInliers = 7) ?: return null
        val check = smaller.match.solve
        if (candidate.parity != check.parity || abs(check.pixScaleArcsec / candidate.pixScaleArcsec - 1) > 0.05) {
            return null
        }
        val skyWcs = candidate.wcs ?: return null
        val checkWcs = check.wcs ?: return null
        val smallerByHip = smaller.match.matchedPairs.associateBy { it.second.hip }
        val common = larger.match.matchedPairs.filter { (point, star) ->
            val other = smallerByHip[star.hip]?.first ?: return@filter false
            val xy = skyWcs.skyToScreen(star.ra, star.dec, width, largerHeight)
            val checkXy = checkWcs.skyToScreen(star.ra, star.dec, width, smallerHeight)
            hypot(point.x - other.x, point.y - other.y) <= 3f &&
                hypot(xy[0] - checkXy[0], xy[1] - checkXy[1]) <= 6f
        }.distinctBy { it.second.hip }
        if (common.size < 6) return null
        if (common.maxOf { it.first.x } - common.minOf { it.first.x } < width * 0.2 ||
            common.maxOf { it.first.y } - common.minOf { it.first.y } < smallerHeight * 0.15
        ) return null

        // Upper crop keeps screen coordinates fixed. The old WCS estimates the whole-image
        // pointing, but its locally fitted scale and rotation must not be used for the overlay.
        val center = skyWcs.fitsPixelToSky(width / 2.0 + 0.5, largerHeight - height / 2.0 + 0.5)
        val crop = gray.copyOf(width * largerHeight)
        val stars = larger.stars + AdaptiveStarDetector.detect(width, largerHeight, crop, 100, token)
            .filter { point -> larger.stars.none { hypot(point.x - it.x, point.y - it.y) < 3f } }
        // A previously cropped photo may retain an off-center optical axis.
        // Evaluate both starting directions using the same fit and validation criteria.
        val calibration = listOf(center, doubleArrayOf(skyWcs.crval1, skyWcs.crval2))
            .mapNotNull { origin ->
                SkyRegionCalibration.refine(
                    width, height, largerHeight, origin, candidate.parity, larger.match.matchedPairs, stars, token,
                )
            }.maxWithOrNull(compareBy<SkyRegionCalibration.Result> { it.pairs.size }.thenBy { -it.checkError })
            ?: return null
        val fullWcs = calibration.wcs
        val scale = calibration.scaleArcsec
        val fullFov = maxOf(width, height) * scale / 3600.0
        if (fullFov !in 10.0..180.0) return null
        if (fovDeg != null) {
            val (low, high) = FovEstimate.fovRange(fovDeg)
            if (fullFov !in low..high) return null
        }
        val fullCenter = fullWcs.fitsPixelToSky(width / 2.0 + 0.5, height / 2.0 + 0.5)
        val full = candidate.copy(
            raDeg = fullCenter[0],
            decDeg = fullCenter[1],
            pixScaleArcsec = scale,
            orientationDeg = calibration.orientationDeg,
            imageHeight = height,
            fieldRadiusArcmin = hypot(width.toDouble(), height.toDouble()) * scale / 120.0,
            wcs = fullWcs,
        )
        token.check()
        return Result(larger.match.copy(
            solve = full, matchedPairs = calibration.pairs, inlierCount = calibration.pairs.size,
        ), stars)
    }
}
