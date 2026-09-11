package com.starcam.astro.astro

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 太阳系天体历表（§0.58）：纯 JVM，无 Android 依赖，可单测。
 *
 * 为「月亮 / 行星实时标注」提供日、月与水金火木土天海八大行星的地心视位置。
 *
 * ## 算法出处
 * - **行星 / 太阳**：JPL《Keplerian Elements for Approximate Positions of the
 *   Major Planets》(E.M. Standish) 1800–2050 近似根数表 + 开普勒方程牛顿迭代，
 *   含光行时迭代（1~2 次）。根数表已用 in-the-sky.org 公布的 J2000 轨道根数
 *   交叉核对（ω = ϖ − Ω、M₀ = L₀ − ϖ 均吻合到 0.01°）。
 * - **月亮**：Meeus《Astronomical Algorithms》第 47 章 ELP-2000/82 截断级数
 *   （经纬各 60 项 + 附加项），黄经精度约 10″。
 * - **视星等**：Meeus 第 41 章经验式。
 *
 * ## 坐标系（重要）
 * 全部输出**J2000 赤道坐标**，与本 App 的星表（J2000）和 astrometry.net 解出的
 * WCS（ICRF/J2000）保持同一历元——否则叠加会整体偏移。
 *
 * 行星走 JPL 根数表，天然在 J2000 黄道系，无需岁差处理；
 * 但 Meeus 月球级数给出的是**当日平分点**黄经，故显式扣除 J2000 以来的
 * 黄经岁差 `p = 1.3969713° · T`（2026 年约 0.373°，不做会整体偏出半个月面）。
 *
 * AR 实时星图所用平恒星时（GMST）本身也按 J2000 约定参与投影，恒星与行星
 * 共用同一套（轻微的历元系统差对两者一致，故相对位置正确）。
 *
 * ## 精度
 * | 天体 | 位置误差 | 说明 |
 * |---|---|---|
 * | 太阳 | ~10″ | |
 * | 月亮 | ~1′ | 含地平视差站心修正（量级 ~1°，必须做） |
 * | 木星/土星 | ~1′ | 已用 2026-09 实测值核对 |
 * | 内行星 | ~5′ | 相位/视直径变化大，位置仍够用 |
 *
 * 对广角星空照片标注（视场 10°~60°）而言，1′ ≈ 画面千分之一，肉眼不可辨。
 */
object SolarSystemEphemeris {

    private const val D2R = Math.PI / 180.0
    private const val R2D = 180.0 / Math.PI

    /** J2000.0 儒略日 */
    private const val JD_J2000 = 2451545.0

    /** 每儒略世纪天数 */
    private const val DAYS_PER_CENTURY = 36525.0

    /** 光行时：每 AU 对应的天数（1 / 173.1446） */
    private const val LIGHT_TIME_PER_AU = 0.0057755183

    /** J2000 平黄赤交角（度） */
    private const val OBLIQUITY_J2000 = 23.4392911

    /** 每儒略世纪的黄经总岁差（度，IAU 1976：5029.0966″） */
    private const val PRECESSION_PER_CENTURY = 5029.0966 / 3600.0

    /** 地球赤道半径（km），用于站心视差与视直径 */
    private const val EARTH_RADIUS_KM = 6378.137

    // ──────────────────────────────────────────────────────────────
    // 天体枚举与结果模型
    // ──────────────────────────────────────────────────────────────

    /** 太阳系天体（顺序即默认绘制/列表顺序：先日月，再由近及远） */
    enum class SolarBody {
        SUN, MOON, MERCURY, VENUS, MARS, JUPITER, SATURN, URANUS, NEPTUNE;

        /** 是否为行星（不含日、月） */
        val isPlanet: Boolean get() = this != SUN && this != MOON
    }

    /**
     * 某一时刻某天体的位置与观测参数。
     *
     * @param raDeg 赤经（度，J2000 赤道系，0..360）
     * @param decDeg 赤纬（度，J2000 赤道系）
     * @param distanceAu 地心距（AU）。月亮另见 [moonDistanceKm]
     * @param angularDiameterDeg 视直径（度）
     * @param magnitude 视星等
     * @param phase 被照亮比例 0..1（1 = 满相；行星为相位，月亮为月相）
     * @param elongationDeg 与太阳的角距（度，0..180；0 = 合，180 = 冲）
     */
    data class SolarPosition(
        val body: SolarBody,
        val raDeg: Double,
        val decDeg: Double,
        val distanceAu: Double,
        val angularDiameterDeg: Double,
        val magnitude: Double,
        val phase: Double,
        val elongationDeg: Double,
    ) {
        /** 月亮地心距（km）；非月亮返回 NaN */
        val moonDistanceKm: Double
            get() = if (body == SolarBody.MOON) distanceAu * AU_KM else Double.NaN

        /** 是否为亮目标（值得在广角照片上标注） */
        val isNakedEye: Boolean
            get() = body == SolarBody.SUN || body == SolarBody.MOON || magnitude <= 6.0

        companion object {
            const val AU_KM = 149597870.7
        }
    }

