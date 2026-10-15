package com.starcam.astro.astro

import androidx.exifinterface.media.ExifInterface
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

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

    /**
     * P0-2.1（外部审计 2026-10-06）：改用 `DateTimeFormatter` —— 不可变、线程安全。
     *
     * 原来是 `SimpleDateFormat`（object 级共享、**非线程安全**，内部 Calendar 是
     * 可变状态）。本读取器在**批量识别的并发路径**上被两个 worker 同时调用
     * （`BatchScreen` → `solarSystemForPhoto`；单张流程见 `StarSolver`），并发
     * 解析会互相污染同一实例：轻则拍摄时刻错乱（太阳系标注位置随时刻偏移，
     * 月亮每小时走 0.55°，错标比不标更糟），重则 `ArrayIndexOutOfBoundsException`
     * 被上层 catch 吞掉。先例与写法见 `data/SolveLogStore.kt` §0.81。
     *
     * 时区语义保持不变：UTC 解析走 [ZoneOffset.UTC]（原 `timeZone=UTC`），
     * 本地解析走 [ZoneId.systemDefault]（原默认时区）。
     */
    private val exifTimeFmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.US)

    /**
     * §0.105（二期审计 2026-10-14）：GPS 日期**单独**解析用。
     *
     * 缘由：`GPSTimeStamp` 的三个分量是 Int，拼字符串若不补前导零，
     * `"HH:mm:ss"` 在 java.time 里要求固定两位宽（旧 `SimpleDateFormat` 宽容，
     * 这是换 `DateTimeFormatter` 时漏掉的语义差异），时/分/秒任一 <10 就会抛
     * `DateTimeParseException` 被吞成 null —— 一天 86400 秒里有 59.5% 中招。
     * 这里改成「日期走格式器 + 时分秒走 `atTime(Int,Int,Int)`」，从根上不依赖
     * 前导零；`parseLocalEpoch` 仍用 [exifTimeFmt]（那边输入本来就是完整字符串）。
     */
    private val exifDateFmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy:MM:dd", Locale.US)

    /** 读取照片 EXIF 先验；任意异常返回"全部未知"（调用方据此降级盲解） */
    fun read(imagePath: String): ExifPriors {
        // §0.108：FITS 没有 EXIF —— 先验改从 FITS 头来（WCS 视场 / DATE-OBS / 观测站坐标）
        if (com.starcam.astro.util.FitsImage.isFitsPath(imagePath)) return readFits(imagePath)
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

    /** GPSDateStamp("2024:08:12") + GPSTimeStamp → Unix 秒（UTC）。
     *
     * §0.109（验证中发现）：**必须同时认两种 GPSTimeStamp 形态** ——
     *  ① `"HH:MM:SS"`：这是 androidx `ExifInterface`（1.3.7 实测，与仓库依赖版本一致）
     *     对标准 RATIONAL 三连（17/1,25/1,46/1）**格式化后**的返回形态，也就是真机上的
     *     常态。旧解析只按 ② 拆，导致时分秒全被当 0 → epoch 静默变成"当天 UTC 零点"，
     *     月亮标注因此可能偏出 7°（17:25:46 与 00:00:00 相差 ~7° 月亮位移）。
     *  ② `"num/den,num/den,num/den"`：少数机型写成纯文本的原始形态（解析器最初的假设）。
     * 两种都认不出时返回 null（调用方回落 DateTimeOriginal），**绝不退化成 0:0:0**。 */
    private fun parseUtcEpoch(dateStamp: String?, timeStamp: String?): Long? {
        if (dateStamp.isNullOrBlank()) return null
        val date = dateStamp.replace('-', ':')
        val hms = parseGpsTimeOfDay(timeStamp) ?: return null
        val epoch = try {
            LocalDate.parse(date.trim(), exifDateFmt)
                .atTime(hms.first, hms.second, hms.third)
                .toEpochSecond(ZoneOffset.UTC)
        } catch (e: Exception) {
            null
        } ?: return null
        return epoch
    }

    /** GPSTimeStamp → (时, 分, 秒)；两种形态都认不出返回 null（见 [parseUtcEpoch]）。 */
    private fun parseGpsTimeOfDay(raw: String?): Triple<Int, Int, Int>? {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty()) return null
        if (t.contains(':')) {
            // androidx 返回的格式化形态，如 "17:25:46"（也可能是 "8:05:09"）
            val p = t.split(':')
            if (p.size < 2) return null
            val h = p[0].trim().toDoubleOrNull()?.toInt() ?: return null
            val m = p[1].trim().toDoubleOrNull()?.toInt() ?: return null
            // 秒可能带小数（部分机型），取整数部分；缺秒按 0
            val s = p.getOrNull(2)?.trim()?.substringBefore('.')?.toDoubleOrNull()?.toInt() ?: 0
            return Triple(h.coerceIn(0, 23), m.coerceIn(0, 59), s.coerceIn(0, 59))
        }
        // 原始分式形态，如 "17/1,25/1,46/1"；时解析失败即整体弃用（不回落到 0 点）
        val p = t.split(',')
        val h = fracOrNull(p.getOrNull(0))?.toInt() ?: return null
        val m = fracOrNull(p.getOrNull(1))?.toInt() ?: 0
        val s = fracOrNull(p.getOrNull(2))?.toInt() ?: 0
        return Triple(h.coerceIn(0, 23), m.coerceIn(0, 59), s.coerceIn(0, 59))
    }

    /** "num/den" 或纯数字 → Double；空/坏值返回 null（区别于"0"）。 */
    private fun fracOrNull(v: String?): Double? {
        val s = v?.trim().orEmpty()
        if (s.isEmpty()) return null
        if (!s.contains("/")) return s.toDoubleOrNull()
        val sp = s.split("/")
        if (sp.size != 2) return null
        val num = sp[0].trim().toDoubleOrNull() ?: return null
        val den = sp[1].trim().toDoubleOrNull() ?: return null
        return if (den == 0.0) null else num / den
    }

    /** DateTimeOriginal("2024:08:12 22:30:15") → Unix 秒（本地时区） */
    private fun parseLocalEpoch(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val epoch = try {
            LocalDateTime.parse(raw.replace('-', ':').trim(), exifTimeFmt)
                .atZone(ZoneId.systemDefault()).toEpochSecond()
        } catch (e: Exception) {
            null
        } ?: return null
        return epoch
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
     * FITS 先验（§0.108）：**没有 EXIF，但 FITS 头往往比 EXIF 更专业**。
     *
     *  - 视场：`CDELT1/2 × NAXIS`（WCS 每像素尺度）→ 与 EXIF 焦距先验同一条「可信视场」闸门；
     *  - 时刻：`DATE-OBS`（FITS 约定即 UTC）—— 太阳系天体标注因此对望远镜素材同样可用；
     *  - 位置：`SITELAT/SITELONG`（部分产品写 `LATITUDE/LONGITUD`）——观测站坐标。
     *
     * 缺项一律 null（不猜）：拿不到就退回盲解，与"照片没有 EXIF"同一降级口径。
     */
    private fun readFits(imagePath: String): ExifPriors {
        val meta = try {
            com.starcam.astro.util.FitsImage.readMeta(imagePath)
        } catch (e: Throwable) {
            null
        } ?: return ExifPriors(null, null, null, null, false)
        val hasSite = meta.siteLat != null && meta.siteLon != null
        return ExifPriors(
            fovDeg = meta.fovDeg,
            latDeg = meta.siteLat,
            lonDeg = meta.siteLon,
            epochSec = parseFitsDateObs(meta.dateObs),
            timeIsUtc = true,
            locationSource = if (hasSite) LocationSource.EXIF else LocationSource.NONE,
        )
    }

    /**
     * DATE-OBS（FITS 标准写法 `2024-01-01T12:00:00`，UTC）→ Unix 秒。
     * 容忍：空格分隔、尾随 `Z`、小数秒、时区后缀（只取到秒的部分）。
     */
    private fun parseFitsDateObs(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val core = raw.trim().replace(' ', 'T').take(19)
        return try {
            LocalDateTime.parse(core).toEpochSecond(ZoneOffset.UTC)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * §0.59：这张照片是否「值得用当前定位兜底」——缺 GPS（或只有半套）但有拍摄时间。
     *
     * 供调用方在求解前判断要不要去取一次定位，避免无谓的定位开销；
     * 也是「有拍摄时间」这一前提的唯一判据（无时刻则补位置毫无意义）。
     *
     * §0.108：**FITS 一律不兜底** —— 望远镜素材的拍摄者在观测站，手机当前位置
     * 与拍摄地无关（拿手机定位去标注天文台拍的照片，比不标更糟）。缺 SITELAT/SITELONG
     * 就当没有位置。
     */
    fun needsLocationFallback(imagePath: String): Boolean {
        if (com.starcam.astro.util.FitsImage.isFitsPath(imagePath)) return false
        val priors = try {
            read(imagePath)
        } catch (e: Throwable) {
            return false
        }
        return priors.epochSec != null && (priors.latDeg == null || priors.lonDeg == null)
    }
}