package com.starcam.astro.astro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** 模拟星空生成模式：经典星座天区 vs 全天球均匀随机 */
enum class SyntheticSkyMode {
    CLASSIC,
    FULL_SKY,
}

/**
 * 离线演示模式：用内置星表生成一张"模拟夜空照片"，并给出与之精确一致的识别结果。
 * 生成器与解算器共用同一个 [WcsTransform]，因此叠加标注永远与照片严格对齐，
 * 无需网络与 API Key 即可完整体验"拍照认星"流程。
 */
object DemoSolver {

    private const val W = 1200
    private const val H = 1600
    private const val MAG_LIMIT = 4.2
    private const val FULL_SKY_MIN_STARS = 10

    /** 演示天空区域（前 9 项保持原始顺序与坐标不变，向后扩展至 18 个经典星座天区，中心坐标 J2000） */
    val regions = listOf(
        SkyRegion("猎户座", 83.5, 4.5, 34.0),
        SkyRegion("北斗七星", 166.0, 58.0, 40.0),
        SkyRegion("仙后座", 12.5, 62.0, 42.0),
        SkyRegion("天鹅座", 305.0, 42.0, 38.0),
        SkyRegion("天琴—天鹰（织女·牛郎）", 287.0, 24.0, 48.0),
        SkyRegion("天蝎座", 249.0, -28.0, 36.0),
        SkyRegion("狮子座", 155.0, 15.0, 36.0),
        SkyRegion("金牛座", 66.0, 20.0, 32.0),
        SkyRegion("牧夫座（大角）", 214.0, 19.0, 32.0),
        SkyRegion("双子座", 108.0, 24.0, 34.0),
        SkyRegion("大犬座（天狼星）", 103.0, -21.0, 34.0),
        SkyRegion("御夫座（五车二）", 82.0, 42.0, 34.0),
        SkyRegion("英仙座", 51.0, 44.0, 34.0),
        SkyRegion("仙女座—飞马座", 8.0, 30.0, 42.0),
        SkyRegion("人马座（银河中心）", 281.0, -28.0, 36.0),
        SkyRegion("南十字—半人马", 196.0, -58.0, 36.0),
        SkyRegion("船底座（老人星）", 125.0, -58.0, 38.0),
        SkyRegion("室女座（角宿一）", 198.0, -6.0, 40.0),
    )

    private val shortLabelsZh = listOf(
        "猎户座", "北斗七星", "仙后座", "天鹅座", "织女·牛郎", "天蝎座",
        "狮子座", "金牛座", "牧夫座", "双子座", "大犬座", "御夫座",
        "英仙座", "仙女·飞马", "人马座", "南十字", "船底座", "室女座",
    )

    private val shortLabelsEn = listOf(
        "Orion", "Big Dipper", "Cassiopeia", "Cygnus", "Summer Tri.", "Scorpius",
        "Leo", "Taurus", "Boötes", "Gemini", "Canis Major", "Auriga",
        "Perseus", "Andromeda", "Sagittarius", "Crux", "Carina", "Virgo",
    )

    fun regionShortLabel(index: Int, isEn: Boolean): String =
        if (isEn) shortLabelsEn.getOrElse(index) { regions.getOrNull(index)?.name ?: "" }
        else shortLabelsZh.getOrElse(index) { regions.getOrNull(index)?.name ?: "" }

    private val classicShuffleBag = ArrayDeque<Int>()
    private var lastClassicIndex = -1

    /**
     * 生成经典星座天区：
     * - 指定 [selectedIndex] 时直接定位于该星座（正北朝上、构图端正）；
     * - 未指定时从 18 个经典星座中无放回轮换抽取，并叠加轻微中心偏移、旋转与视场扰动，避免画面单调重复。
     */
    @Synchronized
    fun nextClassicRegion(
        selectedIndex: Int? = null,
        random: Random = Random.Default,
    ): SkyRegion {
        if (selectedIndex != null && selectedIndex in regions.indices) {
            return regions[selectedIndex]
        }
        if (classicShuffleBag.isEmpty()) {
            val shuffled = regions.indices.shuffled(random)
            // 避免新旧两轮首尾撞车
            val ordered = if (shuffled.size > 1 && shuffled.first() == lastClassicIndex) {
                shuffled.drop(1) + shuffled.first()
            } else {
                shuffled
            }
            classicShuffleBag.addAll(ordered)
        }
        val idx = classicShuffleBag.removeFirst()
        lastClassicIndex = idx
        val base = regions[idx]
        val dRa = random.nextDouble(-4.0, 4.0)
        val dDec = random.nextDouble(-3.5, 3.5)
        val dFov = random.nextDouble(-4.0, 4.0)
        val rot = random.nextDouble(-25.0, 25.0)
        val ra = ((base.raDeg + dRa) % 360.0 + 360.0) % 360.0
        val dec = (base.decDeg + dDec).coerceIn(-82.0, 82.0)
        val fov = (base.fovDeg + dFov).coerceIn(26.0, 62.0)
        return SkyRegion(base.name, ra, dec, fov, rotationDeg = rot)
    }

