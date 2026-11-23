package com.starcam.astro.astro

import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * §0.144 P4 叠加精度客观评估与全图保真微调（Holdout 评估与天区识别解耦）：
 *
 * 1. 对已解出的 WCS 在全图均匀配对实测星点与星表亮星，区分 Fit 子集与 Holdout 留出子集，
 *    独立输出 [OverlayQualityMetrics]（配对数、留出集点数、P50/P90/Max 像素残差、X/Y 覆盖跨度）。
 * 2. 仅当 Holdout 误差显著偏大（如广角畸变/局部四星定标导致的边缘漂移，`p90Px > 6.0`）且存在充分
 *    空间散布时，基于 Fit 子集执行稳健 6 参数仿射 TAN 拟合，并要求 Holdout 集满足全部硬门槛
 *    （正交性/长宽比、中心偏移 <= 0.35°、尺度变化 <= 3.5%、空间跨度、Holdout P90 改善 >= 25%）
 *    方才采纳新 WCS；任何异常或超时均安全回退原始解。
 */
internal object OverlayAlignmentRefiner {

    data class OverlayQualityMetrics(
        val matchedCount: Int,
        val holdoutCount: Int,
        val medianPx: Double,
        val p90Px: Double,
        val maxPx: Double,
        val spanXFrac: Double,
        val spanYFrac: Double,
    )

    data class RefinementOutcome(
        val solve: SolveResult,
        val before: OverlayQualityMetrics?,
        val after: OverlayQualityMetrics?,
        val applied: Boolean,
        val reason: String,
    )

    data class StarMatchPair(
        val screenX: Double,
        val screenY: Double,
        val raDeg: Double,
        val decDeg: Double,
        val mag: Double,
        val hip: Int,
    )

    private const val REFIT_TRIGGER_P90_PX = 6.0
    private const val MIN_FIT_PAIRS = 8
    private const val MIN_HOLDOUT_PAIRS = 4
    private const val MAX_CENTER_SHIFT_DEG = 0.35
    private const val MAX_SCALE_DELTA_FRAC = 0.035
    private const val MAX_ANISOTROPY_FRAC = 0.04
    private const val MAX_SKEW_COS = 0.035
    private const val MIN_SPAN_X_FRAC = 0.25
    private const val MIN_SPAN_Y_FRAC = 0.20
    private const val MIN_P90_IMPROVEMENT_FRAC = 0.25

