package com.starcam.astro.astro

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.random.Random

/** Fits a whole-photo tangent projection from a verified sky-region match and additional stars. */
internal object SkyRegionCalibration {
    data class Result(
        val wcs: WcsTransform,
        val scaleArcsec: Double,
        val orientationDeg: Double,
        val pairs: List<Pair<DetectedStar, StarEntry>>,
        val checkError: Double,
    )

    private data class Sample(val point: DetectedStar, val star: StarEntry, val east: Double, val north: Double)

    // Screen pixels as an affine function of tangent-plane coordinates in degrees.
    private data class Fit(val x: DoubleArray, val y: DoubleArray) {
        val det: Double get() = x[0] * y[1] - x[1] * y[0]
        val scale: Double get() = 3600.0 / sqrt(abs(det))
        val wellShaped: Boolean get() {
            val eastLength = hypot(x[0], y[0])
            val northLength = hypot(x[1], y[1])
            val dot = x[0] * x[1] + y[0] * y[1]
            return eastLength / northLength in 0.8..1.25 && abs(dot) <= 0.2 * eastLength * northLength
        }

        fun residual(s: Sample): Double = hypot(
            x[0] * s.east + x[1] * s.north + x[2] - s.point.x,
            y[0] * s.east + y[1] * s.north + y[2] - s.point.y,
        )

        fun wcs(center: DoubleArray, height: Int): WcsTransform = WcsTransform(
            x[2] + 1.0, height - y[2], center[0], center[1],
            y[1] / det, x[1] / det, -y[0] / det, -x[0] / det,
        )
    }

    fun refine(
        width: Int,
        height: Int,
        skyHeight: Int,
        center: DoubleArray,
        parity: Int,
        anchors: List<Pair<DetectedStar, StarEntry>>,
        stars: List<DetectedStar>,
        token: SolveToken,
    ): Result? {
        val scaleUnit = (maxOf(width, height) / 1354.0).coerceAtLeast(0.6)
        fun sample(point: DetectedStar, star: StarEntry): Sample {
            val (east, north) = LocalStarMatcher.tanXY(star.ra, star.dec, center[0], center[1])
            return Sample(point, star, east, north)
        }
        val seed = LocalStarMatcher.fitPairs(
            anchors.map { it.first }, anchors.mapIndexed { i, pair -> i to pair.second },
            width, height, mirrorX = parity == -1, fixedRa0 = center[0], fixedDec0 = center[1],
        ) ?: return null
        val seedWcs = LocalStarMatcher.buildWcs(seed, width, height, mirrorX = parity == -1)
        val seedScale = hypot(seed.a, seed.b) * 3600.0
        val anchorSamples = anchors.map { (point, star) -> sample(point, star) }
        val anchorIds = anchors.mapTo(HashSet()) { it.second.hip }
        val candidateStars = stars.filter { point ->
            anchors.none { (anchor, _) -> hypot(point.x - anchor.x, point.y - anchor.y) < 3f }
        }

        // Keep a fixed subset out of every fit so upper-sky accuracy is measured with other stars.
        fun heldOut(star: StarEntry): Boolean = star.hip % 4 == 0
        fun search(wcs: WcsTransform, radius: Double, mag: Double): List<Sample> =
            associate(wcs, width, height, skyHeight, candidateStars, anchorIds, radius * scaleUnit, mag, token)
                .map { (point, star) -> sample(point, star) }

        val bright = search(seedWcs, 26.0, 4.8)
        val coarse = consensus(anchorSamples, bright.filterNot { heldOut(it.star) }, parity, seedScale, 11.0 * scaleUnit, token)
            ?: return null
        val faint = search(coarse.wcs(center, height), 13.0, 5.4)
        val refined = consensus(anchorSamples, faint.filterNot { heldOut(it.star) }, parity, seedScale, 5.5 * scaleUnit, token)
            ?: return null
        val wcs = refined.wcs(center, height)
        val tolerance = 4.5 * scaleUnit
        val matched = anchorSamples + search(wcs, 4.5, 6.5)
        val verified = matched.filter { refined.residual(it) <= tolerance }
        val check = verified.filter { it.star.hip !in anchorIds && heldOut(it.star) }
        if (verified.size < 10 || check.size < 3 || check.none { it.point.y < skyHeight * 0.32 }) return null
        if (verified.maxOf { it.point.x } - verified.minOf { it.point.x } < width * 0.43 ||
            verified.maxOf { it.point.y } - verified.minOf { it.point.y } < skyHeight * 0.52
        ) return null
        return Result(
            wcs, refined.scale, Math.toDegrees(atan2(wcs.cd21, wcs.cd11 * parity)),
            verified.map { it.point to it.star },
            check.map(refined::residual).average(),
        )
    }

