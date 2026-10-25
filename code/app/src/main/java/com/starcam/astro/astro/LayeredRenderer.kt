package com.starcam.astro.astro

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface

/**
 * 叠加图层开关（§0.47）：独立控制 6 类标注是否绘制。
 * - lines：星座连线
 * - starNames：恒星专名 / Bayer 标号
 * - constellationNames：星座名称标签
 * - messier：梅西耶深空天体标注
 * - planets：太阳系天体（月亮 / 行星，§0.58）
 * - starCircles：检测星点圈（诊断类）
 *
 * 供结果页预览 Canvas、放大查看器与保存相册位图三处共用，保证渲染一致。
 */
data class LayerFlags(
    val lines: Boolean = true,
    val starNames: Boolean = true,
    val constellationNames: Boolean = true,
    val messier: Boolean = true,
    val planets: Boolean = true,
) {
    val allEnabled: Boolean get() = lines && starNames && constellationNames && messier && planets

    companion object {
        val ALL = LayerFlags()
        /** §0.120：仅保留星座连线图层（导出纯净星座连线图） */
        val LINES_ONLY = LayerFlags(
            lines = true,
            starNames = false,
            constellationNames = false,
            messier = false,
            planets = false,
        )
    }
}

/**
 * 分层渲染统一入口（§0.47）：把投影好的 [OverlayScene] 按 [flags] 画到 canvas。
 * 与位图渲染（保存/查看器）及 Compose Canvas 预览共用一套绘制逻辑。
 */
object LayeredRenderer {

