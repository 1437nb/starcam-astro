package com.starcam.astro.astro

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * §0.144 P3 原生引擎复杂星空单因子预处理：
 * 1. 基于前景边缘检测的天空子区域裁剪与 FITS WCS 零误差坐标回映；
 * 2. 分块分位数背景扣除与孤立热点抑制，压制月光晕/城市光害/噪点导致的星点爆炸；
 * 3. 自适应星点提取显著度阈值（plim）。
 */
internal object NativePreprocess {

    data class CropRect(val x0: Int, val y0: Int, val width: Int, val height: Int)

    private const val MIN_CROP_SIDE_PX = 200

    /**
     * 检测显著干扰前景所在的边缘，返回待剔除前景边缘及应保留的天空子矩形。
     * 优先复用 [SkyRegionRecovery.foregroundEdge] 判定规则，并补充「暗前景遮挡无星带」保护，
     * 避免将纯暗树影对侧的真实星空误当作前景裁掉。
     */
    fun detectSkyCrop(
        stars: List<DetectedStar>?,
        gray: FloatArray,
        width: Int,
        height: Int,
        skyFraction: Double = 0.65,
    ): Pair<SkyRegionRecovery.Edge, CropRect>? {
        if (width < MIN_CROP_SIDE_PX || height < MIN_CROP_SIDE_PX || gray.size < width * height) {
            return null
        }
        val fraction = skyFraction.coerceIn(0.45, 0.85)
        var detectedEdge = SkyRegionRecovery.foregroundEdge(stars, width, height) { gray }

        // Guard: SkyRegionRecovery classifies any band containing >=80% of detected peaks as
        // "foreground". When dark trees/hills cover one side so stars only appear on the opposite
        // side, flip to the dark/empty opposite edge instead of cropping away the actual sky.
        if (detectedEdge != null && stars != null && stars.size >= 20) {
            val oppEdge = oppositeEdge(detectedEdge)
            val oppRect = buildCropRect(detectedEdge, width, height, fraction)
            val oppCount = stars.count { s ->
                s.x >= oppRect.x0 && s.x < oppRect.x0 + oppRect.width &&
                    s.y >= oppRect.y0 && s.y < oppRect.y0 + oppRect.height
            }
            if (oppCount * 20 <= stars.size) { // <= 5% stars in opposite region
                val edgeMean = sampleRectMean(gray, width, height, edgeBandRect(detectedEdge, width, height))
                val oppMean = sampleRectMean(gray, width, height, edgeBandRect(oppEdge, width, height))
                if (oppMean < edgeMean * 0.85) {
                    detectedEdge = oppEdge
                }
            }
        }

        // Secondary rule: no dense bright-noise cluster, but one band is near-empty of stars (<=8%)
        // while the opposite sky crop retains >=75% of stars (dark horizon / forest / mountain silhouette).
        if (detectedEdge == null && stars != null && stars.size >= 20) {
            detectedEdge = detectEmptyHorizonEdge(stars, width, height)
        }

        val edge = detectedEdge ?: return null
        val rect = buildCropRect(edge, width, height, fraction)
        if (rect.width < MIN_CROP_SIDE_PX || rect.height < MIN_CROP_SIDE_PX) return null
        if (rect.width == width && rect.height == height) return null
        return edge to rect
    }

    private fun oppositeEdge(edge: SkyRegionRecovery.Edge): SkyRegionRecovery.Edge = when (edge) {
        SkyRegionRecovery.Edge.BOTTOM -> SkyRegionRecovery.Edge.TOP
        SkyRegionRecovery.Edge.TOP -> SkyRegionRecovery.Edge.BOTTOM
        SkyRegionRecovery.Edge.LEFT -> SkyRegionRecovery.Edge.RIGHT
        SkyRegionRecovery.Edge.RIGHT -> SkyRegionRecovery.Edge.LEFT
    }

    private fun edgeBandRect(edge: SkyRegionRecovery.Edge, w: Int, h: Int): CropRect = when (edge) {
        SkyRegionRecovery.Edge.BOTTOM -> CropRect(0, h * 5 / 8, w, h - h * 5 / 8)
        SkyRegionRecovery.Edge.TOP -> CropRect(0, 0, w, h * 3 / 8)
        SkyRegionRecovery.Edge.LEFT -> CropRect(0, 0, w * 3 / 8, h)
        SkyRegionRecovery.Edge.RIGHT -> CropRect(w * 5 / 8, 0, w - w * 5 / 8, h)
    }

