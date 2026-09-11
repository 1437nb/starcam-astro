package com.starcam.astro.astro

import kotlin.math.sqrt

/**
 * 四元数运算（§0.52，纯 JVM 可单测，无 Android 依赖）。
 *
 * 约定（与 Android SensorManager 一致）：
 * - 四元数 q = (w, x, y, z)，w 为实部；
 * - q 表示"设备坐标系 → 东北天世界坐标系"的旋转；
 * - 世界坐标系：x=东，y=北，z=天（与现有 DeviceOrientationTracker 的
 *   right/up/axis 提取一致：right = R 第 0 列、up = R 第 1 列、axis = −R 第 2 列）。
 */
object QuatMath {

    /** 旋转向量（TYPE_ROTATION_VECTOR 的 3 元素形式 x,y,z）→ 单位四元数 */
    fun quatFromRotationVector(x: Float, y: Float, z: Float): FloatArray {
        val w2 = 1.0 - x.toDouble() * x - y.toDouble() * y - z.toDouble() * z
        val w = if (w2 > 0.0) sqrt(w2) else 0.0
        return floatArrayOf(w.toFloat(), x, y, z)
    }

    /** 单位四元数乘法 q = a ⊗ b（合成旋转） */
    fun quatMul(a: FloatArray, b: FloatArray): FloatArray {
        val (w1, x1, y1, z1) = a
        val (w2, x2, y2, z2) = b
        return floatArrayOf(
            w1 * w2 - x1 * x2 - y1 * y2 - z1 * z2,
            w1 * x2 + x1 * w2 + y1 * z2 - z1 * y2,
            w1 * y2 - x1 * z2 + y1 * w2 + z1 * x2,
            w1 * z2 + x1 * y2 - y1 * x2 + z1 * w2,
        )
    }

    /** 轴角（[axis] 需为单位向量，[angleRad] 弧度，右手定则）→ 单位四元数 */
    fun quatFromAxisAngle(axis: FloatArray, angleRad: Double): FloatArray {
        val h = angleRad / 2.0
        val s = kotlin.math.sin(h)
        return floatArrayOf(
            kotlin.math.cos(h).toFloat(),
            (axis[0] * s).toFloat(),
            (axis[1] * s).toFloat(),
            (axis[2] * s).toFloat(),
        )
    }

    /** 陀螺仪角速度（设备系，rad/s）与时间增量 dt（秒）→ 增量四元数（体坐标右乘） */
    fun gyroDelta(omega: FloatArray, dtSec: Double): FloatArray? {
        val w = sqrt(
            omega[0].toDouble() * omega[0] +
                omega[1].toDouble() * omega[1] +
                omega[2].toDouble() * omega[2],
        )
        if (w < 1e-8 || dtSec <= 0.0) return null
        return quatFromAxisAngle(
            floatArrayOf((omega[0] / w).toFloat(), (omega[1] / w).toFloat(), (omega[2] / w).toFloat()),
            w * dtSec,
        )
    }

    /** 单位四元数 → 旋转矩阵（行主序 9 元素；与 SensorManager.getRotationMatrixFromVector 同式） */
    fun quatToMatrix(q: FloatArray): FloatArray {
        val (w, x, y, z) = q
        val xx = x * x; val yy = y * y; val zz = z * z
        val xy = x * y; val xz = x * z; val yz = y * z
        val wx = w * x; val wy = w * y; val wz = w * z
        return floatArrayOf(
            1 - 2 * (yy + zz), 2 * (xy - wz), 2 * (xz + wy),
            2 * (xy + wz), 1 - 2 * (xx + zz), 2 * (yz - wx),
            2 * (xz - wy), 2 * (yz + wx), 1 - 2 * (xx + yy),
        )
    }

    /** 单位四元数旋转向量 v（v' = R·v） */
    fun rotateVec(q: FloatArray, v: FloatArray): FloatArray {
        val r = quatToMatrix(q)
        return floatArrayOf(
            r[0] * v[0] + r[1] * v[1] + r[2] * v[2],
            r[3] * v[0] + r[4] * v[1] + r[5] * v[2],
            r[6] * v[0] + r[7] * v[1] + r[8] * v[2],
        )
    }

    /** 归一化（数值安全） */
    fun normalize(q: FloatArray): FloatArray {
        val n = sqrt(q[0].toDouble() * q[0] + q[1].toDouble() * q[1] +
            q[2].toDouble() * q[2] + q[3].toDouble() * q[3])
        if (n < 1e-9) return floatArrayOf(1f, 0f, 0f, 0f)
        return floatArrayOf((q[0] / n).toFloat(), (q[1] / n).toFloat(), (q[2] / n).toFloat(), (q[3] / n).toFloat())
    }

    /**
     * 归一化线性插值（nlerp）：小角度下等价于 slerp 且无三角函数开销。
     * t ∈ [0,1]；用于互补滤波把当前姿态缓慢拉向旋转矢量（§0.55 抖动修复）。
     */
    fun nlerp(a: FloatArray, b: FloatArray, t: Float): FloatArray {
        // 处理双覆盖（q 与 -q 同旋转）：点积为负时翻转 b
        val dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]
        val flip = if (dot < 0f) -1f else 1f
        val tClamped = t.coerceIn(0f, 1f)
        return normalize(
            floatArrayOf(
                a[0] + (b[0] * flip - a[0]) * tClamped,
                a[1] + (b[1] * flip - a[1]) * tClamped,
                a[2] + (b[2] * flip - a[2]) * tClamped,
                a[3] + (b[3] * flip - a[3]) * tClamped,
            ),
        )
    }
}
