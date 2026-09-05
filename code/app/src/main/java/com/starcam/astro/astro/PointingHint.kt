package com.starcam.astro.astro

import java.util.concurrent.ConcurrentHashMap

/**
 * 传感器辅助粗定标先验数据（相机光轴指向天球的粗略估计）。
 *
 * @property raDeg 预估赤经（度，0..360）
 * @property decDeg 预估赤纬（度，-90..90）
 * @property altDeg 相机高度角（度，-90..90，>0 为朝向星空）
 * @property azDeg 相机真方位角（度，0..360，北为 0°，东为 90°）
 * @property latDeg 观测地纬度
 * @property lonDeg 观测地经度
 * @property epochSec 拍摄瞬间 UTC 秒
 * @property radiusDeg 先验搜索半径（度，默认 25°，覆盖磁力计与手持姿态常见误差）
 */
data class PointingHint(
    val raDeg: Double,
    val decDeg: Double,
    val altDeg: Double,
    val azDeg: Double,
    val latDeg: Double,
    val lonDeg: Double,
    val epochSec: Long,
    val radiusDeg: Double = 25.0,
)

/**
 * 拍照到识别的瞬时粗定标缓存（内存保存最近拍摄照片的 PointingHint）。
 */
object PointingHintStore {
    private val cache = ConcurrentHashMap<String, PointingHint>()

    fun put(imagePath: String, hint: PointingHint) {
        cache[imagePath] = hint
    }

    fun get(imagePath: String): PointingHint? = cache[imagePath]

    fun remove(imagePath: String): PointingHint? = cache.remove(imagePath)

    fun clear() {
        cache.clear()
    }
}
