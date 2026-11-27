package com.starcam.astro.astro

import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 星表三角形索引、打分单位向量表与 5°×5° 验证网格管理（§0.142 模块化解耦）。
 *
 * 托管三份按星等上限分槽的纯派生缓存：
 * - [indexCache]：归一化角度比单桶三角形索引（浅域 mag≤4.0 / 中域 5.5 / 深域 6.5）
 * - [scoreTableCache]：预计算天球单位向量的打分星表
 * - [catGridCache]：第 4 星几何验证用的 5°×5° 赤道网格
 */
internal object TriangleStarIndex {

    internal const val PROD_CATALOG_MAG = 4.0f
    internal const val INTERMEDIATE_CATALOG_MAG = 5.5f
    internal const val DEEP_CATALOG_MAG = 6.5f

    internal const val CATALOG_ID_BITS = 20
    private const val CATALOG_ID_MASK = (1L shl CATALOG_ID_BITS) - 1

    private const val GRID_CELL_DEG = 5.0
    private const val GRID_COLS = (360.0 / GRID_CELL_DEG).toInt() // 72
    private const val GRID_ROWS = (180.0 / GRID_CELL_DEG).toInt() // 36
    private const val NEIGHBOR_MIN_DEG = 0.8
    private const val NEIGHBOR_MAX_DEG = 25.0
    private const val NEIGHBOR_GRID_THRESHOLD = 1000

    class TriEntry(
        val hipA: Int,
        val hipB: Int,
        val hipC: Int,
        val r2: Float,
        val r3: Float,
        val longestSideDeg: Float,
        val vertexRanks: Int,
    )

    class StarIndex(
        val triMap: HashMap<Int, MutableList<TriEntry>>,
        val flatBuckets: Array<Array<TriEntry>?> = arrayOfNulls(256 * 256),
    )

    class ScoreTable(
        val ux: DoubleArray,
        val uy: DoubleArray,
        val uz: DoubleArray,
        val raDeg: DoubleArray,
        val decDeg: DoubleArray,
        val entries: Array<StarEntry>,
    )

    val hipMap: Map<Int, StarEntry> by lazy { StarCatalogData.stars.associateBy { it.hip } }

    private val indexCache = ConcurrentHashMap<Float, StarIndex>()
    private val scoreTableCache = ConcurrentHashMap<Float, ScoreTable>()
    private val catGridCache = ConcurrentHashMap<Float, Map<Int, List<StarEntry>>>()

    @Volatile
    private var lastOomTimeMs = 0L

    fun trimCaches() {
        indexCache.clear()
        scoreTableCache.clear()
        catGridCache.clear()
    }

    fun vertexRanks(ab: Double, ac: Double, bc: Double): Int {
        val a = (if (ac < bc) 1 else 0) + (if (ab < bc) 1 else 0)
        val b = (if (bc <= ac) 1 else 0) + (if (ab < ac) 1 else 0)
        val c = 3 - a - b
        return a or (b shl 2) or (c shl 4)
    }

    fun scoreTable(mag: Float): ScoreTable {
        return scoreTableCache.computeIfAbsent(mag) { m ->
            val list = ArrayList<StarEntry>(512)
            for (st in StarCatalogData.stars) {
                if (st.mag > m) continue
                list.add(st)
            }
            val n = list.size
            val ux = DoubleArray(n)
            val uy = DoubleArray(n)
            val uz = DoubleArray(n)
            val ras = DoubleArray(n)
            val decs = DoubleArray(n)
            val r = PI / 180.0
            for (i in 0 until n) {
                val st = list[i]
                val ra = st.ra * r
                val dec = st.dec * r
                val cd = cos(dec)
                ux[i] = cd * cos(ra)
                uy[i] = cd * sin(ra)
                uz[i] = sin(dec)
                ras[i] = st.ra
                decs[i] = st.dec
            }
            ScoreTable(ux, uy, uz, ras, decs, list.toTypedArray())
        }
    }

    fun debugIndexBuild(mag: Float): Pair<Int, Long> {
        val t0 = System.nanoTime()
        val idx = index(mag)
        val ms = (System.nanoTime() - t0) / 1_000_000
        return idx.triMap.values.sumOf { it.size } to ms
    }

    fun index(mag: Float, token: SolveToken? = null): StarIndex {
        indexCache[mag]?.let { return it }
        // §0.123 修复 P3-1：深域构建 OOM 后设置 60s 冷却期，避免每次求解反复卡顿 9s
        if (mag > PROD_CATALOG_MAG && System.currentTimeMillis() - lastOomTimeMs < 60_000L) {
            return StarIndex(HashMap())
        }
        token?.check()
        synchronized(this) {
            indexCache[mag]?.let { return it }
            token?.check()
            return try {
                buildIndex(mag, token).also { indexCache[mag] = it }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                if (t is OutOfMemoryError) {
                    lastOomTimeMs = System.currentTimeMillis()
                }
                StarIndex(HashMap())
            }
        }
    }

