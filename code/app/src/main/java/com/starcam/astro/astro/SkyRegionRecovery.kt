package com.starcam.astro.astro

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** Retry a foreground-dominated photo using two overlapping regions opposite its foreground. */
internal object SkyRegionRecovery {
    data class Result(val match: LocalMatchResult, val stars: List<DetectedStar>)

    enum class Edge(val orientation: PhotoOrientation) {
        BOTTOM(PhotoOrientation(1)), TOP(PhotoOrientation(3)), LEFT(PhotoOrientation(8)), RIGHT(PhotoOrientation(6)),
    }

    fun foregroundEdge(stars: List<DetectedStar>?, width: Int, height: Int, gray: () -> FloatArray): Edge? {
        if (stars == null || stars.size < 20) return null
        val supported = listOf(
            Edge.BOTTOM to stars.count { it.y >= height * 5 / 8 },
            Edge.TOP to stars.count { it.y < height * 3 / 8 },
            Edge.LEFT to stars.count { it.x < width * 3 / 8 },
            Edge.RIGHT to stars.count { it.x >= width * 5 / 8 },
        ).filter { (_, count) -> count * 5 >= stars.size * 4 }
        if (supported.size < 2) return supported.firstOrNull()?.first

        // Equally bright foreground features can fill a ranked list in row order. Resolve
        // corner ties using texture sampled from the entire image rather than that prefix.
        val pixels = gray()
        val texture = DoubleArray(Edge.entries.size)
        for (y in 0 until height - 1 step 5) for (x in 0 until width - 1 step 5) {
            val i = y * width + x
            val gradient = abs(pixels[i] - pixels[i + 1]) + abs(pixels[i] - pixels[i + width])
            if (!gradient.isFinite()) continue
            if (y >= height * 5 / 8) texture[Edge.BOTTOM.ordinal] += gradient.toDouble()
            if (y < height * 3 / 8) texture[Edge.TOP.ordinal] += gradient.toDouble()
            if (x < width * 3 / 8) texture[Edge.LEFT.ordinal] += gradient.toDouble()
            if (x >= width * 5 / 8) texture[Edge.RIGHT.ordinal] += gradient.toDouble()
        }
        return supported.maxWith(compareBy<Pair<Edge, Int>> { texture[it.first.ordinal] }.thenBy { it.second }).first
    }

    fun solve(
        gray: FloatArray,
        width: Int,
        height: Int,
        fovDeg: Double?,
        token: SolveToken,
        edge: Edge = Edge.BOTTOM,
    ): Result? {
        if (edge == Edge.BOTTOM) return solveUpperSky(gray, width, height, fovDeg, token)

        val orientation = edge.orientation
        val rotatedWidth = orientation.width(width, height)
        val rotatedHeight = orientation.height(width, height)
        val origin = orientation.inverse(0f, 0f, width, height)
        val axisX = orientation.inverse(1f, 0f, width, height)
        val axisY = orientation.inverse(0f, 1f, width, height)
        val a = (axisX[0] - origin[0]).toInt()
        val b = (axisY[0] - origin[0]).toInt()
        val c = (axisX[1] - origin[1]).toInt()
        val d = (axisY[1] - origin[1]).toInt()
        val start = origin[1].toInt() * width + origin[0].toInt()
        val stepX = c * width + a
        val stepY = d * width + b
        val rotated = FloatArray(gray.size)
        for (y in 0 until rotatedHeight) {
            if (y % 32 == 0) token.check()
            var source = start + stepY * y
            for (x in 0 until rotatedWidth) {
                rotated[y * rotatedWidth + x] = gray[source]
                source += stepX
            }
        }
        val result = solveUpperSky(rotated, rotatedWidth, rotatedHeight, fovDeg, token) ?: return null
        val solve = result.match.solve
        val wcs = solve.wcs ?: return null
        val rx = wcs.crpix1 - 1.0
        val ry = rotatedHeight - wcs.crpix2
        // Rotate offsets in FITS coordinates (Y up), then compose with the original CD.
        val originalWcs = WcsTransform(
            origin[0] + a * rx + b * ry + 1.0, height - (origin[1] + c * rx + d * ry),
            wcs.crval1, wcs.crval2,
            a * wcs.cd11 - b * wcs.cd12, -c * wcs.cd11 + d * wcs.cd12,
            a * wcs.cd21 - b * wcs.cd22, -c * wcs.cd21 + d * wcs.cd22,
        )
        val center = originalWcs.fitsPixelToSky(width / 2.0 + 0.5, height / 2.0 + 0.5)
        fun original(point: DetectedStar): DetectedStar {
            val (x, y) = orientation.inverse(point.x, point.y, width, height)
            return point.copy(x = x, y = y)
        }
        token.check()
        return Result(
            result.match.copy(
                solve = solve.copy(
                    raDeg = center[0], decDeg = center[1], imageWidth = width, imageHeight = height,
                    orientationDeg = Math.toDegrees(atan2(originalWcs.cd21, originalWcs.cd11 * solve.parity)),
                    wcs = originalWcs,
                ),
                matchedPairs = result.match.matchedPairs.map { (point, star) -> original(point) to star },
            ),
            result.stars.map(::original),
        )
    }

    private fun solveUpperSky(
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