    fun refineIfNeeded(
        solve: SolveResult,
        width: Int,
        height: Int,
        grayProvider: () -> FloatArray,
        initialStars: List<DetectedStar>? = null,
        catalogStars: List<StarEntry> = StarCatalogData.stars,
        token: SolveToken? = null,
        log: ((String) -> Unit)? = null,
    ): RefinementOutcome {
        val wcs = solve.wcs ?: return RefinementOutcome(solve, null, null, false, "no-wcs")
        if (width < 64 || height < 64) return RefinementOutcome(solve, null, null, false, "small-image")
        return try {
            token?.check()
            val detected = if (!initialStars.isNullOrEmpty() && initialStars.size >= 40) {
                initialStars
            } else {
                val gray = grayProvider()
                token?.check()
                CompactStarDetector.detect(width, height, gray, token ?: SolveToken())
            }
            if (detected.size < 12) {
                return RefinementOutcome(solve, null, null, false, "too-few-detected(${detected.size})")
            }

            val pairs = collectUniqueStarPairs(solve, wcs, detected, catalogStars, width, height, token)
            if (pairs.size < MIN_FIT_PAIRS + MIN_HOLDOUT_PAIRS) {
                val m = if (pairs.isNotEmpty()) computeMetrics(wcs, pairs, pairs.size, width, height) else null
                log?.invoke(
                    "叠加精度评估：pairs=${pairs.size}(不足微调门槛) " +
                        (m?.let { "p50=%.1fpx p90=%.1fpx max=%.1fpx span=%.2fx%.2f".format(it.medianPx, it.p90Px, it.maxPx, it.spanXFrac, it.spanYFrac) } ?: ""),
                )
                return RefinementOutcome(solve, m, null, false, "insufficient-pairs(${pairs.size})")
            }

            val (fitPairs, holdoutPairs) = splitFitAndHoldout(pairs)
            val before = computeMetrics(wcs, holdoutPairs, pairs.size, width, height)
            log?.invoke(
                "叠加精度评估：pairs=${pairs.size} holdout=${holdoutPairs.size} " +
                    "p50=%.2fpx p90=%.2fpx max=%.2fpx span=%.2fx%.2f".format(
                        before.medianPx, before.p90Px, before.maxPx, before.spanXFrac, before.spanYFrac,
                    ),
            )

            if (before.p90Px <= REFIT_TRIGGER_P90_PX) {
                return RefinementOutcome(solve, before, null, false, "already-accurate(p90=${"%.2f".format(before.p90Px)}px)")
            }

            token?.check()
            val candidate = fitConservativeAffineTan(solve, wcs, fitPairs, width, height)
                ?: return RefinementOutcome(solve, before, null, false, "fit-failed")
            val candidateWcs = candidate.wcs
                ?: return RefinementOutcome(solve, before, null, false, "no-candidate-wcs")

            if (!isWellShapedTransform(wcs, candidateWcs, solve, candidate, width, height)) {
                log?.invoke("全图保真微调：未通过形态/尺度/中心硬约束，保留原解")
                return RefinementOutcome(solve, before, null, false, "rejected-geometry-gate")
            }
            if (before.spanXFrac < MIN_SPAN_X_FRAC || before.spanYFrac < MIN_SPAN_Y_FRAC) {
                log?.invoke("全图保真微调：配对空间跨度不足(${"%.2f".format(before.spanXFrac)}x${"%.2f".format(before.spanYFrac)})，保留原解")
                return RefinementOutcome(solve, before, null, false, "rejected-span-gate")
            }

            val after = computeMetrics(candidateWcs, holdoutPairs, pairs.size, width, height)
            val p90Improved = after.p90Px <= before.p90Px * (1.0 - MIN_P90_IMPROVEMENT_FRAC)
            val medianOk = after.medianPx <= before.medianPx + 0.5
            if (!p90Improved || !medianOk) {
                log?.invoke(
                    "全图保真微调：Holdout 改善未达 25%% 门槛 (p90 %.2f->%.2fpx, p50 %.2f->%.2fpx)，保留原解"
                        .format(before.p90Px, after.p90Px, before.medianPx, after.medianPx),
                )
                return RefinementOutcome(solve, before, after, false, "rejected-improvement-gate")
            }

            log?.invoke(
                "全图保真微调：已采纳(holdout p50 %.2f->%.2fpx, p90 %.2f->%.2fpx, max %.2f->%.2fpx)"
                    .format(before.medianPx, after.medianPx, before.p90Px, after.p90Px, before.maxPx, after.maxPx),
            )
            RefinementOutcome(candidate, before, after, true, "accepted")
        } catch (e: SolveBudgetExceeded) {
            RefinementOutcome(solve, null, null, false, "refine-budget-exceeded")
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            RefinementOutcome(solve, null, null, false, "error:${e.javaClass.simpleName}")
        }
    }

