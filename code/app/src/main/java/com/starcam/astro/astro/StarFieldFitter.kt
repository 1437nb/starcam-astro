package com.starcam.astro.astro

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 星场对应集的 RANSAC 采样、Gauss-Newton 非线性 WCS 精化与全星表内点门禁（§0.142 模块化解耦）。
 */
internal object StarFieldFitter {

    internal const val ORIGIN_ITERATIONS = 4

    class FitParams(
        val a: Double,
        val b: Double,
        val tx: Double,
        val ty: Double,
        val ra0: Double,
        val dec0: Double,
    )

    /**
     * 给定投票对应集，两个 parity 各自拟合验证，返回通过全部门槛的最优者。
     */
    fun bestCandidate(
        detected: List<DetectedStar>,
        width: Int,
        height: Int,
        pairs: List<Pair<Int, StarEntry>>,
        pointingHint: PointingHint? = null,
        mag: Float = TriangleStarIndex.PROD_CATALOG_MAG,
        fovRange: Pair<Double, Double>? = null,
    ): LocalMatchResult? {
        if (pairs.size < 4) return null
        val normal = fitAndVerify(detected, width, height, pairs, mirrorX = false, mag = mag)
        val mirrored = fitAndVerify(detected, width, height, pairs, mirrorX = true, mag = mag)
        return listOfNotNull(normal, mirrored)
            .filter { it.inlierCount >= 6 && plausibleFov(it.solve.pixScaleArcsec, width, height) }
            .filter {
                fovRange == null || maxOf(it.solve.fieldWidthDeg, it.solve.fieldHeightDeg) in fovRange.first..fovRange.second
            }
            .filter { skySpanConsistent(it, detected) }
            .filter { candidate ->
                if (pointingHint == null) true
                else {
                    StarFieldGeometry.haversineDeg(
                        candidate.solve.raDeg,
                        candidate.solve.decDeg,
                        pointingHint.raDeg,
                        pointingHint.decDeg,
                    ) <= pointingHint.radiusDeg + 35.0
                }
            }
            .maxByOrNull { it.inlierCount }
    }

    /** 星点天球跨度一致性（视场-星密度校验的几何形式） */
    private fun skySpanConsistent(result: LocalMatchResult, detected: List<DetectedStar>): Boolean {
        val wcs = result.solve.wcs ?: return true
        val minFov = min(result.solve.fieldWidthDeg, result.solve.fieldHeightDeg)
        if (!minFov.isFinite() || minFov <= 0.0) return false
        val height = result.solve.imageHeight
        val ra = DoubleArray(detected.size)
        val dec = DoubleArray(detected.size)
        for (i in detected.indices) {
            val sky = wcs.fitsPixelToSky(detected[i].x + 1.0, (height - detected[i].y).toDouble())
            ra[i] = sky[0]
            dec[i] = sky[1]
        }
        var span = 0.0
        for (i in detected.indices) {
            for (j in i + 1 until detected.size) {
                val d = StarFieldGeometry.haversineDeg(ra[i], dec[i], ra[j], dec[j])
                if (d > span) span = d
            }
        }
        return span >= 0.30 * minFov
    }

    /** 像素比例尺 → 图像边长视场（度），必须在 0.05°~180° 才算合理天区 */
    private fun plausibleFov(pixScaleArcsec: Double, width: Int, height: Int): Boolean {
        if (!pixScaleArcsec.isFinite() || pixScaleArcsec <= 0.0) return false
        val fovDeg = max(width, height) * pixScaleArcsec / 3600.0
        return fovDeg in 0.05..180.0
    }

    /** 用对应点拟合相似变换 */
    fun fitPairs(
        detected: List<DetectedStar>,
        pairs: List<Pair<Int, StarEntry>>,
        width: Int,
        height: Int,
        mirrorX: Boolean = false,
        fixedRa0: Double? = null,
        fixedDec0: Double? = null,
    ): FitParams? {
        if (pairs.size < 3) return null
        val ra0: Double
        val dec0: Double
        if (fixedRa0 != null && fixedDec0 != null) {
            ra0 = fixedRa0
            dec0 = fixedDec0
        } else {
            val sm = StarFieldGeometry.sphericalMeanOf(pairs.map { it.second })
            ra0 = sm[0]
            dec0 = sm[1]
        }
        val cx = width / 2f
        val cy = height / 2f
        val xs = ArrayList<Float>(pairs.size)
        val ys = ArrayList<Float>(pairs.size)
        val Xs = ArrayList<Float>(pairs.size)
        val Ys = ArrayList<Float>(pairs.size)
        for ((_, pair) in pairs.withIndex()) {
            val d = detected[pair.first]
            val (X, Y) = StarFieldGeometry.tanXY(pair.second.ra, pair.second.dec, ra0, dec0)
            if (X.isNaN() || Y.isNaN()) continue
            xs.add(d.x - cx)
            ys.add(d.y - cy)
            Xs.add((if (mirrorX) -X else X).toFloat())
            Ys.add(Y.toFloat())
        }
        if (xs.size < 3) return null
        val fit = StarFieldGeometry.fitSimilarity(
            xs.toFloatArray(), ys.toFloatArray(), Xs.toFloatArray(), Ys.toFloatArray(),
        ) ?: return null
        return FitParams(fit[0], fit[1], fit[2], fit[3], ra0, dec0)
    }