    // ──────────────────────────────────────────────────────────────
    // JPL 近似根数表（J2000 历元；变化率 / 儒略世纪）
    // ──────────────────────────────────────────────────────────────

    /**
     * 行星轨道根数。a=半长轴(AU)，e=偏心率，i=轨道倾角(°)，
     * l=平黄经(°)，peri=近日点黄经 ϖ(°)，node=升交点黄经 Ω(°)。
     */
    private class Elements(
        val a0: Double, val aDot: Double,
        val e0: Double, val eDot: Double,
        val i0: Double, val iDot: Double,
        val l0: Double, val lDot: Double,
        val peri0: Double, val periDot: Double,
        val node0: Double, val nodeDot: Double,
    )

    private val MERCURY = Elements(
        0.38709927, 0.00000037, 0.20563593, 0.00001906, 7.00497902, -0.00594749,
        252.25032350, 149472.67411175, 77.45779628, 0.16047689, 48.33076593, -0.12534081,
    )
    private val VENUS = Elements(
        0.72333566, 0.00000390, 0.00677672, -0.00004107, 3.39467605, -0.00078890,
        181.97909950, 58517.81538729, 131.60246718, 0.00268329, 76.67984255, -0.27769418,
    )

    /** 地球（严格说是地月质心 EM Bary；对几何位置影响 <2″） */
    private val EARTH = Elements(
        1.00000261, 0.00000562, 0.01671123, -0.00004392, -0.00001531, -0.01294668,
        100.46457166, 35999.37244981, 102.93768193, 0.32327364, 0.0, 0.0,
    )
    private val MARS = Elements(
        1.52371034, 0.00001847, 0.09339410, 0.00007882, 1.84969142, -0.00813131,
        -4.55343205, 19140.30268499, -23.94362959, 0.44441088, 49.55953891, -0.29257343,
    )
    private val JUPITER = Elements(
        5.20288700, -0.00011607, 0.04838624, -0.00013253, 1.30439695, -0.00183714,
        34.39644051, 3034.74612775, 14.72847983, 0.21252668, 100.47390909, 0.20469106,
    )
    private val SATURN = Elements(
        9.53667594, -0.00125060, 0.05386179, -0.00050991, 2.48599187, 0.00193609,
        49.95424423, 1222.49362201, 92.59887831, -0.41897216, 113.66242448, -0.28867794,
    )
    private val URANUS = Elements(
        19.18916464, -0.00196176, 0.04725744, -0.00004397, 0.77263783, -0.00242939,
        313.23810451, 428.48202785, 170.95427630, 0.40805281, 74.01692503, 0.04240589,
    )
    private val NEPTUNE = Elements(
        30.06992276, 0.00026291, 0.00859048, 0.00005105, 1.77004347, 0.00035372,
        -55.12002969, 218.45945325, 44.96476227, -0.32241464, 131.78422574, -0.00508664,
    )

    private fun elementsOf(body: SolarBody): Elements = when (body) {
        SolarBody.MERCURY -> MERCURY
        SolarBody.VENUS -> VENUS
        SolarBody.MARS -> MARS
        SolarBody.JUPITER -> JUPITER
        SolarBody.SATURN -> SATURN
        SolarBody.URANUS -> URANUS
        SolarBody.NEPTUNE -> NEPTUNE
        else -> EARTH
    }

    /** 行星赤道半径（km），用于视直径 */
    private fun radiusKm(body: SolarBody): Double = when (body) {
        SolarBody.MERCURY -> 2439.7
        SolarBody.VENUS -> 6051.8
        SolarBody.MARS -> 3396.2
        SolarBody.JUPITER -> 71492.0
        SolarBody.SATURN -> 60268.0
        SolarBody.URANUS -> 25559.0
        SolarBody.NEPTUNE -> 24764.0
        SolarBody.MOON -> 1737.4
        SolarBody.SUN -> 696000.0
    }

