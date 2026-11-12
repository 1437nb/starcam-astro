package com.starcam.astro.astro

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.sqrt

/** Finds compact peaks with high contrast relative to the noise in their immediate sky. */
internal object CompactStarDetector {
    private const val BACKGROUND_RADIUS = 12
    private const val SUPPRESSION_RADIUS = 7
    private const val NOISE_FLOOR = 8.0
    private const val MAX_STARS = 64
    private data class Peak(val index: Int, val contrast: Float)

    fun detect(width: Int, height: Int, gray: FloatArray, token: SolveToken): List<DetectedStar> {
        require(width > 0 && height > 0 && width.toLong() * height == gray.size.toLong())
        token.check()
        val radius = BACKGROUND_RADIUS
        val side = 2 * radius + 1
        if (width < side || height < side) return emptyList()
        val area = (side * side).toDouble()
        val columnSum = DoubleArray(width)
        val columnSquares = DoubleArray(width)
        val contrast = FloatArray(gray.size)
        fun sample(index: Int): Double = gray[index].toDouble().let { if (it.isFinite()) it else 0.0 }

        // Rolling box moments avoid two full-size summed-area tables on Android.
        for (y in 0 until side) for (x in 0 until width) {
            val v = sample(y * width + x)
            columnSum[x] += v
            columnSquares[x] += v * v
        }
        for (y in radius until height - radius) {
            if (y % 16 == 0) token.check()
            var sum = 0.0
            var squares = 0.0
            for (x in 0 until side) {
                sum += columnSum[x]
                squares += columnSquares[x]
            }
            for (x in radius until width - radius) {
                val i = y * width + x
                val background = sum / area
                val noise = sqrt(maxOf(0.0, squares / area - background * background))
                var core = 0.0
                for (dy in -1..1) for (dx in -1..1) core += sample(i + dy * width + dx)
                contrast[i] = ((core / 9 - background) / (noise + NOISE_FLOOR)).toFloat()
                val entering = x + radius + 1
                if (entering < width) {
                    sum += columnSum[entering] - columnSum[x - radius]
                    squares += columnSquares[entering] - columnSquares[x - radius]
                }
            }
            val entering = y + radius + 1
            if (entering < height) for (x in 0 until width) {
                val added = sample(entering * width + x)
                val removed = sample((y - radius) * width + x)
                columnSum[x] += added - removed
                columnSquares[x] += added * added - removed * removed
            }
        }

        val peakOrder = compareByDescending<Peak> { it.contrast }.thenBy { it.index }
        val peaks = PriorityQueue(peakOrder.reversed())
        val limit = MAX_STARS * 16
        for (y in radius until height - radius) {
            if (y % 16 == 0) token.check()
            for (x in radius until width - radius) {
                val i = y * width + x
                val strength = contrast[i]
                if (strength <= 0f || (peaks.size >= limit && strength <= requireNotNull(peaks.peek()).contrast)) continue
                var strongest = true
                for (dy in -1..1) for (dx in -1..1) {
                    val j = i + dy * width + dx
                    if (contrast[j] > strength || (contrast[j] == strength && j < i)) strongest = false
                }
                if (!strongest) continue
                if (peaks.size >= limit) peaks.poll()
                peaks.add(Peak(i, strength))
            }
        }
        val selected = ArrayList<Peak>(MAX_STARS)
        for (peak in peaks.sortedWith(peakOrder)) {
            token.check()
            val x = peak.index % width
            val y = peak.index / width
            if (selected.any {
                    abs(it.index % width - x) <= SUPPRESSION_RADIUS &&
                        abs(it.index / width - y) <= SUPPRESSION_RADIUS
                }
            ) continue
            selected.add(peak)
            if (selected.size == MAX_STARS) break
        }
        return selected.map { peak ->
            val x = peak.index % width
            val y = peak.index / width
            var background = 0.0
            for (dy in -radius..radius) for (dx in -radius..radius) {
                background += sample(peak.index + dy * width + dx)
            }
            background /= area
            var flux = 0.0
            var sumX = 0.0
            var sumY = 0.0
            for (dy in -2..2) for (dx in -2..2) {
                val weight = maxOf(0.0, sample(peak.index + dy * width + dx) - background)
                flux += weight
                sumX += dx * weight
                sumY += dy * weight
            }
            DetectedStar(
                (x + if (flux > 0) sumX / flux else 0.0).toFloat(),
                (y + if (flux > 0) sumY / flux else 0.0).toFloat(),
                peak.contrast,
            )
        }
    }
}
