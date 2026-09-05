package com.starcam.astro.astro

import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
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

    /**
     * 地平坐标（高度角/方位角）+ 观测地坐标 + 拍摄时刻 → 赤道坐标（赤经/赤纬）。
     *
     * @param altDeg 高度角（仰角，-90°~+90°，地平线上为正）
     * @param azDeg 真方位角（0°~360°，北为 0°，东为 90°，南为 180°，西为 270°）
     * @param latDeg 观测地纬度（北纬为正）
     * @param lonDeg 观测地经度（东经为正）
     * @param epochSec UTC 秒
     * @return Pair(赤经度 [0, 360), 赤纬度 [-90, 90])
     */
    fun altAzToRaDec(
        altDeg: Double,
        azDeg: Double,
        latDeg: Double,
        lonDeg: Double,
        epochSec: Long,
    ): Pair<Double, Double> {
        val jd = unixSecondsToJd(epochSec.toDouble())
        val lst = lstDeg(jd, lonDeg)

        val altRad = Math.toRadians(altDeg)
        val azRad = Math.toRadians(azDeg)
        val latRad = Math.toRadians(latDeg)

        val sinAlt = sin(altRad)
        val cosAlt = cos(altRad)
        val sinLat = sin(latRad)
        val cosLat = cos(latRad)
        val cosAz = cos(azRad)
        val sinAz = sin(azRad)

        // sin(dec) = sin(lat)*sin(alt) + cos(lat)*cos(alt)*cos(az)
        val sinDec = (sinLat * sinAlt + cosLat * cosAlt * cosAz).coerceIn(-1.0, 1.0)
        val decRad = asin(sinDec)
        val decDeg = Math.toDegrees(decRad)

        // 时角 H：
        // cos(dec)*cos(H) = cos(lat)*sin(alt) - sin(lat)*cos(alt)*cos(az)
        // cos(dec)*sin(H) = -cos(alt)*sin(az)
        val y = -cosAlt * sinAz
        val x = cosLat * sinAlt - sinLat * cosAlt * cosAz
        val hRad = atan2(y, x)
        var hDeg = Math.toDegrees(hRad)
        if (hDeg < 0.0) hDeg += 360.0

        // RA = LST - H
        var raDeg = (lst - hDeg) % 360.0
        if (raDeg < 0.0) raDeg += 360.0

        return raDeg to decDeg
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