    /**
     * 两阶段去重配对：
     * 1. 先按较紧半径寻找高置信亮星配对；若外围边缘存在系统性漂移导致初始配对稀疏或残差高，
     *    在较大搜索半径内收集无冲突的最亮唯一配对，再用鲁棒相似变换过滤假配对。
     */
    internal fun collectUniqueStarPairs(
        solve: SolveResult,
        wcs: WcsTransform,
        detected: List<DetectedStar>,
        catalogStars: List<StarEntry>,
        width: Int,
        height: Int,
        token: SolveToken? = null,
    ): List<StarMatchPair> {
        val longEdge = max(width, height).toDouble()
        val fovRadiusDeg = hypot(solve.fieldWidthDeg, solve.fieldHeightDeg) * 0.55 + 3.0
        val centerSky = wcs.fitsPixelToSky(width / 2.0 + 0.5, height / 2.0 + 0.5)
        val marginPx = 6.0

        val projectedCatalog = catalogStars
            .asSequence()
            .filter { StarFieldGeometry.haversineDeg(centerSky[0], centerSky[1], it.ra, it.dec) <= fovRadiusDeg }
            .sortedBy { it.mag }
            .take(180)
            .mapNotNull { cat ->
                val xy = wcs.skyToScreen(cat.ra, cat.dec, width, height)
                val x = xy[0].toDouble()
                val y = xy[1].toDouble()
                if (!x.isFinite() || !y.isFinite()) return@mapNotNull null
                if (x < marginPx || x > width - marginPx || y < marginPx || y > height - marginPx) return@mapNotNull null
                Triple(cat, x, y)
            }
            .toList()

        if (projectedCatalog.isEmpty()) return emptyList()
        val detSorted = detected.sortedByDescending { it.brightness }.take(220)
        val maxSearchPx = min(140.0, max(22.0, longEdge * 0.055))

        val candidates = ArrayList<Pair<StarMatchPair, Double>>()
        for ((cat, px, py) in projectedCatalog) {
            token?.check()
            var bestIdx = -1
            var bestDist = Double.MAX_VALUE
            var secondDist = Double.MAX_VALUE
            for (i in detSorted.indices) {
                val d = detSorted[i]
                val dist = hypot(d.x - px, d.y - py)
                if (dist < bestDist) {
                    secondDist = bestDist
                    bestDist = dist
                    bestIdx = i
                } else if (dist < secondDist) {
                    secondDist = dist
                }
            }
            if (bestIdx >= 0 && bestDist <= maxSearchPx) {
                // Reject ambiguous dense ties when residual is large
                if (bestDist <= 15.0 || secondDist >= bestDist * 1.35) {
                    val d = detSorted[bestIdx]
                    candidates.add(
                        StarMatchPair(d.x.toDouble(), d.y.toDouble(), cat.ra, cat.dec, cat.mag, cat.hip) to bestDist,
                    )
                }
            }
        }

        // Enforce 1-to-1 detected star uniqueness (keep closest residual per detected star)
        val usedDet = HashMap<Long, Pair<StarMatchPair, Double>>()
        for (entry in candidates.sortedBy { it.second }) {
            val key = ((entry.first.screenX * 10.0).toLong() shl 20) xor (entry.first.screenY * 10.0).toLong()
            if (!usedDet.containsKey(key)) {
                usedDet[key] = entry
            }
        }
        return usedDet.values
            .map { it.first }
            .sortedWith(compareBy<StarMatchPair> { it.hip }.thenBy { it.mag })
    }

    internal fun splitFitAndHoldout(pairs: List<StarMatchPair>): Pair<List<StarMatchPair>, List<StarMatchPair>> {
        // Deterministic spatial/magnitude stratified split (every 3rd item held out so both subsets span the frame)
        val ordered = pairs.sortedWith(compareBy<StarMatchPair> { (it.screenY * 10).toInt() }.thenBy { (it.screenX * 10).toInt() })
        val fit = ArrayList<StarMatchPair>()
        val holdout = ArrayList<StarMatchPair>()
        for (i in ordered.indices) {
            if (i % 3 == 1) holdout.add(ordered[i]) else fit.add(ordered[i])
        }
        return fit to holdout
    }