    /** A star and a detection must be each other's nearest candidate inside the search radius. */
    private fun associate(
        wcs: WcsTransform, width: Int, height: Int, skyHeight: Int,
        detections: List<DetectedStar>, excluded: Set<Int>, radius: Double, mag: Double, token: SolveToken,
    ): List<Pair<DetectedStar, StarEntry>> {
        val nearest = arrayOfNulls<StarEntry>(detections.size)
        val distance = DoubleArray(detections.size) { radius * radius }
        val bestForStar = HashMap<Int, Int>()
        for ((index, star) in StarCatalogData.stars.withIndex()) {
            if (index % 256 == 0) token.check()
            if (star.mag > mag || star.hip in excluded) continue
            val (x, y) = wcs.skyToScreen(star.ra, star.dec, width, height)
            if (!x.isFinite() || !y.isFinite() ||
                x < -radius || x >= width + radius || y < -radius || y >= skyHeight + radius
            ) continue
            var closest = radius * radius
            for ((i, p) in detections.withIndex()) {
                val dx = (p.x - x).toDouble()
                val dy = (p.y - y).toDouble()
                val squared = dx * dx + dy * dy
                if (squared < closest) {
                    closest = squared
                    bestForStar[star.hip] = i
                }
                if (squared < distance[i]) {
                    distance[i] = squared
                    nearest[i] = star
                }
            }
        }
        return detections.mapIndexedNotNull { i, point ->
            nearest[i]?.takeIf { bestForStar[it.hip] == i }?.let { point to it }
        }
    }

    private fun consensus(
        anchors: List<Sample>, extra: List<Sample>, parity: Int,
        seedScale: Double, tolerance: Double, token: SolveToken,
    ): Fit? {
        if (extra.size < 4) return null
        val samples = anchors + extra
        fun plausible(fit: Fit): Boolean = fit.wellShaped && fit.scale / seedScale in 0.8..1.25 &&
            (fit.det > 0.0) == (parity == 1)
        val random = Random(42)
        var best = emptyList<Sample>()
        var bestError = Double.POSITIVE_INFINITY
        repeat(120) {
            if (it % 16 == 0) token.check()
            val a = random.nextInt(samples.size)
            val b = random.nextInt(samples.size)
            val c = random.nextInt(samples.size)
            if (a == b || a == c || b == c) return@repeat
            val fit = fit(listOf(samples[a], samples[b], samples[c])) ?: return@repeat
            if (!plausible(fit) || anchors.count { fit.residual(it) <= tolerance } < 5) return@repeat
            val inliers = samples.filter { fit.residual(it) <= tolerance }
            val error = inliers.sumOf { fit.residual(it) }
            if (inliers.size > best.size || inliers.size == best.size && error < bestError) {
                best = inliers
                bestError = error
            }
        }
        if (best.size < 9) return null
        return fit(best)?.takeIf(::plausible)
    }

    private fun fit(samples: List<Sample>): Fit? {
        val east = DoubleArray(samples.size) { samples[it].east }
        val north = DoubleArray(samples.size) { samples[it].north }
        val one = DoubleArray(samples.size) { 1.0 }
        val terms = arrayOf(east, north, one)
        fun axis(coordinate: (DetectedStar) -> Float): DoubleArray? {
            val system = Array(3) { row ->
                DoubleArray(4) { col ->
                    if (col == 3) samples.indices.sumOf { terms[row][it] * coordinate(samples[it].point) }
                    else samples.indices.sumOf { terms[row][it] * terms[col][it] }
                }
            }
            return LocalStarMatcher.gaussSolveN(system)
        }
        return Fit(axis { it.x } ?: return null, axis { it.y } ?: return null)
    }
}
