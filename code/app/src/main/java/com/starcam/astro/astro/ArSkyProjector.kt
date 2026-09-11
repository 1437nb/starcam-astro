package com.starcam.astro.astro

/**
 * AR 实时星图投影引擎（§0.49）。
 *
 * 原理：星表 (α,δ) 预计算为天球单位向量；每帧把"赤道坐标系 → 东北天坐标系"
 * 旋转矩阵 A（由恒星时 LST 与纬度 φ 构成）与"东北天 → 相机坐标系"基矩阵 C
 * （来自 DeviceOrientationTracker 的旋转矩阵列向量）复合为 M = C·A，
 * 对每颗星做针孔投影 screen = f·(v·right, v·up)/(v·axis)。每星仅 9 次乘加，
 * 约 2000 颗星单帧耗时可忽略，可在主线程 60fps 运行。
 *
 * 输出类型与 [StarChartOverlay] 完全一致（Star2D/Line2D/Messier2D），
 * 可直接交给 [LayeredRenderer] 绘制，保证与结果页渲染样式统一。
 *
 * §0.53：`enuToCam` 返回世界铅垂分量，投影处写入 belowHorizon（全天星空变暗）。
 */
object ArSkyProjector {

    /** AR 星等下限：亮于等于该值的星参与投影（5.2 ≈ 全天约 2000 颗，肉眼极限附近） */
    const val AR_MAG_LIMIT = 5.2

    /** 恒星/天体单位向量（3n），与 [entries]/[messierList] 一一对应 */
    private val starVec: FloatArray
    val entries: List<StarEntry>
    private val messierVec: FloatArray
    val messierList: List<MessierObject>

    init {
        val vs = ArrayList<Float>(StarCatalogData.stars.size * 3)
        val es = ArrayList<StarEntry>()
        for (s in StarCatalogData.stars) {
            if (s.mag > AR_MAG_LIMIT) continue
            val d = Math.PI / 180.0
            val cd = kotlin.math.cos(s.dec * d)
            vs += (cd * kotlin.math.cos(s.ra * d)).toFloat()
            vs += (cd * kotlin.math.sin(s.ra * d)).toFloat()
            vs += kotlin.math.sin(s.dec * d).toFloat()
            es += s
        }
        starVec = vs.toFloatArray()
        entries = es

        val vm = ArrayList<Float>(MessierCatalog.byNumber.size * 3)
        val ms = ArrayList<MessierObject>()
        for (m in MessierCatalog.byNumber.values) {
            val d = Math.PI / 180.0
            val cd = kotlin.math.cos(m.dec * d)
            vm += (cd * kotlin.math.cos(m.ra * d)).toFloat()
            vm += (cd * kotlin.math.sin(m.ra * d)).toFloat()
            vm += kotlin.math.sin(m.dec * d).toFloat()
            ms += m
        }
        messierVec = vm.toFloatArray()
        messierList = ms
    }

    /** 天球单位向量：赤经赤纬（度）→ (x,y,z) */
    fun unitVector(raDeg: Double, decDeg: Double): FloatArray {
        val d = Math.PI / 180.0
        val cd = kotlin.math.cos(decDeg * d)
        return floatArrayOf(
            (cd * kotlin.math.cos(raDeg * d)).toFloat(),
            (cd * kotlin.math.sin(raDeg * d)).toFloat(),
            kotlin.math.sin(decDeg * d).toFloat(),
        )
    }

    // 连线可见性索引（主线程逐帧复用，避免每帧分配）
    private val visibleIdx = IntArray(StarCatalogData.stars.size)

    // §0.54b：projectPoint 独立 scratch（与 project() 局部数组不共享，主线程专用，零每帧分配）
    private val ptScratchA = FloatArray(9)
    private val ptScratchC = FloatArray(3)