    /** 由拟合结果构建原始照片坐标系的 WCS（屏幕 y 向下 → FITS y 向上） */
    fun buildWcs(fp: FitParams, width: Int, height: Int, mirrorX: Boolean = false): WcsTransform {
        val den = fp.a * fp.a + fp.b * fp.b
        val cx = width / 2f
        val cy = height / 2f
        val ka = (fp.a * fp.tx + fp.b * fp.ty) / den
        val lg = (fp.b * fp.tx - fp.a * fp.ty) / den
        val crpix1 = cx + 1.0 - ka
        val crpix2 = height - cy - lg
        val uRef = (crpix1 - 1.0) - cx
        val vRef = (height - crpix2) - cy
        val sx = fp.a * uRef - fp.b * vRef + fp.tx
        val sy = fp.b * uRef + fp.a * vRef + fp.ty
        val xwC = if (mirrorX) -sx else sx
        val (raC, decC) = StarFieldGeometry.tanInverse(xwC, sy, fp.ra0, fp.dec0)
        return if (mirrorX) WcsTransform(
            crpix1 = crpix1,
            crpix2 = crpix2,
            crval1 = raC,
            crval2 = decC,
            cd11 = -fp.a, cd12 = -fp.b,
            cd21 = fp.b, cd22 = -fp.a,
        ) else WcsTransform(
            crpix1 = crpix1,
            crpix2 = crpix2,
            crval1 = raC,
            crval2 = decC,
            cd11 = fp.a, cd12 = fp.b,
            cd21 = fp.b, cd22 = -fp.a,
        )
    }

    fun fitAndVerify(
        detected: List<DetectedStar>,
        width: Int,
        height: Int,
        pairs: List<Pair<Int, StarEntry>>,
        mirrorX: Boolean,
        mag: Float = TriangleStarIndex.PROD_CATALOG_MAG,
    ): LocalMatchResult? {
        if (pairs.size < 4) return null
        val rng = Random(42)
        val sm0 = StarFieldGeometry.sphericalMeanOf(pairs.map { it.second })
        val setRa0 = sm0[0]
        val setDec0 = sm0[1]

        val iterations = when {
            pairs.size <= 12 -> 120
            pairs.size <= 30 -> 200
            else -> 300
        }
        var bestInliers: List<Pair<Int, StarEntry>> = emptyList()
        var bestParams: FitParams? = null
        repeat(iterations) {
            val sample = buildList {
                val seen = HashSet<Int>()
                while (size < 3) {
                    val i = rng.nextInt(pairs.size)
                    if (seen.add(i)) add(pairs[i])
                }
            }
            val fp = fitPairs(detected, sample, width, height, mirrorX) ?: return@repeat
            val wcs = buildWcs(fp, width, height, mirrorX)
            val inl = pairs.filter { (pi, star) ->
                val d = detected[pi]
                val p = wcs.skyToScreen(star.ra, star.dec, width, height)
                abs(p[0] - d.x) < 45f && abs(p[1] - d.y) < 45f
            }
            if (inl.size > bestInliers.size) {
                bestInliers = inl
                bestParams = fp
            }
        }
        val ransacParams = bestParams ?: return null
        if (bestInliers.size < 3) return null

        val refined0 = fitPairs(detected, bestInliers, width, height, mirrorX, setRa0, setDec0) ?: ransacParams
        var refined = refineWcs(detected, bestInliers, width, height, refined0, mirrorX)

        // §0.63 切平面原点迭代到图像中心
        repeat(ORIGIN_ITERATIONS) {
            val w = buildWcs(refined, width, height, mirrorX)
            val (cRa, cDec) = w.fitsPixelToSky(width / 2.0 + 0.5, height / 2.0 + 0.5)
            if (cRa.isNaN() || cDec.isNaN()) return@repeat
            val next0 = fitPairs(detected, bestInliers, width, height, mirrorX, cRa, cDec)
                ?: return@repeat
            refined = refineWcs(
                detected, bestInliers, width, height, next0, mirrorX, fixOrigin = true,
            )
        }

        val finalWcs = buildWcs(refined, width, height, mirrorX)

        val inliers = countInliers(finalWcs, detected, width, height, mag = mag)
        if (inliers < 5) return null

        val matched = bestInliers.mapNotNull { (pi, star) ->
            val d = detected[pi]
            val p = finalWcs.skyToScreen(star.ra, star.dec, width, height)
            if (abs(p[0] - d.x) < 8f && abs(p[1] - d.y) < 8f) d to star else null
        }

        val pixScale = sqrt(refined.a * refined.a + refined.b * refined.b) * 3600.0
        val orientation = Math.toDegrees(atan2(refined.b, refined.a))
        val (raC, decC) = finalWcs.fitsPixelToSky(width / 2.0 + 0.5, height / 2.0 + 0.5)
        val parity = if (mirrorX) -1 else 1
        val solve = SolveResult(
            raDeg = raC,
            decDeg = decC,
            pixScaleArcsec = pixScale,
            orientationDeg = orientation,
            parity = parity,
            imageWidth = width,
            imageHeight = height,
            subId = null,
            fieldRadiusArcmin = sqrt((width * width + height * height).toDouble()) * pixScale / 120.0,
            wcs = finalWcs,
        )
        return LocalMatchResult(solve, matched, inliers)
    }

