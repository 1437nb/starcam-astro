package com.starcam.astro.util

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.starcam.astro.astro.PointingHint
import com.starcam.astro.astro.QuatMath
import com.starcam.astro.astro.SkyEphemeris
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * 设备三维姿态与天球指向追踪器。
 *
 * 通过设备旋转矢量传感器（或加速度计+磁力计后备）推算后置摄像头光轴指向的地平高度角与真方位角；
 * 结合 [LocationHelper] 提供的地理位置，实时解算出天球赤道坐标（RA/Dec）。
 *
 * §0.52 AR 姿态平滑与预测：旋转矢量（~50Hz 绝对锚点，消除陀螺漂移）+ 陀螺仪
 * （SENSOR_DELAY_GAME 高频积分，提供帧间平滑姿态）+ 显示时刻外推预测
 * （用最新角速度向未来推 [PREDICT_MS]，抵消传感器→屏幕的延迟）。
 * 无陀螺仪设备自动回退为纯旋转矢量姿态（与旧行为一致）。
 */
class DeviceOrientationTracker(
    private val context: Context,
    private val locationHelper: LocationHelper,
) : SensorEventListener {

    /** 姿态预测外推时长（毫秒）：约 1 帧显示延迟 */
    private val predictMs = 24.0

    /**
     * 相机三轴基（§0.49 AR 星图用）：设备→东北天旋转矩阵的列向量组合。
     * - right：设备 +X（屏幕右）→ 东北天
     * - up：设备 +Y（屏幕上）→ 东北天
     * - axis：相机光轴（设备 −Z，垂直手机背面朝外）→ 东北天
     */
    data class DevicePointing(
        val altDeg: Double = 0.0,
        val azDeg: Double = 0.0,
        val isTrueAzimuth: Boolean = false,
        val declinationDeg: Double = 0.0,
        val raDeg: Double? = null,
        val decDeg: Double? = null,
        val latDeg: Double? = null,
        val lonDeg: Double? = null,
        val hasOrientation: Boolean = false,
        val right: FloatArray = FloatArray(3),
        val up: FloatArray = FloatArray(3),
        val axis: FloatArray = FloatArray(3),
    )

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val rotationVectorSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelerometerSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magneticSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val gyroSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    @Volatile
    var currentPointing = DevicePointing()
        private set

    var onPointingChanged: ((DevicePointing) -> Unit)? = null

    private var isTracking = false
    private val rotationMatrix = FloatArray(9)
    private val gravityValues = FloatArray(3)
    private val magneticValues = FloatArray(3)
    private var hasGravity = false
    private var hasMagnetic = false

    // 平滑滤波缓冲（世界坐标系下的相机光轴向量 E, N, U）
    private var smoothedE = 0.0
    private var smoothedN = 0.0
    private var smoothedU = 0.0
    private var hasSmoothed = false
    private val alpha = 0.25 // 平滑系数

    // §0.52/§0.55 姿态融合（互补滤波：陀螺快跟踪 + 旋转矢量慢校正，消除静止/运动抖动）
    private val fusionLock = Any()
    private val fusion = com.starcam.astro.astro.AttitudeFusion()

    fun startTracking() {
        if (sensorManager == null || isTracking) return
        isTracking = true
        hasSmoothed = false

        if (rotationVectorSensor != null) {
            sensorManager.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_UI)
        } else {
            accelerometerSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
            magneticSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
        }
        // §0.52：陀螺仪高频采样（GAME 档 ~50Hz+，无需 HIGH_SAMPLING_RATE 权限）
        gyroSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stopTracking() {
        if (!isTracking || sensorManager == null) return
        try {
            sensorManager.unregisterListener(this)
        } catch (_: Throwable) {
        } finally {
            isTracking = false
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val type = event.sensor.type
        var updated = false

        if (type == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            updated = true
            // §0.55：互补滤波——旋转矢量做缓慢漂移校正（不再硬锚定，消除静止抖动）
            synchronized(fusionLock) {
                fusion.onRotationVector(event.values[0], event.values[1], event.values[2], event.timestamp)
            }
        } else if (type == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, gravityValues, 0, 3)
            hasGravity = true
            if (hasGravity && hasMagnetic) {
                updated = SensorManager.getRotationMatrix(
                    rotationMatrix, null, gravityValues, magneticValues,
                )
            }
        } else if (type == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, magneticValues, 0, 3)
            hasMagnetic = true
            if (hasGravity && hasMagnetic) {
                updated = SensorManager.getRotationMatrix(
                    rotationMatrix, null, gravityValues, magneticValues,
                )
            }
        } else if (type == Sensor.TYPE_GYROSCOPE) {
            // §0.52/§0.55：陀螺积分（体坐标右乘，快跟踪由它负责）
            synchronized(fusionLock) {
                fusion.onGyro(event.values[0], event.values[1], event.values[2], event.timestamp)
            }
        }

        if (updated) {
            computePointing()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /**
     * §0.52/§0.55：显示时刻的预测姿态（AR 画布逐帧调用）。
     * 姿态 = 互补滤波运行估计 ⊗ 按最新角速度外推 [predictMs]。
     * 传感器未就绪时回退到 [currentPointing]。
     */
    fun predictedPointing(): DevicePointing {
        synchronized(fusionLock) {
            val q = fusion.predicted(android.os.SystemClock.elapsedRealtimeNanos(), predictMs / 1000.0) ?: return currentPointing
            val r = QuatMath.quatToMatrix(q)
            val right = floatArrayOf(r[0], r[3], r[6])
            val up = floatArrayOf(r[1], r[4], r[7])
            val axis = floatArrayOf(-r[2], -r[5], -r[8])
            return buildPointing(right, up, axis)
        }
    }

    /** 由三轴基构建完整指向（与 computePointing 共用 alt/az/RA/Dec 逻辑） */
    private fun buildPointing(right: FloatArray, up: FloatArray, axis: FloatArray): DevicePointing {
        val len = sqrt(
            axis[0].toDouble() * axis[0] + axis[1].toDouble() * axis[1] + axis[2].toDouble() * axis[2],
        )
        if (len < 1e-6) return DevicePointing()
        val eNorm = axis[0] / len
        val nNorm = axis[1] / len
        val uNorm = (axis[2] / len).coerceIn(-1.0, 1.0)

        val altDeg = Math.toDegrees(asin(uNorm))
        var azMagDeg = Math.toDegrees(atan2(eNorm.toDouble(), nNorm.toDouble()))
        if (azMagDeg < 0.0) azMagDeg += 360.0

        val loc = locationHelper.getBestLocation()
        val nowSec = System.currentTimeMillis() / 1000L

        var azTrueDeg = azMagDeg
        var isTrueAzimuth = false
        var declinationDeg = 0.0
        var raDeg: Double? = null
        var decDeg: Double? = null

        if (loc != null) {
            try {
                val geoField = GeomagneticField(
                    loc.latitude.toFloat(),
                    loc.longitude.toFloat(),
                    loc.altitude.toFloat(),
                    System.currentTimeMillis(),
                )
                declinationDeg = geoField.declination.toDouble()
                azTrueDeg = (azMagDeg + geoField.declination + 360.0) % 360.0
                isTrueAzimuth = true
            } catch (_: Throwable) {
            }

            // §0.53 全天星空：任意仰角（含负仰角/朝下）都计算天球指向，
            // 供 AR 全天星空模式显示"脚下半球"的星空
            try {
                val radec = SkyEphemeris.altAzToRaDec(
                    altDeg = altDeg,
                    azDeg = azTrueDeg,
                    latDeg = loc.latitude,
                    lonDeg = loc.longitude,
                    epochSec = nowSec,
                )
                raDeg = radec.first
                decDeg = radec.second
            } catch (_: Throwable) {
            }
        }

        // §0.56：将三轴基由磁北东北天顺时针旋转 declinationDeg 到真北东北天坐标系
        // 使 AR 星图投影、地平线与 HUD 在全天球绝对几何下 100% 对齐真北
        val trueRight = right.clone()
        val trueUp = up.clone()
        val trueAxis = axis.clone()
        if (isTrueAzimuth && kotlin.math.abs(declinationDeg) > 1e-4) {
            com.starcam.astro.astro.CameraMath.rotateBasisAroundUp(
                trueRight, trueUp, trueAxis, declinationDeg,
            )
        }

        return DevicePointing(
            altDeg = altDeg,
            azDeg = azTrueDeg,
            isTrueAzimuth = isTrueAzimuth,
            declinationDeg = declinationDeg,
            raDeg = raDeg,
            decDeg = decDeg,
            latDeg = loc?.latitude,
            lonDeg = loc?.longitude,
            hasOrientation = true,
            right = trueRight,
            up = trueUp,
            axis = trueAxis,
        )
    }

    private fun computePointing() {
        // 后置摄像头光轴为设备 -Z 方向：v_cam = (0, 0, -1)^T
        // 在东北天（ENU）世界坐标系中：v_world = R * v_cam = (-R[2], -R[5], -R[8])^T
        val rawE = -rotationMatrix[2].toDouble()
        val rawN = -rotationMatrix[5].toDouble()
        val rawU = -rotationMatrix[8].toDouble()

        if (!hasSmoothed) {
            smoothedE = rawE
            smoothedN = rawN
            smoothedU = rawU
            hasSmoothed = true
        } else {
            smoothedE += alpha * (rawE - smoothedE)
            smoothedN += alpha * (rawN - smoothedN)
            smoothedU += alpha * (rawU - smoothedU)
        }

        val len = sqrt(smoothedE * smoothedE + smoothedN * smoothedN + smoothedU * smoothedU)
        if (len < 1e-6) return

        // HUD 的 alt/az/RA/Dec 沿用平滑轴（保持旧行为稳定）；
        // right/up 传原始列向量（HUD 不使用；AR 画布走 predictedPointing）
        val axisSmooth = floatArrayOf(
            (smoothedE / len).toFloat(),
            (smoothedN / len).toFloat(),
            (smoothedU / len).toFloat(),
        )
        val right = floatArrayOf(rotationMatrix[0], rotationMatrix[3], rotationMatrix[6])
        val upV = floatArrayOf(rotationMatrix[1], rotationMatrix[4], rotationMatrix[7])

        val pointing = buildPointing(right, upV, axisSmooth)
        currentPointing = pointing
        onPointingChanged?.invoke(pointing)
    }

    /**
     * 生成当前瞬时粗定标先验数据（用于拍照注入或实时识别加速）。
     * 若未检测到朝向、无定位或指向地面（alt <= 0），返回 null。
     */
    fun createPointingHint(radiusDeg: Double = 25.0): PointingHint? {
        val p = currentPointing
        val ra = p.raDeg ?: return null
        val dec = p.decDeg ?: return null
        val lat = p.latDeg ?: return null
        val lon = p.lonDeg ?: return null
        if (!p.hasOrientation || p.altDeg <= 0.0) return null

        return PointingHint(
            raDeg = ra,
            decDeg = dec,
            altDeg = p.altDeg,
            azDeg = p.azDeg,
            latDeg = lat,
            lonDeg = lon,
            epochSec = System.currentTimeMillis() / 1000L,
            radiusDeg = radiusDeg,
        )
    }
}

