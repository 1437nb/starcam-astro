package com.starcam.astro.astro

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/**
 * 天顶星象计算（纯 JVM 可单测，无 Android 依赖）。
 *
 * 由观测地坐标（纬度/经度）+ 拍摄时刻（UTC）计算照片拍摄瞬间的
 * 天顶赤经赤纬：
 *   天顶赤纬 = 观测纬度（北正）
 *   天顶赤经 = 当地恒星时 LST（= GMST + 东经）
 *
 * 用于给官方 astrometry 引擎提供"天区先验"（solver_set_radec），
 * 把全天空盲解缩小到拍摄时刻可见的天区，显著加速并降低误匹配。
 */
object SkyEphemeris {

    /** Unix 秒 → 儒略日（UTC） */
    fun unixSecondsToJd(epochSec: Double): Double = epochSec / 86400.0 + 2440587.5

    /**
     * 格林尼治平恒星时 GMST（度，0..360）。
     * 采用 IAU 1982 简化式（精度约 0.1s 量级，对天区先验绰绰有余）：
     *   GMST = 280.46061837 + 360.98564736629·(JD-2451545) + 0.000387933·T² − T³/38710000
     * T 为儒略世纪数（自 J2000.0）。
     */
    fun gmstDeg(jd: Double): Double {
        val t = (jd - 2451545.0) / 36525.0
        var g = 280.46061837 +
            360.98564736629 * (jd - 2451545.0) +
            0.000387933 * t * t -
            t * t * t / 38710000.0
        g = g % 360.0
        if (g < 0) g += 360.0
        return g
    }

    /** 当地恒星时（度，0..360）：[lonDeg] 东经为正 */
    fun lstDeg(jd: Double, lonDeg: Double): Double {
        var l = gmstDeg(jd) + lonDeg
        l = l % 360.0
        if (l < 0) l += 360.0
        return l
    }

    /**
     * 天顶天球坐标。
     * @return Pair(赤经度, 赤纬度)
     */
    fun zenithRaDec(latDeg: Double, lonDeg: Double, epochSec: Long): Pair<Double, Double> {
        val jd = unixSecondsToJd(epochSec.toDouble())
        return lstDeg(jd, lonDeg) to latDeg
    }

    /** 两星天球角距（度） */
    fun angularSeparationDeg(ra1: Double, dec1: Double, ra2: Double, dec2: Double): Double {
        val p1 = Math.toRadians(ra1)
        val d1 = Math.toRadians(dec1)
        val p2 = Math.toRadians(ra2)
        val d2 = Math.toRadians(dec2)
        val cosd = cos(d1) * cos(d2) * cos(p1 - p2) + sin(d1) * sin(d2)
        return Math.toDegrees(acos(cosd.coerceIn(-1.0, 1.0)))
    }
}