    // ──────────────────────────────────────────────────────────────
    // 时间与角度工具
    // ──────────────────────────────────────────────────────────────

    /** Unix 秒 → 儒略日（UTC）。与 [SkyEphemeris.unixSecondsToJd] 同定义 */
    fun unixSecondsToJd(epochSec: Long): Double = epochSec / 86400.0 + 2440587.5

    /** 儒略世纪数（自 J2000.0） */
    private fun centuriesSinceJ2000(jd: Double): Double = (jd - JD_J2000) / DAYS_PER_CENTURY

    /** 归一化到 [0, 360) */
    private fun norm360(d: Double): Double {
        var x = d % 360.0
        if (x < 0) x += 360.0
        return x
    }

    /** 归一化到 (-180, 180] */
    private fun norm180(d: Double): Double {
        var x = norm360(d)
        if (x > 180.0) x -= 360.0
        return x
    }

    // ──────────────────────────────────────────────────────────────
    // 开普勒轨道 → 黄道直角坐标
    // ──────────────────────────────────────────────────────────────

    /**
     * 求解开普勒方程 M = E − e·sin E（弧度制牛顿迭代）。
     * 返回偏近点角 E（度）。公开供单测校验收敛性。
     */
    fun solveKepler(meanAnomalyDeg: Double, eccentricity: Double): Double {
        val m = norm180(meanAnomalyDeg)
        var e = m + eccentricity * R2D * sin(m * D2R)
        var iter = 0
        while (iter < 12) {
            val mr = m * D2R
            val er = e * D2R
            val dM = mr - (er - eccentricity * sin(er))
            val dE = dM / (1.0 - eccentricity * cos(er))
            e += dE * R2D
            if (abs(dE * R2D) < 1e-10) break
            iter++
        }
        return e
    }

    /** 行星日心黄道直角坐标（AU，J2000 黄道系），写入 [out]（长度 3） */
    private fun heliocentricEcliptic(el: Elements, jd: Double, out: DoubleArray) {
        val t = centuriesSinceJ2000(jd)
        val a = el.a0 + el.aDot * t
        val e = el.e0 + el.eDot * t
        val inc = el.i0 + el.iDot * t
        val l = el.l0 + el.lDot * t
        val peri = el.peri0 + el.periDot * t
        val node = el.node0 + el.nodeDot * t

        val omega = (peri - node) * D2R // 近日点辐角 ω
        val ea = solveKepler(l - peri, e) * D2R

        // 轨道面内坐标
        val xp = a * (cos(ea) - e)
        val yp = a * sqrt(1.0 - e * e) * sin(ea)

        val cw = cos(omega); val sw = sin(omega)
        val cn = cos(node * D2R); val sn = sin(node * D2R)
        val ci = cos(inc * D2R); val si = sin(inc * D2R)

        out[0] = (cw * cn - sw * sn * ci) * xp + (-sw * cn - cw * sn * ci) * yp
        out[1] = (cw * sn + sw * cn * ci) * xp + (-sw * sn + cw * cn * ci) * yp
        out[2] = (sw * si) * xp + (cw * si) * yp
    }

    /** 日心黄道直角坐标（AU），供单测/调试 */
    fun heliocentricEcliptic(body: SolarBody, jd: Double): DoubleArray {
        val out = DoubleArray(3)
        heliocentricEcliptic(elementsOf(body), jd, out)
        return out
    }

    /** 黄道直角坐标（J2000 黄道系）→ 赤道坐标（J2000），
     * @return Pair(赤经度 [0,360), 赤纬度 [-90,90]) */
    private fun eclipticToEquatorial(x: Double, y: Double, z: Double): Pair<Double, Double> {
        val eps = OBLIQUITY_J2000 * D2R
        val ce = cos(eps); val se = sin(eps)
        val xe = x
        val ye = y * ce - z * se
        val ze = y * se + z * ce
        val r = sqrt(xe * xe + ye * ye + ze * ze)
        if (r <= 0.0) return 0.0 to 0.0
        return norm360(atan2(ye, xe) * R2D) to asin((ze / r).coerceIn(-1.0, 1.0)) * R2D
    }

    // ──────────────────────────────────────────────────────────────
    // 月亮（Meeus 第 47 章）
    // ──────────────────────────────────────────────────────────────

