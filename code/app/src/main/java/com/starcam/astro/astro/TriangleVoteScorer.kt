package com.starcam.astro.astro

import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 多线程局部三角形投票、第 4 星几何校验与宽/窄场假设打分轮（§0.142 模块化解耦）。
 */
internal object TriangleVoteScorer {

    internal const val ALIGN_TOL_PX = 8f
    private const val SCORED_EARLY_ALIGNED = 12
    private const val SCORED_EARLY_RATE = 0.5f
    internal const val SCORED_MAX_CANDIDATES = 20_000L
    internal const val SCORED_PROBE_CANDIDATES = 2_000L
    private const val SCORED_HARD_TIME_LIMIT_MS = 60_000L
    private const val INV_RAD = 180.0 / PI
    private const val SCORED_MIN_ALIGNED = 6
    private const val SCORED_MIN_ALIGN_RATE = 0.20f
    private const val SCORED_MIN_IN_FRAME = 4
    private const val SCORED_REFINEMENT_LIMIT = 4
    internal const val NARROW_SCORED_MIN_REFINED_INLIERS = 10

    private val ZERO_SCORE = intArrayOf(0, 0)

    private val nTriangles = AtomicLong()
    private val nBucketLookups = AtomicLong()
    private val nEntriesScanned = AtomicLong()
    private val nMerged = AtomicLong()
    private val nVerified = AtomicLong()

