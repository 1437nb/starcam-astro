package com.starcam.astro.astro

import android.graphics.Bitmap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** 照片中检测到的星点 */
data class DetectedStar(val x: Float, val y: Float, val brightness: Float)

/** 本地匹配结果 */
data class LocalMatchResult(
    val solve: SolveResult,
    val matchedPairs: List<Pair<DetectedStar, StarEntry>>,
    val inlierCount: Int,
)

/**
 * 本地星场识别：星点三角形匹配法（完全离线，不依赖网络/API Key）。
 *
 * 流程：
 *  1. 星点检测：背景减除（box blur）+ 阈值 + 8 邻域连通域亮度加权质心
 *  2. 照片星点组成"局部三角形"，用归一化边长比（对旋转/缩放/平移不变）查询
 *     内置亮星星表（StarCatalogData）预构建的三角形索引
 *  3. 顶点对应投票（每颗照片星统计最可能的星表星）→ 相似变换最小二乘拟合
 *  4. 全星表反投影验证内点，分别尝试 parity ±1（镜像），取内点数多者
 *  5. 输出与现有渲染管线一致的 SolveResult / WcsTransform
 *
 * 原理与 astrometry.net 的三角形匹配一致；适合手机拍摄星空照片
 * （视场约 10°~60°，画面含 ≥8 颗可检测亮星）。
 */
object LocalStarMatcher {

    /** 调试：仅调试强制固定投票亮度门槛（模拟旧版行为）；App 运行时为 null，不影响生产 */
    @Volatile
    var debugForceVoteThreshold: Float? = null

    /** §0.43 调试：强制索引域星等上限（绕过缓存构建），null=生产默认 4.0 */
    @Volatile
    internal var debugForceIndexMag: Float? = null

    /** 双阈值通道（§0.32.3）：主轮内点达到该值即视为强解，不再跑备用轮 */
    private const val DUAL_THRESHOLD_STRONG_INLIERS = 18

    private fun voteBrightnessThreshold(brightness: List<Float>): Float {
        debugForceVoteThreshold?.let { return it }
        return voteThresholds(brightness).first
    }

    /**
     * 双阈值方案（§0.32.3）：返回 (主阈值, 备用阈值)。
     *  - 主阈值：分布头部陡峭（≥10 颗 ≥900 且第 20 亮星 ≥ 900×0.85）时用
     *    演示校准的 900，否则用相对值（20 亮星 ×35%，下限 60）——见
     *    apod4/4998 两类实测形态；
     *  - 备用阈值：另一分支。没有任何单一亮度判据能同时服务"小图富星场"
     *    （apod4：need 亮星精锐，work=16 顶票尖锐）与"大图全曝光"
     *    （4984/4998：need 中暗星也进投票，work=28+），主通道无解时
     *    [tryMatch] 会用备用阈值重投一轮，代价仅一轮投票时间。
     */
    internal fun voteThresholds(brightness: List<Float>): Pair<Float, Float> {
        debugForceVoteThreshold?.let { return it to it }
        val sortedDesc = brightness.map { it.toFloat() }.sortedDescending()
        val anchor20 = sortedDesc[minOf(sortedDesc.size - 1, 19)]
        val brightCount = sortedDesc.count { it >= 900f }
        val sharp = 900f
        val adaptive = maxOf(60f, anchor20 * 0.35f)
        val steep = brightCount >= 10 && anchor20 >= 900f * 0.85f
        return if (steep) sharp to adaptive else adaptive to sharp
    }

    // ================= 1. 星点检测 =================

