package com.starcam.astro.astro

import kotlin.math.atan2
import kotlin.math.pow

/**
 * 相机几何与曝光辅助计算（§0.50，纯 JVM 可单测，无 Android 依赖）。
 */
object CameraMath {

    /**
     * 相机滚转角（电子水平仪，度）：画面内世界铅垂方向相对屏幕竖直的倾角。
     * worldUp = (0,0,1)（东北天坐标系），投影到相机基：
     * roll = atan2(U·right, U·up)；roll = 0 表示画面内地平线水平。
     */
    fun rollDeg(right: FloatArray, up: FloatArray): Double {
        val ux = right[2]
        val uy = up[2]
        return Math.toDegrees(atan2(ux.toDouble(), uy.toDouble()))
    }

    /**
     * 水平视场角（度）：fov = 2·atan(传感器尺寸 / (2·焦距))。
     * @param focalMm 等效实际焦距（毫米，非 35mm 等效）
     * @param sensorMmMm 对应方向的传感器物理尺寸（毫米）
     */
    fun horizontalFovDeg(focalMm: Double, sensorMm: Double): Double {
        if (focalMm <= 0.0 || sensorMm <= 0.0) return 0.0
        return Math.toDegrees(2.0 * atan2(sensorMm, 2.0 * focalMm))
    }

    /**
     * Pro 快门档位（秒，正数表示整秒；<1 用分数表示）。
     * 覆盖星野常用区间：短曝看地景 → 30s 深空积光。
     */
    val shutterStopsSec: List<Double> = listOf(
        1.0 / 30, 1.0 / 8, 1.0 / 2, 1.0, 2.0, 4.0, 8.0, 15.0, 30.0,
    )

    /** 档位下标 → 显示文本（1/30s、1/8s、0.5s、1s、30s） */
    fun shutterLabel(sec: Double): String = when {
        sec < 1.0 -> {
            val denom = Math.round(1.0 / sec)
            "1/$denom s"
        }
        sec % 1.0 == 0.0 -> "${sec.toInt()} s"
        else -> "${"%.1f".format(sec)} s"
    }

    /** 档位秒 → 曝光纳秒（Camera2 SENSOR_EXPOSURE_TIME 单位） */
    fun secToNanos(sec: Double): Long = Math.round(sec * 1_000_000_000.0)

    /** 把 ISO 夹紧到设备支持区间（区间未知时原样返回） */
    fun clampIso(iso: Int, range: IntRange?): Int {
        if (range == null) return iso
        return iso.coerceIn(range.first, range.last)
    }

    /** 把曝光秒夹紧到设备支持区间（纳秒；区间未知时原样返回纳秒） */
    fun clampExposureNanos(nanos: Long, range: LongRange?): Long {
        if (range == null) return nanos
        return nanos.coerceIn(range.first, range.last)
    }

    /** 已解视场与焦距换算的 35mm 等效检查辅助（对角线 FOV） */
    fun diagonalFovDeg(focalMm: Double, widthMm: Double, heightMm: Double): Double =
        horizontalFovDeg(focalMm, kotlin.math.sqrt(widthMm.pow(2) + heightMm.pow(2)))

    /**
     * 将东北天三轴基围绕世界铅垂轴（Up=(0,0,1)）顺时针（由北向东）旋转 [azDeg] 度（§0.56）。
     * - 用于地磁偏角校正（磁北 → 真北）及天球求解航向残差校准
     * - Z 分量绝对保持不变，画面滚转角（rollDeg）与地平线几何 100% 保持稳定一致
     * - 支持就地修改（outX 默认为输入数组，零分配）
     */
    fun rotateBasisAroundUp(
        right: FloatArray,
        up: FloatArray,
        axis: FloatArray,
        azDeg: Double,
        outRight: FloatArray = right,
        outUp: FloatArray = up,
        outAxis: FloatArray = axis,
    ) {
        if (kotlin.math.abs(azDeg) < 1e-5) {
            if (outRight !== right) System.arraycopy(right, 0, outRight, 0, 3)
            if (outUp !== up) System.arraycopy(up, 0, outUp, 0, 3)
            if (outAxis !== axis) System.arraycopy(axis, 0, outAxis, 0, 3)
            return
        }
        val rad = Math.toRadians(azDeg)
        val c = kotlin.math.cos(rad).toFloat()
        val s = kotlin.math.sin(rad).toFloat()
        fun rot(v: FloatArray, out: FloatArray) {
            val x = v[0]
            val y = v[1]
            val z = v[2]
            out[0] = x * c + y * s
            out[1] = -x * s + y * c
            out[2] = z
        }
        rot(right, outRight)
        rot(up, outUp)
        rot(axis, outAxis)
    }

    /**
     * 在多摄机型返回的焦距列表中，选出视场最接近标准主摄（~62° FOV）的焦距（§0.56），
     * 避免误选超广角（>90°）或长焦（<40°）导致 AR 星图缩放严重失真。
     */
    fun selectMainCameraFocal(
        focals: FloatArray?,
        sensorWidthMm: Double,
        sensorHeightMm: Double,
        isPortrait: Boolean,
        rotated: Boolean,
        targetFovDeg: Double = 62.0,
    ): Float? {
        if (focals == null || focals.isEmpty() || sensorWidthMm <= 0.0 || sensorHeightMm <= 0.0) return null
        val dim = if (rotated) {
            if (isPortrait) sensorHeightMm else sensorWidthMm
        } else {
            if (isPortrait) sensorWidthMm else sensorHeightMm
        }
        var bestFocal = focals[0]
        var bestDiff = Double.MAX_VALUE
        for (f in focals) {
            if (f <= 0f) continue
            val fov = horizontalFovDeg(f.toDouble(), dim)
            val diff = kotlin.math.abs(fov - targetFovDeg)
            if (diff < bestDiff) {
                bestDiff = diff
                bestFocal = f
            }
        }
        return bestFocal
    }
}