    /**
     * 真全天球均匀随机采样：按立体角均匀抽取 `(RA, Dec)`、随机相机旋转角 `0°–360°` 与视场角，
     * 并过滤掉可见星过少的空白天区，保证每次生成都是全新且富含亮星的星空。
     */
    fun randomFullSkyRegion(
        fovPresetDeg: Double? = null,
        isEn: Boolean = false,
        random: Random = Random.Default,
    ): SkyRegion {
        var bestRegion: SkyRegion? = null
        var bestCount = -1
        for (attempt in 0 until 40) {
            val ra = random.nextDouble(0.0, 360.0)
            // 按 sin(dec) 均匀采样，保证天球面积（立体角）均匀分布
            val sinDec = random.nextDouble(-0.985, 0.985)
            val dec = Math.toDegrees(asin(sinDec))
            val fov = fovPresetDeg ?: random.nextDouble(28.0, 68.0)
            val rot = random.nextDouble(0.0, 360.0)
            val label = formatFullSkyName(ra, dec, isEn)
            val candidate = SkyRegion(label, ra, dec, fov, rotationDeg = rot)
            val count = countVisibleStars(wcsFor(candidate))
            if (count >= FULL_SKY_MIN_STARS) {
                return candidate
            }
            if (count > bestCount) {
                bestCount = count
                bestRegion = candidate
            }
        }
        return bestRegion ?: regions.first()
    }

    internal fun countVisibleStars(wcs: WcsTransform): Int {
        var count = 0
        for (star in StarCatalogData.stars) {
            if (star.mag > MAG_LIMIT) continue
            val p = wcs.skyToScreen(star.ra, star.dec, W, H)
            val x = p[0]
            val y = p[1]
            if (x in 20f..(W - 20f) && y in 20f..(H - 20f)) {
                count++
            }
        }
        return count
    }

    private fun formatFullSkyName(ra: Double, dec: Double, isEn: Boolean): String {
        val sign = if (dec >= 0) "+" else ""
        return if (isEn) {
            String.format(Locale.ROOT, "All-Sky · RA %.1f° Dec %s%.1f°", ra, sign, dec)
        } else {
            String.format(Locale.ROOT, "全天随机 · RA %.1f° Dec %s%.1f°", ra, sign, dec)
        }
    }

    /** 生成一张模拟夜空照片（PNG，保存到应用缓存目录） */
    fun generateDemoImage(context: Context, region: SkyRegion): File {
        val wcs = wcsFor(region)
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // 夜空背景 + 轻微径向渐变（模拟光污染/暗角）
        val bg = Paint().apply { color = Color.rgb(9, 12, 28) }
        canvas.drawRect(0f, 0f, W.toFloat(), H.toFloat(), bg)
        val vignette = Paint().apply {
            color = Color.argb(18, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = W * 0.9f
        }
        canvas.drawCircle(W / 2f, H / 2f, W * 0.2f, vignette)

        // 星点：半径/亮度随星等变化，亮星带光晕
        val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        for (star in StarCatalogData.stars) {
            if (star.mag > MAG_LIMIT) continue
            val p = wcs.skyToScreen(star.ra, star.dec, W, H)
            val x = p[0]
            val y = p[1]
            if (x < -30f || x > W + 30f || y < -30f || y > H + 30f) continue
            val r = ((4.8 - star.mag).coerceIn(1.0, 3.4) * 1.9).toFloat()
            val alpha = (255.0 * (1.0 - (star.mag - 1.4) / 4.0)).toInt().coerceIn(100, 255)
            if (star.mag < 2.0) {
                haloPaint.color = Color.argb(alpha / 3, 170, 200, 255)
                canvas.drawCircle(x, y, r * 3.0f, haloPaint)
            }
            starPaint.color = Color.argb(alpha, 255, 253, 242)
            canvas.drawCircle(x, y, r, starPaint)
        }

        val file = File(context.cacheDir, "demo_starfield.png")
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return file
    }

    /** 与生成器完全一致的 WCS（支持任意相机旋转角，保持正像 parity=+1 且 det = -cd² 不变） */
    fun wcsFor(region: SkyRegion): WcsTransform {
        val cd = region.fovDeg / W // 度/像素（横向覆盖视场）
        if (region.rotationDeg == 0.0) {
            return WcsTransform(
                crpix1 = W / 2.0 + 0.5,
                crpix2 = H / 2.0 + 0.5,
                crval1 = region.raDeg,
                crval2 = region.decDeg,
                cd11 = -cd, cd12 = 0.0, // 北朝上：赤经增大方向在图像左侧
                cd21 = 0.0, cd22 = cd,
            )
        }
        val theta = region.rotationDeg * PI / 180.0
        val c = cos(theta)
        val s = sin(theta)
        return WcsTransform(
            crpix1 = W / 2.0 + 0.5,
            crpix2 = H / 2.0 + 0.5,
            crval1 = region.raDeg,
            crval2 = region.decDeg,
            cd11 = -cd * c, cd12 = -cd * s,
            cd21 = -cd * s, cd22 = cd * c,
        )
    }

    /** 与生成器一致的识别结果 */
    fun solveFor(region: SkyRegion): SolveResult {
        val wcs = wcsFor(region)
        return SolveResult(
            raDeg = region.raDeg,
            decDeg = region.decDeg,
            pixScaleArcsec = region.fovDeg * 3600.0 / W,
            orientationDeg = region.rotationDeg,
            parity = 1,
            imageWidth = W,
            imageHeight = H,
            subId = null,
            fieldRadiusArcmin = region.fovDeg * 30.0,
            wcs = wcs,
        )
    }
}