    /** 从 Bitmap 检测星点 */
    fun detectStars(bitmap: Bitmap, maxStars: Int = 40): List<DetectedStar> {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val gray = FloatArray(w * h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            gray[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }
        return detectStarsGray(w, h, gray, maxStars)
    }

    /**
     * 核心检测（纯 JVM，可单测）：亮度数组 → 星点列表。
     * maxStars 默认 64：76° 广角场中的 mag≤4.5 星表星可多达 60+ 颗，
     * 旧上限 40 会在富星场把真星表星挤出去（检出 blob 可达 200~600 个）。
     */
    fun detectStarsGray(width: Int, height: Int, gray: FloatArray, maxStars: Int = 64): List<DetectedStar> {
        val n = width * height
        require(gray.size == n) { "灰度数组尺寸不匹配" }

        // 1) 背景估计（box blur）——去除光污染/月晕渐变
        val r = max(3, min(width, height) / 80)
        val bg = boxBlur(gray, width, height, r)

        // 2) 减背景，统计残差噪声水平
        val sub = FloatArray(n)
        var mean = 0.0
        var m2 = 0.0
        for (i in 0 until n) {
            val v = gray[i] - bg[i]
            sub[i] = v
            mean += v
            m2 += v * v
        }
        mean /= n
        m2 = m2 / n - mean * mean
        val sigma = sqrt(max(m2, 1.0)).toFloat()
        val thr = max(8f, 3.5f * sigma)

        // 3) 连通域（8 邻域）→ 亮度加权质心
        val visited = BooleanArray(n)
        val maxArea = max(64, n / 300) // 月亮/路灯等大亮斑上限
        val stackX = IntArray(maxArea + 16)
        val stackY = IntArray(maxArea + 16)
        val stars = ArrayList<DetectedStar>()

        for (i in 0 until n) {
            if (visited[i] || sub[i] <= thr) continue
            var sp = 0
            var sumV = 0.0
            var sumVx = 0.0
            var sumVy = 0.0
            var count = 0
            var maxV = 0f
            var minX = Int.MAX_VALUE
            var minY = Int.MAX_VALUE
            var maxX = 0
            var maxY = 0
            stackX[sp] = i % width
            stackY[sp] = i / width
            sp++
            visited[i] = true
            var overflow = false
            while (sp > 0) {
                sp--
                val x = stackX[sp]
                val y = stackY[sp]
                val v = sub[y * width + x]
                sumV += v
                sumVx += v * x
                sumVy += v * y
                count++
                if (v > maxV) maxV = v
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (count > maxArea) {
                    overflow = true
                    break
                }
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        val ny = y + dy
                        if (nx in 0 until width && ny in 0 until height) {
                            val ni = ny * width + nx
                            if (!visited[ni] && sub[ni] > thr) {
                                visited[ni] = true
                                stackX[sp] = nx
                                stackY[sp] = ny
                                sp++
                            }
                        }
                    }
                }
            }
            if (overflow || count < 2) continue
            if (maxV > 250f && (maxX - minX) * (maxY - minY) > 64) continue // 饱和大斑（路灯等）
            val cx = (sumVx / sumV).toFloat()
            val cy = (sumVy / sumV).toFloat()
            if (cx < 3 || cy < 3 || cx > width - 3 || cy > height - 3) continue
            stars.add(DetectedStar(cx, cy, sumV.toFloat()))
        }
        val ranked = stars.sortedByDescending { it.brightness }.take(maxStars)
        // §0.43b：亮源掩蔽——灯光/月亮区域内的检测星点是伪影，剔除后真解可
        // 浮现（5087/5092 实测：掩蔽后飞马座真解 inliers 16/13；正常星空
        // 掩码为空，跳过过滤零副作用）。
        val grid = maskBrightSource(width, height, gray)
        var masked = 0
        for (g in grid) if (g) masked++
        if (masked == 0) return ranked
        val gridW = (width + 29) / 30
        return ranked.filter {
            val gx = it.x.toInt().coerceIn(0, width - 1) / 30
            val gy = it.y.toInt().coerceIn(0, height - 1) / 30
            !grid[gy * gridW + gx]
        }
    }

    /**
     * §0.43b：大面积亮源（灯光/月亮）掩码——60×60 窗口均值 > 全局 mean+6σ 的
     * 网格标记为 true。点状星不会触发（窗口内占比小），正常星空掩码为空。
     * 由 [detectStarsGray] 末尾对检测星点做剔除。
     */
    private fun maskBrightSource(width: Int, height: Int, gray: FloatArray): BooleanArray {
        val n = width * height
        var mean = 0.0
        for (v in gray) mean += v
        mean /= n
        var varr = 0.0
        for (v in gray) {
            val d = v - mean
            varr += d * d
        }
        varr /= n
        val thr = mean + 6.0 * sqrt(varr)
        // 前缀和：O(n) 得任意窗口均值
        val cs = Array(height) { FloatArray(width) }
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                cs[y][x] = gray[row + x] +
                    (if (x > 0) cs[y][x - 1] else 0f) +
                    (if (y > 0) cs[y - 1][x] else 0f) -
                    (if (x > 0 && y > 0) cs[y - 1][x - 1] else 0f)
            }
        }
        val win = 60
        val gridW = (width + 29) / 30
        val gridH = (height + 29) / 30
        val grid = BooleanArray(gridW * gridH)
        for (gy in 0 until gridH) {
            for (gx in 0 until gridW) {
                val x0 = gx * 30
                val y0 = gy * 30
                val x1 = min(width - 1, x0 + win - 1)
                val y1 = min(height - 1, y0 + win - 1)
                var s = cs[y1][x1].toDouble()
                if (x0 > 0) s -= cs[y1][x0 - 1]
                if (y0 > 0) s -= cs[y0 - 1][x1]
                if (x0 > 0 && y0 > 0) s += cs[y0 - 1][x0 - 1]
                if (s / ((x1 - x0 + 1) * (y1 - y0 + 1)) > thr) {
                    grid[gy * gridW + gx] = true
                }
            }
        }
        return grid
    }

    /** 滑动窗口 box blur（水平 + 垂直两趟，O(n)） */
    private fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(w * h)
        val out = FloatArray(w * h)
        val ws = (2 * r + 1).toFloat()
        for (y in 0 until h) {
            val row = y * w
            var acc = 0f
            for (x in -r..r) acc += src[row + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[row + x] = acc / ws
                acc += src[row + (x + r + 1).coerceIn(0, w - 1)] - src[row + (x - r).coerceIn(0, w - 1)]
            }
        }
        for (x in 0 until w) {
            var acc = 0f
            for (y in -r..r) acc += tmp[y.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = acc / ws
                acc += tmp[(y + r + 1).coerceIn(0, h - 1) * w + x] - tmp[(y - r).coerceIn(0, h - 1) * w + x]
            }
        }
        return out
    }

    // ================= 2. 星表三角形索引 =================

    private class TriEntry(val hipA: Int, val hipB: Int, val hipC: Int, val r2: Float, val r3: Float)

    private class StarIndex(val triMap: HashMap<Int, MutableList<TriEntry>>)

    @Volatile
    private var cachedIndex: StarIndex? = null

    /** 调试：返回（索引三角形总数, 首次构建耗时 ms） */
    internal fun debugIndexBuild(): Pair<Int, Long> {
        val t0 = System.nanoTime()
        val idx = index()
        val ms = (System.nanoTime() - t0) / 1_000_000
        return idx.triMap.values.sumOf { it.size } to ms
    }

    private fun index(): StarIndex {
        cachedIndex?.let { return it }
        synchronized(this) {
            cachedIndex?.let { return it }
            cachedIndex = buildIndex()
            return cachedIndex!!
        }
    }

    /**
     * 亮星集（mag ≤ 4.0 索引子集）：每颗星取角距 0.8°~25° 内最近的 12 颗
     * 邻星，构建局部三角形索引。
     * 注意契约：投票索引与星表（919 颗，mag≤4.5）并非同一集合——索引刻意
     * 只取 mag≤4.0 的亮子集。实测（第四/五轮）若把索引放宽到 4.5，富星场
     * 中每个查询的候选数暴增，弱验证（第 4 星 0.35° 单命中）拦不住假三角形，
     * 假票稀释真票，真实照片解算率从 8/12 跌回 5/12。合成场若渲染出
     * 4.0~4.5 的不可匹配星，同样会稀释亮星近邻列表（仙后座回归实测）——
     * 投票工作集与索引的 mag≤4.0 域必须保持一致。
     */
    private fun buildIndex(magLimit: Float = 4.0f): StarIndex {
        val bright = StarCatalogData.stars.filter { it.mag <= magLimit }
        val triMap = HashMap<Int, MutableList<TriEntry>>()
        for (a in bright) {
            val neighbors = bright
                .filter { it !== a }
                .map { it to angularDist(a, it) }
                .filter { it.second in 0.8..25.0 }
                .sortedBy { it.second } // 与照片端"最近邻"一致
                .take(12)
            for (i in neighbors.indices) {
                for (j in i + 1 until neighbors.size) {
                    val b = neighbors[i].first
                    val c = neighbors[j].first
                    val dab = neighbors[i].second
                    val dac = neighbors[j].second
                    val dbc = angularDist(b, c)
                    val sides = doubleArrayOf(dab, dac, dbc).sortedDescending()
                    val r2 = (sides[1] / sides[0]).toFloat()
                    val r3 = (sides[2] / sides[0]).toFloat()
                    if (r2 < 0.12f) continue // 排除过扁三角形
                    val entry = TriEntry(a.hip, b.hip, c.hip, r2, r3)
                    for (key in quantizeKeys(r2, r3)) {
                        triMap.getOrPut(key) { mutableListOf() }.add(entry)
                    }
                }
            }
        }
        return StarIndex(triMap)
    }

    /**
     * 归一化边长比 → 邻近桶（256 级细粒度，让正确匹配投票尖锐占优）。
     * window=1：严格查询（±0.012）；window=5：宽松回退查询（±0.05，
     * 覆盖大视场 gnomonic 投影造成的比值偏移，实测 ~4%）。
     */
    private fun quantizeKeys(r2: Float, r3: Float, window: Int = 1): List<Int> {
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

    // ================= 3. 匹配 =================

    /**
     * 匹配入口：parity ±1 分别尝试，返回内点数多者。
     * 门限（真实照片验证校准，2026-08-27）：
     *  - inlierCount ≥ 6：低于此的真解在 8 张网络真实照片测试集上无一出现，
     *    而假阳性（Sedna 发现图 apod5）恰为 5 内点 → 5 过松；
     *  - FOV 合理性：pixScale×图像边长换算视场须在 0.05°~180° 区间。假阳性
     *    常表现为尺度坍缩/膨胀（实测假解输出 627° 与 1900° 视场，真解 34°~42°）。
     */
    fun match(detected: List<DetectedStar>, width: Int, height: Int): LocalMatchResult? {
        if (detected.size < 5) return null
        val (t1, t2) = voteThresholds(detected.map { it.brightness })

        // 投票与 parity（镜像）无关：每个阈值只投一次，normal/mirrored 共享结果。
        // （v1.5.8 的双阈值曾按"每 parity × 每阈值"各投一次，投票最多 4 轮，
        //  失败/弱解照片识别耗时从 ~2s 涨到 ~9s；本重构降回最多 2 轮，成功率不变）
        val primaryPairs = votePairs(detected, width, height, t1)
        var best = bestCandidate(detected, width, height, primaryPairs)
        if (best?.inlierCount != null && best.inlierCount >= DUAL_THRESHOLD_STRONG_INLIERS) {
            return best
        }
        // §0.43 稀疏场重投：单候选轮完全无解时，用每星 top-3 候选重投一轮
        // （仅当无解，有解路径完全不动；伪解由 plausibleFov/skySpan 门槛把守）。
        if (best == null) {
            best = bestCandidate(detected, width, height, votePairs(detected, width, height, t1, multi = true))
            if (best?.inlierCount != null && best.inlierCount >= DUAL_THRESHOLD_STRONG_INLIERS) {
                return best
            }
        }
        // §0.43 弱星轮：仍无解时用低于 60 下限的阈值 + 多候选重投，救欠曝/暗场
        // （5031 实测：anchor20=39 → adaptive 被下限 60 卡死，弱星全被踢出投票；
        //  单候选阈值 25~50 时 10~13 内点真解浮现；multi 在低阈值下噪声敏感
        //  反而失败，故弱星轮走单候选，伪模型由 plausibleFov 门槛挡住）。
        if (best == null) {
            val anchor20 = detected.map { it.brightness }.sortedDescending().getOrElse(19) { 0f }
            val weakT = maxOf(25f, anchor20 * 0.25f)
            if (weakT < t1) {
                best = bestCandidate(detected, width, height, votePairs(detected, width, height, weakT))
                if (best?.inlierCount != null && best.inlierCount >= DUAL_THRESHOLD_STRONG_INLIERS) {
                    return best
                }
            }
        }
        if (t2 != t1) {
            val backupPairs = votePairs(detected, width, height, t2)
            var alt = bestCandidate(detected, width, height, backupPairs)
            if (alt == null) {
                alt = bestCandidate(detected, width, height, votePairs(detected, width, height, t2, multi = true))
            }
            // 备用轮只有拿到强解才反超主轮：弱解之间以主轮为准（主口味优先，
            // 备用轮弱解顶掉主轮真解的回归见 §0.32.3 —— apod4 12 内点真解曾被
            // adaptive 轮 13 内点假解替代、再被跨度门槛拦截成 UNSOLVED）
            if (alt != null && (best == null || alt.inlierCount >= DUAL_THRESHOLD_STRONG_INLIERS)) {
                best = alt
            }
        }
        return best
    }

    /**
     * §0.43 诊断：解算轨迹（双阈值 × 双 parity 的投票对数与拟合内点），
     * 测试专用，不影响 [match] 的正常流程。
     */
    internal fun debugSolveTrace(detected: List<DetectedStar>, width: Int, height: Int): String {
        val sb = StringBuilder()
        sb.append("detected=${detected.size} ")
        val (t1, t2) = voteThresholds(detected.map { it.brightness })
        sb.append("t1=$t1 t2=$t2 ")
        for ((tag, t) in listOf("t1" to t1, "t2" to t2)) {
            val pairs = votePairs(detected, width, height, t)
            sb.append("[$tag] pairs=${pairs.size} ")
            for (mx in listOf(false, true)) {
                val r = fitAndVerify(detected, width, height, pairs, mirrorX = mx)
                if (r != null) {
                    sb.append(
                        "%s=%d星[ra=%.1f dec=%.1f fov=%.1f] ".format(
                            if (mx) "mir" else "nor",
                            r.inlierCount, r.solve.raDeg, r.solve.decDeg,
                            maxOf(r.solve.fieldWidthDeg, r.solve.fieldHeightDeg),
                        ),
                    )
                } else {
                    sb.append(if (mx) "mir=NULL " else "nor=NULL ")
                }
            }
        }
        return sb.toString()
    }

    /**
     * 给定投票对应集，两个 parity 各自拟合验证，返回通过全部门槛的最优者。
     * 门限（真实照片验证校准，2026-08-27）：inlierCount ≥ 6、FOV 合理性、
     * 天球跨度一致性（见 [skySpanConsistent]）。
     */
    private fun bestCandidate(
        detected: List<DetectedStar>,
        width: Int,
        height: Int,
        pairs: List<Pair<Int, StarEntry>>,
    ): LocalMatchResult? {
        if (pairs.size < 4) return null
        val normal = fitAndVerify(detected, width, height, pairs, mirrorX = false)
        val mirrored = fitAndVerify(detected, width, height, pairs, mirrorX = true)
        return listOfNotNull(normal, mirrored)
            .filter { it.inlierCount >= 6 && plausibleFov(it.solve.pixScaleArcsec, width, height) }
            .filter { skySpanConsistent(it, detected) }
            .maxByOrNull { it.inlierCount }
    }

    /**
     * 星点天球跨度一致性（视场-星密度校验的几何形式，§0.29 遗留项）：
     * 全部检测星点经候选 WCS 反投影到天球后，最大角距跨度必须达到声称
     * 短边视场的 30%。真实广角照片星点铺满画面（跨度 ≈ 视场对角），
     * 而"宽场误判窄场特写"型假解的星点在天球上挤在极小一片——真机实测
     * 4963（0.2° 实拍被解成 76°）、5092（7.3° 被解成 76°）即此形态。
     * haversine 公式保证小角度跨度下的数值精度。
     */
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
                val d = haversineDeg(ra[i], dec[i], ra[j], dec[j])
                if (d > span) span = d
            }
        }
        return span >= 0.30 * minFov
    }

    /** 球面角距（度），haversine：小角度下比 acos 稳健 */
    private fun haversineDeg(ra1: Double, dec1: Double, ra2: Double, dec2: Double): Double {
        val r = PI / 180.0
        val sd = sin((dec2 - dec1) * r / 2.0)
        val sra = sin((ra2 - ra1) * r / 2.0)
        val a = sd * sd + cos(dec1 * r) * cos(dec2 * r) * sra * sra
        return 2.0 * asin(sqrt(a.coerceIn(0.0, 1.0))) / r
    }

    /** 像素比例尺 → 图像边长视场（度），必须在 0.05°~180° 才算合理天区 */
    private fun plausibleFov(pixScaleArcsec: Double, width: Int, height: Int): Boolean {
        if (!pixScaleArcsec.isFinite() || pixScaleArcsec <= 0.0) return false
        val fovDeg = max(width, height) * pixScaleArcsec / 3600.0
        return fovDeg in 0.05..180.0
    }

    /** 三角形投票 → 候选对应集（照片星下标 → 星表星）。 */
    internal fun votePairs(
        detected: List<DetectedStar>,
        width: Int,
        height: Int,
        threshold: Float = voteBrightnessThreshold(detected.map { it.brightness }),
        multi: Boolean = false,
    ): List<Pair<Int, StarEntry>> {
        val idx = debugForceIndexMag?.let { buildIndex(it) } ?: index()

        // 1) 照片局部三角形：只用亮度足以进入星表索引的亮星（阈值由调用方
        //    依据双通道方案传入，见 voteThresholds），取前 45 颗参与投票。
        val votes = HashMap<Long, Int>() // key = photoIdx shl 20 | hip
        val maxSide = max(width, height) * 0.45f
        val hipToEntry = StarCatalogData.stars.associateBy { it.hip }
        val work = detected
            .filter { it.brightness >= threshold }
            .take(45)

        // 多线程投票：每颗照片星的三角形投票相互独立（key 高位含 photoIdx），
        // 按照片星分块并行，各线程本地累加后合并 —— 结果与单线程完全一致。
        if (work.size <= 8) {
            voteChunk(votes, work, 0, work.size, idx, maxSide)
        } else {
            val chunkCount = Runtime.getRuntime().availableProcessors().coerceIn(2, work.size)
            val chunkSize = (work.size + chunkCount - 1) / chunkCount
            val pool = Executors.newFixedThreadPool(chunkCount)
            try {
                val futures = ArrayList<Future<HashMap<Long, Int>>>(chunkCount)
                for (c in 0 until chunkCount) {
                    val from = c * chunkSize
                    val to = min(work.size, from + chunkSize)
                    if (from >= to) break
                    futures.add(pool.submit(Callable {
                        val local = HashMap<Long, Int>()
                        voteChunk(local, work, from, to, idx, maxSide)
                        local
                    }))
                }
                for (f in futures) {
                    val local = f.get()
                    for ((k, v) in local) votes[k] = (votes[k] ?: 0) + v
                }
            } finally {
                pool.shutdown()
            }
        }

        // 4) 每颗照片星取票数最高且明显领先的星表星 → 对应集。
        //    阈值 v≥2 / 1.1×；剩余的错误对应交给 RANSAC 三点采样剔除。
        val byPhoto = HashMap<Int, HashMap<Int, Int>>()
        for ((key, v) in votes) {
            val photoIdx = (key shr 20).toInt()
            val hip = (key and 0xFFFFF).toInt()
            byPhoto.getOrPut(photoIdx) { HashMap() }[hip] = v
        }
        val pairs = ArrayList<Pair<Int, StarEntry>>()
        for ((photoIdx, m) in byPhoto) {
            // 票数相同时按 hip 升序决出胜负：多线程合并的 HashMap 遍历序不稳定，
            // 稳定排序在 tie 上会跟随遍历序 → 同一照片两次运行结果可能不同
            val sorted = m.entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
            if (sorted.isEmpty()) continue
            val (hip, v) = sorted[0]
            val runnerUp = if (sorted.size > 1) sorted[1].value else 0
            var added = 0
            if (v >= 2 && (runnerUp == 0 || v >= runnerUp * 1.2f)) {
                hipToEntry[hip]?.let {
                    pairs.add(photoIdx to it)
                    added++
                }
            }
            // §0.43 稀疏场多候选：单候选轮无解时的重投轮（match 内启用）。
            // 稀疏场（飞马座等）各照片星顶票 HIP 常区域一致但编号漂移，
            // 单候选对跨星不共享 HIP，RANSAC 采不出 3 真对；放宽为每星
            // 前 3 个 v≥2 候选后真对密度足够，伪对应由拟合门槛剔除。
            if (multi) {
                for (i in 1 until sorted.size) {
                    if (sorted[i].value < 2 || added >= 3) break
                    hipToEntry[sorted[i].key]?.let {
                        pairs.add(photoIdx to it)
                        added++
                    }
                }
            }
        }
        // 固定对应集顺序（RANSAC 以固定种子按索引采样，顺序不稳定会导致
        // 采样轨迹跨运行不同 → 边界照片的解算结果不可复现）
        pairs.sortBy { it.first }
        return pairs
    }

    private fun triKey(t: TriEntry): Long =
        (t.hipA.toLong() shl 40) or (t.hipB.toLong() shl 20) or t.hipC.toLong()

    /** 单（照片星下标）区间内的三角形投票——可被多线程并行调用，各区间互不共享 key */
    private fun voteChunk(
        votes: HashMap<Long, Int>,
        work: List<DetectedStar>,
        from: Int,
        to: Int,
        idx: StarIndex,
        maxSide: Float,
    ) {
        for (pi in from until to) {
            val a = work[pi]
            val nbrs = work.mapIndexed { j, s -> j to dist(a, s) }
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
                    val lbc = dist(b, c)
                    val sides = floatArrayOf(lab, lac, lbc).sortedDescending()
                    val r2 = sides[1] / sides[0]
                    val r3 = sides[2] / sides[0]
                    if (r2 < 0.12f) continue

                    // 2) 按量化比值查索引：严格池（±0.012）与宽松池（±0.05，覆盖
                    //    大视场 gnomonic 比值偏移 ~4%）取并集后按接近度取前 10。
                    //    只查严格池会漏掉被投影畸变推离的真三角形（错误“相似”候选
                    //    常占据严格窗口，宽松池从未触发 → 真票系统性缺失，实测）。
                    val strictRaw = ArrayList<TriEntry>()
                    for (key in quantizeKeys(r2, r3, window = 1)) {
                        idx.triMap[key]?.let { list -> strictRaw.addAll(list) }
                    }
                    val looseRaw = ArrayList<TriEntry>()
                    for (key in quantizeKeys(r2, r3, window = 13)) {
                        idx.triMap[key]?.let { list -> looseRaw.addAll(list) }
                    }
                    val merged = HashMap<Long, TriEntry>()
                    for (t in strictRaw) {
                        if (abs(t.r2 - r2) < 0.012f && abs(t.r3 - r3) < 0.012f) {
                            merged[triKey(t)] = t
                        }
                    }
                    for (t in looseRaw) {
                        if (abs(t.r2 - r2) < 0.05f && abs(t.r3 - r3) < 0.05f) {
                            merged[triKey(t)] = t
                        }
                    }
                    val candidates = merged.values
                        .sortedBy { abs(it.r2 - r2) + abs(it.r3 - r3) }
                        .take(18)

                    // 3) 顶点对应投票：先做几何验证（第 4 星校验），只投验证通过的候选
                    val verified = candidates.filter { verifyCandidate(a, b, c, it, nbrs, work) }
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
    }

private fun vote(votes: HashMap<Long, Int>, photoIdx: Int, hip: Int) {
        val key = (photoIdx.toLong() shl 20) or hip.toLong()
        votes[key] = (votes[key] ?: 0) + 1
    }

    // ============ 候选三角形几何验证（astrometry 式“第 4 星”校验） ============

    private val hipMap by lazy { StarCatalogData.stars.associateBy { it.hip } }

    @Volatile
    private var cachedGrid: HashMap<Int, MutableList<StarEntry>>? = null

    /** 星表赤道网格（5°×5° 赤经/赤纬格），用于验证时的快速最近星查询 */
    private fun catGrid(): HashMap<Int, MutableList<StarEntry>> {
        cachedGrid?.let { return it }
        synchronized(this) {
            cachedGrid?.let { return it }
            val grid = HashMap<Int, MutableList<StarEntry>>()
            // 验证网格只收录照片可检测的亮星（mag<=4.0，与投票索引同域）：
            // §0.42 扩容新增的暗星照片里检测不到，放进来会让假候选的"第 4 星
            // 外推撞星"通过率上升 → 假阳性回归（apod5/pleiades 实测），
            // 而真候选外推应命中的是可检测亮星，不受影响。
            for (star in StarCatalogData.stars) {
                if (star.mag > 4.0f) continue
                val col = (((star.ra % 360.0) + 360.0) % 360.0 / 5.0).toInt()
                val row = ((star.dec + 90.0) / 5.0).toInt().coerceIn(0, 35)
                grid.getOrPut(col * 1000 + row) { ArrayList() }.add(star)
            }
            cachedGrid = grid
            return grid
        }
    }

    /** 球面角距（度），用于网格探测 */
    private fun angDist(ra1: Double, dec1: Double, ra2: Double, dec2: Double): Double {
        val r = PI / 180.0
        val a1 = ra1 * r
        val d1 = dec1 * r
        val a2 = ra2 * r
        val d2 = dec2 * r
        val s = sin(d1) * sin(d2) + cos(d1) * cos(d2) * cos(a1 - a2)
        return acos(s.coerceIn(-1.0, 1.0)) * 180.0 / PI
    }

    /** (ra, dec) 附近 [tolDeg] 内最近的星表星，无则 null */
    private fun nearestStarInGrid(ra: Double, dec: Double, tolDeg: Double): StarEntry? {
        val grid = catGrid()
        val col0 = ((((ra - 0.5) % 360.0) + 360.0) % 360.0 / 5.0).toInt()
        val col1 = ((((ra + 0.5) % 360.0) + 360.0) % 360.0 / 5.0).toInt()
        val row0 = ((dec - 0.5 + 90.0) / 5.0).toInt().coerceIn(0, 35)
        val row1 = ((dec + 0.5 + 90.0) / 5.0).toInt().coerceIn(0, 35)
        var best: StarEntry? = null
        var bestD = tolDeg
        val cols = if (col0 == col1) intArrayOf(col0) else intArrayOf(col0, col1)
        val rows = if (row0 == row1) intArrayOf(row0) else intArrayOf(row0, row1)
        for (col in cols) {
            for (row in rows) {
                grid[col * 1000 + row]?.let { list ->
                    for (s in list) {
                        val d = angDist(ra, dec, s.ra, s.dec)
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

    /**
     * 候选三角形几何验证：
     * 1) 相似拟合「照片三顶点 ↔ 星表三角形切平面坐标（以星表三角形质心为原点）」，
     *    同时尝试正常（det>0）与镜像（X 取负，det<0）两种映射 —— 镜像照片的内容
     *    变换是反射（Y = b·x − a·y），只试 det>0 会拒绝全部真候选；B/C 两种排列任一
     *    通过，且顶点残差 < 形状门槛（吸收 gnomonic 曲率）；
     * 2) 用拟合模型外推第 4~7 近邻照片星的天球坐标，在星表网格 0.35° 内能找到星
     *    即通过（错误候选的邻域外推会塌在空白天区，被此步剔除）。
     */
    private fun verifyCandidate(
        a: DetectedStar,
        b: DetectedStar,
        c: DetectedStar,
        t: TriEntry,
        nbrs: List<Pair<Int, Float>>,
        stars: List<DetectedStar>,
    ): Boolean {
        val eA = hipMap[t.hipA] ?: return false
        val eB = hipMap[t.hipB] ?: return false
        val eC = hipMap[t.hipC] ?: return false
        val ra0 = (eA.ra + eB.ra + eC.ra) / 3.0
        val dec0 = (eA.dec + eB.dec + eC.dec) / 3.0
        val (xA, yA) = tanXY(eA.ra, eA.dec, ra0, dec0)
        val (xB, yB) = tanXY(eB.ra, eB.dec, ra0, dec0)
        val (xC, yC) = tanXY(eC.ra, eC.dec, ra0, dec0)
        val xs = floatArrayOf(a.x, b.x, c.x)
        val ys = floatArrayOf(a.y, b.y, c.y)
        val sideMax = maxOf(angularDist(eA, eB), angularDist(eA, eC), angularDist(eB, eC))
        val tolShape = max(0.05, 0.04 * sideMax)
        // 依次尝试：正常映射（两种顶点排列）→ 镜像映射（两种顶点排列）
        val fitVariants = ArrayList<Pair<DoubleArray?, Boolean>>(4)
        for (negX in listOf(false, true)) {
            val signX = { v: Double -> if (negX) -v else v }
            fitVariants.add(
                fitSimilarity(
                    xs, ys,
                    floatArrayOf(signX(xA).toFloat(), signX(xB).toFloat(), signX(xC).toFloat()),
                    floatArrayOf(yA.toFloat(), yB.toFloat(), yC.toFloat()),
                ) to negX
            )
            fitVariants.add(
                fitSimilarity(
                    xs, ys,
                    floatArrayOf(signX(xA).toFloat(), signX(xC).toFloat(), signX(xB).toFloat()),
                    floatArrayOf(yA.toFloat(), yC.toFloat(), yB.toFloat()),
                ) to negX
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
            // 第 4~7 近邻照片星的天球外推 + 星表网格探测（验证网格仅含
            // mag<=4.0 可检测亮星，见 catGrid）
            for (k in 3 until min(8, nbrs.size)) {
                val d = stars[nbrs[k].first]
                val xm = s * d.x - ss * d.y + tx
                val ym = ss * d.x + s * d.y + ty
                val (raP, decP) = tanInverse(if (negX) -xm else xm, ym, ra0, dec0)
                if (nearestStarInGrid(raP, decP, 0.35) != null) return true
            }
        }
        return false
    }

    /** 调试：每颗照片星票面 Top5 明细（用于定位投票失败根因）。
     *  truth 为真值 WCS（仅调试用），用于标注每颗照片星的真实 hip 与真实票数。 */
    fun debugVoteTable(
        detected: List<DetectedStar>,
        width: Int,
        height: Int,
        truth: WcsTransform? = null,
    ): String {
        val idx = index()
        val sb = StringBuilder()
        val maxSide = max(width, height) * 0.45f
        val votes = HashMap<Long, Int>()
        val work = detected.filter { it.brightness >= voteBrightnessThreshold(detected.map { it.brightness }) }.take(45)
        var strictEmpty = 0
        var looseFired = 0
        // photoIdx -> 真值 hip（最近星表星，<6px 才算）
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
            val nbrs = work.mapIndexed { j, s -> j to dist(a, s) }
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
                    val lbc = dist(b, c)
                    val sides = floatArrayOf(lab, lac, lbc).sortedDescending()
                    val r2 = sides[1] / sides[0]
                    val r3 = sides[2] / sides[0]
                    if (r2 < 0.12f) continue
                    val strictRaw = ArrayList<TriEntry>()
                    for (key in quantizeKeys(r2, r3, window = 1)) {
                        idx.triMap[key]?.let { list -> strictRaw.addAll(list) }
                    }
                    val looseRaw = ArrayList<TriEntry>()
                    for (key in quantizeKeys(r2, r3, window = 13)) {
                        idx.triMap[key]?.let { list -> looseRaw.addAll(list) }
                    }
                    val merged = HashMap<Long, TriEntry>()
                    var strictHit = false
                    for (t in strictRaw) {
                        if (abs(t.r2 - r2) < 0.012f && abs(t.r3 - r3) < 0.012f) {
                            merged[triKey(t)] = t
                            strictHit = true
                        }
                    }
                    for (t in looseRaw) {
                        if (abs(t.r2 - r2) < 0.05f && abs(t.r3 - r3) < 0.05f) {
                            merged[triKey(t)] = t
                        }
                    }
                    if (!strictHit) strictEmpty++
                    val candidates = merged.values
                        .sortedBy { abs(it.r2 - r2) + abs(it.r3 - r3) }
                        .take(18)
                    for (t in candidates) {
                        if (verifyCandidate(a, b, c, t, nbrs, work)) {
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
                .filter { (it.key shr 20).toInt() == pi }
                .sortedByDescending { it.value }
                .take(5)
            if (sorted.isEmpty()) continue
            printed++
            val trueHip = actual[pi]
            val trueV = if (trueHip != null) votes[(pi.toLong() shl 20) or trueHip.toLong()] ?: 0 else -1
            sb.append("P#$pi b=${"%.0f".format(work[pi].brightness)}" +
                (if (trueHip != null) " trueHip=$trueHip(trueV=$trueV)" else " noTruth") +
                " : " + sorted.joinToString { "hip=${it.key and 0xFFFFF}(v=${it.value})" } + "\n")
        }
        return sb.toString()
    }

    // ================= 4. 拟合与验证 =================

    internal class FitParams(
        val a: Double, val b: Double, val tx: Double, val ty: Double,
        val ra0: Double, val dec0: Double,
    )

    /** 用对应点拟合相似变换（切平面原点 = 对应星表星平均 RA/Dec）。
     *  [mirrorX] = true 时对星表侧切平面 X 取负（镜像照片，对应 parity = -1）；屏幕坐标始终为原始照片帧。
     *  [fixedRa0]/[fixedDec0] 可指定切平面原点（应传整个配对集的平均 RA/Dec）：
     *  只取 3 个采样对时，若它们挤在画面一角，以其平均为原点会把 gnomonic 的非线性
     *  偏差写进 (a, b)（实测最多偏 17%，整场投影错位数百像素）；固定在全集中心则
     *  拟合得到的是全场的真比例尺。 */
    internal fun fitPairs(
        detected: List<DetectedStar>,
        pairs: List<Pair<Int, StarEntry>>,
        width: Int,
        height: Int,
        mirrorX: Boolean = false,
        fixedRa0: Double? = null,
        fixedDec0: Double? = null,
    ): FitParams? {
        if (pairs.size < 3) return null
        val ra0 = fixedRa0 ?: pairs.map { it.second.ra }.average()
        val dec0 = fixedDec0 ?: pairs.map { it.second.dec }.average()
        val cx = width / 2f
        val cy = height / 2f
        val xs = ArrayList<Float>(pairs.size)
        val ys = ArrayList<Float>(pairs.size)
        val Xs = ArrayList<Float>(pairs.size)
        val Ys = ArrayList<Float>(pairs.size)
        for ((k, pair) in pairs.withIndex()) {
            val d = detected[pair.first]
            val (X, Y) = tanXY(pair.second.ra, pair.second.dec, ra0, dec0)
            // 过滤不可见天体（球面另一侧）或异常值，防止 NaN 污染拟合
            if (X.isNaN() || Y.isNaN()) continue
            xs.add(d.x - cx)
            ys.add(d.y - cy)
            Xs.add((if (mirrorX) -X else X).toFloat())
            Ys.add(Y.toFloat())
        }
        if (xs.size < 3) return null
        val fit = fitSimilarity(xs.toFloatArray(), ys.toFloatArray(), Xs.toFloatArray(), Ys.toFloatArray()) ?: return null
        return FitParams(fit[0], fit[1], fit[2], fit[3], ra0, dec0)
    }

    /**
     * 由拟合结果构建原始照片坐标系的 WCS（屏幕 y 向下 → FITS y 向上）。
     * 正常照片（mirrorX=false）：CD = [a, b; b, -a]，det < 0。
     * 镜像照片（mirrorX=true）：fitPairs 对星表切平面 X 取负后拟合（X' = -xw，
     * 模型 X' = a·u - b·v + tx、Y = b·u + a·v + ty）。逆推 skyToFitsPixel 两式：
     *   u = (-a·xw + b·yw - a·tx - b·ty)/den、v = (b·xw + a·yw + b·tx - a·ty)/den，
     *   解得 CD = [-a, -b; b, -a]（det = +den > 0），crpix/CRVAL 与正常相同
     *   （参考像素处模型 = (0,0)）。b=0 时 CD = [+s, 0; 0, +s]：东在右 ✓；
     *   v_screen = lg - v_fits = lg + yw/s，北星在图像上方 ✓（镜像只翻转 x，y 不变）。
     * crpix 精确补偿拟合平移量 (tx, ty)，使 WCS 中心与屏幕中心重合。
     */
    internal fun buildWcs(fp: FitParams, width: Int, height: Int, mirrorX: Boolean = false): WcsTransform {
        val den = fp.a * fp.a + fp.b * fp.b
        val cx = width / 2f
        val cy = height / 2f
        val ka = (fp.a * fp.tx + fp.b * fp.ty) / den
        val lg = (fp.b * fp.tx - fp.a * fp.ty) / den
        val crpix1 = cx + 1.0 - ka
        val crpix2 = height - cy - lg
        // 参考像素（FITS，1 起始 → 屏幕 (crpix1-1, H-crpix2)）处模型的切平面坐标
        // 再做 gnomonic 逆投影；两种模式参考像素处模型均为 (0,0)（参见上方推导）：
        //   uRef = -ka、vRef = +lg → X' = -(a·ka + b·lg) + tx = 0、
        //   Y = -(b·ka - a·lg) + ty = 0 （因 a·ka + b·lg ≡ tx、b·ka - a·lg ≡ ty）。
        val uRef = (crpix1 - 1.0) - cx
        val vRef = (height - crpix2) - cy
        val sx = fp.a * uRef - fp.b * vRef + fp.tx
        val sy = fp.b * uRef + fp.a * vRef + fp.ty
        val xwC = if (mirrorX) -sx else sx
        val (raC, decC) = tanInverse(xwC, sy, fp.ra0, fp.dec0)
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

    internal fun fitAndVerify(
        detected: List<DetectedStar>,
        width: Int,
        height: Int,
        pairs: List<Pair<Int, StarEntry>>,
        mirrorX: Boolean,
    ): LocalMatchResult? {
        if (pairs.size < 4) return null
        val rng = Random(42)
        // 切平面原点固定在整个配对集的平均天球坐标（见 fitPairs 注释）
        val setRa0 = pairs.map { it.second.ra }.average()
        val setDec0 = pairs.map { it.second.dec }.average()

        // RANSAC：随机采样 3 对拟合，统计内点，保留最优。
        // 对数越多噪声对占比越高，采样次数随之上调（45 星工作集下对可达 30+），
        // 否则 60 次采样可能凑不齐 3 个真对应，真解反而落选（5049 实测回退）。
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
            // 内点门限放宽到 45px：3 点拟合是插值精确的，但远离采样质心的对会因
            // gnomonic 曲率外推偏移 20~40px（30°+ 视场实测）；12px 门限会让
            // 角簇样本永远凑不满 3 个内点（镜像照片实测），45px 保证真模型入选。
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

        // 用全部内点精拟合：先做线性相似重拟合，再对完整 gnomonic 投影做
        // Gauss-Newton 精化（吸收大视场下 tan(θ) 的三阶畸变，48° 视场也收敛到亚像素）。
        val refined0 = fitPairs(detected, bestInliers, width, height, mirrorX, setRa0, setDec0) ?: ransacParams
        val refined = refineWcs(detected, bestInliers, width, height, refined0, mirrorX)
        val finalWcs = buildWcs(refined, width, height, mirrorX)

        // 全星表验证
        val inliers = countInliers(finalWcs, detected, width, height)
        if (inliers < 5) return null

        val matched = bestInliers.mapNotNull { (pi, star) ->
            val d = detected[pi]
            val p = finalWcs.skyToScreen(star.ra, star.dec, width, height)
            if (abs(p[0] - d.x) < 8f && abs(p[1] - d.y) < 8f) d to star else null
        }

        val pixScale = sqrt(refined.a * refined.a + refined.b * refined.b) * 3600.0 // 角秒/像素
        val orientation = Math.toDegrees(atan2(refined.b, refined.a))
        // 图像中心像素的天球坐标 = 模型在 (cx,cy) 处外推的切平面坐标逆投影。
        // 注意：不能把 finalWcs.crval 当中心 —— crpix 为补偿平移量位于偏离中心处。
        val (raC, decC) = tanInverse(refined.tx, refined.ty, refined.ra0, refined.dec0)
        // parity 表示照片内容是否左右镜像：mirrorX=true 的拟合（星表 X 取负）只对
        // 镜像照片成立，即该照片为镜像内容 → parity = -1；正常照片 = +1。
        // 注：叠加渲染总是直接用 WCS，parity 仅用于信息展示。
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

    /**
     * Gauss-Newton 非线性精化：参数 (a, b, tx, ty, ra0, dec0)，残差通过完整
     * gnomonic 投影（buildWcs + skyToScreen）计算，数值雅可比，阻尼线搜索。
     * 线性相似模型对大视场（>30°）的边缘残差可达数像素到数十像素（tan 畸变），
     * 精化后残差收敛到亚像素，叠加标注与照片严格对齐。
     */
    private fun refineWcs(
        detected: List<DetectedStar>,
        pairs: List<Pair<Int, StarEntry>>,
        width: Int,
        height: Int,
        start: FitParams,
        mirrorX: Boolean,
    ): FitParams {
        val n = 6
        var p = doubleArrayOf(start.a, start.b, start.tx, start.ty, start.ra0, start.dec0)
        val eps = doubleArrayOf(1e-7, 1e-7, 1e-4, 1e-4, 1e-4, 1e-4)

        fun residual(params: DoubleArray): DoubleArray {
            val fp = FitParams(params[0], params[1], params[2], params[3], params[4], params[5])
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
            // 阻尼正规方程：(JᵀJ + λ·diag(JᵀJ)) δ = -Jᵀr
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
            val delta = gaussSolveN(m)
            if (delta == null) break
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
        return FitParams(best[0], best[1], best[2], best[3], best[4], best[5])
    }
    fun fitSimilarity(
        x: FloatArray,
        y: FloatArray,
        X: FloatArray,
        Y: FloatArray,
    ): DoubleArray? {
        val n = x.size
        if (n < 3) return null
        var sxx = 0.0; var syy = 0.0; var sx = 0.0; var sy = 0.0
        var sXx = 0.0; var sYy = 0.0; var sXy = 0.0; var sYx = 0.0
        var sX = 0.0; var sY = 0.0
        for (i in 0 until n) {
            val xi = x[i].toDouble(); val yi = y[i].toDouble()
            val Xi = X[i].toDouble(); val Yi = Y[i].toDouble()
            sxx += xi * xi; syy += yi * yi
            sx += xi; sy += yi
            sXx += Xi * xi; sYy += Yi * yi
            sXy += Xi * yi; sYx += Yi * xi
            sX += Xi; sY += Yi
        }
        val q = sxx + syy
        val m = Array(4) { DoubleArray(5) }
        // 正规方程（模型：X = a·x - b·y + tx ; Y = b·x + a·y + ty）
        // E1(a): a·q + tx·Σx + ty·Σy = ΣxX + ΣyY
        // E2(b): b·q - tx·Σy + ty·Σx = ΣxY - ΣyX
        // E3(tx): a·Σx - b·Σy + n·tx = ΣX
        // E4(ty): a·Σy + b·Σx + n·ty = ΣY
        m[0][0] = q; m[0][2] = sx; m[0][3] = sy; m[0][4] = sXx + sYy
        m[1][1] = q; m[1][2] = -sy; m[1][3] = sx; m[1][4] = sYx - sXy
        m[2][0] = sx; m[2][1] = -sy; m[2][2] = n.toDouble(); m[2][4] = sX
        m[3][0] = sy; m[3][1] = sx; m[3][3] = n.toDouble(); m[3][4] = sY
        return gaussSolve(m)
    }

    /** 高斯消元求解 4 元线性方程组 */
    private fun gaussSolve(m: Array<DoubleArray>): DoubleArray? = gaussSolveN(m)

    /** 高斯消元求解 n 元线性方程组（列主元，n×n+1 增广矩阵） */
    private fun gaussSolveN(m: Array<DoubleArray>): DoubleArray? {
        val n = m.size
        for (col in 0 until n) {
            var pivot = col
            for (row in col + 1 until n) {
                if (abs(m[row][col]) > abs(m[pivot][col])) pivot = row
            }
            if (abs(m[pivot][col]) < 1e-14) return null
            if (pivot != col) {
                val t = m[col]; m[col] = m[pivot]; m[pivot] = t
            }
            for (row in 0 until n) {
                if (row == col) continue
                val f = m[row][col] / m[col][col]
                for (c in col..n) m[row][c] -= f * m[col][c]
            }
        }
        return DoubleArray(n) { m[it][n] / m[it][it] }
    }

    private fun countInliers(wcs: WcsTransform, detected: List<DetectedStar>, width: Int, height: Int): Int {
        var count = 0
        // 只统计照片可检测的亮星（mag<=4.5，原 919 星域）——§0.42 扩容的暗星
        // 在 2200px 照片里检测不到，遍历它们既虚增内点（假解更易过门槛）又拖慢
        // 10 倍（实测单张 3~5s → 20~50s）。
        for (star in StarCatalogData.stars) {
            if (star.mag > 4.5f) continue
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

    // ================= 5. 球面几何工具 =================

    private fun dist(a: DetectedStar, b: DetectedStar): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    /** 球面角距（度） */
    fun angularDist(a: StarEntry, b: StarEntry): Double {
        val r = PI / 180.0
        val dec1 = a.dec * r
        val dec2 = b.dec * r
        val dra = (a.ra - b.ra) * r
        val s = sin(dec1) * sin(dec2) + cos(dec1) * cos(dec2) * cos(dra)
        return acos(s.coerceIn(-1.0, 1.0)) * 180.0 / PI
    }

    /** gnomonic 正投影：天球 → 切平面（东正、北正，单位度） */
    fun tanXY(ra: Double, dec: Double, ra0: Double, dec0: Double): Pair<Double, Double> {
        val r = PI / 180.0
        val a0 = ra0 * r
        val d0 = dec0 * r
        val a = ra * r
        val d = dec * r
        val cosd = cos(d) * cos(a - a0)
        val denom = sin(d0) * sin(d) + cos(d0) * cosd
        if (denom <= 0.0) return Double.NaN to Double.NaN
        val xi = cos(d) * sin(a - a0) / denom
        val eta = (cos(d0) * sin(d) - sin(d0) * cosd) / denom
        return xi / r to eta / r
    }

    /** gnomonic 逆投影：切平面（东正、北正，度）→ 天球（ra 归一到 0..360） */
    fun tanInverse(xDeg: Double, yDeg: Double, ra0: Double, dec0: Double): Pair<Double, Double> {
        val r = PI / 180.0
        val x = xDeg * r
        val y = yDeg * r
        val a0 = ra0 * r
        val d0 = dec0 * r
        val rho = sqrt(x * x + y * y)
        if (rho < 1e-12) return ra0 to dec0
        val c = atan(rho)
        val dec = asin((cos(c) * sin(d0) + y * sin(c) * cos(d0) / rho).coerceIn(-1.0, 1.0))
        val ra = a0 + atan2(x * sin(c), rho * cos(d0) * cos(c) - y * sin(d0) * sin(c))
        var raDeg = ra / r
        raDeg = ((raDeg % 360.0) + 360.0) % 360.0
        return raDeg to dec / r
    }
}