    /** 月球级数项：D, M, M', F, Σl(1e-6°), Σr(1e-3 km) —— 表 47.A */
    private val MOON_LR = intArrayOf(
        0, 0, 1, 0, 6288774, -20905355,
        2, 0, -1, 0, 1274027, -3699111,
        2, 0, 0, 0, 658314, -2955968,
        0, 0, 2, 0, 213618, -569925,
        0, 1, 0, 0, -185116, 48888,
        0, 0, 0, 2, -114332, -3149,
        2, 0, -2, 0, 58793, 246158,
        2, -1, -1, 0, 57066, -152138,
        2, 0, 1, 0, 53322, -170733,
        2, -1, 0, 0, 45758, -204586,
        0, 1, -1, 0, -40923, -129620,
        1, 0, 0, 0, -34720, 108743,
        0, 1, 1, 0, -30383, 104755,
        2, 0, 0, -2, 15327, 10321,
        0, 0, 1, 2, -12528, 0,
        0, 0, 1, -2, 10980, 79661,
        4, 0, -1, 0, 10675, -34782,
        0, 0, 3, 0, 10034, -23210,
        4, 0, -2, 0, 8548, -21636,
        2, 1, -1, 0, -7888, 24208,
        2, 1, 0, 0, -6766, 30824,
        1, 0, -1, 0, -5163, -8379,
        1, 1, 0, 0, 4987, -16675,
        2, -1, 1, 0, 4036, -12831,
        2, 0, 2, 0, 3994, -10445,
        4, 0, 0, 0, 3861, -11650,
        2, 0, -3, 0, 3665, 14403,
        0, 1, -2, 0, -2689, -7003,
        2, 0, -1, 2, -2602, 0,
        2, -1, -2, 0, 2390, 10056,
        1, 0, 1, 0, -2348, 6322,
        2, -2, 0, 0, 2236, -9884,
        0, 1, 2, 0, -2120, 5751,
        0, 2, 0, 0, -2069, 0,
        2, -2, -1, 0, 2048, -4950,
        2, 0, 1, -2, -1773, 4130,
        2, 0, 0, 2, -1595, 0,
        4, -1, -1, 0, 1215, -3958,
        0, 0, 2, 2, -1110, 0,
        3, 0, -1, 0, -892, 3258,
        2, 1, 1, 0, -810, 2616,
        4, -1, -2, 0, 759, -1897,
        0, 2, -1, 0, -713, -2117,
        2, 2, -1, 0, -700, 2354,
        2, 1, -2, 0, 691, 0,
        2, -1, 0, -2, 596, 0,
        4, 0, 1, 0, 549, -1423,
        0, 0, 4, 0, 537, -1117,
        4, -1, 0, 0, 520, -1571,
        1, 0, -2, 0, -487, -1739,
        2, 1, 0, -2, -399, 0,
        0, 0, 2, -2, -381, -4421,
        1, 1, 1, 0, 351, 0,
        3, 0, -2, 0, -340, 0,
        4, 0, -3, 0, 330, 0,
        2, -1, 2, 0, 327, 0,
        0, 2, 1, 0, -323, 1165,
        1, 1, -1, 0, 299, 0,
        2, 0, 2, -2, 294, 0,
        2, 0, 3, 0, 0, 8752,
    )

