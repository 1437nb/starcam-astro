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

    @Test
    fun rotateBasisAroundUpPreservesZAndRotatesHorizon() {
        // 初始基：正北指向 (axis=(0,1,0), right=(1,0,0), up=(0,0,1))
        val right = floatArrayOf(1f, 0f, 0f)
        val up = floatArrayOf(0f, 0f, 1f)
        val axis = floatArrayOf(0f, 1f, 0f)

        // 顺时针旋转 90°（由北向东）：axis 应变成正东 (1,0,0)，right 应变成正南 (0,-1,0)，up 保持 (0,0,1)
        val oR = FloatArray(3)
        val oU = FloatArray(3)
        val oA = FloatArray(3)
        CameraMath.rotateBasisAroundUp(right, up, axis, 90.0, oR, oU, oA)

        assertEquals("East component of rotated axis", 1.0, oA[0].toDouble(), 1e-6)
        assertEquals("North component of rotated axis", 0.0, oA[1].toDouble(), 1e-6)
        assertEquals("Up component of rotated axis", 0.0, oA[2].toDouble(), 1e-6)

        assertEquals("East component of rotated right", 0.0, oR[0].toDouble(), 1e-6)
        assertEquals("North component of rotated right", -1.0, oR[1].toDouble(), 1e-6)
        assertEquals("Up component of rotated right", 0.0, oR[2].toDouble(), 1e-6)

        assertEquals("Up component unchanged", 1.0, oU[2].toDouble(), 1e-6)

        // 验证铅垂不变性与滚转角绝对不变：任意角度旋转后 rollDeg 完全一致
        for (deg in listOf(-12.5, 0.0, 45.0, 88.0, 180.0, -90.0)) {
            val r2 = FloatArray(3); val u2 = FloatArray(3); val a2 = FloatArray(3)
            CameraMath.rotateBasisAroundUp(right, up, axis, deg, r2, u2, a2)
            assertEquals("Z of right invariant", right[2], r2[2], 1e-6f)
            assertEquals("Z of up invariant", up[2], u2[2], 1e-6f)
            assertEquals("Z of axis invariant", axis[2], a2[2], 1e-6f)
            assertEquals("Roll angle invariant", CameraMath.rollDeg(right, up), CameraMath.rollDeg(r2, u2), 1e-6)
        }
    }

    @Test
    fun selectMainCameraFocalPrefersWideOverUltraWideAndTele() {
        // 多摄机型常见：1.85mm（超广角 ~104°）、5.4mm（主摄 ~48°）、18.0mm（长焦 ~15°）
        // 传感器尺寸 6.4mm × 4.8mm，竖屏下对应 4.8mm
        val focals = floatArrayOf(1.85f, 5.4f, 18.0f)
        val chosen = CameraMath.selectMainCameraFocal(
            focals = focals,
            sensorWidthMm = 6.4,
            sensorHeightMm = 4.8,
            isPortrait = true,
            rotated = true,
            targetFovDeg = 62.0,
        )
        assertEquals("应优选主摄 5.4mm 焦距", 5.4f, chosen!!, 1e-4f)

        // 单镜头机型
        val single = CameraMath.selectMainCameraFocal(
            focals = floatArrayOf(4.3f),
            sensorWidthMm = 6.4,
            sensorHeightMm = 4.8,
            isPortrait = true,
            rotated = true,
        )
        assertEquals(4.3f, single!!, 1e-4f)

        // 空列表安全返回 null
        assertEquals(null, CameraMath.selectMainCameraFocal(null, 6.4, 4.8, true, true))
    }
}