    private fun buildIndex(magLimit: Float = PROD_CATALOG_MAG, token: SolveToken? = null): StarIndex {
        val bright = StarCatalogData.stars.filter { it.mag <= magLimit }
        val triMap = HashMap<Int, MutableList<TriEntry>>()
        val useGrid = bright.size > NEIGHBOR_GRID_THRESHOLD
        val grid: HashMap<Long, MutableList<Pair<Int, StarEntry>>>? = if (useGrid) {
            val g = HashMap<Long, MutableList<Pair<Int, StarEntry>>>()
            bright.forEachIndexed { i, s ->
                val row = ((s.dec + 90.0) / GRID_CELL_DEG).toInt().coerceIn(0, GRID_ROWS - 1)
                val col = ((((s.ra % 360.0) + 360.0) % 360.0) / GRID_CELL_DEG).toInt()
                    .coerceIn(0, GRID_COLS - 1)
                g.getOrPut(cellKey(col, row)) { ArrayList() }.add(i to s)
            }
            g
        } else null
        var starCheck = 0
        for (a in bright) {
            if (++starCheck % 32 == 0) {
                token?.check()
            }
            val neighbors: List<Triple<Int, StarEntry, Double>> = if (useGrid) {
                neighborsWithin(a, grid!!)
                    .sortedWith(compareBy({ it.third }, { it.first }))
                    .take(12)
            } else {
                bright.mapIndexed { i, s -> Triple(i, s, StarFieldGeometry.angularDist(a, s)) }
                    .filter { it.second !== a && it.third in NEIGHBOR_MIN_DEG..NEIGHBOR_MAX_DEG }
                    .sortedBy { it.third }
                    .take(12)
            }
            for (i in neighbors.indices) {
                for (j in i + 1 until neighbors.size) {
                    val b = neighbors[i].second
                    val c = neighbors[j].second
                    val dab = neighbors[i].third
                    val dac = neighbors[j].third
                    val dbc = StarFieldGeometry.angularDist(b, c)
                    val sides = doubleArrayOf(dab, dac, dbc).sortedDescending()
                    val r2 = (sides[1] / sides[0]).toFloat()
                    val r3 = (sides[2] / sides[0]).toFloat()
                    if (r2 < 0.12f) continue
                    val entry = TriEntry(a.hip, b.hip, c.hip, r2, r3, sides[0].toFloat(), vertexRanks(dab, dac, dbc))
                    triMap.getOrPut(bucketKey(r2, r3)) { mutableListOf() }.add(entry)
                }
            }
        }
        val flatBuckets = arrayOfNulls<Array<TriEntry>>(256 * 256)
        for ((k, v) in triMap) {
            if (k in flatBuckets.indices) flatBuckets[k] = v.toTypedArray()
        }
        return StarIndex(triMap, flatBuckets)
    }

    private fun cellKey(col: Int, row: Int): Long = col.toLong() * 1000L + row.toLong()

    private fun neighborsWithin(
        a: StarEntry,
        grid: HashMap<Long, MutableList<Pair<Int, StarEntry>>>,
    ): List<Triple<Int, StarEntry, Double>> {
        val out = ArrayList<Triple<Int, StarEntry, Double>>()
        val rowLo = ((a.dec - NEIGHBOR_MAX_DEG + 90.0) / GRID_CELL_DEG).toInt().coerceAtLeast(0)
        val rowHi = ((a.dec + NEIGHBOR_MAX_DEG + 90.0) / GRID_CELL_DEG).toInt().coerceAtMost(GRID_ROWS - 1)
        for (row in rowLo..rowHi) {
            val rowLoDec = row * GRID_CELL_DEG - 90.0
            val rowHiDec = rowLoDec + GRID_CELL_DEG
            val maxAbsDec = maxOf(abs(rowLoDec), abs(rowHiDec))
            val cosD = cos(Math.toRadians(maxAbsDec)).coerceAtLeast(0.02)
            val spanCols = minOf(
                GRID_COLS / 2,
                Math.ceil(NEIGHBOR_MAX_DEG / (GRID_CELL_DEG * cosD)).toInt(),
            )
            val aCol = ((((a.ra % 360.0) + 360.0) % 360.0) / GRID_CELL_DEG).toInt()
                .coerceIn(0, GRID_COLS - 1)
            for (dc in -spanCols..spanCols) {
                val col = ((aCol + dc) % GRID_COLS + GRID_COLS) % GRID_COLS
                val list = grid[cellKey(col, row)] ?: continue
                for ((i, s) in list) {
                    if (s === a) continue
                    val d = StarFieldGeometry.angularDist(a, s)
                    if (d in NEIGHBOR_MIN_DEG..NEIGHBOR_MAX_DEG) out.add(Triple(i, s, d))
                }
            }
        }
        return out
    }