    /**
     * §0.78 渲染用 Paint 集合（**每帧复用的 scratch**）。
     *
     * 原来 [draw] 每次调用都新建 **9 个 Paint** —— 而它是 AR 叠加层的每帧路径
     * （约 60fps）→ 540 个 Paint/秒，Paint 构造带 native 开销，纯 GC 压力。
     * 这里按用途收敛成 3 个（描边 / 文字 / 填充），draw 内只改属性
     * （改属性是廉价的 native setter，远低于重新构造）。
     *
     * **由调用方持有**、而不是放在 object 里：`draw()` 会被主线程（Compose 预览）
     * 与 IO 线程（保存相册 / 放大查看器的位图渲染）同时调用，Paint 不是线程安全的，
     * 共享一份会互相踩。Compose 侧用 `remember { LayeredRenderer.RenderPaints() }`。
     */
    class RenderPaints {
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
        val text = Paint(Paint.ANTI_ALIAS_FLAG)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            stroke.style = Paint.Style.STROKE
            fill.style = Paint.Style.FILL
            text.typeface = Typeface.DEFAULT_BOLD
        }
    }

    /** 已投影的叠加场景数据（结果页 Success 状态持有，复用避免重复投影） */
    data class OverlayScene(
        val width: Int,
        val height: Int,
        val stars: List<StarChartOverlay.Star2D>,
        val lines: List<StarChartOverlay.Line2D>,
        val labels: List<StarChartOverlay.Label2D>,
        val messier: List<StarChartOverlay.Messier2D>,
        /** §0.58 太阳系天体（缺省空列表，兼容既有调用点） */
        val solar: List<StarChartOverlay.Solar2D> = emptyList(),
    )

    /**
     * 把场景各图层绘制到 [canvas]（位图像素坐标系，与 [scene.width/height] 对应）。
     * [density] 控制字号/线宽缩放（位图渲染传 2.0，Compose 预览传 1.sp 的像素值）。
     * [drawWidth]/[drawHeight]：绘制区域实际尺寸（像素）。Compose 预览必须传
     * DrawScope.size —— nativeCanvas.width 是整块窗口画布而非组合件绘制区，
     * 直接用 canvas.width 会让 x/y 缩放比不一致、星座严重变形（v1.5.33 实测）。
     * [dimBelowHorizon]：地平线以下（belowHorizon=true）的元素变暗（§0.53 全天星空）。
     * [degPerPx]：天球角度 / 图像像素（板比例）。非空时日月按**真实视直径**绘制
     * （这才是天文软件该有的样子）；为空则退化为固定尺寸标记。行星因视直径远小于
     * 一像素，一律用标记环绘制。
     * [paints]：§0.78 复用传入的 Paint（每帧路径必须传，见 [RenderPaints]）；
     * 为 null 时本次调用内部临时创建 —— 位图渲染等一次性路径可以省掉这个约定。
     */
    fun draw(
        canvas: Canvas,
        scene: OverlayScene,
        flags: LayerFlags,
        isEnglish: Boolean,
        density: Float = 2.0f,
        night: Boolean = false,
        drawWidth: Float = canvas.width.toFloat(),
        drawHeight: Float = canvas.height.toFloat(),
        dimBelowHorizon: Boolean = false,
        degPerPx: Float? = null,
        paints: RenderPaints? = null,
    ) {
        val w = scene.width
        val h = scene.height
        // 坐标缩放（图像坐标 → 画布像素）；字号/线宽为画布绝对像素，不随 sx 缩放
        val sx = drawWidth / w
        val sy = drawHeight / h

        // §0.78：Paint 每帧复用；seg 是连线的收缩结果缓冲（循环外建一次，见 shrinkInto）
        val p = paints ?: RenderPaints()
        val seg = FloatArray(4)

        val lineColor = if (night) 0x66FF8A80.toInt() else 0x8C7FD0FF.toInt()
        val nameColor = if (night) 0xFFE8A8A0.toInt() else 0xFFF7EDCB.toInt()
        val conColor = if (night) 0x55E8A8A0.toInt() else 0x5CBBD8FF.toInt()
        // 地平线以下变暗色（透明度乘 ~0.35；仅 AR 全天星空模式启用）
        val dimLine = if (night) 0x22FF8A80.toInt() else 0x2E7FD0FF.toInt()
        val dimName = if (night) 0x55E8A8A0.toInt() else 0x55F7EDCB.toInt()
        val dimMessier = if (night) 0x55E8A8A0.toInt() else 0x55F7EDCB.toInt()

        // ① 星座连线：两端按星等留空（星星不被线覆盖）
        if (flags.lines) {
            p.stroke.strokeWidth = 1.2f * density
            for (line in scene.lines) {
                val ok = shrinkInto(
                    seg,
                    line.a.x, line.a.y, line.a.entry.mag,
                    line.b.x, line.b.y, line.b.entry.mag,
                )
                if (!ok) continue
                p.stroke.color = if (dimBelowHorizon && (line.a.belowHorizon || line.b.belowHorizon)) {
                    dimLine
                } else {
                    lineColor
                }
                canvas.drawLine(seg[0] * sx, seg[1] * sy, seg[2] * sx, seg[3] * sy, p.stroke)
            }
        }

        // §0.120：标签避让与防越界机制（BoundingBox 记录已占用的文字/图标区域，避免重叠与裁切）
        val margin = 6f * density
        val pad = 2f * density
        val occupiedBoxes = mutableListOf<BoundingBox>()

        // ② 梅西耶深空天体标注（先排布深空天体，使其圈与文字优先占位；小圆圈 + 编号与名称，颜色按类型）
        if (flags.messier) {
            p.stroke.strokeWidth = 1.4f * density
            p.text.textSize = 10f * density
            p.text.setShadowLayer(2.5f * density, 1f, 1f, 0xFF000000.toInt())
            val fm = p.text.fontMetrics
            val ascentAbs = -fm.ascent
            val descent = fm.descent
            val r = 7f * density
            for (m in scene.messier) {
                if (!m.visible) continue
                val cx = m.x * sx
                val cy = m.y * sy
                // 仅对位于画面内的天体标注
                if (cx !in -r..(drawWidth + r) || cy !in -r..(drawHeight + r)) continue
                val dim = dimBelowHorizon && m.belowHorizon
                p.stroke.color = if (dim) dimMessier else MessierCatalog.typeColor(m.obj.type)
                p.text.color = if (dim) dimMessier else nameColor
                canvas.drawCircle(cx, cy, r, p.stroke)
                occupiedBoxes.add(BoundingBox(cx - r - pad, cy - r - pad, cx + r + pad, cy + r + pad))

                val label = m.obj.label(isEnglish)
                val tw = p.text.measureText(label)
                var lx = cx + 10f * density
                var ly = cy - 6f * density
                // 边界保护：若右侧放不下，翻转至左侧
                if (lx + tw > drawWidth - margin) {
                    lx = cx - 10f * density - tw
                }
                lx = lx.coerceIn(margin, maxOf(margin, drawWidth - margin - tw))
                ly = ly.coerceIn(margin + ascentAbs, maxOf(margin + ascentAbs, drawHeight - margin - descent))

                canvas.drawText(label, lx, ly, p.text)
                occupiedBoxes.add(BoundingBox(lx - pad, ly - ascentAbs - pad, lx + tw + pad, ly + descent + pad))
            }
        }

        // ③ 太阳系天体（§0.58）：月亮 / 行星 / 太阳
        //    日月按真实视直径绘制（给定板比例时），行星用标记环 + 名称。
        if (flags.planets && scene.solar.isNotEmpty()) {
            p.stroke.strokeWidth = 1.6f * density
            p.text.textSize = 11f * density
            p.text.setShadowLayer(3f * density, 1f, 1f, 0xFF000000.toInt())
            // 真实视直径上限：不超过画面短边的 1/3（防止极端长焦下日月糊满屏）
            val maxDiskR = minOf(drawWidth, drawHeight) / 3f
            val fm = p.text.fontMetrics
            val ascentAbs = -fm.ascent
            val descent = fm.descent
            for (s in scene.solar) {
                if (!s.visible) continue
                val cx = s.x * sx
                val cy = s.y * sy
                if (cx !in 0f..drawWidth || cy !in 0f..drawHeight) continue
                val body = s.pos.body
                val dim = dimBelowHorizon && s.belowHorizon
                val baseColor = SolarSystemCatalog.color(body)
                val color = if (dim) withAlpha(baseColor, 0.35f) else baseColor

                val markerR = 8f * density
                // 日月：优先按真实视直径绘制
                var diskR = 0f
                if (degPerPx != null && degPerPx > 0f && SolarSystemCatalog.isDisk(body)) {
                    val px = (s.pos.angularDiameterDeg / 2.0 / degPerPx).toFloat() * sx
                    diskR = px.coerceIn(2.5f * density, maxDiskR)
                }

                val effR = if (SolarSystemCatalog.isDisk(body) && diskR > 0f) diskR else markerR
                if (SolarSystemCatalog.isDisk(body) && diskR > 0f) {
                    // 光晕 → 实心圆面 → 细描边，模拟目视观感
                    p.fill.color = withAlpha(color, if (dim) 0.10f else 0.22f)
                    canvas.drawCircle(cx, cy, diskR * 1.35f, p.fill)
                    p.fill.color = withAlpha(color, if (dim) 0.55f else 0.95f)
                    canvas.drawCircle(cx, cy, diskR, p.fill)
                    p.stroke.color = withAlpha(color, if (dim) 0.5f else 0.9f)
                    canvas.drawCircle(cx, cy, diskR, p.stroke)
                } else {
                    // 行星 / 未知板比例：实心小点 + 外环
                    p.fill.color = withAlpha(color, if (dim) 0.5f else 0.95f)
                    canvas.drawCircle(cx, cy, markerR * 0.42f, p.fill)
                    p.stroke.color = withAlpha(color, if (dim) 0.5f else 0.9f)
                    canvas.drawCircle(cx, cy, markerR, p.stroke)
                }
                occupiedBoxes.add(BoundingBox(cx - effR - pad, cy - effR - pad, cx + effR + pad, cy + effR + pad))

                p.text.color = if (dim) dimName else nameColor
                val planetName = SolarSystemCatalog.name(body, isEnglish)
                val ptw = p.text.measureText(planetName)
                var labelX = cx + effR + 5f * density
                var labelY = cy + 4f * density
                if (labelX + ptw > drawWidth - margin) {
                    labelX = cx - effR - 5f * density - ptw
                }
                labelX = labelX.coerceIn(margin, maxOf(margin, drawWidth - margin - ptw))
                labelY = labelY.coerceIn(margin + ascentAbs, maxOf(margin + ascentAbs, drawHeight - margin - descent))
                canvas.drawText(planetName, labelX, labelY, p.text)
                occupiedBoxes.add(BoundingBox(labelX - pad, labelY - ascentAbs - pad, labelX + ptw + pad, labelY + descent + pad))
            }
        }

        // ④ 恒星专名（亮星且能解析出名字）
        //    §0.120：解决两类重叠与越界 bug：
        //    1. 当显示梅西耶深空天体（如 M45 昴星团、M42 猎户座大星云）时，抑制其近邻星名（昴宿六、伐三等），避免成团重叠；
        //    2. 严格剔除画幅外的星，并采用 4 象限候选 + 边界安全收敛，确保文字永远不会伸出图片边缘或被截断。
        if (flags.starNames) {
            p.text.textSize = 12f * density
            p.text.setShadowLayer(3f * density, 1f, 1f, 0xFF000000.toInt())
            val fm = p.text.fontMetrics
            val ascentAbs = -fm.ascent
            val descent = fm.descent
            val messierSuppressionDist = 28f * density

            val candidates = scene.stars.filter { s ->
                if (!s.visible || s.entry.mag > 3.2) return@filter false
                val sxPos = s.x * sx
                val syPos = s.y * sy
                // 仅对坐标严格在画面内的星点标注名字
                if (sxPos !in 0f..drawWidth || syPos !in 0f..drawHeight) return@filter false
                // 当梅西耶天体开启时，若星点处于可见梅西耶天体中心近距离内（如 M45 内部的昴宿六），抑制星名
                if (flags.messier && scene.messier.any { m ->
                    m.visible && kotlin.math.hypot(sxPos - m.x * sx, syPos - m.y * sy) < messierSuppressionDist
                }) {
                    return@filter false
                }
                true
            }.sortedBy { it.entry.mag } // 优先排布较亮的恒星

            for (s in candidates) {
                val name = StarNames.displayName(s.entry.hip, s.entry.name, isEnglish)
                if (name.isEmpty()) continue
                val tw = p.text.measureText(name)
                val placement = computeStarNamePlacement(
                    name = name,
                    px = s.x * sx,
                    py = s.y * sy,
                    mag = s.entry.mag,
                    textWidth = tw,
                    ascentAbs = ascentAbs,
                    descent = descent,
                    drawWidth = drawWidth,
                    drawHeight = drawHeight,
                    density = density,
                    occupiedBoxes = occupiedBoxes,
                )
                if (placement != null) {
                    p.text.color = if (dimBelowHorizon && s.belowHorizon) dimName else nameColor
                    canvas.drawText(placement.text, placement.x, placement.y, p.text)
                    occupiedBoxes.add(placement.box)
                }
            }
        }

        // ⑤ 星座名称标签（居中并边界收敛，防止超出边缘）
        if (flags.constellationNames) {
            p.text.textSize = 11f * density
            p.text.setShadowLayer(3f * density, 1f, 1f, 0xFF000000.toInt())
            p.text.color = conColor
            val fm = p.text.fontMetrics
            val ascentAbs = -fm.ascent
            val descent = fm.descent
            for (l in scene.labels) {
                val tw = p.text.measureText(l.text)
                val lx = (l.x * sx - tw / 2f).coerceIn(margin, maxOf(margin, drawWidth - margin - tw))
                val ly = (l.y * sy).coerceIn(margin + ascentAbs, maxOf(margin + ascentAbs, drawHeight - margin - descent))
                canvas.drawText(l.text, lx, ly, p.text)
            }
        }
    }

    /** 按比例降低 alpha（用于地平线以下变暗） */
    private fun withAlpha(argb: Int, factor: Float): Int {
        val a = ((argb ushr 24) and 0xFF) * factor.coerceIn(0f, 1f)
        return (a.toInt().coerceIn(0, 255) shl 24) or (argb and 0x00FFFFFF)
    }

    /** 带图层开关的标注位图渲染：把 [scene] 按 [flags] 叠加到 [src] 副本上 */
    fun renderBitmap(
        src: Bitmap,
        scene: OverlayScene,
        flags: LayerFlags,
        isEnglish: Boolean,
        degPerPx: Float? = null,
    ): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        draw(canvas, scene, flags, isEnglish, density = 2.0f, degPerPx = degPerPx)
        return out
    }

    /**
     * §0.78：[shrink] 的**零分配版本** —— 结果写入调用方的 [out]（长度须 ≥ 4），
     * 线段太短无法收缩时返回 false。
     *
     * 每帧绘制路径必须用这个：原 [shrink] 每条线返回一个新 `FloatArray(4)`，
     * 典型 50~200 条可见连线 → 数十~数百个数组/帧。
     */
    fun shrinkInto(
        out: FloatArray,
        ax: Float, ay: Float, magA: Double,
        bx: Float, by: Float, magB: Double,
    ): Boolean {
        val dx = bx - ax
        val dy = by - ay
        val len = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val gapA = gapOf(magA)
        val gapB = gapOf(magB)
        if (len <= gapA + gapB + 2f) return false
        val ux = dx / len
        val uy = dy / len
        out[0] = ax + ux * gapA
        out[1] = ay + uy * gapA
        out[2] = bx - ux * gapB
        out[3] = by - uy * gapB
        return true
    }

    /** 线段两端按星等收缩：越亮的星空隙越大（与 OverlayRenderer.shrink 一致） */
    fun shrink(
        ax: Float, ay: Float, magA: Double,
        bx: Float, by: Float, magB: Double,
    ): FloatArray? {
        val out = FloatArray(4)
        return if (shrinkInto(out, ax, ay, magA, bx, by, magB)) out else null
    }

    private fun gapOf(mag: Double): Float = ((4.8 - mag).coerceIn(0.8, 5.0) * 6f).toFloat()

    /** 轻量边界盒（纯 JVM 内部结构，用于标签避让与防越界计算） */
    internal data class BoundingBox(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        fun intersects(other: BoundingBox): Boolean =
            left < other.right && right > other.left && top < other.bottom && bottom > other.top
    }

    internal data class LabelPlacement(
        val text: String,
        val x: Float,
        val y: Float,
        val box: BoundingBox,
    )

    /**
     * §0.120：计算单个星名的最佳防碰撞避让与防越界坐标。
     * 依次尝试 4 个象限：右上 (1) -> 右下 (2) -> 左上 (3) -> 左下 (4)。
     * 若均碰撞，则使用右上经边界安全收敛的位置；若与已有关键标注重叠且非极亮星（mag > 2.0），返回 null 避让。
     */
    internal fun computeStarNamePlacement(
        name: String,
        px: Float,
        py: Float,
        mag: Double,
        textWidth: Float,
        ascentAbs: Float,
        descent: Float,
        drawWidth: Float,
        drawHeight: Float,
        density: Float,
        occupiedBoxes: List<BoundingBox>,
    ): LabelPlacement? {
        val offset = 7f * density
        val margin = 6f * density
        val pad = 2f * density

        val quadrants = listOf(
            px + offset to py - offset,
            px + offset to py + ascentAbs + offset,
            px - textWidth - offset to py - offset,
            px - textWidth - offset to py + ascentAbs + offset,
        )

        for ((tx, ty) in quadrants) {
            val box = BoundingBox(tx - pad, ty - ascentAbs - pad, tx + textWidth + pad, ty + descent + pad)
            val inCanvas = tx >= margin && (tx + textWidth) <= (drawWidth - margin) &&
                (ty - ascentAbs) >= margin && (ty + descent) <= (drawHeight - margin)
            if (inCanvas && occupiedBoxes.none { it.intersects(box) }) {
                return LabelPlacement(name, tx, ty, box)
            }
        }

        // 若 4 象限均无法完全无冲突容纳，尝试边界强制收敛
        val clampedTx = (px + offset).coerceIn(margin, maxOf(margin, drawWidth - margin - textWidth))
        val clampedTy = (py - offset).coerceIn(margin + ascentAbs, maxOf(margin + ascentAbs, drawHeight - margin - descent))
        val clampedBox = BoundingBox(clampedTx - pad, clampedTy - ascentAbs - pad, clampedTx + textWidth + pad, clampedTy + descent + pad)

        val hasCollision = occupiedBoxes.any { it.intersects(clampedBox) }
        if (hasCollision && mag > 2.0) {
            return null
        }
        return LabelPlacement(name, clampedTx, clampedTy, clampedBox)
    }
}
