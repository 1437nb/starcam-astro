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
}
