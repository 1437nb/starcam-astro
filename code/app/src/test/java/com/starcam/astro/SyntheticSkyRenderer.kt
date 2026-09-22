package com.starcam.astro

import com.starcam.astro.astro.StarCatalogData
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * 合成星空渲染器（测试工具，**不读任何外部素材**）。
 *
 * 把星表按**已知的** TAN WCS 渲染成灰度图，供两条测试路径共用：
 *
 * - `SyntheticMidFieldTest`：与 DSS 实拍对照，用于**归因** —— 实拍失败的场，
 *   到底是「匹配器不行」还是「素材不行」；
 * - `SyntheticRegressionTest`：纯合成真值回归，覆盖宽场与中场，**在 CI 中照常执行**，
 *   作为本机真值回归台（依赖不入库素材、被 `skipPhotoTests` 跳过）的降级保护。
 *
 * 渲染约定（与 DSS 一致）：北上天左，像素中心即 (ra0, dec0)。
 * 高斯 PSF 半径按星等放大，另加 σ=2 的读出噪声 —— 让检测器的阈值行为与实拍同量级。
 */
object SyntheticSkyRenderer {

    /** 渲染结果：灰度图 / 实际渲染星数 / 星位列表（data class 以便调用方解构） */
    data class Rendered(
        val gray: FloatArray,
        val starCount: Int,
        val positions: List<Pair<Double, Double>>,
    )

    /**
     * 标准 gnomonic(TAN) 投影：返回画面像素坐标，越界返回 null。
     * 约定北上天左（与 DSS 的 CDELT1<0 一致），但匹配器对旋转不敏感，
     * 故此约定只影响自检的可读性。
     */
    fun project(
        ra: Double, dec: Double, ra0: Double, dec0: Double, pxDeg: Double, w: Int, h: Int,
    ): Pair<Double, Double>? {
        val d = (ra - ra0) * PI / 180
        val d0 = dec0 * PI / 180
        val dd = dec * PI / 180
        val denom = sin(d0) * sin(dd) + cos(d0) * cos(dd) * cos(d)
        if (denom <= 1e-9) return null // 投影奇点（对径点）
        val x = cos(dd) * sin(d) / denom                                 // 东向，弧度
        val y = (cos(d0) * sin(dd) - sin(d0) * cos(dd) * cos(d)) / denom // 北向，弧度
        val px = w / 2.0 - x / (pxDeg * PI / 180.0)
        val py = h / 2.0 - y / (pxDeg * PI / 180.0)
        if (px < 2 || py < 2 || px > w - 3 || py > h - 3) return null
        return px to py
    }

    /** 按星等给高斯半径：亮星大一点，模拟 DSS 重采样后的点扩散函数 */
    fun sigmaForMag(mag: Double): Double = 1.0 + 0.12 * (6.5 - mag).coerceIn(0.0, 5.0)

    /**
     * 可复现的伪随机（xorshift），避免依赖 JDK 版本差异。
     *
     * 注意这是 object 级可变状态：每次 [render] 开头会重置，因此同一次调用内可复现；
     * 但**不同测试类不要并行调用** [render]（Gradle 默认 `maxParallelForks=1`，
     * 同 JVM 内串行，当前安全）。若将来开启测试并行，需把状态改为传入的种子对象。
     */
    private var rngState = 0x9E3779B97F4A7C15uL.toLong()

    private fun noise(): Double {
        rngState = rngState xor (rngState shl 13)
        rngState = rngState xor (rngState ushr 7)
        rngState = rngState xor (rngState shl 17)
        return (rngState ushr 11).toDouble() / 9007199254740992.0 - 0.5
    }

    /**
     * 渲染合成图。
     *
     * 振幅模型：最亮星 250，按 0.35 dex/星等 的斜率衰减，mag 6.5 的暗星约 25 ——
     * 仍在检测阈值的 3 倍以上（阈值 max(8, 3.5σ)）。斜率刻意取平：本渲染器要问的是
     * 「这些星表星在不在、够不够解」，不是复现 DSS 的动态范围压缩；压得太陡
     * （0.9 dex/mag 的初版）会让暗星全部低于阈值，合成图 det=0，A/B 直接失效。
     * 另加 σ=2 的读出噪声，让检测器的阈值行为与实拍同量级。
     */
    fun render(
        ra0: Double, dec0: Double, fov: Double, px: Int, magLimit: Double, withNoise: Boolean = true,
    ): Rendered {
        val pxDeg = fov / px
        val gray = FloatArray(px * px)
        val flux = StarCatalogData.stars.filter { it.mag <= magLimit }
        val minMag = flux.minOfOrNull { it.mag } ?: 0.0
        var count = 0
        val positions = ArrayList<Pair<Double, Double>>()
        for (s in flux) {
            val p = project(s.ra, s.dec, ra0, dec0, pxDeg, px, px) ?: continue
            val amp = 250.0 * Math.exp(-0.35 * (s.mag - minMag))
            val sg = sigmaForMag(s.mag)
            val r = (sg * 3).toInt() + 1
            val x0 = (p.first - r).toInt().coerceAtLeast(0)
            val x1 = (p.first + r).toInt().coerceAtMost(px - 1)
            val y0 = (p.second - r).toInt().coerceAtLeast(0)
            val y1 = (p.second + r).toInt().coerceAtMost(px - 1)
            val inv = 1.0 / (2.0 * sg * sg)
            for (y in y0..y1) {
                for (x in x0..x1) {
                    val dx = x - p.first
                    val dy = y - p.second
                    val v = amp * Math.exp(-(dx * dx + dy * dy) * inv)
                    val i = y * px + x
                    if (v > gray[i]) gray[i] = v.toFloat()
                }
            }
            count++
            positions.add(p)
        }
        if (withNoise) {
            rngState = 0x9E3779B97F4A7C15uL.toLong()
            for (i in gray.indices) {
                gray[i] = (gray[i] + 2.0 * noise()).toFloat().coerceIn(0f, 255f)
            }
        }
        return Rendered(gray, count, positions)
    }
}
