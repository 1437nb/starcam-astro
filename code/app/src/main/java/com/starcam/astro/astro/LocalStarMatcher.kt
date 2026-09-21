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

    /**
     * ===== 调试开关（仅用于算法标定与 A/B 对照）=====
     *
     * 这 6 个开关的标定依据是一组互相牵制的常量（星表域 4.0 / 对齐率 0.20 /
     * 提前退出 12 与 0.5），改一个就得重调全部 —— 它们的存在本身就是参数敏感
     * 的证据。生产路径（App 自身）**从不写入**任何一个，全部保持 null/false。
     *
     * 可见性一律 internal：防止被其他模块误写导致识别行为静默改变。
     * 字段名刻意保持不变 —— 离线验证台（C:\dev\harness 的 Sweep / ScoreDetail /
     * FailCost 等）用 `getDeclaredField("debugXxx")` 反射读写，改名会让它们失效。
     * 待「内角索引」落地、伪三角形污染降低后，这些开关应连同打分轮一起清理
     * （见 PROGRESS「下一步」）。
     */

    /**
     * 调试：强制固定投票亮度门槛（模拟旧版行为）。
     * 曾为 public var —— 意味着任何模块都能写，且无标注、无日志，误写难排查；
     * 现收为 internal（外部验证台走反射，不受影响）。
     */
    @Volatile
    internal var debugForceVoteThreshold: Float? = null

    /**
     * §0.62 调试：强制**星表域**星等上限——索引 / 第四星验证网格 / 内点统计
     * 三域同时生效（历史上三者不一致时，涉及暗星的三角形会在验证环节被整体否决，
     * 只加深索引完全无效——§0.43 深扫实测 4.5→6.5 全部 UNSOLVED 且慢 14~35 倍）。
     * null = 生产默认（见 [PROD_CATALOG_MAG]）。
     */
    @Volatile
    internal var debugForceCatalogMag: Float? = null

    /** 生产星表域星等上限：投票索引 / 验证网格 / 内点统计统一使用 */
    private const val PROD_CATALOG_MAG = 4.0f

    /**
     * §0.75 深星表域星等上限（窄场兜底）。
     *
     * 取 6.5 而不是更深，是因为仓库星表本身就到 6.5 等（8415 颗）—— 零新增数据。
     * 实测该深度已能让 10° 视场解出；再深需要引入外部星表（hip_main.dat），
     * 索引体积和内存都要再翻一倍以上，见 PROGRESS §0.75 的分级决策记录。
     */
    private const val DEEP_CATALOG_MAG = 6.5f

    /**
     * §0.75 深域路径的视场上界（度）。
     *
     * 取值依据：已知全部真解的最大视场是宽场回归里的 76.9°，而深域坍缩伪影
     * 报出 104°~170°。取 90° 落在两者之间，既容纳真实宽场，又排除全部伪影。
     * 只作用于深域兜底路径，浅域（宽场主路径）的 [plausibleFov] 门槛不变。
     */
    private const val DEEP_PATH_MAX_FOV_DEG = 90.0

    /** 当前生效的星表域星等上限（调试可覆盖） */
    private fun catalogMag(): Float = debugForceCatalogMag ?: PROD_CATALOG_MAG

    /**
     * §0.62 调试：每轮投票送验候选数上限（生产默认 18）。宽场（>60°）实测：
     * 真候选的比值偏差（gnomonic 投影，median 0.019）大于严格窗 0.012，而数百个
     * 伪三角形在比值空间里更贴近查询 → 真候选被 take(18) 挤出送验列表，
     * 真 HIP 得票 4~11 而错误 HIP 得 14~19。提高上限可验证此假设。
     */
    @Volatile
    internal var debugCandidateCap: Int? = null

    private fun candidateCap(): Int = debugCandidateCap ?: 18

    /** §0.62 打分轮：对齐判定容差（像素）与最低对齐星数 */
    private const val ALIGN_TOL_PX = 8f

    /**
     * §0.62 打分轮的高置信提前退出阈值：对齐数 ≥ 12 且对齐率 ≥ 0.5 才提前收工。
     * 真解形态实测（apod4 11/26、用户照片 7/26）都低于此，故不会误触发。
     */
    private const val SCORED_EARLY_ALIGNED = 12
    private const val SCORED_EARLY_RATE = 0.5f

    /**
     * §0.62 打分轮时间预算（毫秒）。评分轮在投票失败后触发，候选可达 7 万个
     * （实测 45 工作星 × C(14,2) 三角形 × 18 候选 ≈ 70k），按 ~150μs/候选
     * 需 10.5 秒。给 3 秒预算后，失败路径的额外开销从 ~10s 压到 ~3s，
     * 而真解候选通常出现在序列前段（用户照片在预算内即命中）。
     */
    private const val SCORED_TIME_BUDGET_MS = 3000L

    /** §0.63 精拟合阶段：切平面原点向图像中心迭代的轮数（3~4 轮即收敛） */
    private const val SCORED_ORIGIN_ITERATIONS = 4

    /** 弧度 → 度的倒数（tanXY 与打分轮共用同一尺度约定） */
    private const val INV_RAD = 180.0 / PI


    /** §0.62 调试：最低对齐星数（生产默认 6；设为极大可停用打分轮做 A/B） */
    @Volatile
    internal var debugMinAligned: Int? = null

    private fun minAligned(): Int = debugMinAligned ?: SCORED_MIN_ALIGNED

    private const val SCORED_MIN_ALIGNED = 6

    /** 打分轮的空结果哨兵 */
    private val ZERO_SCORE = intArrayOf(0, 0)

    /**
     * 打分轮胜出条件（§0.62）：
     *  - 对齐星数 ≥ [SCORED_MIN_ALIGNED]（绝对下限，滤掉零星巧合）；
     *  - 对齐率 = aligned / inFrame ≥ [SCORED_MIN_ALIGN_RATE]，
     *    且 inFrame ≥ [SCORED_MIN_IN_FRAME]（样本太少时比率不可信）。
     *
     * 标定（n=60 全语料实测）：
     *   用户南宁照片（真解） rate = 7/26  = 0.27
     *   pleiades / apod2 / apod5 / apod3 / apod1（伪解） 0.07 ~ 0.11
     *   m44-1910（伪解）0.05
     * 真解与伪解之间有 2.5 倍以上的间隔，取 0.20 作为门槛（居中偏保守）。
     * 伪解的对齐率低是因为尺度坍缩把整片天区压进画面：inFrame 高达 139~235，
     * 而对齐数只有 13~21；真解的 inFrame 只有 26（画面真实覆盖），对齐 7 颗。
     */
    private const val SCORED_MIN_ALIGN_RATE = 0.20f
    private const val SCORED_MIN_IN_FRAME = 4

    /** §0.62 调试：覆盖对齐率门槛（标定用） */
    @Volatile
    internal var debugMinAlignRate: Float? = null

    /** §0.62 调试：彻底跳过打分轮（A/B 计费用；true=完全不执行） */
    @Volatile
    internal var debugDisableScored: Boolean = false

    /** §0.62 调试：打分轮统计（候选数 / scoreHypothesis 调用数 / 耗时 ms） */
    @Volatile
    var debugScoredStats: String? = null

    /**
     * §0.74 调试：matchInternal 各阶段耗时（毫秒，按调用顺序）。
     * 形如 "primary=1230;multi=0;weak=0;backup=0;scored=0"。值为 0 表示该阶段未执行
     * （提前退出或前置条件不满足）。用于定位耗时瓶颈 —— 实测宽场失败路径的时间
     * 并不在常被怀疑的打分轮（它有 [SCORED_TIME_BUDGET_MS] 预算兜底），
     * 而在投票轮被完整跑遍的阶梯上。
     */
    @Volatile
    var debugPhaseTimings: String? = null

    /**
     * §0.70 调试：逐星投票轮统计（参与投票的星数 / 得到票的星数 / 最高票 / 平均领先比）。
     * 用于区分「真信号缺失」与「阈值把关太严」两类失败 ——
     * 在识别失败页与日志里展示，也是用户反馈问题时的关键证据。
     */
    @Volatile
    var debugVoteStats: String? = null

    /**
     * §0.74 调试：voteChunk 的索引扫描计数（三角形数 / 桶查询次数 / 遍历条目数 /
     * 进入合并的条目数 / 送验候选数）。用来定位投票轮的耗时究竟在桶查询还是在
     * 候选合并 —— 实测合并条目数是桶查询次数的 10 倍量级，说明开销在
     * 「把上千候选塞进 HashMap 再全排序」，而不在索引查找本身。
     * 读法：见 [debugVoteScanTake]（读取并清零）。
     */

    // §0.74 计数器（voteChunk 多线程累加，仅调试读）
    private val nTriangles = java.util.concurrent.atomic.AtomicLong()
    private val nBucketLookups = java.util.concurrent.atomic.AtomicLong()
    private val nEntriesScanned = java.util.concurrent.atomic.AtomicLong()
    private val nMerged = java.util.concurrent.atomic.AtomicLong()
    private val nVerified = java.util.concurrent.atomic.AtomicLong()

    /** §0.74 调试：读取并清零 voteChunk 计数器 */
    internal fun debugVoteScanTake(): String {
        val s = "tri=%d buckets=%d entries=%d merged=%d taken=%d".format(
            nTriangles.get(), nBucketLookups.get(), nEntriesScanned.get(),
            nMerged.get(), nVerified.get())
        nTriangles.set(0); nBucketLookups.set(0); nEntriesScanned.set(0)
        nMerged.set(0); nVerified.set(0)
        return s
    }

    /**
     * §0.62 打分轮的星表星缓存：只存一次（按 [catalogMag] 过滤），
     * 每颗星预算好单位向量 (ux, uy, uz)。
     *
     * 打分轮会把 scoreHypothesis 调用上万次，每次都要遍历整个亮星域；
     * 早期版本在循环里对每颗星做 tanXY（含 6 次三角函数）+ haversine，
     * 单张失败照片因此多花 15 秒（实测 10.0s → 25.5s）。
     * 改为预算单位向量后，每次投影只剩点积与除法。
     */
    private class ScoreTable(
        val ux: DoubleArray,
        val uy: DoubleArray,
        val uz: DoubleArray,
        val raDeg: DoubleArray,
        val decDeg: DoubleArray,
        val entries: Array<StarEntry>,
    )

    @Volatile
    private var cachedScoreTable: ScoreTable? = null

    @Volatile
    private var cachedScoreTableMag: Float = Float.NaN

    private fun scoreTable(): ScoreTable {
        val mag = catalogMag()
        cachedScoreTable?.let { if (cachedScoreTableMag == mag) return it }
        synchronized(this) {
            cachedScoreTable?.let { if (cachedScoreTableMag == mag) return it }
            val list = ArrayList<StarEntry>(512)
            for (st in StarCatalogData.stars) {
                if (st.mag > mag) continue
                list.add(st)
            }
            val n = list.size
            val ux = DoubleArray(n); val uy = DoubleArray(n); val uz = DoubleArray(n)
            val ras = DoubleArray(n); val decs = DoubleArray(n)
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
            val tbl = ScoreTable(ux, uy, uz, ras, decs, list.toTypedArray())
            cachedScoreTable = tbl
            cachedScoreTableMag = mag
            return tbl
        }
    }

    private fun minAlignRate(): Float = debugMinAlignRate ?: SCORED_MIN_ALIGN_RATE

    /** §0.62 调试：打分轮最佳对齐星数（诊断用，null=未跑） */
    @Volatile
    var debugScoredBest: Int? = null
        private set

    /** §0.62 调试：打分轮最佳候选的 inFrame（用于对齐率标定） */
    @Volatile
    var debugScoredInFrame: Int? = null
        private set

    /** §0.62 调试：打分轮最佳候选的对齐对数与展开后对数（诊断用） */
    @Volatile
    var debugScoredWinPairs: Int? = null
        private set

    @Volatile
    var debugScoredExpandedPairs: Int? = null
        private set

    /** §0.71e 调试：打分轮获胜候选（照片坐标 + HIP）与展开明细（诊断用） */
    @Volatile
    var debugScoredWinSeed: String? = null
        private set

    @Volatile
    var debugExpandDetail: String? = null
        private set

    /** 双阈值通道（§0.32.3）：主轮内点达到该值即视为强解，不再跑备用轮 */
    private const val DUAL_THRESHOLD_STRONG_INLIERS = 18

    // §0.71 量纲自适应阈值比例（占最亮星 top 的比例）。
    // 取值由既有绝对标定换算而来，基准是 box-blur 检测器的典型最亮星
    // top≈4154（§0.32.3 演示校准），换算后对 box-blur 量纲结果等价，
    // 对 simplexy/SEP 的 flux 量纲（top≈100）自动缩放到合理档位。
    /** 精锐档 900/4154 */
    private const val SHARP_TOP_RATIO = 0.2167f
    /** 相对下限 60/4154 */
    private const val ADAPTIVE_FLOOR_RATIO = 0.0144f
    /** 弱星轮下限 25/4154 */
    private const val WEAK_FLOOR_RATIO = 0.0060f

    private fun voteBrightnessThreshold(brightness: List<Float>): Float {
        debugForceVoteThreshold?.let { return it }
        return voteThresholds(brightness).first
    }

    /**
     * 双阈值方案（§0.32.3 / §0.71 量纲自适应）：返回 (主阈值, 备用阈值)。
     *  - 主阈值：分布头部陡峭时用「精锐档」，否则用相对值（20 亮星 ×35%）；
     *  - 备用阈值：另一分支。没有任何单一亮度判据能同时服务"小图富星场"
     *    （apod4：need 亮星精锐，work=16 顶票尖锐）与"大图全曝光"
     *    （4984/4998：need 中暗星也进投票，work=28+），主通道无解时
     *    会用备用阈值重投一轮，代价仅一轮投票时间。
     *
     * **§0.71 关键修复：全部阈值改为「占最亮星的比例」，不再用绝对值。**
     *
     * 历史坑：原先的 900 / 60 / 25 三个绝对阈值是按 box-blur 检测器的亮度量级
     * （连通域灰度和，典型 top≈4154）标定的。但 SEP 路径喂入的是 simplexy 的
     * flux（背景减除后的净流量，量级 top≈100）。§0.64 曾发现这个错配，给
     * 「头部陡峭」加了相对判据兜底 —— 但**阈值本身仍是绝对值**，于是：
     *   · 主阈值 60 → 只有 2 颗星进投票；
     *   · 备用阈值 900 → 一颗不剩（work=0，投票轮直接空转）。
     * 用户 2026-09-17 照片的失败日志正是这个形态（172 颗星、work=0、
     * 打分轮压根没启动）。§0.64 修了判据、没修量纲，这里补完。
     *
     * 比例取值（以 box-blur 基准 top=4154 换算，保持既有标定等价）：
     *   精锐档  900/4154 ≈ 0.217   （§0.32.3 演示校准）
     *   相对下限 60/4154 ≈ 0.0144
     *   弱星下限 25/4154 ≈ 0.0060
     * 对 box-blur 量纲结果与原实现一致；对 SEP 量纲自动缩放到合理档位。
     */
    internal fun voteThresholds(brightness: List<Float>): Pair<Float, Float> {
        debugForceVoteThreshold?.let { return it to it }
        val sortedDesc = brightness.map { it.toFloat() }.sortedDescending()
        val anchor20 = sortedDesc[minOf(sortedDesc.size - 1, 19)]
        val top = sortedDesc.firstOrNull() ?: 0f
        // top <= 0 是退化输入（全负/零亮度，说明检测器没给出有效信号）：
        // 返回一对**大阈值**让所有星落选，避免在无信号时产生假匹配。
        // 这里刻意不回退到相对值 —— 相对于 0 的比例恒为 0，等于放行全部噪声。
        if (top <= 0f) return Float.MAX_VALUE to Float.MAX_VALUE

        // 量纲自适应：三个档位都表达为 top 的比例。
        // 但「下限」类档位（adaptiveFloor / weakFloor）**不能随 top 无限上移**：
        // 它们是 box-blur 量纲下 60 / 25 的标定值（§0.32.3/§0.43），对更亮的照片
        // （top 上万，如演示照 5057/5087）绝对下限仍是 60/25 —— 用比例会抬高到
        // 185/77，把投票需要的 60~250 亮度带真星整片切掉（实测这两张照片从
        // SOLVED 回归 UNSOLVED）。取 min(比例, 绝对标定) 兼顾两头：
        //   · top ≤ ~4154（典型 box-blur / 全部 SEP）：比例生效，量纲无关；
        //   · top 超过标定基准：钉在绝对标定值，行为与既有版本一致。
        val sharp = top * SHARP_TOP_RATIO              // 精锐档（≈原 900 @box-blur）
        val adaptiveFloor = minOf(top * ADAPTIVE_FLOOR_RATIO, 60f) // 相对下限（≈原 60）
        val adaptive = maxOf(adaptiveFloor, anchor20 * 0.35f)

        // 「头部陡峭」判据同样量纲无关：看第 20 亮星相对最亮星的比例
        val steepAbs = sharp > 0f && sortedDesc.count { it >= sharp } >= 10 &&
            anchor20 >= sharp * 0.85f
        val steepRel = anchor20 >= top * 0.55f
        if (steepAbs || steepRel) {
            return sharp to adaptive
        }
        return adaptive to sharp
    }

    // ================= 1. 星点检测 =================

    /** 从 Bitmap 检测星点 */
    /**
     * [maxStars] 默认 100：与 SEP 路径（200 上限）保持同一量级。
     * 原默认 40 是宽场欠曝照片的瓶颈 —— 检测端只保留最亮 40 颗时，
     * mag 5~6 的星被截断，投票可用星数不足（实测用户照片在 40 星下
     * 内点 25，提到 100 后演示照片多张内点显著提升：5040 37→51、
     * 5092 19→24）。上限不宜过 150：apod5（假阳性对照）在 n>=150
     * 时会被噪声星凑出 6 内点的假解（实测 n=150/200 均误报 SOLVED）。
     */
    fun detectStars(bitmap: Bitmap, maxStars: Int = 100): List<DetectedStar> {
        val w = bitmap.width
        val h = bitmap.height
        val gray = bitmapToGray(bitmap)
        return detectStarsGray(w, h, gray, maxStars)
    }

    /**
     * Bitmap → 灰度数组（0..255，与 [detectStars] 内部同一套系数）。
     * §0.70：识别失败时把匹配器实际吃到的像素落盘，开发机才能精确重放。
     */
    fun bitmapToGray(bitmap: Bitmap): FloatArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val gray = FloatArray(w * h)
        for (i in pixels.indices) {
            val c = pixels[i]
            gray[i] = 0.299f * ((c shr 16) and 0xFF) +
                0.587f * ((c shr 8) and 0xFF) +
                0.114f * (c and 0xFF)
        }
        return gray
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

    /**
     * 三角形索引。
     * [triMap]：角度比键（历史实现，保留用于兜底与对照）。
     * [projMap]：§0.71b 投影感知键 —— 以三角形重心为切点的 gnomonic 距离比，
     *            与照片端像素比严格同尺度，宽场真三角形命中率 100%（角度比仅 31%）。
     */
    private class StarIndex(
        val triMap: HashMap<Int, MutableList<TriEntry>>,
        val projMap: HashMap<Int, MutableList<TriEntry>> = HashMap(),
    )

    /**
     * §0.75 索引缓存：**按星等上限分槽**。
     *
     * 为什么不能是单槽：深域兜底（mag<=6.5，55 万条三角形、构建 8.9s）与浅域
     * （mag<=4.0，3.4 万条、358ms）会在同一批照片里交替出现（批量导出宽窄混杂）。
     * 单槽会让每次交替都重建一次索引 —— 深域重建一次就是 8.9 秒，10 张交替
     * 就要 46 秒。分槽后两域各建一次、之后都命中缓存。
     */
    @Volatile
    private var indexCache: HashMap<Float, StarIndex> = HashMap()

    /** §0.75 深域重试的切域锁，见 [match] 里的说明 */
    private val domainLock = Any()

    /** §0.75 调试：彻底跳过深星表域兜底（A/B 计费用；true=浅域无解即返回 null） */
    @Volatile
    internal var debugDisableDeepCatalog: Boolean = false

    /** 调试：返回（当前星表域索引的三角形总数, 首次构建耗时 ms） */
    internal fun debugIndexBuild(): Pair<Int, Long> {
        val t0 = System.nanoTime()
        val idx = index()
        val ms = (System.nanoTime() - t0) / 1_000_000
        return idx.triMap.values.sumOf { it.size } to ms
    }

    private fun index(): StarIndex {
        val mag = catalogMag()
        indexCache[mag]?.let { return it }
        synchronized(this) {
            indexCache[mag]?.let { return it }
            return buildIndex(mag).also { indexCache[mag] = it }
        }
    }

    /**
     * §0.62 候选打分：把星表三角形 (t) 与照片三角形 (a,b,c) 做相似拟合，
     * 统计有多少颗星表星能落在照片检测星上（[ALIGN_TOL_PX] 内）。
     * 返回 intArrayOf(对齐星数 aligned, 落入画面的星表星数 inFrame)，
     * 判决用两者之比（对齐率），见 [SCORED_MIN_ALIGN_RATE]。
     *
     * 拟合用三点相似变换（照片像素 → 星表切平面），再对全部星表星反投影回
     * 像素域比对 —— 在像素域比对避免了切平面原点不一致的问题。
     */
    private fun scoreHypothesis(
        work: List<DetectedStar>,
        a: DetectedStar,
        b: DetectedStar,
        c: DetectedStar,
        t: TriEntry,
        width: Int,
        height: Int,
        pointingHint: PointingHint?,
    ): IntArray {
        val eA = hipMap[t.hipA] ?: return ZERO_SCORE
        val eB = hipMap[t.hipB] ?: return ZERO_SCORE
        val eC = hipMap[t.hipC] ?: return ZERO_SCORE
        // §0.71c **切平面原点必须取「画面中心」，不能取三颗星的平均位置**。
        //
        // 为什么：相似变换拟合要求「照片像素坐标」与「星表切平面坐标」处在**同一个
        // 投影面**上。照片是以**画面中心**为切点做的 gnomonic 投影；若这里改用三颗
        // 星的平均位置当切点，两者投影面不同，宽场下（三颗星可能离中心 30°+）产生
        // 非相似畸变 —— 实测同一三角形三条边的复比模一致（29.1/29.7/29.4 px/度）
        // 但幅角相差 200°+，拟合残差高达 6 度、对齐数恒为 0。
        //
        // §0.63 已把主拟合路径（matchInternal→refine）的切平面原点迭代到图像中心，
        // 但打分轮这条路径当时漏改，本版补上。
        //
        // 中心未知（正是要求解的量），故先用三颗星的球面向量平均做初值，
        // 再用「初值中心对应的像素位置」作为切点迭代一次 —— 与主路径同思路。
        //
        // 另一个必须同时满足的前提：**像素 y 轴与切平面 y 轴方向相反**。
        // tanXY 给的是「东正、北正」（y 向上），而照片像素是 **y 向下**。
        // 直接把两者喂给 fitSimilarity 会得到一个混入镜像的错误变换 ——
        // 实测同一三角形三条边的复比幅角能差 200°+、拟合残差 190 px；
        // 把像素 y 取反后复比幅角完全一致（-108.1°±0.1），残差降到 **0.24~0.58 px**，
        // 尺度也从错误的 37.6 px/度 回到正确的 29.26 px/度（真值 29.72）。
        // §0.71d 双 parity 拟合：像素 y 与切平面北向的关系取决于照片的镜像奇偶性。
        // 真实样本里两种奇偶性都存在（旧照横拍 parity=+1：北向 ∝ -y；
        // 新照竖拍 parity=-1：北向 ∝ +y）。写死单个方向会让另一类照片的
        // 打分轮全体 0 分 —— 本函数曾因写死「y 取反」让旧照从 SOLVED 回归
        // UNSOLVED（bestScore 7→5，差 1 颗不过 6 的下限）。
        //
        // 做法：每个候选三角形把两个方向各拟合一次（3 点拟合很廉价），
        // 取**拟合残差小**的那个方向。正确方向残差 ~0.02°（0.5px 量级），
        // 错误方向残差 ~6°（190px 量级），分离度 300 倍，无歧义。
        var center = sphericalMean(eA, eB, eC)
        var ra0 = center[0]
        var dec0 = center[1]
        var fit: DoubleArray? = null
        var useFlip = false
        var parityKnown = false
        val px = floatArrayOf(a.x, b.x, c.x)
        val py = floatArrayOf(a.y, b.y, c.y)
        val pyFlip = floatArrayOf(-a.y, -b.y, -c.y)
        for (iter in 0 until SCORED_ORIGIN_ITERATIONS) {
            val txA = tanXY(eA.ra, eA.dec, ra0, dec0)
            val txB = tanXY(eB.ra, eB.dec, ra0, dec0)
            val txC = tanXY(eC.ra, eC.dec, ra0, dec0)
            val sx = floatArrayOf(txA.first.toFloat(), txB.first.toFloat(), txC.first.toFloat())
            val sy = floatArrayOf(txA.second.toFloat(), txB.second.toFloat(), txC.second.toFloat())
            val f0 = fitSimilarity(px, py, sx, sy)
            val f1 = fitSimilarity(px, pyFlip, sx, sy)
            if (!parityKnown) {
                // 首轮定奇偶性：残差小者胜出；之后各轮沿用（照片奇偶性固定）
                val r0 = if (f0 != null) fitResid(f0, px, py, sx, sy) else Double.MAX_VALUE
                val r1 = if (f1 != null) fitResid(f1, px, pyFlip, sx, sy) else Double.MAX_VALUE
                useFlip = r1 < r0
                parityKnown = true
            }
            val f = if (useFlip) f1 else f0
            if (f == null) return ZERO_SCORE
            fit = f
            if (iter == SCORED_ORIGIN_ITERATIONS - 1) break
            // 把切平面原点移到「图像中心」：由当前拟合把画面中心反投影到天球
            val s0 = f[0]; val ss0 = f[1]; val tx0 = f[2]; val ty0 = f[3]
            val det0 = s0 * s0 + ss0 * ss0
            if (det0 < 1e-12) break
            val cx = width / 2.0
            val cy = if (useFlip) -height / 2.0 else height / 2.0
            // 模型：X = s*u - ss*v + tx ; Y = ss*u + s*v + ty（u,v = 翻转后的像素）
            val wx = s0 * cx - ss0 * cy + tx0
            val wy = ss0 * cx + s0 * cy + ty0
            val next = tanInverse(wx, wy, ra0, dec0)
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
        // 模型：X = s*u - ss*v + tx ; Y = ss*u + s*v + ty  (u,v = 照片像素)
        // 逆映射回像素后做**一对一**匹配统计：
        //   [0] = 对齐星数（aligned）
        //   [1] = 落在画面内的星表星数（inFrame）
        // 判决用「对齐率 = aligned / inFrame」而非绝对数：伪解常把整片天区压进
        // 画面（尺度坍缩，inFrame 巨大而 aligned 很少）或只对齐零星几颗，
        // 真解则在对齐率上接近 1（实测用户照片 inFrame≈全画面 25~30、对齐 20+）。
        // 循环不变量提到循环外：本函数在打分轮被调用上万次。
        val invDet = 1.0 / det
        var hits = 0
        var inFrame = 0
        // 一对一匹配：每颗检测星只能被一颗星表星占用，杜绝"多星压一点"虚增
        val used = BooleanArray(work.size)
        // 切平面基向量（gnomonic）：X = atan2(dot(u,east), dot(u,center))，
        // 用单位向量点积代替每星一次 tanXY（三角函数）调用
        val r0 = ra0 * (PI / 180.0)
        val d0 = dec0 * (PI / 180.0)
        val c0x = cos(d0) * cos(r0)
        val c0y = cos(d0) * sin(r0)
        val c0z = sin(d0)
        // east = (-sin r0, cos r0, 0) ; north = center x east
        val exx = -sin(r0); val exy = cos(r0); val exz = 0.0
        val nx = c0y * exz - c0z * exy
        val ny = c0z * exx - c0x * exz
        val nz = c0x * exy - c0y * exx
        val tbl = scoreTable()
        // hint 预过滤的常量提到循环外（点积 -> 角距，避免每星多次三角函数）
        val hintRadius = if (pointingHint != null) pointingHint.radiusDeg + 45.0 else 0.0
        val hx: Double; val hy: Double; val hz: Double
        if (pointingHint != null) {
            val hr = pointingHint.raDeg * (PI / 180.0)
            val hd = pointingHint.decDeg * (PI / 180.0)
            hx = cos(hd) * cos(hr); hy = cos(hd) * sin(hr); hz = sin(hd)
        } else {
            hx = 0.0; hy = 0.0; hz = 0.0
        }
        for (i in tbl.entries.indices) {
            if (pointingHint != null) {
                val dot = tbl.ux[i] * hx + tbl.uy[i] * hy + tbl.uz[i] * hz
                if (acos(dot.coerceIn(-1.0, 1.0)) / (PI / 180.0) > hintRadius) continue
            }
            val uxi = tbl.ux[i]; val uyi = tbl.uy[i]; val uzi = tbl.uz[i]
            val dC = uxi * c0x + uyi * c0y + uzi * c0z
            if (dC <= 1e-9) continue
            val dE = uxi * exx + uyi * exy + uzi * exz
            val dN = uxi * nx + uyi * ny + uzi * nz
            // 与 tanXY 完全同尺度：xi = dE/dC、eta = dN/dC，再除弧度因子。
            // 注意不能用 atan2——那给的是角度值，而模型（fitSimilarity 的输入）
            // 拟合的是正切值；小角度下二者近似，74° 宽场下差异巨大（实测会让
            // 用户照片重新变成 UNSOLVED）。
            val X = (dE / dC) * INV_RAD
            val Y = (dN / dC) * INV_RAD
            // 模型：X = s*u - ss*v + tx ; Y = ss*u + s*v + ty
            // 注意 (u,v) 是**翻转 y 后**的像素坐标（见上方拟合处的说明），
            // 故这里把 v 翻回图像坐标再与 work 比较。
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
    private fun buildIndex(magLimit: Float = PROD_CATALOG_MAG): StarIndex {
        val bright = StarCatalogData.stars.filter { it.mag <= magLimit }
        val triMap = HashMap<Int, MutableList<TriEntry>>()
        val projMap = HashMap<Int, MutableList<TriEntry>>()
        // §0.75 邻居搜索的空间网格。原实现对每颗星遍历整个亮星集算角距 ——
        // O(n²) 在浅域（514 颗）下只要 600ms，但深域（mag<=6.5，8415 颗）
        // 实测要 ~50s，没法在兜底路径里现场构建。改为按赤纬分带、带内按赤经
        // 分格，只算 25° 搜索半径内的候选。
        // §0.75 邻居搜索分两条路，按星表规模选：
        //   · 小星表（浅域 514 颗）—— 直接全量算角距。这是**原实现逐位复现**：
        //     遍历全部亮星、稳定排序（打平时保留星表序）、取前 12。600ms 可接受。
        //   · 大星表（深域 8415 颗）—— 走空间网格。原 O(n²) 实测要 ~50s，
        //     网格后 ~10s。
        // 为什么不让网格也服务浅域：网格版与全量版的邻居集在「距离打平」和
        // 「高纬边界」上有极少数差异（实测浅域索引少几十个三角形），会改变宽场
        // 解算结果。而分级的设计前提正是「宽场行为逐位不变」，所以浅域坚持原算法。
        val useGrid = bright.size > NEIGHBOR_GRID_THRESHOLD
        // 网格里存 (bright 下标, 星)：下标让网格路径也能按星表序决断
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
        for (a in bright) {
            val neighbors: List<Triple<Int, StarEntry, Double>> = if (useGrid) {
                neighborsWithin(a, grid!!)
                    .sortedWith(compareBy({ it.third }, { it.first }))
                    .take(12)
            } else {
                // 与原实现等价：全量算距 → 稳定排序（打平保留星表序）→ 取前 12
                bright.mapIndexed { i, s -> Triple(i, s, angularDist(a, s)) }
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
                    val dbc = angularDist(b, c)
                    val sides = doubleArrayOf(dab, dac, dbc).sortedDescending()
                    val r2 = (sides[1] / sides[0]).toFloat()
                    val r3 = (sides[2] / sides[0]).toFloat()
                    if (r2 < 0.12f) continue // 排除过扁三角形
                    val entry = TriEntry(a.hip, b.hip, c.hip, r2, r3)
                    // §0.74：triMap **每三角形只存一个桶**（原为 quantizeKeys 默认的
                    // 9 个相邻桶）。9 桶存储让查询端每扫一条就要重复见到同一对象 9 次
                    // —— 实测一轮投票要扫 6000 万条目、其中九成是这种重复。改单桶后
                    // 索引从 305,118 条降到 ~34,000 条，查询扫描量同比例下降。
                    // 覆盖范围不变：原「存 ±1 桶 + 查 ±13 桶」等效覆盖 ±14 桶，
                    // 故各查询点的 window 已同步 +1（±13 → ±14、±1 → ±2）。
                    triMap.getOrPut(bucketKey(r2, r3)) { mutableListOf() }.add(entry)
                    // §0.71b 投影感知索引：同时写入「以三角形重心为投影中心」的
                    // gnomonic 距离比键。照片端像素距离本身就是切平面距离，
                    // 故该键与照片端**严格一致**（实测失真 0.0000 vs 角度比 0.0170），
                    // 宽场真三角形得以回到窄窗内（±0.012 命中率 31% → 100%）。
                    val pr = projectedRatios(a, b, c)
                    if (pr != null && pr[0] >= 0.12f) {
                        val pent = TriEntry(a.hip, b.hip, c.hip, pr[0], pr[1])
                        for (key in quantizeKeys(pr[0], pr[1])) {
                            projMap.getOrPut(key) { mutableListOf() }.add(pent)
                        }
                    }
                }
            }
        }
        return StarIndex(triMap, projMap)
    }

    // ================= §0.75 索引构建用的空间网格 =================

    /** 网格cell边长（度）。取 5° 与 [catGrid] 一致，便于共用约定 */
    private const val GRID_CELL_DEG = 5.0
    private const val GRID_COLS = (360.0 / GRID_CELL_DEG).toInt()   // 72
    private const val GRID_ROWS = (180.0 / GRID_CELL_DEG).toInt()   // 36

    private fun cellKey(col: Int, row: Int): Long = col.toLong() * 1000L + row.toLong()

    /**
     * §0.75：返回 [a] 角距 0.8°~25° 内的全部亮星（含距离）。
     *
     * 为什么要按赤纬实算赤经格数：赤经格在天球上的实际宽度是 5°×cos(dec)，
     * 越近北极越窄。若固定扫 ±5 个赤经格，高纬会漏掉 25° 内的星；
     * 固定扫更多格则在低纬做无用功。故按所在赤纬带的 cos(dec) 反算格数。
     */
    private fun neighborsWithin(
        a: StarEntry,
        grid: HashMap<Long, MutableList<Pair<Int, StarEntry>>>,
    ): List<Triple<Int, StarEntry, Double>> {
        val out = ArrayList<Triple<Int, StarEntry, Double>>()
        val rowLo = ((a.dec - NEIGHBOR_MAX_DEG + 90.0) / GRID_CELL_DEG).toInt().coerceAtLeast(0)
        val rowHi = ((a.dec + NEIGHBOR_MAX_DEG + 90.0) / GRID_CELL_DEG).toInt().coerceAtMost(GRID_ROWS - 1)
        for (row in rowLo..rowHi) {
            // 该赤纬带内 |dec| 最大处 —— cos 最小、所需赤经格数最多。
            // 为什么不能按带中心算：带内恒星的赤纬可偏离中心 2.5°，靠近极点时
            // cos 差出好几倍，按中心折算会**漏掉高纬的邻星**（实测浅域索引因此
            // 少 81 个三角形，pleiades 从 UNSOLVED 变 SOLVED，假阳性回归）。
            val rowLoDec = row * GRID_CELL_DEG - 90.0
            val rowHiDec = rowLoDec + GRID_CELL_DEG
            val maxAbsDec = maxOf(abs(rowLoDec), abs(rowHiDec))
            val cosD = cos(Math.toRadians(maxAbsDec)).coerceAtLeast(0.02)
            // 扫描窗不会超过半圈（±36 列即覆盖全部 72 列），避免极区附近做重复劳动
            val spanCols = minOf(GRID_COLS / 2,
                Math.ceil(NEIGHBOR_MAX_DEG / (GRID_CELL_DEG * cosD)).toInt())
            val aCol = ((((a.ra % 360.0) + 360.0) % 360.0) / GRID_CELL_DEG).toInt()
                .coerceIn(0, GRID_COLS - 1)
            for (dc in -spanCols..spanCols) {
                // 赤经环绕：取模而非 clamp
                val col = ((aCol + dc) % GRID_COLS + GRID_COLS) % GRID_COLS
                val list = grid[cellKey(col, row)] ?: continue
                for ((i, s) in list) {
                    if (s === a) continue
                    val d = angularDist(a, s)
                    if (d in NEIGHBOR_MIN_DEG..NEIGHBOR_MAX_DEG) out.add(Triple(i, s, d))
                }
            }
        }
        return out
    }

    /** 邻星搜索的角距窗（与 buildIndex 原实现的 0.8..25.0 一致） */
    private const val NEIGHBOR_MIN_DEG = 0.8
    private const val NEIGHBOR_MAX_DEG = 25.0

    /**
     * §0.75 星表规模超过此值才启用空间网格做邻居搜索。
     * 浅域（mag<=4.0）514 颗 < 1000，走全量算距的原实现；深域（mag<=6.5）
     * 8415 颗 > 1000，走网格。取 1000 是为了让浅域无论星表怎么微调都稳定
     * 落在原实现那一侧。
     */
    private const val NEIGHBOR_GRID_THRESHOLD = 1000

    /**
     * §0.71b：以三角形**重心**为投影中心，算三条边的 gnomonic 切平面距离，
     * 返回归一化比值 (b/a, c/a)。
     *
     * 为什么这样能让键与照片端严格一致：照片是 gnomonic 投影，像素距离 ∝
     * 该点在切平面上的距离；只要索引端用**同一个投影面**（这里取三角形重心
     * 作为切点），两边算出的距离只差一个整体尺度因子，比值完全相同。
     * 实测失真 0.0000，而原「角度比」在 55°×74° 宽场失真中位 0.0170。
     */
    private fun projectedRatios(a: StarEntry, b: StarEntry, c: StarEntry): FloatArray? {
        // 重心方向的单位向量
        val ua = unitVec(a); val ub = unitVec(b); val uc = unitVec(c)
        var cx = ua[0] + ub[0] + uc[0]
        var cy = ua[1] + ub[1] + uc[1]
        var cz = ua[2] + ub[2] + uc[2]
        val cn = sqrt(cx * cx + cy * cy + cz * cz)
        if (cn < 1e-9) return null
        cx /= cn; cy /= cn; cz /= cn
        val pa = tanAbout(ua, cx, cy, cz) ?: return null
        val pb = tanAbout(ub, cx, cy, cz) ?: return null
        val pc = tanAbout(uc, cx, cy, cz) ?: return null
        val dab = hypot(pa[0] - pb[0], pa[1] - pb[1])
        val dac = hypot(pa[0] - pc[0], pa[1] - pc[1])
        val dbc = hypot(pb[0] - pc[0], pb[1] - pc[1])
        val s = doubleArrayOf(dab, dac, dbc).sortedDescending()
        if (s[0] <= 1e-12) return null
        return floatArrayOf((s[1] / s[0]).toFloat(), (s[2] / s[0]).toFloat())
    }

    /** 星表星的单位向量（缓存在 StarEntry 上避免重复三角函数） */
    private fun unitVec(e: StarEntry): DoubleArray {
        val r = e.ra * (PI / 180.0)
        val d = e.dec * (PI / 180.0)
        val cd = cos(d)
        return doubleArrayOf(cd * cos(r), cd * sin(r), sin(d))
    }

    /** 单位向量 u 在以 (cx,cy,cz) 为切点的切平面上的坐标（度） */
    private fun tanAbout(u: DoubleArray, cx: Double, cy: Double, cz: Double): DoubleArray? {
        val dc = u[0] * cx + u[1] * cy + u[2] * cz
        if (dc <= 1e-9) return null
        // east = (-sin r0, cos r0, 0)，north = center × east
        val r0 = atan2(cy, cx)
        val ex = -sin(r0); val ey = cos(r0)
        val nx = cy * 0.0 - cz * ey
        val ny = cz * ex - cx * 0.0
        val nz = cx * ey - cy * ex
        val de = u[0] * ex + u[1] * ey
        val dn = u[0] * nx + u[1] * ny + u[2] * nz
        val k = 180.0 / PI
        return doubleArrayOf(de / dc * k, dn / dc * k)
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

    /**
     * §0.74 单桶键（triMap 存储用）。与 [quantizeKeys] 同一量化，只是不向相邻桶扩散。
     * 查询侧用 window+1 补偿，覆盖范围与旧的「9 桶存储 + window」完全一致。
     */
    private fun bucketKey(r2: Float, r3: Float): Int {
        val b2 = (r2 * 256).toInt().coerceIn(0, 255)
        val b3 = (r3 * 256).toInt().coerceIn(0, 255)
        return b2 * 256 + b3
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
    /**
     * 自研星表求解主入口。
     *
     * 改进（v1.5.31 传感器粗定标支持）：
     *  - 当传入 [pointingHint] 时，优先执行以指向天区为中心、半径受限的快速匹配；
     *  - 若先验快速匹配未果（如地磁异常），自动平滑回退至原有全天盲解，保证识别率绝不下降。
     */
    fun match(
        detected: List<DetectedStar>,
        width: Int,
        height: Int,
        pointingHint: PointingHint? = null,
    ): LocalMatchResult? {
        if (detected.size < 5) return null
        if (pointingHint != null) {
            val hintResult = matchInternal(detected, width, height, pointingHint)
            if (hintResult != null) return hintResult
        }
        val shallow = matchInternal(detected, width, height, null)
        if (shallow != null) return shallow
        if (debugDisableDeepCatalog) return null
        // §0.75 窄场兜底：浅星表域（mag<=4.0）整条阶梯都无解时，用深星表域
        // （mag<=6.5）重试一次。宽场照片在浅域就能解出，永远不会走到这里 ——
        // 这是分级而非全局加深的原因：实测全局加深会让 30° 宽场（mid30 的
        // 9 内点弱解）从 SOLVED 变 UNSOLVED，并让 4 个窄场出现尺度坍缩错解
        // （报出 FOV 115°~170°）。
        //
        // 切域方式：catalogMag() 是**五个域共用的唯一取值点** —— 三角形索引、
        // 第 4 星验证网格（catGrid）、打分表（scoreTable）、假设展开
        // （expandHypothesisPairs）、内点统计（countInliers）。§0.43 已用实测
        // 记下教训：只改其中一个域「完全无效」（4.5→6.5 深扫全部 UNSOLVED 且慢
        // 14~35 倍）。所以这里统一切到深域，而不是只换索引。
        //
        // 加锁的原因：切域是改对象级状态，并发求解会串域。实测 App 内求解本就
        // 串行（批量导出是顺序 for 循环，相机/相册一次一张），加锁只是把这个
        // 既有约束变成显式保证。
        val savedMag = debugForceCatalogMag
        val deep = synchronized(domainLock) {
            debugForceCatalogMag = DEEP_CATALOG_MAG
            try {
                matchInternal(detected, width, height, null)
            } finally {
                debugForceCatalogMag = savedMag
            }
        }
        // §0.75 深域是**窄场路径**，对它的解再设一道视场上界。
        //
        // 为什么需要：深域候选密度是浅域的 16 倍，尺度坍缩型伪影随之增多 ——
        // 实测 6 个窄场从 UNSOLVED 变成 WRONG，报出视场 104°~170° 而真值只有
        // 1°~5°。而一个解若声称视场 >90°，它本质是宽场；宽场在浅域就该解出
        // （宽场回归 12/12），浅域整条阶梯都无解的宽场照片拿到 >90° 的解，
        // 基本都是坍缩伪影。窄场目标（10°）远低于此界，不受影响。
        if (deep != null &&
            maxOf(deep.solve.fieldWidthDeg, deep.solve.fieldHeightDeg) > DEEP_PATH_MAX_FOV_DEG
        ) {
            return null
        }
        return deep
    }

    private fun matchInternal(
        detected: List<DetectedStar>,
        width: Int,
        height: Int,
        pointingHint: PointingHint?,
    ): LocalMatchResult? {
        val (t1, t2) = voteThresholds(detected.map { it.brightness })
        val tStart = System.currentTimeMillis()
        var msPrimary = 0L; var msMulti = 0L; var msWeak = 0L
        var msBackup = 0L; var msBackupMulti = 0L; var msScored = 0L

        // 投票与 parity（镜像）无关：每个阈值只投一次，normal/mirrored 共享结果。
        // （v1.5.8 的双阈值曾按"每 parity × 每阈值"各投一次，投票最多 4 轮，
        //  失败/弱解照片识别耗时从 ~2s 涨到 ~9s；本重构降回最多 2 轮，成功率不变）
        val tA = System.currentTimeMillis()
        val primaryPairs = votePairs(detected, width, height, t1, pointingHint = pointingHint)
        var best = bestCandidate(detected, width, height, primaryPairs, pointingHint)
        msPrimary = System.currentTimeMillis() - tA
        if (best?.inlierCount != null && best.inlierCount >= DUAL_THRESHOLD_STRONG_INLIERS) {
            debugPhaseTimings = "primary=$msPrimary;multi=$msMulti;weak=$msWeak;backup=$msBackup;scored=$msScored"
            return best
        }
        // §0.43 稀疏场重投：单候选轮完全无解时，用每星 top-3 候选重投一轮
        // （仅当无解，有解路径完全不动；伪解由 plausibleFov/skySpan 门槛把守）。
        if (best == null) {
            val tB = System.currentTimeMillis()
            best = bestCandidate(
                detected, width, height,
                votePairs(detected, width, height, t1, multi = true, pointingHint = pointingHint),
                pointingHint,
            )
            msMulti = System.currentTimeMillis() - tB
            if (best?.inlierCount != null && best.inlierCount >= DUAL_THRESHOLD_STRONG_INLIERS) {
                debugPhaseTimings = "primary=$msPrimary;multi=$msMulti;weak=$msWeak;backup=$msBackup;scored=$msScored"
                return best
            }
        }
        // §0.43 弱星轮：仍无解时用低于下限的阈值 + 多候选重投，救欠曝/暗场
        // （5031 实测：anchor20=39 → adaptive 被下限卡死，弱星全被踢出投票；
        //  单候选阈值 25~50 时 10~13 内点真解浮现；multi 在低阈值下噪声敏感
        //  反而失败，故弱星轮走单候选，伪模型由 plausibleFov 门槛挡住）。
        // §0.71：下限同样改为相对值（原 25 @box-blur top≈4154 → 0.0060×top），
        // 否则 SEP flux 量纲下 25 会把弱星轮也堵死。
        if (best == null) {
            val tC = System.currentTimeMillis()
            val desc = detected.map { it.brightness }.sortedDescending()
            val anchor20 = desc.getOrElse(19) { 0f }
            val top = desc.firstOrNull() ?: 0f
            // §0.71：下限改为相对值（原 25 @box-blur top≈4154 → 0.0060×top），
            // 否则 SEP flux 量纲下 25 会把弱星轮也堵死；同时钉住绝对上限 25，
            // 避免亮照片（top 上万）把弱星轮下限抬到 77+ 整片切掉（5057/5087 回归实测）
            val weakT = maxOf(minOf(top * WEAK_FLOOR_RATIO, 25f), anchor20 * 0.25f)
            if (weakT < t1) {
                best = bestCandidate(
                    detected, width, height,
                    votePairs(detected, width, height, weakT, pointingHint = pointingHint),
                    pointingHint,
                )
                if (best?.inlierCount != null && best.inlierCount >= DUAL_THRESHOLD_STRONG_INLIERS) {
                    msWeak = System.currentTimeMillis() - tC
                    debugPhaseTimings = "primary=$msPrimary;multi=$msMulti;weak=$msWeak;backup=$msBackup;scored=$msScored"
                    return best
                }
            }
            msWeak = System.currentTimeMillis() - tC
        }
        if (t2 != t1) {
            val tD = System.currentTimeMillis()
            val backupPairs = votePairs(detected, width, height, t2, pointingHint = pointingHint)
            var alt = bestCandidate(detected, width, height, backupPairs, pointingHint)
            msBackup = System.currentTimeMillis() - tD
            if (alt == null) {
                val tE = System.currentTimeMillis()
                alt = bestCandidate(
                    detected, width, height,
                    votePairs(detected, width, height, t2, multi = true, pointingHint = pointingHint),
                    pointingHint,
                )
                msBackupMulti = System.currentTimeMillis() - tE
            }
            // 备用轮只有拿到强解才反超主轮：弱解之间以主轮为准（主口味优先，
            // 备用轮弱解顶掉主轮真解的回归见 §0.32.3 —— apod4 12 内点真解曾被
            // adaptive 轮 13 内点假解替代、再被跨度门槛拦截成 UNSOLVED）
            if (alt != null && (best == null || alt.inlierCount >= DUAL_THRESHOLD_STRONG_INLIERS)) {
                best = alt
            }
        }
        // §0.62 打分轮（宽场救场）：逐星投票在 60°+ 宽场会失效 —— 索引里的
        // 真三角形占比被伪三角形淹没（实测用户照片真 HIP 仅 4~11 票，伪 HIP
        // 14~19 票）。但"逐候选拟合 + 数对齐星数"分离度极高：真候选对齐
        // 16.8/25 颗，伪候选仅 1.0/25（实测同一张照片）。故投票全败时按此打分。
        if (best == null && !debugDisableScored) {
            val tF = System.currentTimeMillis()
            best = scoredRound(detected, width, height, pointingHint)
            msScored = System.currentTimeMillis() - tF
        }
        debugPhaseTimings = "primary=$msPrimary;multi=$msMulti;weak=$msWeak;" +
                "backup=$msBackup;backupMulti=$msBackupMulti;scored=$msScored;" +
                "total=${System.currentTimeMillis() - tStart}"
        return best
    }

    /**
     * §0.62 打分轮：对每个（照片三角形 → 星表三角形）候选做相似变换拟合，
     * 统计有多少颗星表星能落到照片检测星上，取最优。
     *
     * 与投票轮的区别：投票是"每颗照片星独立累计票数"，候选爆炸时真信号被
     * 稀释；打分是"整组一致性"，一组正确对应会让大量星同时对齐，天然抗稀释。
     * 实测（南宁 74° 照片）：真候选 16.8/25、伪候选 1.0/25，分离 16 倍；
     * 而投票轮同一组真对应只得 4~11 票、伪对应 14~19 票（被淹没）。
     */
    private fun scoredRound(
        detected: List<DetectedStar>,
        width: Int,
        height: Int,
        pointingHint: PointingHint?,
    ): LocalMatchResult? {
        val idx = index()
        val (t1, _) = voteThresholds(detected.map { it.brightness })
        val workIdx = detected.indices.filter { detected[it].brightness >= t1 * 0.5f }.take(45)
        val work = workIdx.map { detected[it] }
        if (work.size < 5) return null
        val maxSide = max(width, height) * 0.45f

        // 星表星在当前帧的像素坐标只依赖候选变换，故打分统一在"照片像素"域做：
        // 用候选变换把星表星投影到像素，再数检测星命中数。
        var bestScore = 0
        var bestInFrame = 0
        var bestPairs: List<Pair<Int, StarEntry>>? = null
        val seen = HashSet<Long>()
        val tStart = System.nanoTime()
        var nCand = 0L
        var nScored = 0L
        var earlyOut = false

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
                    val sides = floatArrayOf(nbrs[i].second, nbrs[j].second, dist(b, c)).sortedDescending()
                    if (sides[0] <= 0f) continue
                    val r2 = sides[1] / sides[0]
                    val r3 = sides[2] / sides[0]
                    if (r2 < 0.12f) continue
                    // 打分轮查询：**角度比键**（严格窗 + 宽松窗，历史行为）。
                    // §0.71b 曾尝试在这里用投影键（projMap），实测在旧照片（南宁）
                    // 上候选数从 ~2.3 万暴涨到 ~7.2 万且最佳候选 inFrame=160
                    // （尺度坍缩型错误模型）→ 旧照片从 SOLVED 回归 UNSOLVED。
                    // 打分轮真正的修复在 scoreHypothesis（y 翻转 + 向量平均中心），
                    // 候选召回用角度比 + 宽窗已足够。projMap 保留在索引里
                    // 供后续窄窗实验用，当前不参与查询。
                    val merged = HashMap<Long, TriEntry>()
                    for (key in quantizeKeys(r2, r3, window = 2)) {
                        idx.triMap[key]?.let { list ->
                            for (t in list) {
                                if (abs(t.r2 - r2) < 0.012f && abs(t.r3 - r3) < 0.012f) merged[triKey(t)] = t
                            }
                        }
                    }
                    for (key in quantizeKeys(r2, r3, window = 14)) {
                        idx.triMap[key]?.let { list ->
                            for (t in list) {
                                if (abs(t.r2 - r2) < 0.05f && abs(t.r3 - r3) < 0.05f) merged[triKey(t)] = t
                            }
                        }
                    }
                    if (merged.isEmpty()) continue
                    val candidates = merged.values
                        .sortedWith(compareBy({ abs(it.r2 - r2) + abs(it.r3 - r3) }, { it.hipA }, { it.hipB }, { it.hipC }))
                        .take(candidateCap())
                    nCand += candidates.size
                    for (t in candidates) {
                        nScored++
                        val sc = scoreHypothesis(work, a, b, c, t, width, height, pointingHint)
                        val aligned = sc[0]
                        val inFrame = sc[1]
                        val key = triKey(t)
                        if (aligned > bestScore && !seen.contains(key)) {
                            seen.add(key)
                            val eA = hipMap[t.hipA]
                            val eB = hipMap[t.hipB]
                            val eC = hipMap[t.hipC]
                            if (eA != null && eB != null && eC != null) {
                                bestScore = aligned
                                bestInFrame = inFrame
                                bestPairs = listOf(
                                    workIdx[pi] to eA,
                                    workIdx[bi] to eB,
                                    workIdx[ci] to eC,
                                )
                                debugScoredWinSeed = "a=(%.1f,%.1f)h%d b=(%.1f,%.1f)h%d c=(%.1f,%.1f)h%d".format(
                                    a.x, a.y, t.hipA, b.x, b.y, t.hipB, c.x, c.y, t.hipC,
                                )
                                // 提前退出：仅在**高置信**真解形态下收工。
                                // 注意门槛不能压到 minAligned()/minAlignRate() 本身——
                                // 那会让第一个勉强达标的候选就终止搜索，选到次优解
                                // （实测用户照片 inl 15 → 9、scale 122.8 → 118.6）。
                                if (inFrame >= SCORED_MIN_IN_FRAME &&
                                    aligned >= SCORED_EARLY_ALIGNED &&
                                    aligned.toFloat() / inFrame >= SCORED_EARLY_RATE
                                ) {
                                    earlyOut = true
                                }
                            }
                        }
                        if (earlyOut) break
                        if ((nScored and 0x3F) == 0L &&
                            (System.nanoTime() - tStart) / 1_000_000 > SCORED_TIME_BUDGET_MS
                        ) {
                            earlyOut = true
                            break
                        }
                    }
                    if (earlyOut) break
                }
                if (earlyOut) break
            }
            if (earlyOut) break
        }
        debugScoredBest = bestScore
        debugScoredInFrame = bestInFrame
        debugScoredWinPairs = bestPairs?.size
        debugScoredStats = "cand=$nCand scored=$nScored work=${work.size} " +
            "ms=${(System.nanoTime() - tStart) / 1_000_000}"
        if (bestPairs == null) {
            return null
        }
        if (bestScore < minAligned()) {
            return null
        }
        // 对齐率门槛：伪解（尺度坍缩）会把整片天区压进画面，绝对对齐数不低但
        // 对齐率极低；真解对齐率明显更高。样本太少时比率不可信，故要求 inFrame 下限。
        if (bestInFrame < SCORED_MIN_IN_FRAME) {
            return null
        }
        if (bestScore.toFloat() / bestInFrame < minAlignRate()) {
            return null
        }
        // bestCandidate 需要 ≥4 对：用获胜候选的拟合把全部对齐星补进对应集
        val expanded = expandHypothesisPairs(bestPairs, detected, width, height)
        debugScoredExpandedPairs = expanded?.size
        return bestCandidate(detected, width, height, expanded ?: bestPairs, pointingHint)
    }

    /**
     * §0.62 把 3 对初始对应扩展为全部对齐对：先用 3 点拟合，再把所有落在检测星
     * 上的星表星加入对应集。对应集越密，后续 RANSAC/内点拟合越稳。
     */
    private fun expandHypothesisPairs(
        seed: List<Pair<Int, StarEntry>>,
        detected: List<DetectedStar>,
        width: Int,
        height: Int,
    ): List<Pair<Int, StarEntry>>? {
        if (seed.size < 3) {
            return null
        }
        // §0.71c 重写：**用 buildWcs 生成 WCS 再投影**，替代手写逆映射。
        //
        // 原实现手写逆映射（u = (a(X-tx)+b(Y-ty))/det + cx 等），但该公式没有
        // 复刻 buildWcs 的镜像语义：fitPairs 用「中心化屏幕像素」与「北正切平面」
        // 拟合，得到的是蕴含一次镜像的相似变换；buildWcs 用 CD=[a,b;b,-a]（或
        // mirror 变体）显式补偿。手写逆映射漏了补偿 → 真值三点反投偏出 50~220px，
        // 扩展出的全是错误对应（实测 expand 返回 null，打分轮找到的 22 内点解
        // 因此作废）。
        //
        // 正确做法：两种 mirror 各试一次（主路径 fitAndVerify 正是这样），
        // 取扩展对应多者。
        var best: List<Pair<Int, StarEntry>>? = null
        for (mirror in booleanArrayOf(false, true)) {
            val fp0 = fitPairs(detected, seed, width, height, mirror) ?: continue
            var fp: FitParams = fp0
            // §0.71e 切平面原点迭代到图像中心（与 fitAndVerify §0.63 同思路）。
            // 打分轮的胜出模型把原点收敛到了画面中心；这里若沿用 3 颗种子星的
            // 平均位置当原点，宽场下（种子星可离中心 30°+）外推模型与打分模型
            // 差出整个视场 —— 实测 5087/5092 打分轮 aligned=24（对齐率 0.75，
            // 远超 0.20 门槛）但扩展返回 null，24 内点解整体作废、照片 UNSOLVED。
            repeat(SCORED_ORIGIN_ITERATIONS) {
                val w = buildWcs(fp, width, height, mirror)
                val (cRa, cDec) = w.fitsPixelToSky(width / 2.0 + 0.5, height / 2.0 + 0.5)
                if (cRa.isNaN() || cDec.isNaN()) return@repeat
                val next0 = fitPairs(detected, seed, width, height, mirror, cRa, cDec)
                    ?: return@repeat
                fp = next0
            }
            val wcs = buildWcs(fp, width, height, mirror)
            val out = ArrayList<Pair<Int, StarEntry>>()
            val used = HashSet<Int>()
            val mag = catalogMag()
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
            debugExpandDetail = "mirror=$mirror out=${out.size} " +
                "center=(%.3f,%.3f)".format(wcs.crval1, wcs.crval2)
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
        pointingHint: PointingHint? = null,
    ): LocalMatchResult? {
        if (pairs.size < 4) return null
        val normal = fitAndVerify(detected, width, height, pairs, mirrorX = false)
        val mirrored = fitAndVerify(detected, width, height, pairs, mirrorX = true)
        return listOfNotNull(normal, mirrored)
            .filter { it.inlierCount >= 6 && plausibleFov(it.solve.pixScaleArcsec, width, height) }
            .filter { skySpanConsistent(it, detected) }
            .filter { candidate ->
                if (pointingHint == null) true
                else {
                    haversineDeg(candidate.solve.raDeg, candidate.solve.decDeg, pointingHint.raDeg, pointingHint.decDeg) <= pointingHint.radiusDeg + 35.0
                }
            }
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
        pointingHint: PointingHint? = null,
    ): List<Pair<Int, StarEntry>> {
        val idx = index()

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
        // §0.70 诊断：投票是否被伪三角形淹没，看这两个数最直接 ——
        // top 票数高但分散（runnerUp 接近 top）说明真信号缺失；
        // 参与投票的星数过少（work 小）说明亮度阈值把关太严。
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
            debugVoteStats = "work=${work.size} votedStars=$nPhoto topVote=$topV " +
                "avgMargin=${if (nPhoto > 0) "%.2f".format(sumMargin / nPhoto) else "-"}"
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

        // 若有传感器粗定标先验，优先保留目标天区附近的对应星，过滤反半球伪对应
        if (pointingHint != null) {
            val maxDist = pointingHint.radiusDeg + 45.0
            val nearPairs = pairs.filter { (_, s) ->
                haversineDeg(s.ra, s.dec, pointingHint.raDeg, pointingHint.decDeg) <= maxDist
            }
            if (nearPairs.size >= 4) {
                return nearPairs
            }
        }

        return pairs
    }

    /**
     * 三角形键（§0.71e）：**必须规范化 hip 顺序**。
     *
     * 同一颗星三颗星 {A,B,C} 会以不同 anchor 顺序（A/B/C 各自为锚）各插入一条
     * 索引记录，r2/r3 完全相同但 hipA/hipB/hipC 排列不同。若不规范化，同一个
     * 三角形在查询合并里是 2~3 条「并列同分」记录 —— take(candidateCap) 在并列
     * 处按 HashMap 遍历序截断（每次运行可能不同），投票/打分结果随之抖动
     * （实测 5057/5087/5092 在不同进程间 SOLVED/UNSOLVED 翻转）。
     * 规范化后同三角形去重为一条，并列只剩真正的几何巧合（罕见）。
     */
    private fun triKey(t: TriEntry): Long {
        val a = t.hipA
        val b = t.hipB
        val c = t.hipC
        val lo = minOf(a, b, c)
        val hi = maxOf(a, b, c)
        val mid = a + b + c - lo - hi
        return (lo.toLong() shl 40) or (mid.toLong() shl 20) or hi.toLong()
    }

    /**
     * §0.74 有界选择的比较键：与原先
     * `compareBy({ |Δr2|+|Δr3| }, { hipA }, { hipB }, { hipC })` 完全一致。
     * 距离相同时按 HIP 升序 —— 这一步是确定性的关键（§0.71e）。
     */
    private fun triBefore(t: TriEntry, d: Float, other: TriEntry, od: Float): Boolean {
        if (d != od) return d < od
        if (t.hipA != other.hipA) return t.hipA < other.hipA
        if (t.hipB != other.hipB) return t.hipB < other.hipB
        return t.hipC < other.hipC
    }

    /**
     * §0.71c 三颗星在球面上的「向量平均」方向 → (raDeg, decDeg)。
     *
     * 为什么不能用 `(ra1+ra2+ra3)/3`：赤经在 0°/360° 处有接缝。跨接缝的三颗星
     * （RA=354° 与 RA=2°）算术平均会得到 ~180°，与真实中心相差 160°+，
     * 导致切平面投影完全错位、正确候选被打 0 分。用单位向量相加求方向则天然无此问题。
     *
     * 权重上给三颗星等权（与原来的 /3 语义一致）。
     */
    private fun sphericalMean(eA: StarEntry, eB: StarEntry, eC: StarEntry): DoubleArray =
        sphericalMeanOf(listOf(eA, eB, eC))

    /** §0.71e 任意多颗星的球面向量平均（RA 跨 0° 接缝安全） */
    private fun sphericalMeanOf(stars: List<StarEntry>): DoubleArray {
        var x = 0.0; var y = 0.0; var z = 0.0
        for (e in stars) {
            val r = e.ra * (PI / 180.0)
            val d = e.dec * (PI / 180.0)
            val cd = cos(d)
            x += cd * cos(r); y += cd * sin(r); z += sin(d)
        }
        val n = sqrt(x * x + y * y + z * z)
        if (stars.isEmpty() || n < 1e-9) {
            // 退化（近似对径或空）：退回第一颗星，避免 NaN 扩散
            return doubleArrayOf(stars[0].ra, stars[0].dec)
        }
        x /= n; y /= n; z /= n
        val ra = atan2(y, x) * (180.0 / PI)
        val dec = asin(z.coerceIn(-1.0, 1.0)) * (180.0 / PI)
        return doubleArrayOf((ra + 360.0) % 360.0, dec)
    }

    /**
     * §0.71b 候选查询：**投影键优先，角度比兜底**。
     *
     * 照片端给的 (r2, r3) 是像素距离比，本身就是切平面距离比；而 [StarIndex.projMap]
     * 的键也是切平面距离比（以三角形重心为切点）—— 两者同尺度，故可用**严格窗**
     * （±0.012）。原先只用角度比键，宽场下失真中位 0.0170、只有 31% 真三角形能落进
     * 严格窗，且必须靠 ±0.05 的宽窗兜底，而宽窗会放进海量伪候选（实测 cand 数万）。
     *
     * 兜底逻辑保留：老键在窄场仍更精确（窄场失真极小），且合成场/小图不受影响。
     */
    private fun queryCandidates(idx: StarIndex, r2: Float, r3: Float): HashMap<Long, TriEntry> {
        val merged = HashMap<Long, TriEntry>()
        // 1) 投影键（宽场主力）：严格窗即可
        if (idx.projMap.isNotEmpty()) {
            for (key in quantizeKeys(r2, r3, window = 1)) {
                idx.projMap[key]?.let { list ->
                    for (t in list) {
                        if (abs(t.r2 - r2) < 0.012f && abs(t.r3 - r3) < 0.012f) merged[triKey(t)] = t
                    }
                }
            }
            if (merged.size >= candidateCap()) return merged
        }
        // 2) 角度比键：严格窗 + 宽窗（历史行为，窄场与合成场兜底）
        for (key in quantizeKeys(r2, r3, window = 2)) {
            idx.triMap[key]?.let { list ->
                for (t in list) {
                    if (abs(t.r2 - r2) < 0.012f && abs(t.r3 - r3) < 0.012f) merged[triKey(t)] = t
                }
            }
        }
        for (key in quantizeKeys(r2, r3, window = 14)) {
            idx.triMap[key]?.let { list ->
                for (t in list) {
                    if (abs(t.r2 - r2) < 0.05f && abs(t.r3 - r3) < 0.05f) merged[triKey(t)] = t
                }
            }
        }
        return merged
    }

    /** 单（照片星下标）区间内的三角形投票——可被多线程并行调用，各区间互不共享 key */
    private fun voteChunk(
        votes: HashMap<Long, Int>,
        work: List<DetectedStar>,
        from: Int,
        to: Int,
        idx: StarIndex,
        maxSide: Float,
    ) {
        // §0.74 计数器：局部累加、退出时一次性并入原子量。内循环每轮要扫数百万条目，
        // 在那里做 AtomicLong 自增会把生产路径拖慢（实测总时长反升）。
        var cTriangles = 0L; var cBuckets = 0L; var cEntries = 0L
        var cMerged = 0L; var cTaken = 0L
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
                    //    投票轮**保持历史的角度比查询**：projMap（§0.71b 投影键）只服务
                    //    打分轮。理由：投票轮的候选会经 verifyCandidate 的第 4 星校验，
                    //    投影键带来的额外候选在旧照上实测拉高伪票（topVote 26 vs 29、
                    //    旧照从 SOLVED 回归到 UNSOLVED），而打分轮才是宽场的主战场。
                    //
                    // §0.74 有界 top-K 选择（原为「全量进 HashMap + 全排序」）：
                    // 实测每轮要扫 ~6000 万条目、往 HashMap 插 ~390 万次，而最终只取
                    // ~7 万个候选 —— 即每个三角形插近千次、只要 18 个。这是投票轮
                    // 2.7~4.4s 的主要开销。改为一次扫描 + 有界插入排序：
                    //   ① 严格池是宽松池的子集（容差 ±0.012 ⊂ ±0.05，桶窗 ±1 ⊂ ±13），
                    //      故严格池整个省去 —— 它能找到的条目宽松池必然也能找到；
                    //   ② 同一三角形的重复记录（9 桶冗余 + 锚点顺序冗余）r2/r3 完全
                    //      相同 → 距离也相同。但**不同锚点插入的实例 (hipA,hipB,hipC)
                    //      排列不同**，而排序的第三~五键正是它们 —— 所以必须复现旧
                    //      `merged[triKey] = t` 的「后写覆盖」语义：重复记录到来时
                    //      换成后出现的那个实例（距离不变，只换实例），否则候选集
                    //      会变（实测 4984 从 51 内点掉到 15、5068 从 50 掉到 13）。
                    //   ③ 距离**严格大于**当前第 cap 名的条目才跳过。必须用严格大于：
                    //      旧算法是全排序后取前 cap，距离打平时按 hip 决胜 —— 若用
                    //      `>=` 把打平条目一并跳过，就会漏掉本可靠更小 hip 入选的那个。
                    // 选出的集合与顺序和原 `merged.values.sortedWith(...).take(cap)`
                    // 完全一致（同样的比较键：距离、hipA、hipB、hipC）。
                    val cap = candidateCap()
                    // 原生数组而非 ArrayList<Long>：去重是每条扫描条目都要做的一次
                    // O(cap) 比较，用装箱集合会把本轮 5000 万次扫描拖慢一个量级
                    // （实测总时长反升 12%）。
                    val sel = arrayOfNulls<TriEntry>(cap)
                    val selDist = FloatArray(cap)
                    val selKey = LongArray(cap)
                    var n = 0
                    for (key in quantizeKeys(r2, r3, window = 14)) {
                        cBuckets++
                        val list = idx.triMap[key] ?: continue
                        for (t in list) {
                            cEntries++
                            val dr2 = abs(t.r2 - r2)
                            if (dr2 >= 0.05f) continue
                            val dr3 = abs(t.r3 - r3)
                            if (dr3 >= 0.05f) continue
                            val d = dr2 + dr3
                            val k = triKey(t)
                            var at = -1
                            for (q in 0 until n) { if (selKey[q] == k) { at = q; break } }
                            if (at >= 0) {
                                // 后写覆盖：同 triKey 的重复记录换成后出现的实例。
                                // 距离相同故名次不变，只换 (hipA,hipB,hipC) 排列。
                                if (sel[at] !== t) sel[at] = t
                                continue
                            }
                            if (n >= cap && d > selDist[n - 1]) continue
                            var p = n
                            while (p > 0 && triBefore(t, d, sel[p - 1]!!, selDist[p - 1])) p--
                            if (p == cap) continue   // 打平且 hip 更大 → 仍不入选
                            if (n < cap) n++
                            var q = n - 1
                            while (q > p) {
                                sel[q] = sel[q - 1]; selDist[q] = selDist[q - 1]; selKey[q] = selKey[q - 1]
                                q--
                            }
                            sel[p] = t; selDist[p] = d; selKey[p] = k
                        }
                    }
                    cTriangles++
                    cMerged += n.toLong()
                    cTaken += n.toLong()
                    val candidates = ArrayList<TriEntry>(n)
                    for (q in 0 until n) candidates.add(sel[q]!!)

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
        nTriangles.addAndGet(cTriangles)
        nBucketLookups.addAndGet(cBuckets)
        nEntriesScanned.addAndGet(cEntries)
        nMerged.addAndGet(cMerged)
        nVerified.addAndGet(cTaken)
    }

private fun vote(votes: HashMap<Long, Int>, photoIdx: Int, hip: Int) {
        val key = (photoIdx.toLong() shl 20) or hip.toLong()
        votes[key] = (votes[key] ?: 0) + 1
    }

    // ============ 候选三角形几何验证（astrometry 式“第 4 星”校验） ============

    private val hipMap by lazy { StarCatalogData.stars.associateBy { it.hip } }

    @Volatile
    private var cachedGrid: HashMap<Int, MutableList<StarEntry>>? = null

    /** [cachedGrid] 构建时使用的星等上限（调试覆盖变化时用于失效重建） */
    @Volatile
    private var cachedGridMag: Float = Float.NaN

    /** 星表赤道网格（5°×5° 赤经/赤纬格），用于验证时的快速最近星查询 */
    private fun catGrid(): HashMap<Int, MutableList<StarEntry>> {
        val mag = catalogMag()
        cachedGrid?.let { if (cachedGridMag == mag) return it }
        synchronized(this) {
            cachedGrid?.let { if (cachedGridMag == mag) return it }
            val grid = HashMap<Int, MutableList<StarEntry>>()
            // 验证网格只收录照片可检测的亮星（与投票索引同域，见 catalogMag）：
            // §0.42 扩容新增的暗星照片里检测不到，放进来会让假候选的"第 4 星
            // 外推撞星"通过率上升 → 假阳性回归（apod5/pleiades 实测），
            // 而真候选外推应命中的是可检测亮星，不受影响。
            for (star in StarCatalogData.stars) {
                if (star.mag > mag) continue
                val col = (((star.ra % 360.0) + 360.0) % 360.0 / 5.0).toInt()
                val row = ((star.dec + 90.0) / 5.0).toInt().coerceIn(0, 35)
                grid.getOrPut(col * 1000 + row) { ArrayList() }.add(star)
            }
            cachedGrid = grid
            cachedGridMag = mag
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
                    for (key in quantizeKeys(r2, r3, window = 2)) {
                        idx.triMap[key]?.let { list -> strictRaw.addAll(list) }
                    }
                    val looseRaw = ArrayList<TriEntry>()
                    for (key in quantizeKeys(r2, r3, window = 14)) {
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
                        .sortedWith(compareBy({ abs(it.r2 - r2) + abs(it.r3 - r3) }, { it.hipA }, { it.hipB }, { it.hipC }))
                        .take(candidateCap())
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
        // §0.71e 切平面原点：固定原点直接采用；否则用**球面向量平均**而非
        // 赤经算术平均 —— 跨 RA=0°/360° 接缝的配对（实测 5087 种子星
        // RA 345.5°/2.1°/3.3°，算术平均 117° 偏出真实中心 120°+）会让
        // tanXY 全部 NaN、拟合直接失败（打分轮 24 内点解因扩展作废）。
        val ra0: Double
        val dec0: Double
        if (fixedRa0 != null && fixedDec0 != null) {
            ra0 = fixedRa0
            dec0 = fixedDec0
        } else {
            val sm = sphericalMeanOf(pairs.map { it.second })
            ra0 = sm[0]
            dec0 = sm[1]
        }
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
        // 切平面原点固定在整个配对集的平均天球坐标（见 fitPairs 注释）；
        // §0.71e 改用球面向量平均，避免跨 RA=0° 接缝时算术平均偏出 120°+
        val sm0 = sphericalMeanOf(pairs.map { it.second })
        val setRa0 = sm0[0]
        val setDec0 = sm0[1]

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
        var refined0 = fitPairs(detected, bestInliers, width, height, mirrorX, setRa0, setDec0) ?: ransacParams
        var refined = refineWcs(detected, bestInliers, width, height, refined0, mirrorX)

        // §0.63 切平面原点迭代到图像中心。
        // 照片本身是关于**图像中心**的 gnomonic 投影，而模型"像素 → 切平面"只有在
        // 切平面原点=投影中心时才严格是相似变换。若沿用星表星均值作原点（宽场下
        // 可偏离画面中心数度），整场会带一个相似变换吸收不掉的畸变：实测 74° 照片
        // 平均偏差 14px、边缘达 45px（星座连线因此整体漂移、线与星点对不上）。
        // 迭代 3~4 遍即收敛（实测平均偏差 14.13px → 0.17px，与理想原点持平）。
        repeat(SCORED_ORIGIN_ITERATIONS) {
            val w = buildWcs(refined, width, height, mirrorX)
            val (cRa, cDec) = w.fitsPixelToSky(width / 2.0 + 0.5, height / 2.0 + 0.5)
            if (cRa.isNaN() || cDec.isNaN()) return@repeat
            val next0 = fitPairs(detected, bestInliers, width, height, mirrorX, cRa, cDec)
                ?: return@repeat
            // fixOrigin=true：把原点钉在图像中心，只精化 (a,b,tx,ty)，
            // 否则 GN 会把原点优化跑（实测那样做等于没修，仍是 6px 偏差）
            refined = refineWcs(
                detected, bestInliers, width, height, next0, mirrorX, fixOrigin = true,
            )
        }

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
        // 图像中心像素的天球坐标：直接从最终 WCS 的参考像素反推。
        // §0.63 修正：原实现用 tanInverse(refined.tx, refined.ty, ...) —— 那是把
        // 模型的**平移量**当中心，只有切平面原点恰在图像中心时才等价。宽场下二者
        // 可差 0.4°~1.1°（实测 74° 照片差 0.38°/1.07°），结果页显示的坐标随之偏移。
        // 渲染始终用 WCS，故只影响显示数值；现与 WCS 保持同一来源，二者永不失配。
        val (raC, decC) = finalWcs.fitsPixelToSky(width / 2.0 + 0.5, height / 2.0 + 0.5)
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
     *
     * [fixOrigin] = true 时只优化 (a, b, tx, ty)，把 ra0/dec0 固定为调用方给出的
     * 切平面原点。§0.63：切平面原点必须落在**图像中心**（照片的 gnomonic 投影
     * 中心），否则整场会带相似变换吸收不掉的畸变。若让 GN 自由优化原点，它会
     * 收敛到"该模型下残差最小"却并非图像中心的位置 —— 实测 74° 照片由此产生
     * 0.9% 比例尺偏差、连线相对星点漂移 6~23px。
     */
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
        // fixOrigin 时参数数组只放 (a,b,tx,ty)，切平面原点由闭包固定（见下）
        val fixedRa0 = start.ra0
        val fixedDec0 = start.dec0
        var p = if (fixOrigin) {
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
        return if (fixOrigin) {
            FitParams(best[0], best[1], best[2], best[3], fixedRa0, fixedDec0)
        } else {
            FitParams(best[0], best[1], best[2], best[3], best[4], best[5])
        }
    }
    /** §0.71d 三点相似拟合的残差平方和（切平面度²），用于挑选正确奇偶性 */
    private fun fitResid(
        f: DoubleArray, px: FloatArray, py: FloatArray, sx: FloatArray, sy: FloatArray,
    ): Double {
        val s = f[0]; val ss = f[1]; val tx = f[2]; val ty = f[3]
        var r = 0.0
        for (i in px.indices) {
            val ex = sx[i] - (s * px[i] - ss * py[i] + tx)
            val ey = sy[i] - (ss * px[i] + s * py[i] + ty)
            r += ex * ex + ey * ey
        }
        return r
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
        // 只统计照片可检测的亮星（与投票索引/验证网格同域，见 catalogMag）——
        // §0.42 扩容的暗星在 2200px 照片里检测不到，遍历它们既虚增内点
        // （假解更易过门槛）又拖慢 10 倍（实测单张 3~5s → 20~50s）。
        val mag = catalogMag()
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