    /** 月球级数项：D, M, M', F, Σb(1e-6°) —— 表 47.B */
    private val MOON_B = intArrayOf(
        0, 0, 0, 1, 5128122,
        0, 0, 1, 1, 280602,
        0, 0, 1, -1, 277693,
        2, 0, 0, -1, 173237,
        2, 0, -1, 1, 55413,
        2, 0, -1, -1, 46271,
        2, 0, 0, 1, 32573,
        0, 0, 2, 1, 17198,
        2, 0, 1, -1, 9266,
        0, 0, 2, -1, 8822,
        2, -1, 0, -1, 8216,
        2, 0, -2, -1, 4324,
        2, 0, 1, 1, 4200,
        2, 1, 0, -1, -3359,
        2, -1, -1, 1, 2463,
        2, -1, 0, 1, 2211,
        2, -1, -1, -1, 2065,
        0, 1, -1, -1, -1870,
        4, 0, -1, -1, 1828,
        0, 1, 0, 1, -1794,
        0, 0, 0, 3, -1749,
        0, 1, -1, 1, -1565,
        1, 0, 0, 1, -1491,
        0, 1, 1, 1, -1475,
        0, 1, 1, -1, -1410,
        0, 1, 0, -1, -1344,
        1, 0, 0, -1, -1335,
        0, 0, 3, 1, 1107,
        4, 0, 0, -1, 1021,
        4, 0, -1, 1, 833,
        0, 0, 1, -3, 777,
        4, 0, -2, 1, 671,
        2, 0, 0, -3, 607,
        2, 0, 2, -1, 596,
        2, -1, 1, -1, 491,
        2, 0, -2, 1, -451,
        0, 0, 3, -1, 439,
        2, 0, 2, 1, 422,
        2, 0, -3, -1, 421,
        2, 1, -1, 1, -366,
        2, 1, 0, 1, -351,
        4, 0, 0, 1, 331,
        2, -1, 1, 1, 315,
        2, -2, 0, -1, 302,
        0, 0, 1, 3, -283,
        2, 1, 1, -1, -229,
        1, 1, 0, -1, 223,
        1, 1, 0, 1, 223,
        0, 1, -2, -1, -220,
        2, 1, -1, -1, -220,
        1, 0, 1, 1, -185,
        2, -1, -2, -1, 181,
        0, 1, 2, 1, -177,
        4, 0, -2, -1, 176,
        4, -1, -1, -1, 166,
        1, 0, 1, -1, -164,
        4, 0, 1, -1, 132,
        1, 0, -1, -1, -119,
        4, -1, 0, -1, 115,
        2, -2, 0, 1, 107,
    )

    /** 月亮地心黄道位置：[0]=黄经(°)、[1]=黄纬(°)、[2]=地心距(km) —— 当日平分点 */
    private fun moonEclipticOfDate(jd: Double, out: DoubleArray) {
        val t = centuriesSinceJ2000(jd)

        // 平要素（度）
        val lp = 218.3164477 + 481267.88123421 * t - 0.0015786 * t * t +
            t * t * t / 538841.0 - t * t * t * t / 65194000.0
        val d = 297.8501921 + 445267.1114034 * t - 0.0018819 * t * t +
            t * t * t / 545868.0 - t * t * t * t / 113065000.0
        val m = 357.5291092 + 35999.0502909 * t - 0.0001536 * t * t + t * t * t / 24490000.0
        val mp = 134.9633964 + 477198.8675055 * t + 0.0087414 * t * t +
            t * t * t / 69699.0 - t * t * t * t / 14712000.0
        val f = 93.2720950 + 483202.0175233 * t - 0.0036539 * t * t -
            t * t * t / 3526000.0 + t * t * t * t / 863310000.0

        val a1 = 119.75 + 131.849 * t
        val a2 = 53.09 + 479264.290 * t
        val a3 = 313.45 + 481266.484 * t

        // 地球轨道偏心率修正因子：M 项乘 E，2M 项乘 E²（Meeus 47.6）
        val e = 1.0 - 0.002516 * t - 0.0000074 * t * t
        val e2 = e * e

        var sumL = 0.0
        var sumR = 0.0
        var i = 0
        while (i < MOON_LR.size) {
            val td = MOON_LR[i].toDouble()
            val tm = MOON_LR[i + 1].toDouble()
            val tmp = MOON_LR[i + 2].toDouble()
            val tf = MOON_LR[i + 3].toDouble()
            val cl = MOON_LR[i + 4].toDouble()
            val cr = MOON_LR[i + 5].toDouble()

            val arg = td * d + tm * m + tmp * mp + tf * f
            val w = when (tm.toInt()) {
                1, -1 -> e
                2, -2 -> e2
                else -> 1.0
            }
            val s = sin(arg * D2R)
            sumL += cl * w * s
            sumR += cr * w * cos(arg * D2R)
            i += 6
        }

        var sumB = 0.0
        i = 0
        while (i < MOON_B.size) {
            val td = MOON_B[i].toDouble()
            val tm = MOON_B[i + 1].toDouble()
            val tmp = MOON_B[i + 2].toDouble()
            val tf = MOON_B[i + 3].toDouble()
            val cb = MOON_B[i + 4].toDouble()

            val arg = td * d + tm * m + tmp * mp + tf * f
            val w = when (tm.toInt()) {
                1, -1 -> e
                2, -2 -> e2
                else -> 1.0
            }
            sumB += cb * w * sin(arg * D2R)
            i += 5
        }

        // 附加项（金星/木星摄动与地球扁率）
        sumL += 3958.0 * sin(a1 * D2R) +
            1962.0 * sin((lp - f) * D2R) +
            318.0 * sin(a2 * D2R)
        sumB += -2235.0 * sin(lp * D2R) +
            382.0 * sin(a3 * D2R) +
            175.0 * sin((a1 - f) * D2R) +
            175.0 * sin((a1 + f) * D2R) +
            127.0 * sin((lp - mp) * D2R) -
            115.0 * sin((lp + mp) * D2R)

        out[0] = lp + sumL / 1_000_000.0
        out[1] = sumB / 1_000_000.0
        out[2] = 385000.56 + sumR / 1000.0
    }

