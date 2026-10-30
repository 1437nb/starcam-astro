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
        else -> "${String.format(java.util.Locale.ROOT, "%.1f", sec)} s"
    }

    /**
     * PreviewView FIT_CENTER 显示矩形（§0.115 审计 B14 纯函数化）。
     */
    data class FitRect(
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
    )

    /**
     * PreviewView FIT_CENTER 显示矩形计算（纯数学，无 Android 依赖，§0.115 修复审计 B14）。
     *
     * @param containerWidth 渲染容器宽度（如屏幕或 Box 宽度）
     * @param containerHeight 渲染容器高度
     * @param streamAspectLandscape 相机预览流横屏横纵比（典型如 4f / 3f 或 16f / 9f）
     * @param isPortrait 是否为竖屏显示
     */
    fun computeFitCenterRect(
        containerWidth: Float,
        containerHeight: Float,
        streamAspectLandscape: Float = 4f / 3f,
        isPortrait: Boolean = true,
    ): FitRect {
        val aspect = if (isPortrait) 1f / streamAspectLandscape else streamAspectLandscape
        val rectH = minOf(containerHeight, containerWidth / aspect)
        val rectW = rectH * aspect
        val rectL = (containerWidth - rectW) / 2f
        val rectT = (containerHeight - rectH) / 2f
        return FitRect(rectL, rectT, rectW, rectH)
    }

    /**
     * PreviewView FIT_CENTER 显示矩形计算（按实际内容像素尺寸，§0.123 修复 P1-1）。
     *
     * @param containerWidth 渲染容器宽度
     * @param containerHeight 渲染容器高度
     * @param contentWidth 实际帧/位图内容宽度
     * @param contentHeight 实际帧/位图内容高度
     */
    fun computeFitCenterRect(
        containerWidth: Float,
        containerHeight: Float,
        contentWidth: Int,
        contentHeight: Int,
    ): FitRect {
        if (contentWidth <= 0 || contentHeight <= 0) {
            return FitRect(0f, 0f, containerWidth, containerHeight)
        }
        val aspect = contentWidth.toFloat() / contentHeight.toFloat()
        val rectH = minOf(containerHeight, containerWidth / aspect)
        val rectW = rectH * aspect
        val rectL = (containerWidth - rectW) / 2f
        val rectT = (containerHeight - rectH) / 2f
        return FitRect(rectL, rectT, rectW, rectH)
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

    /**
     * 根据相机物理传感器参数与当前屏幕方向，计算对应的水平 FOV 角度（§0.123 修复 P1-2）。
     *
     * @param focals 镜头可用实际焦距列表
     * @param sensorWidthMm 传感器物理宽度（毫米）
     * @param sensorHeightMm 传感器物理高度（毫米）
     * @param isPortrait 当前屏幕是否为竖屏
     * @param sensorOrientationDeg 传感器硬件朝向（典型为 90 或 270）
     * @param targetFovDeg 目标主摄基准视场（默认 62°）
     * @return 对应的水平视场（度，收敛在 40°~90° 内），不可用或异常返回 null
     */
    fun computeCameraHorizontalFovDeg(
        focals: FloatArray?,
        sensorWidthMm: Double,
        sensorHeightMm: Double,
        isPortrait: Boolean,
        sensorOrientationDeg: Int,
        targetFovDeg: Double = 62.0,
    ): Float? {
        if (focals == null || focals.isEmpty() || sensorWidthMm <= 0.0 || sensorHeightMm <= 0.0) return null
        val rotated = sensorOrientationDeg % 180 != 0
        val focal = selectMainCameraFocal(
            focals, sensorWidthMm, sensorHeightMm, isPortrait, rotated, targetFovDeg,
        ) ?: return null
        if (focal <= 0f) return null
        val fovPortrait = horizontalFovDeg(focal.toDouble(), sensorHeightMm)
        val fovLandscape = horizontalFovDeg(focal.toDouble(), sensorWidthMm)
        val fov = if (rotated) {
            if (isPortrait) fovPortrait else fovLandscape
        } else {
            if (isPortrait) fovLandscape else fovPortrait
        }
        return if (fov in 30.0..100.0) fov.toFloat().coerceIn(40f, 90f) else null
    }

    /**
     * 地平线大圆针孔投影与矩形视口 4 边鲁棒求交（§0.125 消除 AR 逐帧闭包 Ref 分配，docs/74 §4.1）。
     *
     * 直线方程：ru*(x - cx) - uu*(y - cy) + f*au = 0，即 A*x + B*y + C = 0。
     * 求该直线与 [0, rectW] × [0, rectH] 边界的两个不重合交点，写入 [out] = [x1, y1, x2, y2]。
     *
     * @return 交点满 2 个返回 true（此时 [out] 有效），否则返回 false。
     */
    fun computeHorizonSegmentInto(
        ru: Float,
        uu: Float,
        au: Float,
        focalPx: Float,
        rectW: Float,
        rectH: Float,
        out: FloatArray,
    ): Boolean {
        val halfW = rectW / 2f
        val halfH = rectH / 2f
        val lineA = ru
        val lineB = -uu
        val lineC = focalPx * au - ru * halfW + uu * halfH

        var p1x = 0f
        var p1y = 0f
        var p2x = 0f
        var p2y = 0f
        var hitCount = 0

        if (kotlin.math.abs(lineB) > 1e-5f) {
            val yLeft = -lineC / lineB
            if (yLeft in 0f..rectH) {
                p1x = 0f; p1y = yLeft; hitCount = 1
            }
            val yRight = -(lineC + lineA * rectW) / lineB
            if (yRight in 0f..rectH) {
                if (hitCount == 0) {
                    p1x = rectW; p1y = yRight; hitCount = 1
                } else if (kotlin.math.abs(p1x - rectW) > 1f || kotlin.math.abs(p1y - yRight) > 1f) {
                    p2x = rectW; p2y = yRight; hitCount = 2
                }
            }
        }
        if (kotlin.math.abs(lineA) > 1e-5f && hitCount < 2) {
            val xTop = -lineC / lineA
            if (xTop in 0f..rectW) {
                if (hitCount == 0) {
                    p1x = xTop; p1y = 0f; hitCount = 1
                } else if (kotlin.math.abs(p1x - xTop) > 1f || kotlin.math.abs(p1y - 0f) > 1f) {
                    p2x = xTop; p2y = 0f; hitCount = 2
                }
            }
            if (hitCount < 2) {
                val xBottom = -(lineC + lineB * rectH) / lineA
                if (xBottom in 0f..rectW) {
                    if (hitCount == 0) {
                        p1x = xBottom; p1y = rectH; hitCount = 1
                    } else if (kotlin.math.abs(p1x - xBottom) > 1f || kotlin.math.abs(p1y - rectH) > 1f) {
                        p2x = xBottom; p2y = rectH; hitCount = 2
                    }
                }
            }
        }
        if (hitCount >= 2) {
            out[0] = p1x
            out[1] = p1y
            out[2] = p2x
            out[3] = p2y
            return true
        }
        return false
    }
}
