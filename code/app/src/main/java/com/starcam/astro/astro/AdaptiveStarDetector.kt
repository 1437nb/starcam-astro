package com.starcam.astro.astro

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Local background/noise and compact-source detection for failed, contaminated fields.
 * Kept separate from the fast detector: changing its input list can change triangle candidates.
 * Coordinates remain in the original image; no contrast-modified image is exported.
 */
internal object AdaptiveStarDetector {
    fun detect(
        width: Int, height: Int, gray: FloatArray, maxStars: Int = 64, token: SolveToken? = null,
    ): List<DetectedStar> {
        require(width > 0 && height > 0 && width.toLong() * height == gray.size.toLong())
        require(maxStars in 1..100)
        token?.check()
        if (width < 19 || height < 19) return emptyList()
        val cell = 64
        val gw = (width + cell - 1) / cell
        val gh = (height + cell - 1) / cell
        val background = FloatArray(gw * gh)
        val noise = FloatArray(gw * gh)
        val samples = FloatArray(cell * cell)
        val differences = FloatArray(cell * cell)
        for (gy in 0 until gh) {
            token?.check()
            for (gx in 0 until gw) {
                var ns = 0
                var nd = 0
                val xEnd = min(width, (gx + 1) * cell)
                val yEnd = min(height, (gy + 1) * cell)
                for (y in gy * cell until yEnd step 2) {
                    for (x in gx * cell until xEnd step 2) {
                        val v = gray[y * width + x]
                        if (!v.isFinite()) continue
                        samples[ns++] = v
                        if (x + 1 < xEnd && gray[y * width + x + 1].isFinite()) {
                            differences[nd++] = abs(v - gray[y * width + x + 1])
                        }
                        if (y + 1 < yEnd && gray[(y + 1) * width + x].isFinite()) {
                            differences[nd++] = abs(v - gray[(y + 1) * width + x])
                        }
                    }
                }
                java.util.Arrays.sort(samples, 0, ns)
                java.util.Arrays.sort(differences, 0, nd)
                val at = gy * gw + gx
                background[at] = if (ns > 0) samples[ns / 2] else 0f
                // Median |X-Y| / (sqrt(2) * Phi^-1(.75)); robust to sparse stars/hot pixels.
                noise[at] = if (nd > 0) max(0.5f, differences[nd / 2] * 1.04836f) else 0.5f
            }
        }
        fun interpolate(grid: FloatArray, x: Int, y: Int): Float {
            val fx = ((x + 0.5f) / cell - 0.5f).coerceIn(0f, (gw - 1).toFloat())
            val fy = ((y + 0.5f) / cell - 0.5f).coerceIn(0f, (gh - 1).toFloat())
            val x0 = fx.toInt(); val y0 = fy.toInt()
            val x1 = min(x0 + 1, gw - 1); val y1 = min(y0 + 1, gh - 1)
            val dx = fx - x0; val dy = fy - y0
            val top = grid[y0 * gw + x0] * (1 - dx) + grid[y0 * gw + x1] * dx
            val bottom = grid[y1 * gw + x0] * (1 - dx) + grid[y1 * gw + x1] * dx
            return top * (1 - dy) + bottom * dy
        }
        val residual = FloatArray(gray.size)
        for (y in 0 until height) {
            if (y % 32 == 0) token?.check()
            for (x in 0 until width) {
                val i = y * width + x
                residual[i] = if (gray[i].isFinite()) gray[i] - interpolate(background, x, y) else 0f
            }
        }
        val smooth = FloatArray(gray.size)
        for (y in 1 until height - 1) {
            if (y % 32 == 0) token?.check()
            for (x in 1 until width - 1) {
                val i = y * width + x
                smooth[i] = (residual[i - width - 1] + 2 * residual[i - width] + residual[i - width + 1] +
                    2 * residual[i - 1] + 4 * residual[i] + 2 * residual[i + 1] +
                    residual[i + width - 1] + 2 * residual[i + width] + residual[i + width + 1]) / 16
            }
        }
        // Bound metadata even for a textured foreground with thousands of local maxima.
        val candidateLimit = maxStars * 32
        val candidates = java.util.PriorityQueue<DetectedStar>(compareBy { it.brightness })
        val ring = FloatArray(80)
        for (y in 9 until height - 9) {
            if (y % 16 == 0) token?.check()
            for (x in 9 until width - 9) {
                val i = y * width + x
                val sigma = interpolate(noise, x, y)
                val peak = smooth[i]
                if (peak < max(1.5f, 4.5f * 0.375f * sigma)) continue
                var isPeak = true
                for (dy in -1..1) for (dx in -1..1) {
                    val j = i + dy * width + dx
                    if (j != i && (smooth[j] > peak || (smooth[j] == peak && j < i))) isPeak = false
                }
                if (!isPeak) continue
                var nr = 0
                for (d in -8..8) {
                    ring[nr++] = residual[(y - 8) * width + x + d]
                    ring[nr++] = residual[(y + 8) * width + x + d]
                    if (abs(d) < 8) {
                        ring[nr++] = residual[(y + d) * width + x - 8]
                        ring[nr++] = residual[(y + d) * width + x + 8]
                    }
                }
                java.util.Arrays.sort(ring, 0, nr)
                val floor = ring[nr / 2]
                if (peak - floor < max(1.5f, 4.5f * 0.375f * sigma)) continue
                var flux = 0.0; var core = 0.0
                var sx = 0.0; var sy = 0.0
                var sxx = 0.0; var syy = 0.0; var sxy = 0.0
                var rawPeak = 0f; var pixels = 0
                for (dy in -6..6) for (dx in -6..6) {
                    val d2 = dx * dx + dy * dy
                    if (d2 > 36) continue
                    val v = max(0f, residual[i + dy * width + dx] - floor).toDouble()
                    if (v > sigma * 2) pixels++
                    rawPeak = max(rawPeak, v.toFloat())
                    flux += v; sx += v * dx; sy += v * dy
                    sxx += v * dx * dx; syy += v * dy * dy; sxy += v * dx * dy
                    if (d2 <= 4) core += v
                }
                if (flux <= 0 || pixels < 3 || rawPeak / flux > 0.65 || core / flux < 0.25) continue
                val cx = sx / flux; val cy = sy / flux
                if (abs(cx) > 2 || abs(cy) > 2) continue
                val xx = sxx / flux - cx * cx
                val yy = syy / flux - cy * cy
                val xy = sxy / flux - cx * cy
                val delta = sqrt(max(0.0, (xx - yy) * (xx - yy) + 4 * xy * xy))
                val major = (xx + yy + delta) / 2
                val minor = (xx + yy - delta) / 2
                if (major > 12 || major > max(0.2, minor) * 9) continue
                if (candidates.size < candidateLimit || flux > (candidates.peek()?.brightness ?: Float.NEGATIVE_INFINITY)) {
                    if (candidates.size == candidateLimit) candidates.poll()
                    candidates += DetectedStar((x + cx).toFloat(), (y + cy).toFloat(), flux.toFloat())
                }
            }
        }
        // One unresolved source must not occupy several triangle seeds.
        val selected = ArrayList<DetectedStar>(maxStars)
        for (s in candidates.sortedByDescending { it.brightness }) {
            token?.check()
            if (selected.any { (it.x - s.x) * (it.x - s.x) + (it.y - s.y) * (it.y - s.y) < 36 }) continue
            selected += s
            if (selected.size == maxStars) break
        }
        return selected
    }
}
