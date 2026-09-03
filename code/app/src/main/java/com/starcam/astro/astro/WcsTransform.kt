package com.starcam.astro.astro

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.asin
import kotlin.math.sqrt

/**
 * 简易 FITS TAN（gnomonic）投影 WCS。
 *
 * 从 astrometry.net 返回的 wcs.txt 解析出 CRPIX / CRVAL / CD 矩阵，
 * 提供「天球坐标 → 像素坐标」的正向投影，用于把星座连线与星名叠加到照片上。
 *
 * 像素约定：FITS 坐标 1 起始、y 向上；Android/Compose 屏幕坐标 0 起始、y 向下。
 */
class WcsTransform(
    val crpix1: Double,
    val crpix2: Double,      // 参考像素（FITS，1 起始）
    val crval1: Double,
    val crval2: Double,      // 参考点天球坐标（ra, dec，度，J2000）
    val cd11: Double,
    val cd12: Double,        // CD 矩阵（度/像素）
    val cd21: Double,
    val cd22: Double,
) {

    private val det = cd11 * cd22 - cd12 * cd21
    private val rad = PI / 180.0

    /** 天球坐标（度）→ FITS 像素坐标（1 起始，y 向上）。不可见区域返回 NaN。 */
    fun skyToFitsPixel(raDeg: Double, decDeg: Double): DoubleArray {
        val ra0 = crval1 * rad
        val dec0 = crval2 * rad
        val ra = raDeg * rad
        val dec = decDeg * rad
        val dra = ra - ra0
        val cosDec = cos(dec)
        val sinDec = sin(dec)
        val cosDec0 = cos(dec0)
        val sinDec0 = sin(dec0)

        // 与 astrometry.net util/sip.c 的 tan_xyz2iwc 一致的中间世界坐标：
        //   iwcX = -dot(P,i)/dot(P,r)，其中 i = cross(r,north) = -east，
        //   故 iwcX = +dot(P,east)/dot(P,r)（东为正）
        //   iwcY = dot(P,j)/dot(P,r)（北为正，j = cross(i,r)）
        // iwc 为无量纲比值（≈弧度），此处转成度以便与 CD（度/像素）相乘。
        val cosd = cosDec * cos(dra)
        val denom = sinDec0 * sinDec + cosDec0 * cosd
        if (denom <= 0.0) return doubleArrayOf(Double.NaN, Double.NaN)
        val east = cosDec * sin(dra) / denom            // 东向为正（弧度）
        val north = (cosDec0 * sinDec - sinDec0 * cosd) / denom // 北向为正（弧度）

        // 中间世界坐标（度）
        val xw = east / rad
        val yw = north / rad

        // CD 矩阵求逆：像素偏移 u = CD⁻¹ · w
        val u = (cd22 * xw - cd12 * yw) / det
        val v = (-cd21 * xw + cd11 * yw) / det
        return doubleArrayOf(crpix1 + u, crpix2 + v)
    }

    /** 天球坐标（度）→ Android 屏幕像素（0 起始，y 向下，画布尺寸 width×height） */
    fun skyToScreen(raDeg: Double, decDeg: Double, width: Int, height: Int): FloatArray {
        val (fx, fy) = skyToFitsPixel(raDeg, decDeg)
        return floatArrayOf((fx - 1.0).toFloat(), (height - fy).toFloat())
    }

    /**
     * FITS 像素坐标（1 起始，y 向上）→ 天球坐标（度）。
     * CD 矩阵把像素偏移映到切平面中间世界坐标（度），再做 gnomonic 逆投影。
     * [LocalStarMatcher.buildWcs] 的正向推导与此互逆（§0.27 竖屏换算同样依赖）。
     */
    fun fitsPixelToSky(fx: Double, fy: Double): DoubleArray {
        val u = fx - crpix1
        val v = fy - crpix2
        val xw = cd11 * u + cd12 * v
        val yw = cd21 * u + cd22 * v
        val r = PI / 180.0
        val x = xw * r
        val y = yw * r
        val a0 = crval1 * r
        val d0 = crval2 * r
        val rho = sqrt(x * x + y * y)
        if (rho < 1e-12) return doubleArrayOf(crval1, crval2)
        val c = atan(rho)
        val dec = asin((cos(c) * sin(d0) + y * sin(c) * cos(d0) / rho).coerceIn(-1.0, 1.0))
        val ra = a0 + atan2(x * sin(c), rho * cos(d0) * cos(c) - y * sin(d0) * sin(c))
        var raDeg = ra / r
        raDeg = ((raDeg % 360.0) + 360.0) % 360.0
        return doubleArrayOf(raDeg, dec / r)
    }

    companion object {

        /** 解析 FITS WCS 头文本（astrometry.net 的 wcs.txt），失败返回 null */
        fun parse(wcsText: String): WcsTransform? {
            val kv = HashMap<String, Double>()
            for (line in wcsText.split(Regex("\\r?\\n"))) {
                val t = line.trim()
                if (t.startsWith("CRPIX") || t.startsWith("CRVAL") ||
                    t.startsWith("CD1_") || t.startsWith("CD2_")
                ) {
                    val m = Regex("([A-Z0-9_]+)\\s*=\\s*([-+0-9.eE]+)").find(t)
                    if (m != null) kv[m.groupValues[1]] = m.groupValues[2].toDouble()
                }
            }
            val need = listOf("CRPIX1", "CRPIX2", "CRVAL1", "CRVAL2", "CD1_1", "CD1_2", "CD2_1", "CD2_2")
            if (need.any { it !in kv }) return null
            return WcsTransform(
                crpix1 = kv.getValue("CRPIX1"),
                crpix2 = kv.getValue("CRPIX2"),
                crval1 = kv.getValue("CRVAL1"),
                crval2 = kv.getValue("CRVAL2"),
                cd11 = kv.getValue("CD1_1"),
                cd12 = kv.getValue("CD1_2"),
                cd21 = kv.getValue("CD2_1"),
                cd22 = kv.getValue("CD2_2"),
            )
        }

        /**
         * 由 astrometry.net calibration 字段重建 WCS（CD 矩阵公式与官方源码
         * net/wcs.py + util/sip.c 一致，已经数值验证）：
         *   s = pixscale/3600（度/像素），θ = orientation（度）
         *   parity +1: [cd11,cd12,cd21,cd22] = [ s·cosθ,  s·sinθ, -s·sinθ,  s·cosθ]
         *   parity -1: [cd11,cd12,cd21,cd22] = [-s·cosθ,  s·sinθ,  s·sinθ,  s·cosθ]
         * 参考像素 crpix = (W/2+0.5, H/2+0.5)（FITS 1 起始），即图像中心。
         */
        fun fromCalibration(
            raDeg: Double,
            decDeg: Double,
            orientationDeg: Double,
            pixScaleArcsec: Double,
            parity: Int,
            width: Int,
            height: Int,
        ): WcsTransform {
            val s = pixScaleArcsec / 3600.0
            val th = orientationDeg * PI / 180.0
            val cosTh = cos(th)
            val sinTh = sin(th)
            return if (parity >= 0) {
                WcsTransform(
                    crpix1 = width / 2.0 + 0.5,
                    crpix2 = height / 2.0 + 0.5,
                    crval1 = raDeg,
                    crval2 = decDeg,
                    cd11 = s * cosTh, cd12 = s * sinTh,
                    cd21 = -s * sinTh, cd22 = s * cosTh,
                )
            } else {
                WcsTransform(
                    crpix1 = width / 2.0 + 0.5,
                    crpix2 = height / 2.0 + 0.5,
                    crval1 = raDeg,
                    crval2 = decDeg,
                    cd11 = -s * cosTh, cd12 = s * sinTh,
                    cd21 = s * sinTh, cd22 = s * cosTh,
                )
            }
        }
    }
}
