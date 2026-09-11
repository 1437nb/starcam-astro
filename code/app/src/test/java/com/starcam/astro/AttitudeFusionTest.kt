package com.starcam.astro

import com.starcam.astro.astro.AttitudeFusion
import com.starcam.astro.astro.QuatMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * §0.55 AR 抖动修复——互补滤波姿态融合的纯 JVM 验证。
 * 核心不变量：静止时旋转矢量噪声被深度平滑（不硬跳变）；真实转动由陀螺瞬时跟踪。
 */
class AttitudeFusionTest {

    private fun rotationAngle(q: FloatArray): Double {
        // 相对恒等的旋转角
        val w = q[0].coerceIn(-1f, 1f)
        return Math.toDegrees(2.0 * kotlin.math.acos(w.toDouble()))
    }

    /** 单位向量绕 Z 转 angleRad 的旋转矢量（sin(θ/2)·axis） */
    private fun rvOfZ(angleDeg: Double): FloatArray {
        val half = Math.toRadians(angleDeg) / 2.0
        return floatArrayOf(0f, 0f, sin(half).toFloat())
    }

    @Test
    fun stationaryNoiseIsSmoothed() {
        // 静止：旋转矢量在 0° 附近 ±0.3° 等幅交替抖动（零均值，每 50ms）。
        // 首事件会瞬时取噪声值（无先验），故跳过 20 次热身；
        // 用户可感知的"抖动"= 逐帧角度差，稳态应远小于瞬时噪声峰值（理论 ~0.018°）
        val fusion = AttitudeFusion(correctionTimeSec = 0.8)
        var t = 0L
        var prevAngle = 0.0
        var maxJitter = 0.0
        var maxAbs = 0.0
        for (i in 0 until 100) {
            val noise = (if (i % 2 == 0) 1 else -1) * 0.3
            val rv = rvOfZ(noise)
            t += 50_000_000L
            fusion.onRotationVector(rv[0], rv[1], rv[2], t)
            if (i < 60) continue // 热身（瞬态按 τ=0.8s 衰减，3τ≈48 事件）
            fusion.predicted(t, 0.0)?.let { q ->
                val a = rotationAngle(q)
                if (i > 20) {
                    val j = kotlin.math.abs(a - prevAngle)
                    if (j > maxJitter) maxJitter = j
                }
                prevAngle = a
                if (kotlin.math.abs(a) > maxAbs) maxAbs = kotlin.math.abs(a)
            }
        }
        assertTrue(
            "稳态逐帧抖动应远小于噪声峰值（±0.3°），实际 ${"%.3f".format(maxJitter)}°",
            maxJitter < 0.05,
        )
        assertTrue(
            "稳态绝对偏差应很小，实际 ${"%.3f".format(maxAbs)}°",
            maxAbs < 0.05,
        )
    }

    @Test
    fun slowCorrectionConvergesToTruth() {
        // 旋转矢量从 0° 跳变到 5°（真实朝向变化），互补滤波应在 ~2s 内收敛
        val fusion = AttitudeFusion(correctionTimeSec = 0.8)
        var t = 0L
        fusion.onRotationVector(0f, 0f, 0f, t)
        t += 50_000_000L
        val target = rvOfZ(5.0)
        for (i in 0 until 80) { // 4 秒
            fusion.onRotationVector(target[0], target[1], target[2], t)
            t += 50_000_000L
        }
        val q = fusion.predicted(t, 0.0)!!
        val err = kotlin.math.abs(rotationAngle(q) - 5.0)
        assertTrue("慢校正应收敛到真实朝向，误差 ${"%.3f".format(err)}°", err < 0.5)
    }

    @Test
    fun gyroMotionTracksImmediately() {
        // 快速转动：旋转矢量保持旧值（延迟），陀螺 ω=(0,0,π/2 rad/s) 积分 1s → 转 90°
        val fusion = AttitudeFusion(correctionTimeSec = 0.8)
        var t = 0L
        fusion.onRotationVector(0f, 0f, 0f, t)
        val omegaZ = (PI / 2).toFloat()
        val step = 10_000_000L // 10ms 采样
        for (i in 0 until 100) {
            t += step
            fusion.onGyro(0f, 0f, omegaZ, t)
        }
        val q = fusion.predicted(t, 0.0)!!
        val angle = rotationAngle(q)
        assertEquals("陀螺应瞬时跟踪 90° 转动", 90.0, angle, 2.0)
    }

    @Test
    fun predictionExtrapolates() {
        // 静止 + 恒定角速度：外推 200ms（时间戳带外推、predictSec=0）应 +45°/s × 0.2s = 9°
        val fusion = AttitudeFusion()
        var t = 0L
        fusion.onRotationVector(0f, 0f, 0f, t)
        fusion.onGyro(0f, 0f, (PI / 4).toFloat(), t + 5_000_000L)
        val now = t + 5_000_000L
        val current = fusion.predicted(now, 0.0)!!
        val future = fusion.predicted(now + 200_000_000L, 0.0)!!
        val d = kotlin.math.abs(rotationAngle(future) - rotationAngle(current) - 9.0)
        assertTrue("预测外推应 +9°，偏差 ${"%.2f".format(d)}°", d < 0.5)
    }

    @Test
    fun nlerpBasics() {
        val a = floatArrayOf(1f, 0f, 0f, 0f)
        val b = floatArrayOf(0f, 0f, 0f, 1f) // 180° 绕 Z
        val mid = QuatMath.nlerp(a, b, 0.5f)
        // nlerp 应保持单位范数
        val n = kotlin.math.sqrt(
            (mid[0] * mid[0] + mid[1] * mid[1] + mid[2] * mid[2] + mid[3] * mid[3]).toDouble(),
        )
        assertEquals(1.0, n, 1e-5)
        // t=1 应等于 b（双覆盖翻转后）
        val end = QuatMath.nlerp(a, b, 1f)
        assertTrue(kotlin.math.abs(end[0]) < 1e-4 && kotlin.math.abs(end[3]) > 0.99)
        // t=0 应等于 a
        val start = QuatMath.nlerp(a, b, 0f)
        assertTrue(start[0] > 0.99f)
    }
}
