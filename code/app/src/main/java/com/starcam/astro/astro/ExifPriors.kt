package com.starcam.astro.astro

import androidx.exifinterface.media.ExifInterface
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 位置来源。§0.59
 */
enum class LocationSource {
    /** 照片 EXIF 自带 GPS：反映拍摄当时的地点，最可信。 */
    EXIF,

    /**
     * 照片无 GPS，改用设备**当前定位**兜底。
     * 仅当「拍摄地 ≈ 当前所在地」时成立（典型：刚拍完就在原地处理照片）；
     * 处理异地或久前的旧照片时会给出错误先验，UI 必须如实标注来源。
     */
    CURRENT_FALLBACK,

    /** 全然没有位置信息。 */
    NONE,
}

/**
 * EXIF 求解先验：焦距 → 视场 scale 区间；GPS + 拍摄时间 → 天顶 RA/Dec 天区。
 *
 * 时间读取优先级：
 *  1. GPSDateStamp + GPSTimeStamp（UTC，与时区无关，最准）
 *  2. DateTimeOriginal（无时区标记，按设备本地时区解析）
 *
 * §0.59 起位置可能有两个来源：照片 EXIF，或调用方用当前定位兜底
 * （见 [LocationSource]）。EXIF 永远优先。
 */
data class ExifPriors(
    val fovDeg: Double?,
    val latDeg: Double?,
    val lonDeg: Double?,
    val epochSec: Long?,
    val timeIsUtc: Boolean,
    val locationSource: LocationSource = LocationSource.NONE,
) {
    /** GPS + 时间齐全 → 天顶 RA/Dec（度）。 */
    val zenith: Pair<Double, Double>?
        get() = if (latDeg != null && lonDeg != null && epochSec != null) {
            SkyEphemeris.zenithRaDec(latDeg, lonDeg, epochSec)
        } else {
            null
        }

    val hasSkyPrior: Boolean get() = zenith != null

    /** 位置来自「当前定位兜底」时为真——UI 据此提示用户「可能不准」。 */
    val locationIsFallback: Boolean get() = locationSource == LocationSource.CURRENT_FALLBACK

    /**
     * §0.59：照片缺 GPS 时，用设备当前定位补全经纬度。
     *
     * - 已有 EXIF 定位：原样返回（EXIF 永远优先，绝不被覆盖）。
     * - 无拍摄时间：原样返回——没有时刻就算不出天顶，补位置没有意义。
     * - 只有经纬度之一缺失：视为缺 GPS，一并补全（EXIF 里半套 GPS 不可用）。
     */
    fun withFallbackLocation(lat: Double, lon: Double): ExifPriors {
        if (latDeg != null && lonDeg != null) return this
        if (epochSec == null) return this
        return copy(
            latDeg = lat,
            lonDeg = lon,
            locationSource = LocationSource.CURRENT_FALLBACK,
        )
    }
}

/** EXIF 先验读取器 */
object ExifPriorsReader {