    fun quantizeKeys(r2: Float, r3: Float, window: Int = 1): List<Int> {
        val b2 = (r2 * 256).toInt().coerceIn(0, 255)
        val b3 = (r3 * 256).toInt().coerceIn(0, 255)
        val size = (2 * window + 1) * (2 * window + 1)
        val keys = ArrayList<Int>(size)
        for (d2 in -window..window) {
            for (d3 in -window..window) {
                keys.add((b2 + d2).coerceIn(0, 255) * 256 + (b3 + d3).coerceIn(0, 255))
            }
        }
        return keys
    }

    fun bucketKey(r2: Float, r3: Float): Int {
        val b2 = (r2 * 256).toInt().coerceIn(0, 255)
        val b3 = (r3 * 256).toInt().coerceIn(0, 255)
        return b2 * 256 + b3
    }

    fun catGrid(mag: Float): Map<Int, List<StarEntry>> {
        return catGridCache.computeIfAbsent(mag) { m ->
            val grid = HashMap<Int, MutableList<StarEntry>>()
            for (star in StarCatalogData.stars) {
                if (star.mag > m) continue
                val col = (((star.ra % 360.0) + 360.0) % 360.0 / 5.0).toInt()
                val row = ((star.dec + 90.0) / 5.0).toInt().coerceIn(0, 35)
                grid.getOrPut(col * 1000 + row) { ArrayList() }.add(star)
            }
            grid
        }
    }

    fun nearestStarInGrid(
        ra: Double,
        dec: Double,
        tolDeg: Double,
        grid: Map<Int, List<StarEntry>>,
    ): StarEntry? {
        val marginDeg = maxOf(0.5, tolDeg)
        val row0 = ((dec - marginDeg + 90.0) / 5.0).toInt().coerceIn(0, 35)
        val row1 = ((dec + marginDeg + 90.0) / 5.0).toInt().coerceIn(0, 35)
        val nearPole = abs(dec) + marginDeg >= 90.0
        val cols = if (nearPole) {
            IntArray(72) { it }
        } else {
            val cosDec = cos(dec * PI / 180.0).coerceAtLeast(0.05)
            val raHalfSpan = (marginDeg / cosDec).coerceAtMost(180.0)
            val colSpan = (raHalfSpan / 5.0).toInt() + 1
            val cCenter = ((((ra % 360.0) + 360.0) % 360.0) / 5.0).toInt().coerceIn(0, 71)
            val cSet = HashSet<Int>(minOf(72, colSpan * 2 + 1))
            for (dc in -colSpan..colSpan) {
                cSet.add(((cCenter + dc) % 72 + 72) % 72)
            }
            cSet.toIntArray()
        }
        var best: StarEntry? = null
        var bestD = tolDeg
        for (col in cols) {
            for (row in row0..row1) {
                grid[col * 1000 + row]?.let { list ->
                    for (s in list) {
                        val d = StarFieldGeometry.angDist(ra, dec, s.ra, s.dec)
                        if (d < bestD) {
                            bestD = d
                            best = s
                        }
                    }
                }
            }
        }
        return best
    }

    fun packedCatalogId(id: Int): Long = id.toLong() and CATALOG_ID_MASK

    fun catalogVoteKey(photoIndex: Int, id: Int): Long =
        (photoIndex.toLong() shl CATALOG_ID_BITS) or packedCatalogId(id)

    fun catalogIdFromVote(key: Long): Int =
        (key.toInt() shl (32 - CATALOG_ID_BITS)) shr (32 - CATALOG_ID_BITS)

    fun triKey(t: TriEntry): Long = triangleKey(t.hipA, t.hipB, t.hipC)

    fun triangleKey(a: Int, b: Int, c: Int): Long {
        val lo = minOf(a, b, c)
        val hi = maxOf(a, b, c)
        val mid = a + b + c - lo - hi
        return (packedCatalogId(lo) shl (2 * CATALOG_ID_BITS)) or
            (packedCatalogId(mid) shl CATALOG_ID_BITS) or packedCatalogId(hi)
    }

    fun triBefore(t: TriEntry, d: Float, other: TriEntry, od: Float): Boolean {
        if (d != od) return d < od
        if (t.hipA != other.hipA) return t.hipA < other.hipA
        if (t.hipB != other.hipB) return t.hipB < other.hipB
        return t.hipC < other.hipC
    }
}
