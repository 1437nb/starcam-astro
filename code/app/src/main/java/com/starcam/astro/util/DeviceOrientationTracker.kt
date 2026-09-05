package com.starcam.astro.util

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.starcam.astro.astro.PointingHint
import com.starcam.astro.astro.SkyEphemeris
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * 设备三维姿态与天球指向追踪器。
 *
 * 通过设备旋转矢量传感器（或加速度计+磁力计后备）推算后置摄像头光轴指向的地平高度角与真方位角；
 * 结合 [LocationHelper] 提供的地理位置，实时解算出天球赤道坐标（RA/Dec）。
 */
class DeviceOrientationTracker(
    private val context: Context,
    private val locationHelper: LocationHelper,
) : SensorEventListener {

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
        }

        if (updated) {
            computePointing()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

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

        val eNorm = smoothedE / len
        val nNorm = smoothedN / len
        val uNorm = (smoothedU / len).coerceIn(-1.0, 1.0)

        // 仰角/高度角：asin(U)
        val altDeg = Math.toDegrees(asin(uNorm))

        // 磁方位角：atan2(E, N)，转为 [0, 360) 度
        var azMagDeg = Math.toDegrees(atan2(eNorm, nNorm))
        if (azMagDeg < 0.0) azMagDeg += 360.0

        val loc = locationHelper.getBestLocation()
        val nowSec = System.currentTimeMillis() / 1000L

        var azTrueDeg = azMagDeg
        var isTrueAzimuth = false
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
                azTrueDeg = (azMagDeg + geoField.declination + 360.0) % 360.0
                isTrueAzimuth = true
            } catch (_: Throwable) {
            }

            // 当相机朝向地平线以上时，计算天球指向（RA/Dec）
            if (altDeg > 0.0) {
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
        }

        // 相机三轴基（设备→东北天旋转矩阵的列向量；axis 取 −Z 列，与光轴一致）
        // right = R·(1,0,0) = 第 0 列；up = R·(0,1,0) = 第 1 列；axis = −R·(0,0,1)
        val right = floatArrayOf(rotationMatrix[0], rotationMatrix[3], rotationMatrix[6])
        val upV = floatArrayOf(rotationMatrix[1], rotationMatrix[4], rotationMatrix[7])
        val axisV = floatArrayOf(-rotationMatrix[2], -rotationMatrix[5], -rotationMatrix[8])

        val pointing = DevicePointing(
            altDeg = altDeg,
            azDeg = azTrueDeg,
            isTrueAzimuth = isTrueAzimuth,
            raDeg = raDeg,
            decDeg = decDeg,
            latDeg = loc?.latitude,
            lonDeg = loc?.longitude,
            hasOrientation = true,
            right = right,
            up = upV,
            axis = axisV,
        )
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
