package com.starcam.astro.astro

import android.graphics.Bitmap

/** 照片中检测到的星点 */
data class DetectedStar(val x: Float, val y: Float, val brightness: Float)

/**
 * §0.103（P0-2.5）：解来自哪个星表域。
 * 浅域（mag≤4.0）是宽场主路径；深域（mag≤6.5）是窄场兜底 —— 调用方据此知道
 * "这个解是高密度星表兜底得到的"，可做更谨慎的呈现/记录（审计 P0-2.5 (d)）。
 */
enum class CatalogDomain { SHALLOW, INTERMEDIATE, DEEP }

/** 本地匹配结果 */
data class LocalMatchResult(
    val solve: SolveResult,
    val matchedPairs: List<Pair<DetectedStar, StarEntry>>,
    val inlierCount: Int,
    /** §0.103：解来源域（默认浅域 —— 既有构造点全部产出浅域解，无需逐个改）。 */
    val catalogDomain: CatalogDomain = CatalogDomain.SHALLOW,
)

/**
 * 一次求解的**留档快照**（§0.107，二期审计 2026-10-14）。
 *
 * 这些数字原先作为进程级 `@Volatile` 字段暴露，调用方（[StarSolver]）在 `match()`
 * **返回之后**才去读 —— 那时锁已经放开，批量识别的另一个 worker 可能已进入下一次
 * 求解并在入口把它们清零/覆写（`matchLocked` 开头就是一轮清零），于是日志里出现
 * "读到 null" 或"读到别人的数字"的串台。现在改为**在 solveLock 内快照、随结果返回**：
 * 拿到它的人一定读到的是自己那一次求解的数字。
 *
 * 字段语义：
 *  - [voteStats]/[scoredStats]/…：**最后一次尝试的域**（有深域重试时是深域的数字）；
 *  - [shallow*]：**主路径（浅域）**那一轮的数字（切深域前的留档；未切域时与上面相同）；
 *  - [usedFallback]：本次解是否由备用星表命中；
 *  - [deepRejectedReason]：深域被跳过/被拒时的原因（未涉深域为 null）。
 */
data class MatchDiagnostics(
    val usedFallback: Boolean,
    val deepRejectedReason: String?,
    val voteStats: String?,
    val scoredStats: String?,
    val scoredBest: Int?,
    val scoredInFrame: Int?,
    val shallowVoteStats: String?,
    val shallowScoredStats: String?,
    val shallowScoredBest: Int?,
    val shallowScoredInFrame: Int?,
    val usedRecovery: Boolean = false,
    val recoveryDetail: String? = null,
    val usedCompactRecovery: Boolean = false,
)