    internal fun computeMetrics(
        wcs: WcsTransform,
        evalPairs: List<StarMatchPair>,
        totalMatched: Int,
        width: Int,
        height: Int,
    ): OverlayQualityMetrics {
        if (evalPairs.isEmpty()) {
            return OverlayQualityMetrics(totalMatched, 0, 0.0, 0.0, 0.0, 0.0, 0.0)
        }
        val errs = DoubleArray(evalPairs.size)
        var minX = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        for (i in evalPairs.indices) {
            val p = evalPairs[i]
            val proj = wcs.skyToScreen(p.raDeg, p.decDeg, width, height)
            errs[i] = hypot(proj[0].toDouble() - p.screenX, proj[1].toDouble() - p.screenY)
            minX = min(minX, p.screenX)
            maxX = max(maxX, p.screenX)
            minY = min(minY, p.screenY)
            maxY = max(maxY, p.screenY)
        }
        errs.sort()
        val median = if (errs.size % 2 == 1) {
            errs[errs.size / 2]
        } else {
            0.5 * (errs[errs.size / 2 - 1] + errs[errs.size / 2])
        }
        val p90Idx = ((errs.size - 1) * 0.90).toInt().coerceIn(0, errs.size - 1)
        val maxErr = errs[errs.size - 1]
        val spanX = ((maxX - minX) / width.coerceAtLeast(1)).coerceIn(0.0, 1.0)
        val spanY = ((maxY - minY) / height.coerceAtLeast(1)).coerceIn(0.0, 1.0)
        return OverlayQualityMetrics(totalMatched, evalPairs.size, median, errs[p90Idx], maxErr, spanX, spanY)
    }

    /**
     * 基于 Fit 子集拟合全图 6 参数 TAN 仿射解，并在质心/图像中心进行 2 轮重中心化与外点裁枝。
     */
    internal fun fitConservativeAffineTan(
        baseSolve: SolveResult,
        baseWcs: WcsTransform,
        fitPairs: List<StarMatchPair>,
        width: Int,
        height: Int,
    ): SolveResult? {
        if (fitPairs.size < MIN_FIT_PAIRS) return null
        val crpix1 = width / 2.0 + 0.5
        val crpix2 = height / 2.0 + 0.5
        var center = baseWcs.fitsPixelToSky(crpix1, crpix2)
        if (!center[0].isFinite() || !center[1].isFinite()) return null

        var ra0 = center[0]
        var dec0 = center[1]
        var currentWcs = baseWcs

        // Iteratively reweighted / outlier-trimmed least-squares TAN fit (3 rounds)
        for (iter in 0 until 3) {
            val activePairs = if (iter == 0) {
                fitPairs
            } else {
                val resList = fitPairs.map { p ->
                    val proj = currentWcs.skyToScreen(p.raDeg, p.decDeg, width, height)
                    p to hypot(proj[0] - p.screenX, proj[1] - p.screenY)
                }.sortedBy { it.second }
                val med = resList[resList.size / 2].second
                val cutoff = max(4.0, med * 2.8)
                resList.filter { it.second <= cutoff }.map { it.first }.takeIf { it.size >= MIN_FIT_PAIRS } ?: fitPairs
            }

            val mXi = Array(3) { DoubleArray(4) }
            val mEta = Array(3) { DoubleArray(4) }
            var used = 0
            for (p in activePairs) {
                val (xi, eta) = StarFieldGeometry.tanXY(p.raDeg, p.decDeg, ra0, dec0)
                if (!xi.isFinite() || !eta.isFinite()) continue
                val fx = (p.screenX + 1.0) - crpix1
                val fy = (height - p.screenY) - crpix2
                val row = doubleArrayOf(fx, fy, 1.0)
                for (r in 0..2) {
                    for (c in 0..2) {
                        val w = row[r] * row[c]
                        mXi[r][c] += w
                        mEta[r][c] += w
                    }
                    mXi[r][3] += row[r] * xi
                    mEta[r][3] += row[r] * eta
                }
                used++
            }
            if (used < MIN_FIT_PAIRS) return null
            val solXi = LocalStarMatcher.gaussSolveN(mXi) ?: return null
            val solEta = LocalStarMatcher.gaussSolveN(mEta) ?: return null

            val cd11 = solXi[0]
            val cd12 = solXi[1]
            val cXi = solXi[2]
            val cd21 = solEta[0]
            val cd22 = solEta[1]
            val cEta = solEta[2]
            if (!cd11.isFinite() || !cd12.isFinite() || !cd21.isFinite() || !cd22.isFinite()) return null

            // Re-center tangent point so (crpix1, crpix2) maps cleanly to (crval1, crval2)
            val (newRa, newDec) = StarFieldGeometry.tanInverse(cXi, cEta, ra0, dec0)
            ra0 = newRa
            dec0 = newDec
            currentWcs = WcsTransform(crpix1, crpix2, ra0, dec0, cd11, cd12, cd21, cd22)
        }

        val finalCenter = currentWcs.fitsPixelToSky(crpix1, crpix2)
        val scaleDegPerPx = 0.5 * (
            hypot(currentWcs.cd11, currentWcs.cd21) + hypot(currentWcs.cd12, currentWcs.cd22)
        )
        val pixScaleArcsec = scaleDegPerPx * 3600.0
        val det = currentWcs.cd11 * currentWcs.cd22 - currentWcs.cd12 * currentWcs.cd21
        val parity = if (det < 0.0) 1 else -1
        val orient = Math.toDegrees(atan2(currentWcs.cd21, currentWcs.cd11))
        val radiusArcmin = hypot(width.toDouble(), height.toDouble()) * pixScaleArcsec / 120.0
        return baseSolve.copy(
            raDeg = finalCenter[0],
            decDeg = finalCenter[1],
            pixScaleArcsec = pixScaleArcsec,
            orientationDeg = orient,
            parity = parity,
            fieldRadiusArcmin = radiusArcmin,
            wcs = currentWcs,
        )
    }