    private val utcFormat = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val localFormat = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)

    /** 读取照片 EXIF 先验；任意异常返回"全部未知"（调用方据此降级盲解） */
    fun read(imagePath: String): ExifPriors {
        val exif = try {
            ExifInterface(imagePath)
        } catch (e: Exception) {
            return ExifPriors(null, null, null, null, false)
        }
        val fovDeg = FovEstimate.fovDegFromFocal35(
            exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0.0),
            FovEstimate.isPortrait(
                exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL),
            ),
        )
        val lat = parseDms(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))?.let {
            if (exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF) == "S") -it else it
        }
        val lon = parseDms(exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE))?.let {
            if (exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF) == "W") -it else it
        }

        // GPS 时间（UTC）优先，其次 DateTimeOriginal（本地时区）
        // （GPS tag 用标准 EXIF 名读取，兼容不同 androidx.exifinterface 版本）
        val gpsDate = exif.getAttribute("GPSDateStamp")
        val gpsTime = exif.getAttribute("GPSTimeStamp")
        val gpsEpoch = parseUtcEpoch(gpsDate, gpsTime)
        var epoch: Long? = gpsEpoch
        var isUtc = gpsEpoch != null
        if (epoch == null) {
            val dt = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            epoch = parseLocalEpoch(dt)
        }
        // §0.59：经纬度成对存在才算 EXIF 定位；只有半套 GPS 视为缺失，交给兜底处理。
        val source = if (lat != null && lon != null) {
            LocationSource.EXIF
        } else {
            LocationSource.NONE
        }
        return ExifPriors(fovDeg, lat, lon, epoch, isUtc, source)
    }

    /**
     * 天区先验搜索半径（度）：以天顶为中心需覆盖手持拍摄的方向不确定性。
     * 值越大越安全（低空目标），越小越快；取 视场半宽 + 45° 并限制在 60°~85°。
     */
    fun skyPriorRadiusDeg(fovDeg: Double?): Double {
        val base = (fovDeg ?: 45.0) / 2.0 + 45.0
        return base.coerceIn(60.0, 85.0)
    }

    /** 解析 EXIF 分数格式的度分秒（"39/1,54/1,32/1" 或 "39,54.5"） */
    private fun parseDms(raw: String?): Double? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split(",")
        if (parts.size < 2 || parts.size > 3) return null
        fun frac(v: String): Double {
            if (v.isBlank()) return 0.0
            if (!v.contains("/")) return v.toDoubleOrNull() ?: 0.0
            val sp = v.split("/")
            if (sp.size != 2) return 0.0
            val num = sp[0].toDoubleOrNull() ?: return 0.0
            val den = sp[1].toDoubleOrNull() ?: return 0.0
            return if (den == 0.0) 0.0 else num / den
        }
        val deg = frac(parts[0])
        val min = if (parts.size > 1) frac(parts[1]) else 0.0
        val sec = if (parts.size > 2) frac(parts[2]) else 0.0
        val dms = deg + min / 60.0 + sec / 3600.0
        if (!dms.isFinite() || dms !in -540.0..540.0) return null
        return dms
    }

    /** GPSDateStamp("2024:08:12") + GPSTimeStamp("22/1,30/1,15/1") → Unix 秒（UTC） */
    private fun parseUtcEpoch(dateStamp: String?, timeStamp: String?): Long? {
        if (dateStamp.isNullOrBlank()) return null
        val date = dateStamp.replace('-', ':')
        val time = timeStamp ?: ""
        val parts = time.split(",")
        fun frac(v: String): Double {
            if (!v.contains("/")) return v.toDoubleOrNull() ?: 0.0
            val sp = v.split("/")
            if (sp.size != 2) return 0.0
            val num = sp[0].toDoubleOrNull() ?: return 0.0
            val den = sp[1].toDoubleOrNull() ?: return 0.0
            return if (den == 0.0) 0.0 else num / den
        }
        val h = if (parts.size > 0) frac(parts[0]) else 0.0
        val m = if (parts.size > 1) frac(parts[1]) else 0.0
        val s = if (parts.size > 2) frac(parts[2]) else 0.0
        val hh = h.toInt().coerceIn(0, 23)
        val mm = m.toInt().coerceIn(0, 59)
        val ss = s.toInt().coerceIn(0, 59)
        val text = "$date $hh:$mm:$ss"
        val ms = try {
            utcFormat.parse(text)?.time
        } catch (e: Exception) {
            null
        } ?: return null
        return ms / 1000
    }

    /** DateTimeOriginal("2024:08:12 22:30:15") → Unix 秒（本地时区） */
    private fun parseLocalEpoch(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val ms = try {
            localFormat.parse(raw.replace('-', ':'))?.time
        } catch (e: Exception) {
            null
        } ?: return null
        return ms / 1000
    }

    /**
     * §0.58：从照片 EXIF 推算**该照片拍摄瞬间**太阳系天体的站心位置。
     *
     * 日月行星的位置随时刻显著变化（月亮每小时走约 0.55°），因此必须同时具备
     * 拍摄时间与 GPS 定位才能给出正确结果——**缺任意一项即返回空表**，
     * 宁可不标注也不能标错（错标比不标更糟）。演示合成图无真实时刻，同样走空表。
     *
     * 返回结果已按 [SolarSystemCatalog.defaultAnnotated] 过滤（外加太阳），
     * 只保留肉眼可见、值得标注的目标。
     */
    fun solarSystemForPhoto(
        imagePath: String,
        fallbackLocation: Pair<Double, Double>? = null,
    ): List<SolarSystemEphemeris.SolarPosition> {
        var priors = try {
            read(imagePath)
        } catch (e: Throwable) {
            return emptyList()
        }
        // §0.59：照片无 GPS 时可用当前定位兜底。注意天体位置对经度敏感
        // （经度差 1° ≈ 地方恒星时差 4 分钟 ≈ 月亮走 0.04°），异地旧照片会产生
        // 可见偏差——调用方有责任把 locationIsFallback 透出到 UI，如实告知来源。
        if (fallbackLocation != null) {
            priors = priors.withFallbackLocation(fallbackLocation.first, fallbackLocation.second)
        }
        val epoch = priors.epochSec ?: return emptyList()
        val lat = priors.latDeg ?: return emptyList()
        val lon = priors.lonDeg ?: return emptyList()
        val jd = SolarSystemEphemeris.unixSecondsToJd(epoch)
        val wanted = setOf(SolarSystemEphemeris.SolarBody.SUN) + SolarSystemCatalog.defaultAnnotated
        return SolarSystemEphemeris.topocentricPositions(jd, lat, lon).filter { it.body in wanted }
    }

    /**
     * §0.59：这张照片是否「值得用当前定位兜底」——缺 GPS（或只有半套）但有拍摄时间。
     *
     * 供调用方在求解前判断要不要去取一次定位，避免无谓的定位开销；
     * 也是「有拍摄时间」这一前提的唯一判据（无时刻则补位置毫无意义）。
     */
    fun needsLocationFallback(imagePath: String): Boolean {
        val priors = try {
            read(imagePath)
        } catch (e: Throwable) {
            return false
        }
        return priors.epochSec != null && (priors.latDeg == null || priors.lonDeg == null)
    }
}