    /**
     * §0.54b 找星导航：把目标天球方向投影到屏幕（与 [project] 同一套针孔/校准数学，
     * 但不触碰 project() 主路径；零每帧分配——scratch 数组复用，结果写入 [out]）。
     *
     * @return true 表示目标在视锥内（out[0]=sx, out[1]=sy, out[2]=z>0.08）；
     *         false 表示在身后/视锥外（相机背面）。
     */
    fun projectPoint(
        raDeg: Double,
        decDeg: Double,
        right: FloatArray,
        up: FloatArray,
        axis: FloatArray,
        lstDeg: Double,
        latDeg: Double,
        fovDeg: Double,
        widthPx: Float,
        heightPx: Float,
        correctionVec: FloatArray?,
        out: FloatArray,
    ): Boolean {
        if (widthPx <= 0f || heightPx <= 0f) return false
        val d = Math.PI / 180.0
        val sl = kotlin.math.sin(lstDeg * d).toFloat()
        val cl = kotlin.math.cos(lstDeg * d).toFloat()
        val sp = kotlin.math.sin(latDeg * d).toFloat()
        val cp = kotlin.math.cos(latDeg * d).toFloat()
        val a = ptScratchA
        a[0] = -sl; a[1] = cl; a[2] = 0f          // E
        a[3] = -sp * cl; a[4] = -sp * sl; a[5] = cp  // N
        a[6] = cp * cl; a[7] = cp * sl; a[8] = sp    // U

        val halfFov = Math.toRadians(fovDeg.coerceIn(5.0, 170.0) / 2.0)
        val f = (widthPx / 2f) / kotlin.math.tan(halfFov).toFloat()
        val cx = widthPx / 2f
        val cy = heightPx / 2f

        // 校准偏移（与 project() 同式）
        var offX = 0f
        var offY = 0f
        if (correctionVec != null) {
            val c = ptScratchC
            val e0 = a[0] * correctionVec[0] + a[1] * correctionVec[1] + a[2] * correctionVec[2]
            val n0 = a[3] * correctionVec[0] + a[4] * correctionVec[1] + a[5] * correctionVec[2]
            val u0 = a[6] * correctionVec[0] + a[7] * correctionVec[1] + a[8] * correctionVec[2]
            c[0] = e0 * right[0] + n0 * right[1] + u0 * right[2]
            c[1] = e0 * up[0] + n0 * up[1] + u0 * up[2]
            c[2] = e0 * axis[0] + n0 * axis[1] + u0 * axis[2]
            if (c[2] > 0.05f) {
                offX = cx - (cx + f * c[0] / c[2])
                offY = cy - (cy - f * c[1] / c[2])
            }
        }

        // 目标天球向量 → 东北天 → 相机坐标（内联，零分配）
        val cd = kotlin.math.cos(decDeg * d).toFloat()
        val vx = (cd * kotlin.math.cos(raDeg * d)).toFloat()
        val vy = (cd * kotlin.math.sin(raDeg * d)).toFloat()
        val vz = kotlin.math.sin(decDeg * d).toFloat()
        val c = ptScratchC
        val e = a[0] * vx + a[1] * vy + a[2] * vz
        val n = a[3] * vx + a[4] * vy + a[5] * vz
        val u = a[6] * vx + a[7] * vy + a[8] * vz
        c[0] = e * right[0] + n * right[1] + u * right[2]
        c[1] = e * up[0] + n * up[1] + u * up[2]
        c[2] = e * axis[0] + n * axis[1] + u * axis[2]
        if (c[2] <= 0.08f) return false
        out[0] = cx + f * c[0] / c[2] + offX
        out[1] = cy - f * c[1] / c[2] + offY
        out[2] = c[2]
        return true
    }