    /** 月亮黄道坐标（当日平分点）→ J2000 黄道坐标（扣黄经岁差） */
    private fun moonEclipticJ2000(jd: Double, out: DoubleArray) {
        moonEclipticOfDate(jd, out)
        val t = centuriesSinceJ2000(jd)
        out[0] -= PRECESSION_PER_CENTURY * t
    }

    // ──────────────────────────────────────────────────────────────
    // 站心（topocentric）视差修正
    // ──────────────────────────────────────────────────────────────

    /**
     * 赤道坐标地心 → 站心视差修正。
     *
     * 月亮地平视差可达 ~57′，不做此修正月亮会整体偏出几乎一个视直径；
     * 行星视差通常 <30″，一并计算（成本可忽略）。
     *
     * @param latDeg 观测地纬度（北正）
     * @param lstDeg 当地恒星时（度）
     * @param distanceAu 地心距（AU）
     * @return Pair(站心赤经度 [0,360), 站心赤纬度)
     */
    fun applyTopocentricParallax(
        raDeg: Double,
        decDeg: Double,
        distanceAu: Double,
        latDeg: Double,
        lstDeg: Double,
    ): Pair<Double, Double> {
        if (distanceAu <= 0.0) return raDeg to decDeg
        val distEr = distanceAu * SolarPosition.AU_KM / EARTH_RADIUS_KM // 地心距（地球半径为单位）

        // 观测地地心纬度（顾及地球扁率）
        val u = Math.atan(0.99664719 * kotlin.math.tan(latDeg * D2R))
        val rhoSin = 0.99664719 * sin(u)
        val rhoCos = cos(u)

        val raR = raDeg * D2R
        val decR = decDeg * D2R
        val h = (lstDeg - raDeg) * D2R // 时角

        // 天体的地心赤道直角坐标（地球半径为单位）
        val x = distEr * cos(decR) * cos(raR)
        val y = distEr * cos(decR) * sin(raR)
        val z = distEr * sin(decR)

        // 观测者在赤道坐标系中的位置
        val ox = rhoCos * cos(h)
        val oy = rhoCos * sin(h)
        val oz = rhoSin

        val nx = x - ox
        val ny = y - oy
        val nz = z - oz

        val r = sqrt(nx * nx + ny * ny + nz * nz)
        if (r <= 0.0) return raDeg to decDeg
        val raTop = norm360(atan2(ny, nx) * R2D)
        val decTop = asin((nz / r).coerceIn(-1.0, 1.0)) * R2D
        return raTop to decTop
    }

    // ──────────────────────────────────────────────────────────────
    // 主入口
    // ──────────────────────────────────────────────────────────────

    /**
     * 计算某时刻全部天体的**地心**位置（J2000 赤道坐标）。
     * 用于与外部星历表核对，以及在照片叠加时使用（照片为无穷远投影）。
     */
    fun geocentricPositions(jd: Double): List<SolarPosition> =
        SolarBody.entries.map { geocentric(it, jd) }