    private val votePool: ExecutorService by lazy {
        Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors().coerceAtLeast(2),
        ) { r -> Thread(r, "starcam-vote").apply { isDaemon = true } }
    }

    private data class ScoredSeed(
        val a: Int,
        val b: Int,
        val c: Int,
        val triangle: TriangleStarIndex.TriEntry,
        val aligned: Int,
        val inFrame: Int,
        val scoredAt: Long,
    )

    private fun candidateCap(): Int = LocalStarMatcher.debugCandidateCap ?: 18
    private fun minAligned(): Int = LocalStarMatcher.debugMinAligned ?: SCORED_MIN_ALIGNED
    private fun minAlignRate(): Float = LocalStarMatcher.debugMinAlignRate ?: SCORED_MIN_ALIGN_RATE

    private fun passesScoredGate(aligned: Int, inFrame: Int): Boolean =
        aligned >= minAligned() && inFrame >= SCORED_MIN_IN_FRAME &&
            aligned.toFloat() / inFrame >= minAlignRate()

    fun debugVoteScanTake(): String {
        val s = "tri=%d buckets=%d entries=%d merged=%d taken=%d".format(
            nTriangles.get(), nBucketLookups.get(), nEntriesScanned.get(),
            nMerged.get(), nVerified.get(),
        )
        nTriangles.set(0)
        nBucketLookups.set(0)
        nEntriesScanned.set(0)
        nMerged.set(0)
        nVerified.set(0)
        return s
    }

    private fun scoreHypothesis(
        work: List<DetectedStar>,
        a: DetectedStar,
        b: DetectedStar,
        c: DetectedStar,
        t: TriangleStarIndex.TriEntry,
        width: Int,
        height: Int,
        pointingHint: PointingHint?,
        tbl: TriangleStarIndex.ScoreTable,
        fovRange: Pair<Double, Double>? = null,
    ): IntArray {
        val eA = TriangleStarIndex.hipMap[t.hipA] ?: return ZERO_SCORE
        val eB = TriangleStarIndex.hipMap[t.hipB] ?: return ZERO_SCORE
        val eC = TriangleStarIndex.hipMap[t.hipC] ?: return ZERO_SCORE

        val center = StarFieldGeometry.sphericalMean(eA, eB, eC)
        var ra0 = center[0]
        var dec0 = center[1]
        var fit: DoubleArray? = null
        var useFlip = false
        var parityKnown = false
        val px = floatArrayOf(a.x, b.x, c.x)
        val py = floatArrayOf(a.y, b.y, c.y)
        val pyFlip = floatArrayOf(-a.y, -b.y, -c.y)
        for (iter in 0 until StarFieldFitter.ORIGIN_ITERATIONS) {
            val txA = StarFieldGeometry.tanXY(eA.ra, eA.dec, ra0, dec0)
            val txB = StarFieldGeometry.tanXY(eB.ra, eB.dec, ra0, dec0)
            val txC = StarFieldGeometry.tanXY(eC.ra, eC.dec, ra0, dec0)
            val sx = floatArrayOf(txA.first.toFloat(), txB.first.toFloat(), txC.first.toFloat())
            val sy = floatArrayOf(txA.second.toFloat(), txB.second.toFloat(), txC.second.toFloat())
            val f0 = StarFieldGeometry.fitSimilarity(px, py, sx, sy)
            val f1 = StarFieldGeometry.fitSimilarity(px, pyFlip, sx, sy)
            if (!parityKnown) {
                val r0 = if (f0 != null) StarFieldGeometry.fitResid(f0, px, py, sx, sy) else Double.MAX_VALUE
                val r1 = if (f1 != null) StarFieldGeometry.fitResid(f1, px, pyFlip, sx, sy) else Double.MAX_VALUE
                useFlip = r1 < r0
                parityKnown = true
            }
            val f = if (useFlip) f1 else f0
            if (f == null) return ZERO_SCORE
            fit = f
            if (iter == StarFieldFitter.ORIGIN_ITERATIONS - 1) break
            val s0 = f[0]
            val ss0 = f[1]
            val tx0 = f[2]
            val ty0 = f[3]
            val det0 = s0 * s0 + ss0 * ss0
            if (det0 < 1e-12) break
            val cx = width / 2.0
            val cy = if (useFlip) -height / 2.0 else height / 2.0
            val wx = s0 * cx - ss0 * cy + tx0
            val wy = ss0 * cx + s0 * cy + ty0
            val next = StarFieldGeometry.tanInverse(wx, wy, ra0, dec0)
            val nra = next.first
            val ndec = next.second
            if (!nra.isFinite() || !ndec.isFinite()) break
            val moved = abs(nra - ra0) + abs(ndec - dec0)
            ra0 = nra
            dec0 = ndec
            if (moved < 1e-6) break
        }
        val fitV = fit ?: return ZERO_SCORE
        val s = fitV[0]
        val ss = fitV[1]
        val tx = fitV[2]
        val ty = fitV[3]
        val det = s * s + ss * ss
        if (det < 1e-12) return ZERO_SCORE
        if (fovRange != null) {
            val candidateFov = max(width, height) * sqrt(det)
            if (candidateFov < fovRange.first * 0.8 || candidateFov > fovRange.second * 1.2) return ZERO_SCORE
        }

        val invDet = 1.0 / det
        val u0 = (-s * tx - ss * ty) * invDet
        val v0 = (ss * tx - s * ty) * invDet * if (useFlip) -1 else 1
        val farX = maxOf(abs(u0 + 21.0), abs(width + 21.0 - u0))
        val farY = maxOf(abs(v0 + 21.0), abs(height + 21.0 - v0))
        val farTangentSquared = det * (farX * farX + farY * farY) / (INV_RAD * INV_RAD)
        val minCenterDot = 1.0 / sqrt(1.0 + farTangentSquared)
        var hits = 0
        var inFrame = 0
        val used = BooleanArray(work.size)
        val r0 = ra0 * (PI / 180.0)
        val d0 = dec0 * (PI / 180.0)
        val c0x = cos(d0) * cos(r0)
        val c0y = cos(d0) * sin(r0)
        val c0z = sin(d0)
        val exx = -sin(r0)
        val exy = cos(r0)
        val exz = 0.0
        val nx = c0y * exz - c0z * exy
        val ny = c0z * exx - c0x * exz
        val nz = c0x * exy - c0y * exx
        val hintRadius = if (pointingHint != null) pointingHint.radiusDeg + 45.0 else 0.0
        val hx: Double
        val hy: Double
        val hz: Double
        if (pointingHint != null) {
            val hr = pointingHint.raDeg * (PI / 180.0)
            val hd = pointingHint.decDeg * (PI / 180.0)
            hx = cos(hd) * cos(hr)
            hy = cos(hd) * sin(hr)
            hz = sin(hd)
        } else {
            hx = 0.0
            hy = 0.0
            hz = 0.0
        }
        for (i in tbl.entries.indices) {
            val uxi = tbl.ux[i]
            val uyi = tbl.uy[i]
            val uzi = tbl.uz[i]
            val dC = uxi * c0x + uyi * c0y + uzi * c0z
            if (dC <= 1e-9 || dC < minCenterDot) continue
            if (pointingHint != null) {
                val dot = uxi * hx + uyi * hy + uzi * hz
                if (acos(dot.coerceIn(-1.0, 1.0)) / (PI / 180.0) > hintRadius) continue
            }
            val dE = uxi * exx + uyi * exy + uzi * exz
            val dN = uxi * nx + uyi * ny + uzi * nz
            val X = (dE / dC) * INV_RAD
            val Y = (dN / dC) * INV_RAD
            val u = ((s * (X - tx) + ss * (Y - ty)) * invDet).toFloat()
            val vRaw = ((-ss * (X - tx) + s * (Y - ty)) * invDet).toFloat()
            val v = if (useFlip) -vRaw else vRaw
            if (u < -20f || u > width + 20f || v < -20f || v > height + 20f) continue
            inFrame++
            var bestI = -1
            var bestD = ALIGN_TOL_PX
            for (wi in work.indices) {
                if (used[wi]) continue
                val dd = maxOf(abs(u - work[wi].x), abs(v - work[wi].y))
                if (dd < bestD) {
                    bestD = dd
                    bestI = wi
                }
            }
            if (bestI >= 0) {
                used[bestI] = true
                hits++
            }
        }
        return intArrayOf(hits, inFrame)
    }

    fun scoredRound(
        ctx: LocalStarMatcher.MatchContext,
        detected: List<DetectedStar>,
        width: Int,
        height: Int,
        pointingHint: PointingHint?,
    ): LocalMatchResult? {
        val idx = TriangleStarIndex.index(ctx.catalogMag, ctx.token)
        val tbl = TriangleStarIndex.scoreTable(ctx.catalogMag)
        val (t1, _) = StarFieldDetector.voteThresholds(
            detected.map { it.brightness },
            LocalStarMatcher.debugForceVoteThreshold,
        )
        val workIdx = detected.indices.filter { detected[it].brightness >= t1 * 0.5f }.take(45)
        val work = workIdx.map { detected[it] }
        if (work.size < 5) return null
        val maxSide = max(width, height) * 0.45f

        var bestScore = 0
        var bestInFrame = 0
        var legacyLeader: ScoredSeed? = null
        val finalists = ArrayList<ScoredSeed>(SCORED_REFINEMENT_LIMIT + 1)
        val visitedTriangles = if (ctx.narrowFovHint == null) null else HashSet<Int>()
        val leaderTriangles = if (visitedTriangles == null) HashSet<Long>() else null
        val rank = compareByDescending<ScoredSeed> { it.aligned }
            .thenBy { it.inFrame }.thenBy { it.scoredAt }
        val tStart = System.nanoTime()
        val capThisRound = ctx.scoredCap
        var nCand = 0L
        var nScored = 0L
        var earlyOut = false
        var cutByCap = false
        var cutByTime = false
        ctx.scoredWinAt = -1

        for (pi in work.indices) {
            val a = work[pi]
            val nbrs = work.mapIndexed { j, s -> j to StarFieldGeometry.dist(a, s) }
                .filter { it.second in 1.5f..maxSide }
                .sortedBy { it.second }
                .take(14)
            if (nbrs.size < 2) continue
            for (i in nbrs.indices) {
                for (j in i + 1 until nbrs.size) {
                    val bi = nbrs[i].first
                    val ci = nbrs[j].first
                    val b = work[bi]
                    val c = work[ci]
                    val lab = nbrs[i].second
                    val lac = nbrs[j].second
                    val lbc = StarFieldGeometry.dist(b, c)
                    val sides = floatArrayOf(lab, lac, lbc).sortedDescending()
                    if (sides[0] <= 0f) continue
                    val r2 = sides[1] / sides[0]
                    val r3 = sides[2] / sides[0]
                    if (r2 < 0.12f) continue
                    val expectedSide = ctx.narrowFovHint?.let { sides[0] * it / max(width, height) }
                    val pointsByRank = if (expectedSide == null) null else {
                        val ranks = TriangleStarIndex.vertexRanks(lab.toDouble(), lac.toDouble(), lbc.toDouble())
                        IntArray(3).also {
                            it[ranks and 3] = pi
                            it[(ranks shr 2) and 3] = bi
                            it[ranks shr 4] = ci
                        }
                    }
                    if (pointsByRank != null && visitedTriangles != null) {
                        val triangle = (pointsByRank[0] * work.size + pointsByRank[1]) * work.size + pointsByRank[2]
                        if (!visitedTriangles.add(triangle)) continue
                    }
                    val sideMin = expectedSide?.times(0.55) ?: 0.0
                    val sideMax = expectedSide?.times(1.65) ?: Double.POSITIVE_INFINITY
                    val merged = HashMap<Long, TriangleStarIndex.TriEntry>()
                    for (key in TriangleStarIndex.quantizeKeys(r2, r3, window = 2)) {
                        idx.triMap[key]?.let { list ->
                            for (t in list) {
                                if (t.longestSideDeg >= sideMin && t.longestSideDeg <= sideMax &&
                                    abs(t.r2 - r2) < 0.012f && abs(t.r3 - r3) < 0.012f
                                ) merged[TriangleStarIndex.triKey(t)] = t
                            }
                        }
                    }
                    for (key in TriangleStarIndex.quantizeKeys(r2, r3, window = 14)) {
                        idx.triMap[key]?.let { list ->
                            for (t in list) {
                                if (t.longestSideDeg >= sideMin && t.longestSideDeg <= sideMax &&
                                    abs(t.r2 - r2) < 0.05f && abs(t.r3 - r3) < 0.05f
                                ) merged[TriangleStarIndex.triKey(t)] = t
                            }
                        }
                    }
                    if (merged.isEmpty()) continue
                    val candidates = merged.values
                        .sortedWith(compareBy({
                            val scaleError = if (expectedSide != null) 0.10 * abs(it.longestSideDeg / expectedSide - 1.0) else 0.0
                            abs(it.r2 - r2) + abs(it.r3 - r3) + scaleError
                        }, { it.hipA }, { it.hipB }, { it.hipC }))
                        .take(candidateCap())
                    nCand += candidates.size
                    for (t in candidates) {
                        val ia = pointsByRank?.get(t.vertexRanks and 3) ?: pi
                        val ib = pointsByRank?.get((t.vertexRanks shr 2) and 3) ?: bi
                        val ic = pointsByRank?.get(t.vertexRanks shr 4) ?: ci
                        nScored++
                        val sc = scoreHypothesis(
                            work, work[ia], work[ib], work[ic], t, width, height,
                            pointingHint, tbl, ctx.scoredFovRange,
                        )
                        val aligned = sc[0]
                        val inFrame = sc[1]
                        if (aligned > bestScore && (leaderTriangles == null || leaderTriangles.add(TriangleStarIndex.triKey(t)))) {
                            bestScore = aligned
                            bestInFrame = inFrame
                            if (leaderTriangles != null) {
                                legacyLeader = ScoredSeed(workIdx[ia], workIdx[ib], workIdx[ic], t, aligned, inFrame, nScored)
                                earlyOut = inFrame >= SCORED_MIN_IN_FRAME &&
                                    aligned >= SCORED_EARLY_ALIGNED && aligned.toFloat() / inFrame >= SCORED_EARLY_RATE
                            }
                        }
                        if (leaderTriangles == null && passesScoredGate(aligned, inFrame)) {
                            val seed = ScoredSeed(workIdx[ia], workIdx[ib], workIdx[ic], t, aligned, inFrame, nScored)
                            finalists.add(seed)
                            finalists.sortWith(rank)
                            if (finalists.size > SCORED_REFINEMENT_LIMIT) finalists.removeAt(finalists.lastIndex)
                            if (aligned >= SCORED_EARLY_ALIGNED && aligned.toFloat() / inFrame >= SCORED_EARLY_RATE) {
                                earlyOut = true
                            }
                        }
                        if (earlyOut) break
                        if (nScored >= capThisRound) {
                            cutByCap = true
                            earlyOut = true
                            break
                        }
                        if ((nScored and 0x3F) == 0L) {
                            ctx.token?.check()
                            if ((System.nanoTime() - tStart) / 1_000_000 > SCORED_HARD_TIME_LIMIT_MS) {
                                cutByTime = true
                                earlyOut = true
                                break
                            }
                        }
                    }
                    if (earlyOut) break
                }
                if (earlyOut) break
            }
            if (earlyOut) break
        }
        ctx.scoredBest = bestScore
        ctx.scoredInFrame = bestInFrame
        ctx.scoredStats = "cand=$nCand scored=$nScored work=${work.size} " +
            "ms=${(System.nanoTime() - tStart) / 1_000_000}" +
            (
                if (cutByCap) {
                    if (capThisRound < SCORED_MAX_CANDIDATES) " CUT=soft" else " CUT=cap"
                } else ""
            ) +
            (if (cutByTime) " CUT=time" else "")

        val seeds = if (leaderTriangles == null) finalists else listOfNotNull(
            legacyLeader?.takeIf { passesScoredGate(it.aligned, it.inFrame) },
        )
        for (seed in seeds) {
            ctx.token?.check()
            val (a, b, c, t) = seed
            val pairs = listOf(
                a to TriangleStarIndex.hipMap.getValue(t.hipA),
                b to TriangleStarIndex.hipMap.getValue(t.hipB),
                c to TriangleStarIndex.hipMap.getValue(t.hipC),
            )
            val expanded = expandHypothesisPairs(pairs, detected, width, height, ctx)
            val result = StarFieldFitter.bestCandidate(
                detected, width, height, expanded ?: pairs, pointingHint, ctx.catalogMag, ctx.scoredFovRange,
            ) ?: continue
            if (leaderTriangles == null && result.inlierCount < NARROW_SCORED_MIN_REFINED_INLIERS) continue
            ctx.scoredBest = seed.aligned
            ctx.scoredInFrame = seed.inFrame
            ctx.scoredWinAt = seed.scoredAt
            ctx.scoredWinPairs = pairs.size
            ctx.scoredExpandedPairs = expanded?.size
            ctx.scoredWinSeed = pairs.joinToString(" ") { (i, star) ->
                val point = detected[i]
                "(${point.x},${point.y})h${star.hip}"
            }
            return result
        }
        return null
    }

    private fun expandHypothesisPairs(
        seed: List<Pair<Int, StarEntry>>,
        detected: List<DetectedStar>,
        width: Int,
        height: Int,
        ctx: LocalStarMatcher.MatchContext,
    ): List<Pair<Int, StarEntry>>? {
        if (seed.size < 3) return null
        var best: List<Pair<Int, StarEntry>>? = null
        for (mirror in booleanArrayOf(false, true)) {
            val fp0 = StarFieldFitter.fitPairs(detected, seed, width, height, mirror) ?: continue
            var fp: StarFieldFitter.FitParams = fp0
            repeat(StarFieldFitter.ORIGIN_ITERATIONS) {
                val w = StarFieldFitter.buildWcs(fp, width, height, mirror)
                val (cRa, cDec) = w.fitsPixelToSky(width / 2.0 + 0.5, height / 2.0 + 0.5)
                if (cRa.isNaN() || cDec.isNaN()) return@repeat
                val next0 = StarFieldFitter.fitPairs(detected, seed, width, height, mirror, cRa, cDec)
                    ?: return@repeat
                fp = next0
            }
            val wcs = StarFieldFitter.buildWcs(fp, width, height, mirror)
            val out = ArrayList<Pair<Int, StarEntry>>()
            val used = HashSet<Int>()
            val mag = ctx.catalogMag
            for (star in StarCatalogData.stars) {
                if (star.mag > mag) continue
                val p = wcs.skyToScreen(star.ra, star.dec, width, height)
                if (java.lang.Float.isNaN(p[0])) continue
                val u = p[0].toDouble()
                val v = p[1].toDouble()
                if (u < -20.0 || u > width + 20.0 || v < -20.0 || v > height + 20.0) continue
                var bestI = -1
                var bestD = ALIGN_TOL_PX
                for (pi in detected.indices) {
                    if (used.contains(pi)) continue
                    val dd = maxOf(
                        abs(u - detected[pi].x).toDouble(),
                        abs(v - detected[pi].y).toDouble(),
                    ).toFloat()
                    if (dd < bestD) {
                        bestD = dd
                        bestI = pi
                    }
                }
                if (bestI >= 0) {
                    used.add(bestI)
                    out.add(bestI to star)
                }
            }
            if (out.size >= 3 && (best == null || out.size > best.size)) {
                best = out
            }
            ctx.expandDetail = String.format(
                java.util.Locale.ROOT,
                "mirror=%s out=%d center=(%.3f,%.3f)",
                mirror, out.size, wcs.crval1, wcs.crval2,
            )
        }
        return best
    }

    fun votePairs(
        ctx: LocalStarMatcher.MatchContext,
        detected: List<DetectedStar>,
        width: Int,
        height: Int,
        threshold: Float,
        multi: Boolean = false,
        pointingHint: PointingHint? = null,
    ): List<Pair<Int, StarEntry>> {
        ctx.token?.check()
        val idx = TriangleStarIndex.index(ctx.catalogMag, ctx.token)
        val grid = TriangleStarIndex.catGrid(ctx.catalogMag)

        val votes = HashMap<Long, Int>()
        val maxSide = max(width, height) * 0.45f
        val work = detected
            .filter { it.brightness >= threshold }
            .take(45)

        if (work.size <= 8) {
            voteChunk(votes, work, 0, work.size, idx, maxSide, grid, ctx.token)
        } else {
            val chunkCount = Runtime.getRuntime().availableProcessors().coerceIn(2, work.size)
            val chunkSize = (work.size + chunkCount - 1) / chunkCount
            val futures = ArrayList<Future<HashMap<Long, Int>>>(chunkCount)
            for (c in 0 until chunkCount) {
                val from = c * chunkSize
                val to = min(work.size, from + chunkSize)
                if (from >= to) break
                futures.add(votePool.submit(Callable {
                    val local = HashMap<Long, Int>()
                    voteChunk(local, work, from, to, idx, maxSide, grid, ctx.token)
                    local
                }))
            }
            try {
                for (f in futures) {
                    var local: HashMap<Long, Int>? = null
                    while (local == null) {
                        ctx.token?.check()
                        try {
                            local = f.get(50, TimeUnit.MILLISECONDS)
                        } catch (_: TimeoutException) {
                            // Poll cancellation while worker votes are still running.
                        } catch (e: ExecutionException) {
                            throw (e.cause ?: e)
                        }
                    }
                    for ((k, v) in local) votes[k] = (votes[k] ?: 0) + v
                }
            } finally {
                futures.forEach { it.cancel(true) }
            }
        }

        val byPhoto = HashMap<Int, HashMap<Int, Int>>()
        for ((key, v) in votes) {
            val photoIdx = (key shr TriangleStarIndex.CATALOG_ID_BITS).toInt()
            val hip = TriangleStarIndex.catalogIdFromVote(key)
            byPhoto.getOrPut(photoIdx) { HashMap() }[hip] = v
        }
        run {
            var topV = 0
            var sumMargin = 0.0
            var nPhoto = 0
            for ((_, m) in byPhoto) {
                if (m.isEmpty()) continue
                nPhoto++
                val s = m.values.sortedDescending()
                topV = maxOf(topV, s[0])
                if (s.size > 1) sumMargin += s[0].toDouble() / s[1]
            }
            val avgMarginStr = if (nPhoto > 0) String.format(java.util.Locale.ROOT, "%.2f", sumMargin / nPhoto) else "-"
            ctx.voteStats = "work=${work.size} votedStars=$nPhoto topVote=$topV avgMargin=$avgMarginStr"
        }
        val pairs = ArrayList<Pair<Int, StarEntry>>()
        for ((photoIdx, m) in byPhoto) {
            val sorted = m.entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
            if (sorted.isEmpty()) continue
            val (hip, v) = sorted[0]
            val runnerUp = if (sorted.size > 1) sorted[1].value else 0
            var added = 0
            if (v >= 2 && (runnerUp == 0 || v >= runnerUp * 1.2f)) {
                TriangleStarIndex.hipMap[hip]?.let {
                    pairs.add(photoIdx to it)
                    added++
                }
            }
            if (multi) {
                for (i in 1 until sorted.size) {
                    if (sorted[i].value < 2 || added >= 3) break
                    TriangleStarIndex.hipMap[sorted[i].key]?.let {
                        pairs.add(photoIdx to it)
                        added++
                    }
                }
            }
        }
        pairs.sortBy { it.first }

        if (pointingHint != null) {
            val maxDist = pointingHint.radiusDeg + 45.0
            val nearPairs = pairs.filter { (_, s) ->
                StarFieldGeometry.haversineDeg(s.ra, s.dec, pointingHint.raDeg, pointingHint.decDeg) <= maxDist
            }
            if (nearPairs.size >= 4) {
                return nearPairs
            }
        }

        return pairs
    }

    private fun voteChunk(
        votes: HashMap<Long, Int>,
        work: List<DetectedStar>,
        from: Int,
        to: Int,
        idx: TriangleStarIndex.StarIndex,
        maxSide: Float,
        grid: Map<Int, List<StarEntry>>,
        token: SolveToken? = null,
    ) {
        var cTriangles = 0L
        var cBuckets = 0L
        var cEntries = 0L
        var cMerged = 0L
        var cTaken = 0L
        for (pi in from until to) {
            token?.check()
            if (Thread.currentThread().isInterrupted) throw CancellationException()
            val a = work[pi]
            val nbrs = work.mapIndexed { j, s -> j to StarFieldGeometry.dist(a, s) }
                .filter { it.second in 1.5f..maxSide }
                .sortedBy { it.second }
                .take(14)
            if (nbrs.size < 2) continue
            for (i in nbrs.indices) {
                token?.check()
                for (j in i + 1 until nbrs.size) {
                    val bi = nbrs[i].first
                    val ci = nbrs[j].first
                    val b = work[bi]
                    val c = work[ci]
                    val lab = nbrs[i].second
                    val lac = nbrs[j].second
                    val lbc = StarFieldGeometry.dist(b, c)
                    val sides = floatArrayOf(lab, lac, lbc).sortedDescending()
                    val r2 = sides[1] / sides[0]
                    val r3 = sides[2] / sides[0]
                    if (r2 < 0.12f) continue

                    val cap = candidateCap()
                    val sel = arrayOfNulls<TriangleStarIndex.TriEntry>(cap)
                    val selDist = FloatArray(cap)
                    val selKey = LongArray(cap)
                    var n = 0
                    for (key in TriangleStarIndex.quantizeKeys(r2, r3, window = 14)) {
                        cBuckets++
                        val list = idx.triMap[key] ?: continue
                        for (t in list) {
                            cEntries++
                            val dr2 = abs(t.r2 - r2)
                            if (dr2 >= 0.05f) continue
                            val dr3 = abs(t.r3 - r3)
                            if (dr3 >= 0.05f) continue
                            val d = dr2 + dr3
                            val k = TriangleStarIndex.triKey(t)
                            var at = -1
                            for (q in 0 until n) {
                                if (selKey[q] == k) {
                                    at = q
                                    break
                                }
                            }
                            if (at >= 0) {
                                if (sel[at] !== t) sel[at] = t
                                continue
                            }
                            if (n >= cap && d > selDist[n - 1]) continue
                            var p = n
                            while (p > 0 && TriangleStarIndex.triBefore(t, d, sel[p - 1]!!, selDist[p - 1])) p--
                            if (p == cap) continue
                            if (n < cap) n++
                            var q = n - 1
                            while (q > p) {
                                sel[q] = sel[q - 1]
                                selDist[q] = selDist[q - 1]
                                selKey[q] = selKey[q - 1]
                                q--
                            }
                            sel[p] = t
                            selDist[p] = d
                            selKey[p] = k
                        }
                    }
                    cTriangles++
                    cMerged += n.toLong()
                    cTaken += n.toLong()
                    val candidates = ArrayList<TriangleStarIndex.TriEntry>(n)
                    for (q in 0 until n) candidates.add(sel[q]!!)

                    val verified = candidates.filter { verifyCandidate(a, b, c, it, nbrs, work, grid) }
                    for (t in verified) {
                        vote(votes, pi, t.hipA)
                        vote(votes, bi, t.hipB)
                        vote(votes, ci, t.hipC)
                        vote(votes, pi, t.hipA)
                        vote(votes, bi, t.hipC)
                        vote(votes, ci, t.hipB)
                    }
                }
            }
        }
        nTriangles.addAndGet(cTriangles)
        nBucketLookups.addAndGet(cBuckets)
        nEntriesScanned.addAndGet(cEntries)
        nMerged.addAndGet(cMerged)
        nVerified.addAndGet(cTaken)
    }

    private fun vote(votes: HashMap<Long, Int>, photoIdx: Int, hip: Int) {
        val key = TriangleStarIndex.catalogVoteKey(photoIdx, hip)
        votes[key] = (votes[key] ?: 0) + 1
    }

    private fun verifyCandidate(
        a: DetectedStar,
        b: DetectedStar,
        c: DetectedStar,
        t: TriangleStarIndex.TriEntry,
        nbrs: List<Pair<Int, Float>>,
        stars: List<DetectedStar>,
        grid: Map<Int, List<StarEntry>>,
    ): Boolean {
        val eA = TriangleStarIndex.hipMap[t.hipA] ?: return false
        val eB = TriangleStarIndex.hipMap[t.hipB] ?: return false
        val eC = TriangleStarIndex.hipMap[t.hipC] ?: return false
        val sm = StarFieldGeometry.sphericalMean(eA, eB, eC)
        val ra0 = sm[0]
        val dec0 = sm[1]
        val (xA, yA) = StarFieldGeometry.tanXY(eA.ra, eA.dec, ra0, dec0)
        val (xB, yB) = StarFieldGeometry.tanXY(eB.ra, eB.dec, ra0, dec0)
        val (xC, yC) = StarFieldGeometry.tanXY(eC.ra, eC.dec, ra0, dec0)
        val xs = floatArrayOf(a.x, b.x, c.x)
        val ys = floatArrayOf(a.y, b.y, c.y)
        val sideMax = maxOf(
            StarFieldGeometry.angularDist(eA, eB),
            StarFieldGeometry.angularDist(eA, eC),
            StarFieldGeometry.angularDist(eB, eC),
        )
        val tolShape = max(0.05, 0.04 * sideMax)
        val fitVariants = ArrayList<Pair<DoubleArray?, Boolean>>(4)
        for (negX in listOf(false, true)) {
            val signX = { v: Double -> if (negX) -v else v }
            fitVariants.add(
                StarFieldGeometry.fitSimilarity(
                    xs, ys,
                    floatArrayOf(signX(xA).toFloat(), signX(xB).toFloat(), signX(xC).toFloat()),
                    floatArrayOf(yA.toFloat(), yB.toFloat(), yC.toFloat()),
                ) to negX,
            )
            fitVariants.add(
                StarFieldGeometry.fitSimilarity(
                    xs, ys,
                    floatArrayOf(signX(xA).toFloat(), signX(xC).toFloat(), signX(xB).toFloat()),
                    floatArrayOf(yA.toFloat(), yC.toFloat(), yB.toFloat()),
                ) to negX,
            )
        }
        for ((fit, negX) in fitVariants) {
            if (fit == null) continue
            val s = fit[0]
            val ss = fit[1]
            val tx = fit[2]
            val ty = fit[3]
            val bx = if (fit === fitVariants[0].first || fit === fitVariants[2].first) xB else xC
            val by = if (fit === fitVariants[0].first || fit === fitVariants[2].first) yB else yC
            val cx2 = if (fit === fitVariants[0].first || fit === fitVariants[2].first) xC else xB
            val cy2 = if (fit === fitVariants[0].first || fit === fitVariants[2].first) yC else yB
            fun residual(px: Float, py: Float, X: Double, Y: Double): Double =
                hypot(s * px - ss * py + tx - X, ss * px + s * py + ty - Y)
            if (residual(a.x, a.y, if (negX) -xA else xA, yA) > tolShape) continue
            if (residual(b.x, b.y, if (negX) -bx else bx, by) > tolShape) continue
            if (residual(c.x, c.y, if (negX) -cx2 else cx2, cy2) > tolShape) continue
            for (k in 3 until min(8, nbrs.size)) {
                val d = stars[nbrs[k].first]
                val xm = s * d.x - ss * d.y + tx
                val ym = ss * d.x + s * d.y + ty
                val (raP, decP) = StarFieldGeometry.tanInverse(if (negX) -xm else xm, ym, ra0, dec0)
                if (TriangleStarIndex.nearestStarInGrid(raP, decP, 0.35, grid) != null) return true
            }
        }
        return false
    }

    fun debugVoteTable(
        detected: List<DetectedStar>,
        width: Int,
        height: Int,
        mag: Float,
        truth: WcsTransform? = null,
    ): String {
        val idx = TriangleStarIndex.index(mag)
        val grid = TriangleStarIndex.catGrid(mag)
        val sb = StringBuilder()
        val maxSide = max(width, height) * 0.45f
        val votes = HashMap<Long, Int>()
        val thr = StarFieldDetector.voteBrightnessThreshold(
            detected.map { it.brightness },
            LocalStarMatcher.debugForceVoteThreshold,
        )
        val work = detected.filter { it.brightness >= thr }.take(45)
        var strictEmpty = 0
        var looseFired = 0
        val actual = HashMap<Int, Int>()
        if (truth != null) {
            for ((pi, d) in work.withIndex()) {
                for (star in StarCatalogData.stars) {
                    val p = truth.skyToScreen(star.ra, star.dec, width, height)
                    if (abs(p[0] - d.x) < 6f && abs(p[1] - d.y) < 6f) {
                        actual[pi] = star.hip
                        break
                    }
                }
            }
        }
        for (pi in work.indices) {
            val a = work[pi]
            val nbrs = work.mapIndexed { j, s -> j to StarFieldGeometry.dist(a, s) }
                .filter { it.second in 1.5f..maxSide }
                .sortedBy { it.second }
                .take(14)
            if (nbrs.size < 2) continue
            for (i in nbrs.indices) {
                for (j in i + 1 until nbrs.size) {
                    val bi = nbrs[i].first
                    val ci = nbrs[j].first
                    val b = work[bi]
                    val c = work[ci]
                    val lab = nbrs[i].second
                    val lac = nbrs[j].second
                    val lbc = StarFieldGeometry.dist(b, c)
                    val sides = floatArrayOf(lab, lac, lbc).sortedDescending()
                    val r2 = sides[1] / sides[0]
                    val r3 = sides[2] / sides[0]
                    if (r2 < 0.12f) continue
                    val strictRaw = ArrayList<TriangleStarIndex.TriEntry>()
                    for (key in TriangleStarIndex.quantizeKeys(r2, r3, window = 2)) {
                        idx.triMap[key]?.let { list -> strictRaw.addAll(list) }
                    }
                    val looseRaw = ArrayList<TriangleStarIndex.TriEntry>()
                    for (key in TriangleStarIndex.quantizeKeys(r2, r3, window = 14)) {
                        idx.triMap[key]?.let { list -> looseRaw.addAll(list) }
                    }
                    val merged = HashMap<Long, TriangleStarIndex.TriEntry>()
                    var strictHit = false
                    for (t in strictRaw) {
                        if (abs(t.r2 - r2) < 0.012f && abs(t.r3 - r3) < 0.012f) {
                            merged[TriangleStarIndex.triKey(t)] = t
                            strictHit = true
                        }
                    }
                    var looseHit = false
                    for (t in looseRaw) {
                        if (abs(t.r2 - r2) < 0.05f && abs(t.r3 - r3) < 0.05f) {
                            val k = TriangleStarIndex.triKey(t)
                            if (merged.put(k, t) == null) {
                                looseHit = true
                            }
                        }
                    }
                    if (!strictHit) strictEmpty++
                    if (looseHit) looseFired++
                    val candidates = merged.values
                        .sortedWith(compareBy({ abs(it.r2 - r2) + abs(it.r3 - r3) }, { it.hipA }, { it.hipB }, { it.hipC }))
                        .take(candidateCap())
                    for (t in candidates) {
                        if (verifyCandidate(a, b, c, t, nbrs, work, grid)) {
                            vote(votes, pi, t.hipA)
                            vote(votes, bi, t.hipB)
                            vote(votes, ci, t.hipC)
                            vote(votes, pi, t.hipA)
                            vote(votes, bi, t.hipC)
                            vote(votes, ci, t.hipB)
                        }
                    }
                }
            }
        }
        sb.append("work=${work.size} strictEmpty=$strictEmpty looseFired=$looseFired\n")
        var printed = 0
        for (pi in work.indices) {
            if (printed >= 14) break
            val sorted = votes.entries
                .filter { (it.key shr TriangleStarIndex.CATALOG_ID_BITS).toInt() == pi }
                .sortedByDescending { it.value }
                .take(5)
            if (sorted.isEmpty()) continue
            printed++
            val trueHip = actual[pi]
            val trueV = if (trueHip != null) votes[TriangleStarIndex.catalogVoteKey(pi, trueHip)] ?: 0 else -1
            sb.append(
                "P#$pi b=${String.format(java.util.Locale.ROOT, "%.0f", work[pi].brightness)}" +
                    (if (trueHip != null) " trueHip=$trueHip(trueV=$trueV)" else " noTruth") +
                    " : " + sorted.joinToString { "hip=${TriangleStarIndex.catalogIdFromVote(it.key)}(v=${it.value})" } + "\n",
            )
        }
        return sb.toString()
    }
}