    /**
     * 投影一帧星空到 [out]（调用方复用容器，避免每帧分配）。
     *
     * @param right/up/axis 相机三轴在东北天坐标系的单位向量（axis = 光轴指向）
     * @param lstDeg 当地恒星时（度）
     * @param latDeg 纬度（度，北正）
     * @param fovDeg 水平视场（度）
     * @param correctionVec 已解中心的天球单位向量（非空时按其屏幕位置整体平移，
     *   把真解中心对准画面中心，修正磁力计漂移）
     * @param out 复用的输出容器
     * @param solarPositions §0.58 太阳系天体站心位置（月亮/行星）。为空则不投影
     *   （调用方用 [SolarSystemEphemeris.cachedTopocentric] 取，30 秒内命中缓存，
     *   不会给逐帧路径带来重算开销）。放在参数表末尾以保持既有位置调用不变。
     */
    fun project(
        right: FloatArray,
        up: FloatArray,
        axis: FloatArray,
        lstDeg: Double,
        latDeg: Double,
        fovDeg: Double,
        widthPx: Float,
        heightPx: Float,
        correctionVec: FloatArray?,
        out: ProjectedSky,
        solarPositions: List<SolarSystemEphemeris.SolarPosition>? = null,
    ) {
        out.reset(widthPx, heightPx)
        if (widthPx <= 0f || heightPx <= 0f) return

        // 赤道 → 东北天旋转矩阵 A（行主序 3×3，行即 E/N/U 的系数）
        val d = Math.PI / 180.0
        val sl = kotlin.math.sin(lstDeg * d).toFloat()
        val cl = kotlin.math.cos(lstDeg * d).toFloat()
        val sp = kotlin.math.sin(latDeg * d).toFloat()
        val cp = kotlin.math.cos(latDeg * d).toFloat()
        val a = floatArrayOf(
            -sl, cl, 0f,          // E
            -sp * cl, -sp * sl, cp,  // N
            cp * cl, cp * sl, sp,    // U
        )

        // 视锥参数
        val halfFov = Math.toRadians(fovDeg.coerceIn(5.0, 170.0) / 2.0)
        val f = (widthPx / 2f) / kotlin.math.tan(halfFov).toFloat()
        val cx = widthPx / 2f
        val cy = heightPx / 2f

        fun enuToCam(v: FloatArray, result: FloatArray): Float {
            // A·v → 东北天；再与相机基点积 → 相机坐标 (x右, y上, z前)
            // 返回值 = 世界铅垂分量 u（<0 表示地平线以下，§0.53 全天星空变暗）
            val e = a[0] * v[0] + a[1] * v[1] + a[2] * v[2]
            val n = a[3] * v[0] + a[4] * v[1] + a[5] * v[2]
            val u = a[6] * v[0] + a[7] * v[1] + a[8] * v[2]
            result[0] = e * right[0] + n * right[1] + u * right[2]
            result[1] = e * up[0] + n * up[1] + u * up[2]
            result[2] = e * axis[0] + n * axis[1] + u * axis[2]
            return u
        }

        // 校准偏移：真解中心应落在画面中心，求其当前屏幕位与中心的差
        var offX = 0f
        var offY = 0f
        if (correctionVec != null) {
            val c = FloatArray(3)
            enuToCam(correctionVec, c)
            if (c[2] > 0.05f) {
                offX = cx - (cx + f * c[0] / c[2])
                offY = cy - (cy - f * c[1] / c[2])
            }
        }

        // 恒星
        val cam = FloatArray(3)
        val nStars = entries.size
        for (i in 0 until nStars) {
            val o = i * 3
            cam[0] = starVec[o]; cam[1] = starVec[o + 1]; cam[2] = starVec[o + 2]
            val worldU = enuToCam(cam, cam)
            val z = cam[2]
            if (z <= 0.08f) continue // 身后或贴边剔除（约 >85° 视锥外）
            val sx = cx + f * cam[0] / z + offX
            val sy = cy - f * cam[1] / z + offY
            val margin = 60f
            if (sx < -margin || sx > widthPx + margin || sy < -margin || sy > heightPx + margin) continue
            out.stars += StarChartOverlay.Star2D(
                entries[i], sx, sy, visible = true, belowHorizon = worldU < 0f,
            )
        }

        // 星座连线：两端点均可见才保留（按目录下标 O(1) 查屏幕坐标）
        // visibleIdx 复用（主线程逐帧调用，避免每帧分配 8419 数组）。
        // 必须用 -1 填充重置：IntArray 默认 0 是合法槽位号，若清成 0，
        // 所有"一端在视野外"的连线都会被错误连到第 0 颗可见星 → 射线扇面（v1.5.37 修复）
        java.util.Arrays.fill(visibleIdx, -1)
        for ((slot, s2) in out.stars.withIndex()) {
            val idx = StarCatalogData.indexOfHip(s2.entry.hip)
            if (idx >= 0) visibleIdx[idx] = slot
        }
        for (seg in StarCatalogData.constellationLines) {
            val ia = visibleIdx[seg[0]]
            val ib = visibleIdx[seg[1]]
            if (ia >= 0 && ib >= 0) out.lines += StarChartOverlay.Line2D(out.stars[ia], out.stars[ib])
        }

        // 梅西耶深空天体
        for (i in messierList.indices) {
            val o = i * 3
            cam[0] = messierVec[o]; cam[1] = messierVec[o + 1]; cam[2] = messierVec[o + 2]
            val worldU = enuToCam(cam, cam)
            val z = cam[2]
            if (z <= 0.08f) continue
            val sx = cx + f * cam[0] / z + offX
            val sy = cy - f * cam[1] / z + offY
            val margin = 40f
            if (sx < -margin || sx > widthPx + margin || sy < -margin || sy > heightPx + margin) continue
            out.messier += StarChartOverlay.Messier2D(
                messierList[i], sx, sy, visible = true, belowHorizon = worldU < 0f,
            )
        }

        // §0.58 太阳系天体（月亮 / 行星）：与恒星同一套针孔投影与地平线判定
        if (solarPositions != null) {
            val dd = Math.PI / 180.0
            for (p in solarPositions) {
                val cdd = kotlin.math.cos(p.decDeg * dd).toFloat()
                cam[0] = (cdd * kotlin.math.cos(p.raDeg * dd)).toFloat()
                cam[1] = (cdd * kotlin.math.sin(p.raDeg * dd)).toFloat()
                cam[2] = kotlin.math.sin(p.decDeg * dd).toFloat()
                val worldU = enuToCam(cam, cam)
                val z = cam[2]
                if (z <= 0.08f) continue
                val sx = cx + f * cam[0] / z + offX
                val sy = cy - f * cam[1] / z + offY
                val margin = 80f
                if (sx < -margin || sx > widthPx + margin ||
                    sy < -margin || sy > heightPx + margin
                ) {
                    continue
                }
                out.solar += StarChartOverlay.Solar2D(p, sx, sy, visible = true, belowHorizon = worldU < 0f)
            }
        }

        // 星座名标签（质心，直接复用 StarChartOverlay 的分组逻辑）
        out.labels += StarChartOverlay.constellationLabels(out.stars)
    }

    /** 单帧投影结果（可复用容器） */
    class ProjectedSky {
        val stars = ArrayList<StarChartOverlay.Star2D>(256)
        val lines = ArrayList<StarChartOverlay.Line2D>(48)
        val labels = ArrayList<StarChartOverlay.Label2D>(8)
        val messier = ArrayList<StarChartOverlay.Messier2D>(16)
        /** §0.58 太阳系天体（最多 9 个，容量留足避免扩容） */
        val solar = ArrayList<StarChartOverlay.Solar2D>(9)
        var widthPx: Float = 0f
            private set
        var heightPx: Float = 0f
            private set

        fun reset(w: Float, h: Float) {
            stars.clear(); lines.clear(); labels.clear(); messier.clear(); solar.clear()
            widthPx = w; heightPx = h
        }
    }
}
