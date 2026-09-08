package com.starcam.astro

import com.starcam.astro.astro.CameraMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** §0.50 相机几何与曝光辅助计算（Pro 曝光/电子水平仪/FOV 自动标定）的纯 JVM 验证 */
class CameraMathTest {

    private fun rotZ(deg: Double): FloatArray {
        // 绕东北天 U 轴旋转 deg 度（右手系），模拟手机绕视轴滚转后的 right/up 基
        val r = Math.toRadians(deg)
        return floatArrayOf(cos(r).toFloat(), sin(r).toFloat(), 0f)
    }

    @Test
    fun rollZeroWhenWorldUpIsScreenUp() {
        // 相机水平朝北：right=东(1,0,0)，up=天顶(0,0,1) → 世界铅垂恰为屏幕上方向 → roll=0
        val roll = CameraMath.rollDeg(
            right = floatArrayOf(1f, 0f, 0f),
            up = floatArrayOf(0f, 0f, 1f),
        )
        assertEquals(0.0, roll, 1e-9)
    }

    @Test
    fun rollTracksRotationAroundViewAxis() {
        // 视轴 = 正北 (0,1,0)，屏幕上方向从天顶绕视轴滚转 10°/−10°
        // 基向量：right = E·cosθ + U·sinθ，up = U·cosθ − E·sinθ
        for (deg in listOf(10.0, -10.0, 30.0, -45.0)) {
            val r = Math.toRadians(deg)
            val right = floatArrayOf(cos(r).toFloat(), 0f, sin(r).toFloat())
            val up = floatArrayOf(-sin(r).toFloat(), 0f, cos(r).toFloat())
            val roll = CameraMath.rollDeg(right, up)
            assertEquals("滚转 $deg° 应原样返回", deg, roll, 1e-6)
        }
    }

    @Test
    fun horizontalFovKnownValues() {
        // 全画幅 50mm 镜头水平 FOV（36mm 宽）≈ 39.6°
        assertEquals(39.6, CameraMath.horizontalFovDeg(50.0, 36.0), 0.1)
        // 手机主摄典型：焦距 4.3mm、传感器宽 6.4mm → 2·atan(6.4/8.6) ≈ 73.3°
        assertEquals(73.3, CameraMath.horizontalFovDeg(4.3, 6.4), 0.15)
        // 非法输入返回 0
        assertEquals(0.0, CameraMath.horizontalFovDeg(0.0, 6.4), 1e-9)
        assertEquals(0.0, CameraMath.horizontalFovDeg(4.3, -1.0), 1e-9)
    }

    @Test
    fun shutterStopsAndLabels() {
        assertEquals(9, CameraMath.shutterStopsSec.size)
        assertEquals("1/30 s", CameraMath.shutterLabel(1.0 / 30))
        assertEquals("1/8 s", CameraMath.shutterLabel(1.0 / 8))
        assertEquals("1 s", CameraMath.shutterLabel(1.0))
        assertEquals("30 s", CameraMath.shutterLabel(30.0))
        // 纳秒换算
        assertEquals(4_000_000_000L, CameraMath.secToNanos(4.0))
        assertEquals(33_333_333L, CameraMath.secToNanos(1.0 / 30))
    }

    @Test
    fun clampRespectsDeviceRanges() {
        assertEquals(3200, CameraMath.clampIso(6400, 100..3200))
        assertEquals(100, CameraMath.clampIso(50, 100..3200))
        assertEquals(1600, CameraMath.clampIso(1600, null))
        // 设备曝光上限 20s：30s 档被夹紧
        val range = 1_000_000L..20_000_000_000L
        assertEquals(20_000_000_000L, CameraMath.clampExposureNanos(CameraMath.secToNanos(30.0), range))
        assertEquals(CameraMath.secToNanos(0.5), CameraMath.clampExposureNanos(CameraMath.secToNanos(0.5), range))
    }

    @Test
    fun diagonalFovSanity() {
        // 全画幅 50mm 对角线（43.3mm）FOV ≈ 46.8°
        assertEquals(46.8, CameraMath.diagonalFovDeg(50.0, 36.0, 24.0), 0.1)
    }
}
