package com.starcam.astro.ui.result

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.starcam.astro.astro.LayerFlags
import com.starcam.astro.astro.LayeredRenderer
import com.starcam.astro.astro.SolveDiagnostics
import com.starcam.astro.ui.theme.LocaleState
import kotlin.math.hypot

/** 将识别结果（照片 + 星座连线 + 星名标注）渲染为位图，供保存/分享（支持双语与图层开关 §0.47） */
internal fun renderAnnotatedBitmap(
    state: ResultUiState.Success,
    isEnglish: Boolean = LocaleState.isEnglish,
    flags: LayerFlags = LayerFlags.ALL,
): Bitmap {
    val src = state.bitmap
    val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    canvas.drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
    val scene = LayeredRenderer.OverlayScene(
        src.width, src.height, state.stars, state.lines, state.labels, state.messier, state.solar,
    )
    LayeredRenderer.draw(
        canvas, scene, flags, isEnglish, density = 2.0f,
        degPerPx = plateScaleDegPerPx(state) ?: Float.NaN,
    )
    return out
}

/**
 * §0.58 板比例（天球度 / 图像像素）：由求解视场与位图宽度推得。
 * 供渲染器把日月画成真实视直径；求解视场异常时返回 null（退化为固定标记）。
 */
internal fun plateScaleDegPerPx(state: ResultUiState.Success): Float? {
    val w = state.bitmap.width
    val fov = state.solve.fieldWidthDeg
    if (w <= 0 || fov <= 0.0 || !fov.isFinite()) return null
    return (fov / w).toFloat()
}

/**
 * 失败诊断合成图（§0.34）：照片 + 金色星点圈，供放大查看/查看器使用。
 * 与加载页/失败页的动态标注一致：圈大小随星点亮度。
 *
 * §0.60：输出与原图**同分辨率**。此前固定降采样到长边 800px，在查看器里放大后
 * 明显比原图糊（2026-09-12 开发者报）；[SolveDiagnostics.DiagStar] 坐标为归一化值，换尺寸不会错位，
 * 圆半径与描边宽度按同一倍率放大以保持视觉比例。
 */
internal fun renderDiagnosticBitmap(src: Bitmap, diag: SolveDiagnostics): Bitmap {
    val outW = src.width
    val outH = src.height
    // 以原 800px 输出为基准的放大倍率：用于等比放大圆半径/描边，观感与旧版一致
    val annotationScale = maxOf(outW, outH) / 800f
    val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    canvas.drawBitmap(src, null, Rect(0, 0, outW, outH), Paint(Paint.FILTER_BITMAP_FLAG))
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFFFC24B.toInt()
        strokeWidth = 2.5f * annotationScale
    }
    for (s in diag.stars) {
        canvas.drawCircle(
            s.x * outW,
            s.y * outH,
            (3f + 7f * s.brightness01).coerceAtLeast(3f) * annotationScale,
            paint,
        )
    }
    return out
}

/**
 * §0.47：天体命中测试（图像坐标系）。
 * [imgX]/[imgY] 为位图像素坐标；容差亦为位图像素，调用方按当前缩放换算：
 * 位图容差 = 屏幕容差px ÷ (屏幕px ÷ 位图px)。
 */
internal fun hitTestObjectAt(
    state: ResultUiState.Success,
    imgX: Float,
    imgY: Float,
    messierTolImgPx: Float,
    starTolImgPx: Float,
    flags: LayerFlags = LayerFlags.ALL,
): SkyObjectRef? {
    // §0.58 太阳系天体优先级最高：日月行星是全画面最醒目的目标
    if (flags.planets) {
        var solarRef: SkyObjectRef? = null
        var solarDist = messierTolImgPx * 1.6f
        for (s in state.solar) {
            if (!s.visible) continue
            val d = hypot((s.x - imgX).toDouble(), (s.y - imgY).toDouble()).toFloat()
            if (d < solarDist) {
                solarDist = d
                solarRef = SkyObjectRef.SolarRef(s.pos)
            }
        }
        if (solarRef != null) return solarRef
    }

    // 梅西耶
    if (flags.messier) {
        var best: SkyObjectRef? = null
        var bestDist = messierTolImgPx
        for (m in state.messier) {
            if (!m.visible) continue
            val d = hypot((m.x - imgX).toDouble(), (m.y - imgY).toDouble()).toFloat()
            if (d < bestDist) {
                bestDist = d
                best = SkyObjectRef.MessierRef(m.obj)
            }
        }
        if (best != null) return best
    }

    // 亮星：只挑有标注价值的亮星（mag ≤ 3.2，与星名图层同域）
    if (flags.starNames) {
        var starDist = starTolImgPx
        var starRef: SkyObjectRef? = null
        for (s in state.stars) {
            if (!s.visible || s.entry.mag > 3.2) continue
            val d = hypot((s.x - imgX).toDouble(), (s.y - imgY).toDouble()).toFloat()
            if (d < starDist) {
                starDist = d
                starRef = SkyObjectRef.StarRef(s.entry)
            }
        }
        return starRef
    }

    return null
}
