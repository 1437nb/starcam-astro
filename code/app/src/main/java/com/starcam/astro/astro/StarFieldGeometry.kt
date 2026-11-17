package com.starcam.astro.astro

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 本地星表匹配器的球面几何、切平面投影（gnomonic TAN）与线性代数基础运算（§0.142 模块化解耦）。
 *
 * 所有计算保持纯 JVM 无状态，坐标统一为 J2000（度）。
 */
internal object StarFieldGeometry {

    fun dist(a: DetectedStar, b: DetectedStar): Float {
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

    /** 球面角距（度），用于网格探测 */
    fun angDist(ra1: Double, dec1: Double, ra2: Double, dec2: Double): Double {
        val r = PI / 180.0
        val a1 = ra1 * r
        val d1 = dec1 * r
        val a2 = ra2 * r
        val d2 = dec2 * r
        val s = sin(d1) * sin(d2) + cos(d1) * cos(d2) * cos(a1 - a2)
        return acos(s.coerceIn(-1.0, 1.0)) * 180.0 / PI
    }

    /** 球面角距（度），haversine：小角度下比 acos 稳健 */
    fun haversineDeg(ra1: Double, dec1: Double, ra2: Double, dec2: Double): Double {
        val r = PI / 180.0
        val sd = sin((dec2 - dec1) * r / 2.0)
        val sra = sin((ra2 - ra1) * r / 2.0)
        val a = sd * sd + cos(dec1 * r) * cos(dec2 * r) * sra * sra
        return 2.0 * asin(sqrt(a.coerceIn(0.0, 1.0))) / r
    }

    /**
     * §0.71c 三颗星在球面上的「向量平均」方向 → (raDeg, decDeg)。
     * 用单位向量相加求方向，天然安全跨越 RA 0°/360° 接缝。
     */
    fun sphericalMean(eA: StarEntry, eB: StarEntry, eC: StarEntry): DoubleArray =
        sphericalMeanOf(listOf(eA, eB, eC))

    /** §0.71e 任意多颗星的球面向量平均（RA 跨 0° 接缝安全） */
    fun sphericalMeanOf(stars: List<StarEntry>): DoubleArray {
        var x = 0.0
        var y = 0.0
        var z = 0.0
        for (e in stars) {
            val r = e.ra * (PI / 180.0)
            val d = e.dec * (PI / 180.0)
            val cd = cos(d)
            x += cd * cos(r)
            y += cd * sin(r)
            z += sin(d)
        }
        val n = sqrt(x * x + y * y + z * z)
        if (stars.isEmpty() || n < 1e-9) {
            // 退化（近似对径或空）：退回第一颗星，避免 NaN 扩散
            return doubleArrayOf(stars[0].ra, stars[0].dec)
        }
        x /= n
        y /= n
        z /= n
        val ra = atan2(y, x) * (180.0 / PI)
        val dec = asin(z.coerceIn(-1.0, 1.0)) * (180.0 / PI)
        return doubleArrayOf((ra + 360.0) % 360.0, dec)
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

    /** §0.71d 三点相似拟合的残差平方和（切平面度²），用于挑选正确奇偶性 */
    fun fitResid(
        f: DoubleArray,
        px: FloatArray,
        py: FloatArray,
        sx: FloatArray,
        sy: FloatArray,
    ): Double {
        val s = f[0]
        val ss = f[1]
        val tx = f[2]
        val ty = f[3]
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
        var sxx = 0.0
        var syy = 0.0
        var sx = 0.0
        var sy = 0.0
        var sXx = 0.0
        var sYy = 0.0
        var sXy = 0.0
        var sYx = 0.0
        var sX = 0.0
        var sY = 0.0
        for (i in 0 until n) {
            val xi = x[i].toDouble()
            val yi = y[i].toDouble()
            val Xi = X[i].toDouble()
            val Yi = Y[i].toDouble()
            sxx += xi * xi
            syy += yi * yi
            sx += xi
            sy += yi
            sXx += Xi * xi
            sYy += Yi * yi
            sXy += Xi * yi
            sYx += Yi * xi
            sX += Xi
            sY += Yi
        }
        val q = sxx + syy
        val m = Array(4) { DoubleArray(5) }
        // 正规方程（模型：X = a·x - b·y + tx ; Y = b·x + a·y + ty）
        m[0][0] = q; m[0][2] = sx; m[0][3] = sy; m[0][4] = sXx + sYy
        m[1][1] = q; m[1][2] = -sy; m[1][3] = sx; m[1][4] = sYx - sXy
        m[2][0] = sx; m[2][1] = -sy; m[2][2] = n.toDouble(); m[2][4] = sX
        m[3][0] = sy; m[3][1] = sx; m[3][3] = n.toDouble(); m[3][4] = sY
        return gaussSolveN(m)
    }

    /** 高斯消元求解 n 元线性方程组（列主元，n×(n+1) 增广矩阵） */
    fun gaussSolveN(m: Array<DoubleArray>): DoubleArray? {
        val n = m.size
        for (col in 0 until n) {
            var pivot = col
            for (row in col + 1 until n) {
                if (abs(m[row][col]) > abs(m[pivot][col])) pivot = row
            }
            if (abs(m[pivot][col]) < 1e-14) return null
            if (pivot != col) {
                val t = m[col]
                m[col] = m[pivot]
                m[pivot] = t
            }
            for (row in 0 until n) {
                if (row == col) continue
                val f = m[row][col] / m[col][col]
                for (c in col..n) m[row][c] -= f * m[col][c]
            }
        }
        return DoubleArray(n) { m[it][n] / m[it][it] }
    }
}
