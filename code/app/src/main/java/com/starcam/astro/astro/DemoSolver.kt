package com.starcam.astro.astro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI

/**
 * 离线演示模式：用内置星表生成一张"模拟夜空照片"，并给出与之精确一致的识别结果。
 * 生成器与解算器共用同一个 [WcsTransform]，因此叠加标注永远与照片严格对齐，
 * 无需网络与 API Key 即可完整体验"拍照认星"流程。
 */
object DemoSolver {

    private const val W = 1200
    private const val H = 1600
    private const val MAG_LIMIT = 4.2

    /** 演示天空区域（均为著名天区，中心坐标 J2000） */
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
    )

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

    /** 与生成器完全一致的 WCS（北朝上、无翻转） */
    fun wcsFor(region: SkyRegion): WcsTransform {
        val cd = region.fovDeg / W // 度/像素（横向覆盖视场）
        return WcsTransform(
            crpix1 = W / 2.0 + 0.5,
            crpix2 = H / 2.0 + 0.5,
            crval1 = region.raDeg,
            crval2 = region.decDeg,
            cd11 = -cd, cd12 = 0.0, // 北朝上：赤经增大方向在图像左侧
            cd21 = 0.0, cd22 = cd,
        )
    }

    /** 与生成器一致的识别结果 */
    fun solveFor(region: SkyRegion): SolveResult {
        val wcs = wcsFor(region)
        return SolveResult(
            raDeg = region.raDeg,
            decDeg = region.decDeg,
            pixScaleArcsec = region.fovDeg * 3600.0 / W,
            orientationDeg = 0.0,
            parity = 1,
            imageWidth = W,
            imageHeight = H,
            subId = null,
            fieldRadiusArcmin = region.fovDeg * 30.0,
            wcs = wcs,
        )
    }
}
