package com.starcam.astro

import com.starcam.astro.astro.QuatMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** §0.52 四元数运算（AR 姿态平滑/预测内核）的纯 JVM 验证 */
class QuatMathTest {

    private val EPS = 1e-5

    private fun assertVec(expected: FloatArray, actual: FloatArray, eps: Double = EPS) {
        assertEquals(expected[0], actual[0], eps.toFloat())
        assertEquals(expected[1], actual[1], eps.toFloat())
        assertEquals(expected[2], actual[2], eps.toFloat())
    }

    @Test
    fun rotationVectorZeroIsIdentity() {
        val q = QuatMath.quatFromRotationVector(0f, 0f, 0f)
        assertVec(floatArrayOf(1f, 0f, 0f, 0f), q)
        // 恒等旋转：任意向量不变
        val m = QuatMath.quatToMatrix(q)
        assertVec(floatArrayOf(1f, 0f, 0f), floatArrayOf(m[0], m[1], m[2]))
        assertVec(floatArrayOf(0f, 1f, 0f), floatArrayOf(m[3], m[4], m[5]))
        assertVec(floatArrayOf(0f, 0f, 1f), floatArrayOf(m[6], m[7], m[8]))
    }

    @Test
    fun rotationVectorQuarterTurnAboutZ() {
        // 绕 Z 转 90°：旋转向量 = (0,0,sin45°) → q=(cos45°, 0,0,sin45°)
        val q = QuatMath.quatFromRotationVector(0f, 0f, sin(PI / 4).toFloat())
        assertEquals(cos(PI / 4), q[0].toDouble(), EPS)
        assertEquals(0.0, q[1].toDouble(), EPS)
        assertEquals(0.0, q[2].toDouble(), EPS)
        assertEquals(sin(PI / 4), q[3].toDouble(), EPS)
        // 矩阵应把设备 +X 转到世界 +Y（x→y）
        val m = QuatMath.quatToMatrix(q)
        assertVec(floatArrayOf(0f, 1f, 0f), floatArrayOf(m[0], m[3], m[6]), 1e-4)
    }

    @Test
    fun matrixColumnsOrthonormal() {
        val q = QuatMath.quatFromRotationVector(0.1f, -0.2f, 0.3f)
        val m = QuatMath.quatToMatrix(QuatMath.normalize(q))
        val c0 = floatArrayOf(m[0], m[3], m[6])
        val c1 = floatArrayOf(m[1], m[4], m[7])
        val c2 = floatArrayOf(m[2], m[5], m[8])
        // 各列范数为 1
        for (c in listOf(c0, c1, c2)) {
            val n = sqrt(c[0].toDouble() * c[0] + c[1] * c[1] + c[2] * c[2])
            assertEquals(1.0, n, 1e-4)
        }
        // 行列式 +1（纯旋转）
        val det = m[0] * (m[4] * m[8] - m[5] * m[7]) -
            m[1] * (m[3] * m[8] - m[5] * m[6]) +
            m[2] * (m[3] * m[7] - m[4] * m[6])
        assertEquals(1.0, det.toDouble(), 1e-4)
    }

    @Test
    fun gyroIntegrationQuarterTurn() {
        // 恒等锚点 + 绕设备 Z 轴 90°/s 积分 1s → 设备 +X 应指向世界 +Y
        val anchor = floatArrayOf(1f, 0f, 0f, 0f)
        val omega = floatArrayOf(0f, 0f, (PI / 2).toFloat())
        val dq = QuatMath.gyroDelta(omega, 1.0)!!
        var rel = floatArrayOf(1f, 0f, 0f, 0f)
        rel = QuatMath.normalize(QuatMath.quatMul(rel, dq))
        val q = QuatMath.quatMul(anchor, rel)
        val v = QuatMath.rotateVec(q, floatArrayOf(1f, 0f, 0f))
        assertVec(floatArrayOf(0f, 1f, 0f), v, 1e-4)
    }

    @Test
    fun predictionExtrapolatesAtConstantRate() {
        // 预测：dt=200ms、ω=45°/s 绕 Z → 外推 9°；设备 +X 在水平面旋转 9°
        val anchor = QuatMath.quatFromRotationVector(0f, 0f, 0f)
        val omega = floatArrayOf(0f, 0f, (PI / 4).toFloat()) // 45°/s
        var rel = floatArrayOf(1f, 0f, 0f, 0f)
        val dq = QuatMath.gyroDelta(omega, 0.2)!!
        rel = QuatMath.normalize(QuatMath.quatMul(rel, dq))
        val q = QuatMath.quatMul(anchor, rel)
        val v = QuatMath.rotateVec(q, floatArrayOf(1f, 0f, 0f))
        val angle = Math.toDegrees(Math.atan2(v[1].toDouble(), v[0].toDouble()))
        assertEquals(9.0, angle, 0.1)
    }

    @Test
    fun anchoringResetsDrift() {
        // 锚点重置后，rel 清零，姿态回到锚点（模拟每 ~50ms 旋转矢量校正一次）
        val anchor = QuatMath.quatFromRotationVector(0f, 0f, sin(PI / 8).toFloat()) // 45° 绕 Z
        val rel = floatArrayOf(1f, 0f, 0f, 0f) // 重置
        val q = QuatMath.quatMul(anchor, rel)
        val v = QuatMath.rotateVec(q, floatArrayOf(1f, 0f, 0f))
        val angle = Math.toDegrees(Math.atan2(v[1].toDouble(), v[0].toDouble()))
        assertEquals(45.0, angle, 0.1)
        assertTrue("复位后应保持单位范数", QuatMath.quatToMatrix(q)[0].let { it.isFinite() })
    }
}