/** [LocalStarMatcher.matchWithDiagnostics] 的返回：结果 + 与它同源的留档。 */
data class MatchAttempt(
    val result: LocalMatchResult?,
    val diagnostics: MatchDiagnostics,
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
 * （视场 10°~120°，画面含 ≥8 颗可检测亮星）。
 *
 * §0.75 起采用**分级星表域**：浅域（mag≤4.0，514 颗）承担 30° 以上的宽场，
 * 整条兜底阶梯都无解时才切到深域（mag≤6.5，8415 颗）重试一次，把能力下沿
 * 从 ~20° 拉到 10°。宽场照片在浅域就解出，永远碰不到深域 —— 这是分级而非
 * 全局加深的原因：实测全局加深会让宽场弱解被稀释、并引入尺度坍缩伪影。
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
     * null = 不覆盖，走运行时域 [runtimeCatalogMag]。
     *
     * §0.77：本字段恢复**只读覆盖**语义（仅供测试与标定台）。v1.5.63 曾把它当运行时
     * 域状态机用（深域重试时写入），既污染了调试字段的语义，又因为「写入侧加锁、
     * 读取侧裸奔」而存在并发串域风险 —— 运行时域已改由 [runtimeCatalogMag] 承载。
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
    private const val INTERMEDIATE_CATALOG_MAG = 5.5f
    private const val INTERMEDIATE_BUDGET_MS = 8_000L

    /**
     * §0.103（P0-2.5）深域路径的视场上界（度）与深域准入判据。
     *
     * 原值 90°：旧注释自己写着"已知全部真解的最大视场是宽场回归里的 76.9°"，取 90°
     * 是为"容纳真实宽场" —— 但**深域是窄场兜底路径**（目标 ≤20°），真实宽场在浅域
     * 就该解出（宽场回归 12/12）；浅域整条阶梯都无解的宽场照片拿到 45°~90° 的"解"，
     * 基本是尺度坍缩伪影。docs/72（代码审查 B2）与外部审计（2026-10-06 P0-2.5）
     * 都建议把深域上界收紧到 ~40°~45°。
     *
     * 取 **45°** 的理由：① 窄场/中场真值台覆盖 0.5°~20°，离该界很远，收紧不影响
     * 既有能力；② 与"可信 EXIF 视场 > 45° 时跳过深域"的准入判据共用同一个界 ——
     * 一个口子、一处定义。
     *
     * 可见性 internal：仅供测试做"不得再放宽"的守卫（与调试开关同一约定）。
     */
    internal const val DEEP_PATH_MAX_FOV_DEG = 45.0

    /**
     * §0.103（P0-2.5）深域准入判据：有**可信视场**（EXIF 焦距推算）且明显是宽场时，
     * 深域不值得跑 —— §0.87 的密度换算表明视场 >66° 时深域过门在数学上不可达；
     * 真机实测 5057（74°~75°）在深域空转、打满 60 秒硬上限（PROGRESS §0.97）。
     *
     * ⚠️ 判据只允许用**调用方传入的可信视场**。§0.98 曾按"浅域失败候选反推的视场"
     * 做同类闸门并当场回滚 —— 那种候选在浅域失败时被尺度坍缩伪解主导，与真实视场
     * 无关（窄场 12°/20° 合成用例当场 UNSOLVED）。这正是本方案改用 EXIF 视场的原因。
     */
    internal fun shouldSkipDeepRetry(fovHintDeg: Double?): Boolean =
        fovHintDeg != null && fovHintDeg > DEEP_PATH_MAX_FOV_DEG

    /**
     * 单次求解上下文（Sprint 4：4.3 去除 solveLock 全局排他锁）。
     *
     * 解决历史问题：星表域（mag）、打分轮候选上限（cap）与调试留档原为单例对象级可变字段，
     * 靠进程级 solveLock 互斥，导致批量识别与双引擎竞速被全局锁串行化。
     * 将单次求解的可变状态与留档封装于 MatchContext 中，使多线程并发求解各自独立、无锁运行。
     */
    data class MatchContext(
        var catalogMag: Float = debugForceCatalogMag ?: PROD_CATALOG_MAG,
        var scoredCap: Long = SCORED_MAX_CANDIDATES,
        val token: SolveToken? = null,
    ) {
        var voteStats: String? = null
        var scoredStats: String? = null
        var phaseTimings: String? = null
        var scoredBest: Int? = null
        var scoredInFrame: Int? = null
        var scoredWinPairs: Int? = null
        var scoredExpandedPairs: Int? = null
        var scoredWinSeed: String? = null
        var scoredWinAt: Long = -1L
        var usedFallback: Boolean = false
        var expandDetail: String? = null
        var shallowVoteStats: String? = null
        var shallowScoredStats: String? = null
        var shallowScoredBest: Int? = null
        var shallowScoredInFrame: Int? = null
        var shallowPhaseTimings: String? = null
        var deepRejectedReason: String? = null
        var usedRecovery: Boolean = false
        var usedCompactRecovery: Boolean = false
        var recoveryDetail: String? = null
        var scoredFovRange: Pair<Double, Double>? = null
        var narrowFovHint: Double? = null

        fun copyMatchDiagnostics(from: MatchContext) {
            voteStats = from.voteStats
            scoredStats = from.scoredStats
            scoredBest = from.scoredBest
            scoredInFrame = from.scoredInFrame
            phaseTimings = from.phaseTimings
            scoredWinPairs = from.scoredWinPairs
            scoredExpandedPairs = from.scoredExpandedPairs
            scoredWinSeed = from.scoredWinSeed
            scoredWinAt = from.scoredWinAt
            expandDetail = from.expandDetail
        }

        fun snapshotDiagnostics(): MatchDiagnostics = MatchDiagnostics(
            usedFallback = usedFallback,
            deepRejectedReason = deepRejectedReason,
            voteStats = voteStats,
            scoredStats = scoredStats,
            scoredBest = scoredBest,
            scoredInFrame = scoredInFrame,
            shallowVoteStats = shallowVoteStats,
            shallowScoredStats = shallowScoredStats,
            shallowScoredBest = shallowScoredBest,
            shallowScoredInFrame = shallowScoredInFrame,
            usedRecovery = usedRecovery,
            recoveryDetail = recoveryDetail,
            usedCompactRecovery = usedCompactRecovery,
        )
    }

    /** 兼容历史调试读取：最后一次求解完成时同步更新对象级字段（见 syncLegacyDiagnostics） */
    @Volatile
    private var runtimeCatalogMag: Float = PROD_CATALOG_MAG

    /** 当前生效的星表域星等上限（调试字段优先级最高，仅测试/标定台会写） */
    private fun catalogMag(): Float = debugForceCatalogMag ?: runtimeCatalogMag

    /** §0.62 调试：每轮投票送验候选数上限（生产默认 18，见 [TriangleVoteScorer]） */
    @Volatile
    internal var debugCandidateCap: Int? = null

    private const val SCORED_MAX_CANDIDATES = TriangleVoteScorer.SCORED_MAX_CANDIDATES
    private const val SCORED_PROBE_CANDIDATES = TriangleVoteScorer.SCORED_PROBE_CANDIDATES
    private const val NARROW_SCORED_MIN_REFINED_INLIERS = TriangleVoteScorer.NARROW_SCORED_MIN_REFINED_INLIERS

    /** §0.62 调试：最低对齐星数（生产默认 6；设为极大可停用打分轮做 A/B） */
    @Volatile
    internal var debugMinAligned: Int? = null

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
     * §0.74 / §0.125 调试：matchInternal 各阶段耗时（毫秒，按调用顺序）。
     */
    @Volatile
    var debugPhaseTimings: String? = null

    /**
     * §0.70 调试：逐星投票轮统计（参与投票的星数 / 得到票的星数 / 最高票 / 平均领先比）。
     */
    @Volatile
    var debugVoteStats: String? = null

    /** §0.74 调试：读取并清零 voteChunk 计数器 */
    internal fun debugVoteScanTake(): String = TriangleVoteScorer.debugVoteScanTake()

    /** §0.62 调试：打分轮最佳对齐星数（诊断用，null=未跑） */
    @Volatile
    var debugScoredBest: Int? = null
        private set

    /** §0.62 调试：打分轮最佳候选的 inFrame（用于对齐率标定） */
    @Volatile
    var debugScoredInFrame: Int? = null
        private set

    // ── §0.86 浅域留档 ───────────────────────────────────────────────
    // 上面那组是**单槽**，每次 matchInternal 都覆盖。而 §0.75 之后一次求解最多
    // 跑两遍（浅域 + 深域重试），失败页读到的永远是**深域**那一遍的数字。
    // 实测 5031：浅域打分轮 16/30=0.533（可解），深域只有 7/715 的伪解 ——
    // 只看深域会误判成"打分轮找不到解"，而真正该查的是浅域为什么没解出来。
    // 故在切深域之前把浅域那一轮原样留档，失败页两轮都显示。
    @Volatile var debugShallowVoteStats: String? = null
        private set
    @Volatile var debugShallowScoredStats: String? = null
        private set
    @Volatile var debugShallowScoredBest: Int? = null
        private set
    @Volatile var debugShallowScoredInFrame: Int? = null
        private set
    @Volatile var debugShallowPhaseTimings: String? = null
        private set

    // ── §0.103（P0-2.5）深域准入留档 ────────────────────────────────
    /**
     * 最近一次求解**深域路径未产出可用解的原因**（`null` = 深域有可用解、或未走到深域）。
     *
     * 两种情形会写入：① 可信视场 > 上界，**跳过**深域；② 深域解视场超出窄场上界，
     * **拒绝**为坍缩伪解。失败页/识别日志据此说明"为什么没有深域结果"——否则用户
     * 只看到"没解出来"，分不清是"不值得跑"还是"跑了也没解出"（真机 5057 曾白跑 60 秒，
     * 有了这个字段才能在诊断里一眼看出闸门生效）。
     */
    @Volatile var debugDeepRejectedReason: String? = null
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

    /**
     * §0.87 调试：获胜候选被刷新时的**已评分序号**（1 起；未刷新保持 -1）。
     *
     * 存在的理由：宽场照片的获胜候选恒在候选序列**末位**（实测 8/8 张凡是走
     * 打分轮的照片，`winAt == scored`），故打分轮必须跑完整串才能解出 ——
     * 这正是「打分轮不能用时间预算截断」的依据。回归测试
     * `ScoredRoundBudgetTest` 用它把该性质钉住。
     */
    @Volatile
    var debugScoredWinAt: Long = -1
        private set

    /**
     * §0.88 上次 [match] 是否由**备用星表**解出（由调用方读取，用于识别日志与统计）。
     * 每次求解入口清零；主星表浅域成功时不置位。
     */
    @Volatile
    var lastMatchUsedFallback: Boolean = false
        private set

    @Volatile
    var debugExpandDetail: String? = null
        private set

    /** 双阈值通道（§0.32.3）：主轮内点达到该值即视为强解，不再跑备用轮 */
    private const val DUAL_THRESHOLD_STRONG_INLIERS = 18

    private const val WEAK_FLOOR_RATIO = StarFieldDetector.WEAK_FLOOR_RATIO

    private fun voteBrightnessThreshold(brightness: List<Float>): Float =
        StarFieldDetector.voteBrightnessThreshold(brightness, debugForceVoteThreshold)

    internal fun voteThresholds(brightness: List<Float>): Pair<Float, Float> =
        StarFieldDetector.voteThresholds(brightness, debugForceVoteThreshold)

    // ================= 1. 星点检测（委托至 StarFieldDetector） =================

    fun detectStars(bitmap: Bitmap, maxStars: Int = 100): List<DetectedStar> =
        StarFieldDetector.detectStars(bitmap, maxStars)

    fun bitmapToGray(bitmap: Bitmap): FloatArray =
        StarFieldDetector.bitmapToGray(bitmap)

    fun detectStarsGray(width: Int, height: Int, gray: FloatArray, maxStars: Int = 64): List<DetectedStar> =
        StarFieldDetector.detectStarsGray(width, height, gray, maxStars)

    // ================= 2. 星表三角形索引（委托至 TriangleStarIndex） =================

    /** §0.75 调试：彻底跳过深星表域兜底（A/B 计费用；true=浅域无解即返回 null） */
    @Volatile
    internal var debugDisableDeepCatalog: Boolean = false

    /** 调试：返回（当前星表域索引的三角形总数, 首次构建耗时 ms） */
    internal fun debugIndexBuild(): Pair<Int, Long> =
        TriangleStarIndex.debugIndexBuild(catalogMag())

    /** §0.81：归还可重建的托管堆缓存（内存压力时由 [StarCamApplication] 调用） */
    fun trimCaches() = TriangleStarIndex.trimCaches()

    internal fun quantizeKeys(r2: Float, r3: Float, window: Int = 1): List<Int> =
        TriangleStarIndex.quantizeKeys(r2, r3, window)

    internal fun bucketKey(r2: Float, r3: Float): Int =
        TriangleStarIndex.bucketKey(r2, r3)

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
     *
     * @param fallbackDetected §0.88 备用星表（第二个检测器的星点列表）。主星表浅域无解时，
     *   在**深域重试之前**用它在浅域再试一次：两个检测器是独立证据，主列表不可用
     *   （如被多余星挤坏候选生成）不代表备用列表也解不出。编号在前的顺序保持不变，
     *   仅在失败路径上多一次便宜的浅域尝试（备用列表通常更稀疏、候选更少）。
     */
    fun match(
        detected: List<DetectedStar>,
        width: Int,
        height: Int,
        pointingHint: PointingHint? = null,
        allowDeepRetry: Boolean = true,
        fallbackDetected: List<DetectedStar>? = null,
        fovHintDeg: Double? = null,
        token: SolveToken? = null,
        recoveryDetected: ((SolveToken) -> List<DetectedStar>)? = null,
    ): LocalMatchResult? = matchWithDiagnostics(
        detected, width, height, pointingHint, allowDeepRetry, fallbackDetected, fovHintDeg, token,
        recoveryDetected = recoveryDetected,
    ).result

    /**
     * 与 [match] 同一入口，但同时返回**与本次求解同源的留档**（§0.107）。
     *
     * 为什么需要独立入口：留档字段是进程级共享的单槽（`debugVoteStats` 等），
     * 出了 `solveLock` 再读就可能被下一个求解覆写（批量两 worker 并发时必现）；
     * 这里在锁内一次性快照，调用方拿到的是"自己那次"的数字。
     * 既有的进程级字段保留（失败页 `ResultScreen` 仍读它们 —— 单张流程一次只有一个
     * 求解，不存在串台）。
     */
    fun matchWithDiagnostics(
        detected: List<DetectedStar>,
        width: Int,
        height: Int,
        pointingHint: PointingHint? = null,
        allowDeepRetry: Boolean = true,
        fallbackDetected: List<DetectedStar>? = null,
        fovHintDeg: Double? = null,
        token: SolveToken? = null,
        compactRecoveryDetected: ((SolveToken) -> List<DetectedStar>)? = null,
        recoveryDetected: ((SolveToken) -> List<DetectedStar>)? = null,
    ): MatchAttempt {
        val ctx = MatchContext(
            catalogMag = debugForceCatalogMag ?: PROD_CATALOG_MAG,
            scoredCap = SCORED_MAX_CANDIDATES,
            token = token,
        )
        val result = matchInternalContext(
            ctx, detected, width, height, pointingHint, allowDeepRetry, fallbackDetected, fovHintDeg,
            recoveryDetected, compactRecoveryDetected,
        )
        val diag = ctx.snapshotDiagnostics()
        syncLegacyDiagnostics(ctx)
        return MatchAttempt(result, diag)
    }

    private fun syncLegacyDiagnostics(ctx: MatchContext) {
        debugVoteStats = ctx.voteStats
        debugScoredStats = ctx.scoredStats
        debugPhaseTimings = ctx.phaseTimings
        debugScoredBest = ctx.scoredBest
        debugScoredInFrame = ctx.scoredInFrame
        debugShallowVoteStats = ctx.shallowVoteStats
        debugShallowScoredStats = ctx.shallowScoredStats
        debugShallowScoredBest = ctx.shallowScoredBest
        debugShallowScoredInFrame = ctx.shallowScoredInFrame
        debugShallowPhaseTimings = ctx.shallowPhaseTimings
        debugDeepRejectedReason = ctx.deepRejectedReason
        debugScoredWinPairs = ctx.scoredWinPairs
        debugScoredExpandedPairs = ctx.scoredExpandedPairs
        debugScoredWinSeed = ctx.scoredWinSeed
        debugScoredWinAt = ctx.scoredWinAt
        lastMatchUsedFallback = ctx.usedFallback
        debugExpandDetail = ctx.expandDetail
    }

    private fun matchInternalContext(
        ctx: MatchContext,
        detected: List<DetectedStar>,
        width: Int,
        height: Int,
        pointingHint: PointingHint?,
        allowDeepRetry: Boolean,
        fallbackDetected: List<DetectedStar>? = null,
        fovHintDeg: Double? = null,
        recoveryDetected: ((SolveToken) -> List<DetectedStar>)? = null,
        compactRecoveryDetected: ((SolveToken) -> List<DetectedStar>)? = null,
    ): LocalMatchResult? {
        ctx.token?.check()
        // A trusted narrow-field scale lets the scored search prioritize plausible triangles.
        ctx.narrowFovHint = fovHintDeg?.takeIf { it in 10.0..25.0 }
        ctx.scoredFovRange = ctx.narrowFovHint?.let { FovEstimate.fovRange(it) }
        val tryIntermediate = allowDeepRetry && !debugDisableDeepCatalog &&
            debugForceCatalogMag == null && ctx.narrowFovHint != null

        fun intermediate(parent: MatchContext, stars: List<DetectedStar>): LocalMatchResult? {
            if (!tryIntermediate || stars.size < NARROW_SCORED_MIN_REFINED_INLIERS) return null
            val trial = MatchContext(INTERMEDIATE_CATALOG_MAG, parent.scoredCap,
                SolveToken(parent.token, timeoutMillis = INTERMEDIATE_BUDGET_MS))
            trial.narrowFovHint = parent.narrowFovHint
            trial.scoredFovRange = parent.scoredFovRange
            val result = try {
                matchInternal(trial, stars, width, height, null)
            } catch (_: SolveBudgetExceeded) {
                parent.token?.check()
                null
            }
            if (result == null || !acceptableRecovery(result, fovHintDeg)) return null
            parent.copyMatchDiagnostics(trial)
            return result.copy(catalogDomain = CatalogDomain.INTERMEDIATE)
        }
        // A contaminated primary list is not evidence that the image has no usable stars.
        // Run at most one independent recovery search after the existing paths fail.
        // A child deadline limits the additional latency without consuming the whole solve budget.
        fun recover(): LocalMatchResult? {
            if (recoveryDetected == null) return null
            // Product scope is >=10 degrees; do not spend a second search on a known
            // telescope-scale field outside that scope.
            if (fovHintDeg != null && fovHintDeg < 10.0) return null
            val child = SolveToken(ctx.token, timeoutMillis = 30_000)
            val recovery = MatchContext(ctx.catalogMag, ctx.scoredCap, child)
            recovery.narrowFovHint = ctx.narrowFovHint
            recovery.scoredFovRange = fovHintDeg?.let { FovEstimate.fovRange(it) }
            return try {
                child.check()
                val stars = recoveryDetected(child)
                child.check()
                // Retry the last failed domain first. Repeating all shallow vote ladders
                // before a dense-field retry can spend the whole recovery budget on a
                // catalog too sparse for that field. The original paths already ran.
                val deepAllowed = allowDeepRetry && !debugDisableDeepCatalog && !shouldSkipDeepRetry(fovHintDeg)
                val domains = if (deepAllowed && fovHintDeg != null && fovHintDeg <= 20.0) listOf(DEEP_CATALOG_MAG, ctx.catalogMag).distinct()
                    else if (deepAllowed) listOf(ctx.catalogMag, DEEP_CATALOG_MAG).distinct()
                    else listOf(ctx.catalogMag)
                var accepted = intermediate(recovery, stars)
                var selectedCount = stars.size
                if (accepted == null && tryIntermediate && compactRecoveryDetected != null) {
                    val compactStars = try {
                        compactRecoveryDetected(child)
                    } catch (_: OutOfMemoryError) {
                        emptyList()
                    }
                    val compact = intermediate(recovery, compactStars)
                    if (compact != null) {
                        accepted = compact
                        selectedCount = compactStars.size
                        ctx.usedCompactRecovery = true
                    }
                }
                if (accepted == null && stars.size >= 5) for (mag in domains) {
                    child.check()
                    recovery.catalogMag = mag
                    val candidate = matchInternal(recovery, stars, width, height, null) ?: continue
                    if (mag == DEEP_CATALOG_MAG &&
                        maxOf(candidate.solve.fieldWidthDeg, candidate.solve.fieldHeightDeg) > DEEP_PATH_MAX_FOV_DEG) continue
                    if (!acceptableRecovery(candidate, fovHintDeg)) continue
                    accepted = if (mag == DEEP_CATALOG_MAG) candidate.copy(catalogDomain = CatalogDomain.DEEP) else candidate
                    break
                }
                val detection = if (ctx.usedCompactRecovery) "局部信噪提星" else "局部背景提星"
                ctx.recoveryDetail = "$detection=${selectedCount}颗，" +
                    if (accepted != null) "命中(${accepted.inlierCount}内点)" else "未通过匹配/质量门槛"
                if (accepted != null) {
                    ctx.usedRecovery = true
                    ctx.copyMatchDiagnostics(recovery)
                    ctx.deepRejectedReason = recovery.deepRejectedReason
                    ctx.shallowVoteStats = recovery.shallowVoteStats
                    ctx.shallowScoredStats = recovery.shallowScoredStats
                    ctx.shallowScoredBest = recovery.shallowScoredBest
                    ctx.shallowScoredInFrame = recovery.shallowScoredInFrame
                    ctx.shallowPhaseTimings = recovery.shallowPhaseTimings
                }
                accepted
            } catch (_: SolveBudgetExceeded) {
                ctx.token?.check() // Do not hide expiry of the parent/global budget.
                ctx.recoveryDetail = "复杂星空补救达到30秒预算，继续原有路径"
                null
            } catch (_: OutOfMemoryError) {
                ctx.recoveryDetail = "复杂星空补救内存不足，继续原有路径"
                null
            }
        }
        if (detected.size < 5) return recover()
        // §0.86：清掉上一张图的留档，避免失败页显示陈旧的浅域数字
        ctx.shallowVoteStats = null
        ctx.shallowScoredStats = null
        ctx.shallowScoredBest = null
        ctx.shallowScoredInFrame = null
        ctx.shallowPhaseTimings = null
        ctx.deepRejectedReason = null
        ctx.usedFallback = false
        if (pointingHint != null) {
            ctx.token?.check()
            val hintResult = matchInternal(ctx, detected, width, height, pointingHint)
            if (hintResult != null) return hintResult
        }
        // §0.88 备用星表：主星表浅域无解时，用第二个检测器的列表再试一次。两个检测器
        // 是独立证据 —— 真机实测（5031，宽场欠曝）：SEP 列表 177 颗把打分轮打到 2 万
        // 候选上限仍只有 8/206（对齐率 0.04，**真解候选根本生成不出来**），而同一张
        // 照片的 box-blur 列表只有 32 颗、本机同口径像素复刻 16/30=0.533 过门。
        //
        // §0.89 顺序与预算（在 §0.88 之上收紧延迟）：有备用列表兜底时，主星表**先只用
        // 低成本试探预算**（[SCORED_PROBE_CANDIDATES]）—— 真机实测那份 177 颗的列表
        // 要跑满 2 万候选才罢休（24.5 秒）却永远解不出，先付满额是纯浪费。试探失败后：
        // ① 备用星表（完整预算）→ ② 主星表（完整预算）→ ③ 深域重试。
        val hasAlt = fallbackDetected != null && fallbackDetected !== detected &&
            fallbackDetected.size >= 5
        if (hasAlt) {
            ctx.token?.check()
            val savedCap = ctx.scoredCap
            ctx.scoredCap = SCORED_PROBE_CANDIDATES
            val probe = try {
                matchInternal(ctx, detected, width, height, null)
            } finally {
                ctx.scoredCap = savedCap
            }
            if (probe != null) return probe
            ctx.token?.check()
            val alt = matchInternal(ctx, fallbackDetected!!, width, height, null)
            if (alt != null) {
                ctx.usedFallback = true
                return alt
            }
            ctx.token?.check()
            // 备用列表也没解出：回到主星表的完整预算（即未启用备用时的默认搜索）
            val full = matchInternal(ctx, detected, width, height, null)
            if (full != null) return full
        } else {
            ctx.token?.check()
            val shallow = matchInternal(ctx, detected, width, height, null)
            if (shallow != null) return shallow
        }
        // §0.77 深域准入闸门：深域重试的触发条件是「浅域整条阶梯无解」，而这个词对
        // **任何**失败帧都成立 —— 白天、室内、纯噪声帧全都算。相机预览是每 5~8s 一次
        // 的长驻循环，不做准入就要首帧付 9.3s 的索引构建、之后每帧都跑一遍 16 倍
        // 候选密度的深域阶梯，纯属浪费：预览用的是主摄广角（≈67°~77°），本就落在
        // 浅域覆盖的宽场里，深域是给 ≤30° 窄场照片兜底的。
        if (!allowDeepRetry) return recover()
        if (debugDisableDeepCatalog) return recover()
        ctx.token?.check()
        // §0.103（P0-2.5）深域准入：有可信 EXIF 视场且 > [DEEP_PATH_MAX_FOV_DEG] 时
        // **直接跳过深域**。两条依据：① 深域是窄场路径，宽场在浅域就该解出；浅域全败的
        // 宽场照片拿到深域"解"基本是坍缩伪影；② 深域在这种视场下过门在数学上不可达
        // （§0.87 密度换算：>66° 不可达），真机 5057 因此白跑 60 秒（PROGRESS §0.97）。
        // 判据**只用手持的 EXIF 视场**，绝不用浅域失败候选反推的视场（§0.98 的回滚教训）。
        if (shouldSkipDeepRetry(fovHintDeg)) {
            ctx.deepRejectedReason = String.format(
                java.util.Locale.ROOT,
                "可信视场 %.1f° > %.0f°，深域不可达/只会出伪解，已跳过",
                fovHintDeg,
                DEEP_PATH_MAX_FOV_DEG,
            )
            return recover()
        }
        // §0.75 窄场兜底：浅星表域（mag<=4.0）整条阶梯都无解时，用深星表域
        // （mag<=6.5）重试一次。
        ctx.shallowVoteStats = ctx.voteStats
        ctx.shallowScoredStats = ctx.scoredStats
        ctx.shallowScoredBest = ctx.scoredBest
        ctx.shallowScoredInFrame = ctx.scoredInFrame
        ctx.shallowPhaseTimings = ctx.phaseTimings
        intermediate(ctx, detected)?.let { return it }
        val savedMag = ctx.catalogMag
        ctx.catalogMag = DEEP_CATALOG_MAG
        val deep = try {
            ctx.token?.check()
            matchInternal(ctx, detected, width, height, null)
        } finally {
            ctx.catalogMag = savedMag
        }
        // §0.75 深域是**窄场路径**，对它的解再设一道视场上界（§0.103 起为 45°）。
        if (deep != null &&
            maxOf(deep.solve.fieldWidthDeg, deep.solve.fieldHeightDeg) > DEEP_PATH_MAX_FOV_DEG
        ) {
            ctx.deepRejectedReason = String.format(
                java.util.Locale.ROOT,
                "深域解视场 %.1f° 超出窄场上界 %.0f°，按坍缩伪解拒绝",
                maxOf(deep.solve.fieldWidthDeg, deep.solve.fieldHeightDeg),
                DEEP_PATH_MAX_FOV_DEG,
            )
            return recover()
        }
        // §0.103（P0-2.5）：标记解来源域 —— 调用方（识别日志/结果页）据此知道
        // "这是高密度星表兜底得到的解"，可做更谨慎的记录与呈现。
        return deep?.copy(catalogDomain = CatalogDomain.DEEP) ?: recover()
    }

    /** Additional searches increase opportunities for chance coincidences: require ten inliers
     * and consistency with a trusted input FOV, without weakening the existing acceptance gates.
     */
    internal fun acceptableRecovery(result: LocalMatchResult, fovHintDeg: Double?): Boolean {
        if (result.inlierCount < 10) return false
        if (fovHintDeg == null) return true
        val (lo, hi) = FovEstimate.fovRange(fovHintDeg)
        return maxOf(result.solve.fieldWidthDeg, result.solve.fieldHeightDeg) in lo..hi
    }

    private fun matchInternal(
        ctx: MatchContext,
        detected: List<DetectedStar>,
        width: Int,
        height: Int,
        pointingHint: PointingHint?,
    ): LocalMatchResult? {
        ctx.token?.check()
        val (t1, t2) = voteThresholds(detected.map { it.brightness })
        val tStart = System.currentTimeMillis()
        var msPrimary = 0L; var msMulti = 0L; var msWeak = 0L
        var msBackup = 0L; var msBackupMulti = 0L; var msScored = 0L
        fun formatPhaseTimings(): String =
            "primary=$msPrimary;multi=$msMulti;weak=$msWeak;" +
                "backup=$msBackup;backupMulti=$msBackupMulti;scored=$msScored;" +
                "total=${System.currentTimeMillis() - tStart}"

        // 投票与 parity（镜像）无关：每个阈值只投一次，normal/mirrored 共享结果。
        // （v1.5.8 的双阈值曾按"每 parity × 每阈值"各投一次，投票最多 4 轮，
        //  失败/弱解照片识别耗时从 ~2s 涨到 ~9s；本重构降回最多 2 轮，成功率不变）
        val tA = System.currentTimeMillis()
        val primaryPairs = votePairs(ctx, detected, width, height, t1, pointingHint = pointingHint)
        fun candidate(pairs: List<Pair<Int, StarEntry>>): LocalMatchResult? =
            bestCandidate(detected, width, height, pairs, pointingHint, ctx.catalogMag, ctx.scoredFovRange)
                ?.takeIf { ctx.catalogMag != INTERMEDIATE_CATALOG_MAG || it.inlierCount >= NARROW_SCORED_MIN_REFINED_INLIERS }
        var best = candidate(primaryPairs)
        msPrimary = System.currentTimeMillis() - tA
        if (best?.inlierCount != null && best.inlierCount >= DUAL_THRESHOLD_STRONG_INLIERS) {
            ctx.phaseTimings = formatPhaseTimings()
            return best
        }
        // §0.43 稀疏场重投：单候选轮完全无解时，用每星 top-3 候选重投一轮
        // （仅当无解，有解路径完全不动；伪解由 plausibleFov/skySpan 门槛把守）。
        if (best == null) {
            ctx.token?.check()
            val tB = System.currentTimeMillis()
            best = candidate(
                votePairs(ctx, detected, width, height, t1, multi = true, pointingHint = pointingHint),
            )
            msMulti = System.currentTimeMillis() - tB
            if (best?.inlierCount != null && best.inlierCount >= DUAL_THRESHOLD_STRONG_INLIERS) {
                ctx.phaseTimings = formatPhaseTimings()
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
            ctx.token?.check()
            val tC = System.currentTimeMillis()
            val desc = detected.map { it.brightness }.sortedDescending()
            val anchor20 = desc.getOrElse(19) { 0f }
            val top = desc.firstOrNull() ?: 0f
            // §0.71：下限改为相对值（原 25 @box-blur top≈4154 → 0.0060×top），
            // 否则 SEP flux 量纲下 25 会把弱星轮也堵死；同时钉住绝对上限 25，
            // 避免亮照片（top 上万）把弱星轮下限抬到 77+ 整片切掉（5057/5087 回归实测）
            val weakT = maxOf(minOf(top * WEAK_FLOOR_RATIO, 25f), anchor20 * 0.25f)
            if (weakT < t1) {
                best = candidate(
                    votePairs(ctx, detected, width, height, weakT, pointingHint = pointingHint),
                )
                if (best?.inlierCount != null && best.inlierCount >= DUAL_THRESHOLD_STRONG_INLIERS) {
                    msWeak = System.currentTimeMillis() - tC
                    ctx.phaseTimings = formatPhaseTimings()
                    return best
                }
            }
            msWeak = System.currentTimeMillis() - tC
        }
        if (t2 != t1) {
            ctx.token?.check()
            val tD = System.currentTimeMillis()
            val backupPairs = votePairs(ctx, detected, width, height, t2, pointingHint = pointingHint)
            var alt = candidate(backupPairs)
            msBackup = System.currentTimeMillis() - tD
            if (alt == null) {
                ctx.token?.check()
                val tE = System.currentTimeMillis()
                alt = candidate(
                    votePairs(ctx, detected, width, height, t2, multi = true, pointingHint = pointingHint),
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
            ctx.token?.check()
            val tF = System.currentTimeMillis()
            best = TriangleVoteScorer.scoredRound(ctx, detected, width, height, pointingHint)
            msScored = System.currentTimeMillis() - tF
        }
        ctx.phaseTimings = formatPhaseTimings()
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
        val mag = debugForceCatalogMag ?: PROD_CATALOG_MAG
        val traceCtx = MatchContext(catalogMag = mag)
        for ((tag, t) in listOf("t1" to t1, "t2" to t2)) {
            val pairs = votePairs(traceCtx, detected, width, height, t)
            sb.append("[$tag] pairs=${pairs.size} ")
            for (mx in listOf(false, true)) {
                val r = fitAndVerify(detected, width, height, pairs, mirrorX = mx, mag = mag)
                if (r != null) {
                    sb.append(
                        String.format(
                            java.util.Locale.ROOT,
                            "%s=%d星[ra=%.1f dec=%.1f fov=%.1f] ",
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

    private fun bestCandidate(
        detected: List<DetectedStar>,
        width: Int,
        height: Int,
        pairs: List<Pair<Int, StarEntry>>,
        pointingHint: PointingHint? = null,
        mag: Float = PROD_CATALOG_MAG,
        fovRange: Pair<Double, Double>? = null,
    ): LocalMatchResult? =
        StarFieldFitter.bestCandidate(detected, width, height, pairs, pointingHint, mag, fovRange)

    // ================= 3b. 投票与网格查询委托（TriangleVoteScorer / TriangleStarIndex） =================

    internal fun votePairs(
        detected: List<DetectedStar>,
        width: Int,
        height: Int,
        threshold: Float = voteBrightnessThreshold(detected.map { it.brightness }),
        multi: Boolean = false,
        pointingHint: PointingHint? = null,
        token: SolveToken? = null,
    ): List<Pair<Int, StarEntry>> = votePairs(
        MatchContext(token = token),
        detected, width, height, threshold, multi, pointingHint,
    )

    internal fun votePairs(
        ctx: MatchContext,
        detected: List<DetectedStar>,
        width: Int,
        height: Int,
        threshold: Float = voteBrightnessThreshold(detected.map { it.brightness }),
        multi: Boolean = false,
        pointingHint: PointingHint? = null,
    ): List<Pair<Int, StarEntry>> =
        TriangleVoteScorer.votePairs(ctx, detected, width, height, threshold, multi, pointingHint)

    internal fun triangleKey(a: Int, b: Int, c: Int): Long =
        TriangleStarIndex.triangleKey(a, b, c)

    internal fun catalogVoteKey(photoIndex: Int, id: Int): Long =
        TriangleStarIndex.catalogVoteKey(photoIndex, id)

    internal fun catalogIdFromVote(key: Long): Int =
        TriangleStarIndex.catalogIdFromVote(key)

    internal fun nearestStarInGrid(
        ra: Double,
        dec: Double,
        tolDeg: Double,
        grid: Map<Int, List<StarEntry>> = TriangleStarIndex.catGrid(catalogMag()),
    ): StarEntry? =
        TriangleStarIndex.nearestStarInGrid(ra, dec, tolDeg, grid)

    fun debugVoteTable(
        detected: List<DetectedStar>,
        width: Int,
        height: Int,
        truth: WcsTransform? = null,
    ): String =
        TriangleVoteScorer.debugVoteTable(detected, width, height, catalogMag(), truth)

    // ================= 4. 拟合与验证委托（StarFieldFitter） =================

    internal fun fitPairs(
        detected: List<DetectedStar>,
        pairs: List<Pair<Int, StarEntry>>,
        width: Int,
        height: Int,
        mirrorX: Boolean = false,
        fixedRa0: Double? = null,
        fixedDec0: Double? = null,
    ): StarFieldFitter.FitParams? =
        StarFieldFitter.fitPairs(detected, pairs, width, height, mirrorX, fixedRa0, fixedDec0)

    internal fun buildWcs(
        fp: StarFieldFitter.FitParams,
        width: Int,
        height: Int,
        mirrorX: Boolean = false,
    ): WcsTransform =
        StarFieldFitter.buildWcs(fp, width, height, mirrorX)

    internal fun fitAndVerify(
        detected: List<DetectedStar>,
        width: Int,
        height: Int,
        pairs: List<Pair<Int, StarEntry>>,
        mirrorX: Boolean,
        mag: Float = PROD_CATALOG_MAG,
    ): LocalMatchResult? =
        StarFieldFitter.fitAndVerify(detected, width, height, pairs, mirrorX, mag)

    fun fitSimilarity(
        x: FloatArray,
        y: FloatArray,
        X: FloatArray,
        Y: FloatArray,
    ): DoubleArray? =
        StarFieldGeometry.fitSimilarity(x, y, X, Y)

    internal fun gaussSolveN(m: Array<DoubleArray>): DoubleArray? =
        StarFieldGeometry.gaussSolveN(m)

    // ================= 5. 球面几何工具委托（StarFieldGeometry） =================

    fun angularDist(a: StarEntry, b: StarEntry): Double =
        StarFieldGeometry.angularDist(a, b)

    fun tanXY(ra: Double, dec: Double, ra0: Double, dec0: Double): Pair<Double, Double> =
        StarFieldGeometry.tanXY(ra, dec, ra0, dec0)

    fun tanInverse(xDeg: Double, yDeg: Double, ra0: Double, dec0: Double): Pair<Double, Double> =
        StarFieldGeometry.tanInverse(xDeg, yDeg, ra0, dec0)
}