    private fun sampleRectMean(gray: FloatArray, w: Int, h: Int, r: CropRect): Double {
        var sum = 0.0
        var n = 0
        val xEnd = min(w, r.x0 + r.width)
        val yEnd = min(h, r.y0 + r.height)
        for (y in max(0, r.y0) until yEnd step 8) {
            val row = y * w
            for (x in max(0, r.x0) until xEnd step 8) {
                val v = gray[row + x]
                if (v.isFinite()) {
                    sum += v
                    n++
                }
            }
        }
        return if (n > 0) sum / n else 0.0
    }

    private fun detectEmptyHorizonEdge(stars: List<DetectedStar>, w: Int, h: Int): SkyRegionRecovery.Edge? {
        val total = stars.size
        val candidates = listOf(
            SkyRegionRecovery.Edge.BOTTOM to stars.count { it.y >= h * 5 / 8 },
            SkyRegionRecovery.Edge.TOP to stars.count { it.y < h * 3 / 8 },
            SkyRegionRecovery.Edge.LEFT to stars.count { it.x < w * 3 / 8 },
            SkyRegionRecovery.Edge.RIGHT to stars.count { it.x >= w * 5 / 8 },
        )
        val empty = candidates.filter { (_, c) -> c * 12 <= total } // <= ~8% of stars
        if (empty.isEmpty()) return null
        // Pick the edge whose complementary 65% sky retains the most stars (>=75%).
        return empty.mapNotNull { (edge, _) ->
            val rect = buildCropRect(edge, w, h, 0.65)
            val kept = stars.count { s ->
                s.x >= rect.x0 && s.x < rect.x0 + rect.width &&
                    s.y >= rect.y0 && s.y < rect.y0 + rect.height
            }
            if (kept * 4 >= total * 3) edge to kept else null
        }.maxByOrNull { it.second }?.first
    }

    internal fun buildCropRect(
        edge: SkyRegionRecovery.Edge,
        width: Int,
        height: Int,
        skyFraction: Double = 0.65,
    ): CropRect {
        val f = skyFraction.coerceIn(0.45, 0.85)
        return when (edge) {
            SkyRegionRecovery.Edge.BOTTOM -> CropRect(0, 0, width, (height * f).toInt())
            SkyRegionRecovery.Edge.TOP -> {
                val ch = (height * f).toInt()
                CropRect(0, height - ch, width, ch)
            }
            SkyRegionRecovery.Edge.LEFT -> {
                val cw = (width * f).toInt()
                CropRect(width - cw, 0, cw, height)
            }
            SkyRegionRecovery.Edge.RIGHT -> CropRect(0, 0, (width * f).toInt(), height)
        }
    }

    /** 抽取子矩形区域内的连续灰度浮点数组。 */
    fun cropGray(gray: FloatArray, fullW: Int, fullH: Int, rect: CropRect): FloatArray {
        require(rect.x0 >= 0 && rect.y0 >= 0 && rect.x0 + rect.width <= fullW && rect.y0 + rect.height <= fullH)
        val out = FloatArray(rect.width * rect.height)
        for (row in 0 until rect.height) {
            System.arraycopy(
                gray, (rect.y0 + row) * fullW + rect.x0,
                out, row * rect.width,
                rect.width,
            )
        }
        return out
    }

    /**
     * 将裁剪图上的解精确平移回全图 FITS 坐标系：
     * - `full_crpix1 = crop_crpix1 + x0`
     * - `full_crpix2 = crop_crpix2 + (fullH - y0 - cropH)`
     * `cd` 矩阵与 `crval` 保持不变，全图叠加与四角像素重映射误差为 0。
     */
    fun remapCroppedSolve(
        solve: SolveResult,
        crop: CropRect,
        fullW: Int,
        fullH: Int,
    ): SolveResult {
        val wcs = solve.wcs ?: return solve.copy(imageWidth = fullW, imageHeight = fullH)
        val fullWcs = WcsTransform(
            crpix1 = wcs.crpix1 + crop.x0,
            crpix2 = wcs.crpix2 + (fullH - crop.y0 - crop.height),
            crval1 = wcs.crval1,
            crval2 = wcs.crval2,
            cd11 = wcs.cd11,
            cd12 = wcs.cd12,
            cd21 = wcs.cd21,
            cd22 = wcs.cd22,
        )
        val center = fullWcs.fitsPixelToSky(fullW / 2.0 + 0.5, fullH / 2.0 + 0.5)
        val radiusArcmin = hypot(fullW.toDouble(), fullH.toDouble()) * solve.pixScaleArcsec / 120.0
        return solve.copy(
            raDeg = center[0],
            decDeg = center[1],
            imageWidth = fullW,
            imageHeight = fullH,
            fieldRadiusArcmin = radiusArcmin,
            wcs = fullWcs,
        )
    }