    /** Gauss-Newton 非线性精化 */
    private fun refineWcs(
        detected: List<DetectedStar>,
        pairs: List<Pair<Int, StarEntry>>,
        width: Int,
        height: Int,
        start: FitParams,
        mirrorX: Boolean,
        fixOrigin: Boolean = false,
    ): FitParams {
        val n = if (fixOrigin) 4 else 6
        val fixedRa0 = start.ra0
        val fixedDec0 = start.dec0
        val p = if (fixOrigin) {
            doubleArrayOf(start.a, start.b, start.tx, start.ty)
        } else {
            doubleArrayOf(start.a, start.b, start.tx, start.ty, start.ra0, start.dec0)
        }
        val eps = doubleArrayOf(1e-7, 1e-7, 1e-4, 1e-4, 1e-4, 1e-4)

        fun residual(params: DoubleArray): DoubleArray {
            val r0 = if (fixOrigin) fixedRa0 else params[4]
            val d0 = if (fixOrigin) fixedDec0 else params[5]
            val fp = FitParams(params[0], params[1], params[2], params[3], r0, d0)
            val wcs = buildWcs(fp, width, height, mirrorX)
            val r = DoubleArray(pairs.size * 2)
            for ((k, pair) in pairs.withIndex()) {
                val d = detected[pair.first]
                val pp = wcs.skyToScreen(pair.second.ra, pair.second.dec, width, height)
                r[k * 2] = (pp[0] - d.x).toDouble()
                r[k * 2 + 1] = (pp[1] - d.y).toDouble()
            }
            return r
        }

        var lambda = 1e-3
        var best = p
        var bestNorm = residual(best).sumOf { it * it }
        for (iter in 0 until 14) {
            val r = residual(best)
            val jac = Array(r.size) { DoubleArray(n) }
            for (c in 0 until n) {
                val e = eps[c]
                val pp = best.copyOf().also { it[c] += e }
                val pm = best.copyOf().also { it[c] -= e }
                val rp = residual(pp)
                val rm = residual(pm)
                for (i in r.indices) jac[i][c] = (rp[i] - rm[i]) / (2 * e)
            }
            val m = Array(n) { DoubleArray(n + 1) }
            for (row in 0 until n) {
                for (col in 0 until n) {
                    var s = 0.0
                    for (i in r.indices) s += jac[i][row] * jac[i][col]
                    m[row][col] = s + if (row == col) lambda * s else 0.0
                }
                var s = 0.0
                for (i in r.indices) s += jac[i][row] * r[i]
                m[row][n] = -s
            }
            val delta = StarFieldGeometry.gaussSolveN(m) ?: break
            var improved = false
            for (scale in doubleArrayOf(1.0, 0.5, 0.25, 0.125, 0.0625)) {
                val cand = DoubleArray(n) { best[it] + scale * delta[it] }
                val candNorm = residual(cand).sumOf { it * it }
                if (candNorm < bestNorm) {
                    best = cand
                    bestNorm = candNorm
                    lambda /= 3.0
                    improved = true
                    break
                }
            }
            if (!improved) lambda *= 3.0
            var deltaNorm = 0.0
            for (v in delta) deltaNorm += v * v
            if (deltaNorm < 1e-12) break
        }
        return if (fixOrigin) {
            FitParams(best[0], best[1], best[2], best[3], fixedRa0, fixedDec0)
        } else {
            FitParams(best[0], best[1], best[2], best[3], best[4], best[5])
        }
    }

    private fun countInliers(
        wcs: WcsTransform,
        detected: List<DetectedStar>,
        width: Int,
        height: Int,
        mag: Float = TriangleStarIndex.PROD_CATALOG_MAG,
    ): Int {
        var count = 0
        for (star in StarCatalogData.stars) {
            if (star.mag > mag) continue
            val p = wcs.skyToScreen(star.ra, star.dec, width, height)
            if (p[0].isNaN() || p[1].isNaN()) continue
            for (d in detected) {
                if (abs(p[0] - d.x) < 5f && abs(p[1] - d.y) < 5f) {
                    count++
                    break
                }
            }
        }
        return count
    }
}
