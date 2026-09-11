package com.starcam.astro.astro

import kotlin.math.exp

/**
 * 姿态互补滤波（§0.55 AR 抖动修复，纯 JVM 可单测）。
 *
 * 旧方案（v1.5.40~43）把旋转矢量事件硬性设为姿态锚点——旋转矢量自身带噪声，
 * 静止时每 ~50ms 姿态被"拽"一下，星图抖动；移动时旋转矢量有延迟，硬锚定还会回拽。
 *
 * 本实现改为**互补滤波**：
 * - 陀螺仪（体坐标右乘积分）负责快速、平滑的运动跟踪；
 * - 旋转矢量只做缓慢的漂移校正：每次事件把运行估计按时间常数 [correctionTimeSec]
 *   （默认 0.8s）nlerp 拉向旋转矢量——静止噪声被深度平滑，真实转动由陀螺瞬时跟随。
 */
class AttitudeFusion(private val correctionTimeSec: Double = 0.8) {

    private var q: FloatArray? = null        // 运行估计（设备 → 世界）
    private val lastOmega = FloatArray(3)    // 最近陀螺角速度（设备系 rad/s）
    private var lastEventNanos = 0L          // 最近一次旋转矢量/陀螺事件
    private var lastRvNanos = 0L             // 最近一次旋转矢量事件
    private var hasEvents = false

    /** 旋转矢量事件（values 为 3 元素 x,y,z） */
    fun onRotationVector(x: Float, y: Float, z: Float, timestampNanos: Long) {
        val qRv = QuatMath.quatFromRotationVector(x, y, z)
        val cur = q
        if (cur == null) {
            q = qRv
        } else {
            val dt = ((timestampNanos - lastRvNanos) / 1e9).coerceIn(0.001, 0.5)
            val alpha = (1.0 - exp(-dt / correctionTimeSec)).toFloat()
            q = QuatMath.nlerp(cur, qRv, alpha)
        }
        lastRvNanos = timestampNanos
        lastEventNanos = timestampNanos
        hasEvents = true
    }

    /** 陀螺仪事件（角速度 rad/s，设备系） */
    fun onGyro(omegaX: Float, omegaY: Float, omegaZ: Float, timestampNanos: Long) {
        val cur = q ?: return
        val dt = (timestampNanos - lastEventNanos) / 1e9
        if (dt in 0.0..0.5) {
            QuatMath.gyroDelta(floatArrayOf(omegaX, omegaY, omegaZ), dt)?.let { dq ->
                q = QuatMath.normalize(QuatMath.quatMul(cur, dq))
            }
        }
        lastOmega[0] = omegaX
        lastOmega[1] = omegaY
        lastOmega[2] = omegaZ
        lastEventNanos = timestampNanos
        hasEvents = true
    }

    /**
     * 预测姿态（设备 → 世界四元数）：运行估计 + 按最新角速度外推 [predictSec]。
     * 无任何事件时返回 null。
     */
    fun predicted(nowNanos: Long, predictSec: Double): FloatArray? {
        val cur = q ?: return null
        if (!hasEvents) return null
        val dt = ((nowNanos - lastEventNanos) / 1e9 + predictSec).coerceIn(0.0, 0.5)
        var out = cur
        QuatMath.gyroDelta(lastOmega, dt)?.let { out = QuatMath.normalize(QuatMath.quatMul(out, it)) }
        return out
    }
}