    internal fun isWellShapedTransform(
        beforeWcs: WcsTransform,
        afterWcs: WcsTransform,
        beforeSolve: SolveResult,
        afterSolve: SolveResult,
        width: Int,
        height: Int,
    ): Boolean {
        val sx = hypot(afterWcs.cd11, afterWcs.cd21)
        val sy = hypot(afterWcs.cd12, afterWcs.cd22)
        if (!sx.isFinite() || !sy.isFinite() || sx <= 0.0 || sy <= 0.0) return false
        val aniso = abs(sx / sy - 1.0)
        if (aniso > MAX_ANISOTROPY_FRAC) return false

        val dot = afterWcs.cd11 * afterWcs.cd12 + afterWcs.cd21 * afterWcs.cd22
        val orthoCos = abs(dot / (sx * sy))
        if (orthoCos > MAX_SKEW_COS) return false

        val beforeDet = beforeWcs.cd11 * beforeWcs.cd22 - beforeWcs.cd12 * beforeWcs.cd21
        val afterDet = afterWcs.cd11 * afterWcs.cd22 - afterWcs.cd12 * afterWcs.cd21
        if (beforeDet * afterDet <= 0.0) return false

        val bCenter = beforeWcs.fitsPixelToSky(width / 2.0 + 0.5, height / 2.0 + 0.5)
        val aCenter = afterWcs.fitsPixelToSky(width / 2.0 + 0.5, height / 2.0 + 0.5)
        val centerShift = StarFieldGeometry.haversineDeg(bCenter[0], bCenter[1], aCenter[0], aCenter[1])
        if (!centerShift.isFinite() || centerShift > MAX_CENTER_SHIFT_DEG) return false

        val scaleDelta = abs(afterSolve.pixScaleArcsec / beforeSolve.pixScaleArcsec.coerceAtLeast(1e-6) - 1.0)
        if (!scaleDelta.isFinite() || scaleDelta > MAX_SCALE_DELTA_FRAC) return false
        return true
    }
}