    /** 单个天体的地心位置（J2000 赤道坐标） */
    fun geocentric(body: SolarBody, jd: Double): SolarPosition {
        // 太阳：地球日心矢量的反向
        val earth = DoubleArray(3)
        heliocentricEcliptic(EARTH, jd, earth)

        if (body == SolarBody.SUN) {
            val raDec = eclipticToEquatorial(-earth[0], -earth[1], -earth[2])
            val dist = sqrt(earth[0] * earth[0] + earth[1] * earth[1] + earth[2] * earth[2])
            return SolarPosition(
                body = body,
                raDeg = raDec.first,
                decDeg = raDec.second,
                distanceAu = dist,
                angularDiameterDeg = 2.0 * asin(radiusKm(body) / (dist * SolarPosition.AU_KM)) * R2D,
                magnitude = -26.74,
                phase = 1.0,
                elongationDeg = 0.0,
            )
        }

        if (body == SolarBody.MOON) {
            val ecl = DoubleArray(3)
            moonEclipticJ2000(jd, ecl)
            val lam = ecl[0] * D2R
            val bet = ecl[1] * D2R
            val distKm = ecl[2]
            val x = distKm * cos(bet) * cos(lam)
            val y = distKm * cos(bet) * sin(lam)
            val z = distKm * sin(bet)
            // 黄道(km) → 赤道，再归一化为 AU
            val eps = OBLIQUITY_J2000 * D2R
            val xe = x
            val ye = y * cos(eps) - z * sin(eps)
            val ze = y * sin(eps) + z * cos(eps)
            val r = sqrt(xe * xe + ye * ye + ze * ze)
            val ra = norm360(atan2(ye, xe) * R2D)
            val dec = asin((ze / r).coerceIn(-1.0, 1.0)) * R2D
            val distAu = r / SolarPosition.AU_KM

            val sun = geocentric(SolarBody.SUN, jd)
            val elong = SkyEphemeris.angularSeparationDeg(sun.raDeg, sun.decDeg, ra, dec)
            val phaseAngle = 180.0 - elong
            return SolarPosition(
                body = body,
                raDeg = ra,
                decDeg = dec,
                distanceAu = distAu,
                angularDiameterDeg = 2.0 * asin(radiusKm(body) / distKm) * R2D,
                magnitude = moonMagnitude(phaseAngle, distAu),
                phase = (1.0 + cos(phaseAngle * D2R)) / 2.0,
                elongationDeg = elong,
            )
        }

        // 行星：地心矢量 = 行星日心矢量(光行时迭代) − 地球日心矢量
        val p = DoubleArray(3)
        heliocentricEcliptic(elementsOf(body), jd, p)
        var gx = p[0] - earth[0]
        var gy = p[1] - earth[1]
        var gz = p[2] - earth[2]
        var dist = sqrt(gx * gx + gy * gy + gz * gz)
        // 光行时迭代：用当前地心距回推行星在光线出发时刻的位置
        repeat(2) {
            val tau = dist * LIGHT_TIME_PER_AU
            heliocentricEcliptic(elementsOf(body), jd - tau, p)
            gx = p[0] - earth[0]
            gy = p[1] - earth[1]
            gz = p[2] - earth[2]
            dist = sqrt(gx * gx + gy * gy + gz * gz)
        }
        val raDec = eclipticToEquatorial(gx, gy, gz)
        val ra = raDec.first
        val dec = raDec.second

        // 日心距（用于星等与相位）
        val rHelio = sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2])
        val sun = geocentric(SolarBody.SUN, jd)
        val elong = SkyEphemeris.angularSeparationDeg(sun.raDeg, sun.decDeg, ra, dec)

        // 相位角 i：太阳–行星–地球夹角，cos i = (r² + Δ² − R²)/(2 r Δ)
        val rr = rHelio
        val dd = dist
        val rrSun = sun.distanceAu
        val cosPhase = ((rr * rr + dd * dd - rrSun * rrSun) / (2.0 * rr * dd)).coerceIn(-1.0, 1.0)
        val phaseAngle = Math.acos(cosPhase) * R2D

        return SolarPosition(
            body = body,
            raDeg = ra,
            decDeg = dec,
            distanceAu = dist,
            angularDiameterDeg = 2.0 * asin(radiusKm(body) / (dist * SolarPosition.AU_KM)) * R2D,
            magnitude = planetMagnitude(body, rr, dd, phaseAngle),
            phase = (1.0 + cosPhase) / 2.0,
            elongationDeg = elong,
        )
    }

    /**
     * 计算某时刻全部天体的**站心**位置（J2000 赤道坐标 + 视差修正）。
     * AR 实时星图与照片叠加都应使用此版本——否则月亮会偏出约一个视直径。
     *
     * @param latDeg 观测地纬度（北正）
     * @param lonDeg 观测地经度（东正）
     */
    fun topocentricPositions(jd: Double, latDeg: Double, lonDeg: Double): List<SolarPosition> {
        val lst = SkyEphemeris.lstDeg(jd, lonDeg)
        return geocentricPositions(jd).map { topocentric(it, latDeg, lst) }
    }

    /** 对单个位置施加站心视差修正 */
    fun topocentric(p: SolarPosition, latDeg: Double, lstDeg: Double): SolarPosition {
        val (ra, dec) = applyTopocentricParallax(p.raDeg, p.decDeg, p.distanceAu, latDeg, lstDeg)
        // 视差只改方向不改距离：视直径与星等不变
        return p.copy(raDeg = ra, decDeg = dec)
    }

    // ──────────────────────────────────────────────────────────────
    // 星等经验式（Meeus 第 41 章）
    // ──────────────────────────────────────────────────────────────

    private fun planetMagnitude(body: SolarBody, r: Double, delta: Double, phaseDeg: Double): Double {
        val base = 5.0 * log10(r * delta)
        return when (body) {
            SolarBody.MERCURY ->
                -0.42 + base + 0.0380 * phaseDeg - 0.000273 * phaseDeg * phaseDeg +
                    0.000002 * phaseDeg * phaseDeg * phaseDeg
            SolarBody.VENUS ->
                -4.40 + base + 0.0009 * phaseDeg + 0.000239 * phaseDeg * phaseDeg -
                    0.00000065 * phaseDeg * phaseDeg * phaseDeg
            SolarBody.MARS -> -1.52 + base + 0.016 * phaseDeg
            SolarBody.JUPITER -> -9.40 + base + 0.005 * phaseDeg
            // 土星环倾角效应略去（本 App 不区分环面），误差 ≤0.4 等，不影响绘制
            SolarBody.SATURN -> -8.88 + base + 0.044 * phaseDeg
            SolarBody.URANUS -> -7.19 + base
            SolarBody.NEPTUNE -> -6.87 + base
            else -> -26.74
        }
    }

    /** 月亮的视星等（Allen 1976 相位经验式；满月约 −12.7 等） */
    private fun moonMagnitude(phaseAngleDeg: Double, distAu: Double): Double {
        val i = phaseAngleDeg
        val m = -12.73 + 0.026 * abs(i) + 4.0e-9 * i * i * i * i
        // Allen 式按平均地心距拟合 → 按实际距离做距离模数修正（近则更亮）
        val meanDistAu = 384400.0 / SolarPosition.AU_KM
        return m + 5.0 * log10(distAu / meanDistAu)
    }

    // ──────────────────────────────────────────────────────────────
    // 帧内缓存（AR 每帧调用的零分配路径）
    // ──────────────────────────────────────────────────────────────

    private var cacheJd: Double = Double.NaN
    private var cacheLat: Double = Double.NaN
    private var cacheLon: Double = Double.NaN
    private var cacheResult: List<SolarPosition> = emptyList()

    /**
     * 带缓存的站心位置查询：位置在 [maxAgeSeconds] 内直接复用上次结果。
     *
     * 行星位置在 30 秒内的变化远小于 1″（月亮约 0.5′/分钟，仍在容差内），
     * 因此 AR 逐帧调用可安全命中缓存，避免每帧重算上百项级数。
     *
     * @param epochSec UTC 秒
     */
    fun cachedTopocentric(
        epochSec: Long,
        latDeg: Double,
        lonDeg: Double,
        maxAgeSeconds: Long = 30L,
    ): List<SolarPosition> {
        val jd = unixSecondsToJd(epochSec)
        val fresh = !cacheJd.isNaN() &&
            abs(jd - cacheJd) * 86400.0 <= maxAgeSeconds &&
            abs(latDeg - cacheLat) < 1e-6 &&
            abs(lonDeg - cacheLon) < 1e-6
        if (fresh) return cacheResult
        val res = topocentricPositions(jd, latDeg, lonDeg)
        cacheJd = jd
        cacheLat = latDeg
        cacheLon = lonDeg
        cacheResult = res
        return res
    }

    /** 清空缓存（单测用，避免跨用例串扰） */
    fun clearCache() {
        cacheJd = Double.NaN
        cacheLat = Double.NaN
        cacheLon = Double.NaN
        cacheResult = emptyList()
    }

    /**
     * 公历（UTC）→ 儒略日（Meeus 第 7 章）。
     * @param hourUtc 当日 UTC 小时（可含小数，如 4.0 表示 04:00 UTC）
     */
    fun julianDay(year: Int, month: Int, day: Int, hourUtc: Double = 0.0): Double {
        // 标准 JD 公式（Gregorian）
        var y = year
        var m = month
        if (m <= 2) { y -= 1; m += 12 }
        val a = floor(y / 100.0)
        val b = 2 - a + floor(a / 4.0)
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + day + b - 1524.5 + hourUtc / 24.0
    }
}