    /**
     * 局部背景拟合扣除 + 孤立单像素热点抑制（< 80ms）。
     * 用于城市光害/月晕渐变或银河密集星场下 `simplexy` 产生数千伪峰时的降噪重提。
     */
    fun cleanBackgroundAndSpots(
        gray: FloatArray,
        width: Int,
        height: Int,
        tileSize: Int = 48,
    ): FloatArray {
        val n = width * height
        if (width <= 4 || height <= 4 || gray.size < n) return gray.copyOf()
        val ts = tileSize.coerceIn(16, 128)
        val cols = max(1, (width + ts - 1) / ts)
        val rows = max(1, (height + ts - 1) / ts)
        val tileBg = FloatArray(cols * rows)

        // Coarse tile 25th-percentile background estimate (subsampled stride-2 inside each tile)
        val hist = IntArray(256)
        for (tr in 0 until rows) {
            val y0 = tr * ts
            val y1 = min(height, y0 + ts)
            for (tc in 0 until cols) {
                val x0 = tc * ts
                val x1 = min(width, x0 + ts)
                hist.fill(0)
                var count = 0
                for (y in y0 until y1 step 2) {
                    val base = y * width
                    for (x in x0 until x1 step 2) {
                        val b = gray[base + x].toInt().coerceIn(0, 255)
                        hist[b]++
                        count++
                    }
                }
                val target = max(1, (count * 7) / 20)
                var acc = 0
                var bgEst = 0
                for (bin in 0 until 256) {
                    acc += hist[bin]
                    if (acc >= target) {
                        bgEst = bin
                        break
                    }
                }
                tileBg[tr * cols + tc] = bgEst.toFloat()
            }
        }

        val out = FloatArray(n)
        val invTs = 1f / ts
        for (y in 0 until height) {
            val fy = (y + 0.5f) * invTs - 0.5f
            val r0 = fy.toInt().coerceIn(0, rows - 1)
            val r1 = min(rows - 1, r0 + 1)
            val wy = (fy - r0).coerceIn(0f, 1f)
            val base = y * width
            for (x in 0 until width) {
                val fx = (x + 0.5f) * invTs - 0.5f
                val c0 = fx.toInt().coerceIn(0, cols - 1)
                val c1 = min(cols - 1, c0 + 1)
                val wx = (fx - c0).coerceIn(0f, 1f)

                val b00 = tileBg[r0 * cols + c0]
                val b01 = tileBg[r0 * cols + c1]
                val b10 = tileBg[r1 * cols + c0]
                val b11 = tileBg[r1 * cols + c1]
                val bg = (b00 * (1f - wx) + b01 * wx) * (1f - wy) +
                    (b10 * (1f - wx) + b11 * wx) * wy

                var v = gray[base + x]
                // Single-pixel hot-spot despike on interior pixels: sharp isolated impulse (>maxNeighbor+45 & >2.2x)
                if (x in 1 until (width - 1) && y in 1 until (height - 1)) {
                    val l = gray[base + x - 1]
                    val r = gray[base + x + 1]
                    val u = gray[base - width + x]
                    val d = gray[base + width + x]
                    val maxN = maxOf(l, r, u, d)
                    if (v > maxN + 45f && v > maxN * 2.2f) {
                        v = maxN
                    }
                }
                val cleaned = (v - bg + 10f).coerceIn(0f, 255f)
                out[base + x] = cleaned
            }
        }
        return out
    }

    /** 根据首轮实测 simplexy 检出星数推荐自适应显著度阈值；0.0 表示保持默认(8.0)。 */
    fun recommendedPlim(nStarsObserved: Int?): Double = when {
        nStarsObserved == null -> 0.0
        nStarsObserved >= 5000 -> 16.0
        nStarsObserved >= 2500 -> 13.5
        nStarsObserved >= 1500 -> 11.5
        else -> 0.0
    }
}
