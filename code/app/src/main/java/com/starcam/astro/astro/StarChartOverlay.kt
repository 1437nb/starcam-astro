package com.starcam.astro.astro

/**
 * 星座叠加渲染：把星表投影到屏幕坐标，供 Compose Canvas 绘制
 * 星座连线、星点与星名。
 */
object StarChartOverlay {

    /** 投影后的星点 */
    data class Star2D(
        val entry: StarEntry,
        val x: Float,
        val y: Float,
        val visible: Boolean,
    )

    /** 星座连线（两端点均为可见星） */
    data class Line2D(val a: Star2D, val b: Star2D)

    /** 星座名称标签（屏幕坐标） */
    data class Label2D(val text: String, val x: Float, val y: Float)

    /**
     * 将全部星表投影到屏幕。
     * 返回列表与 [StarCatalogData.stars] 一一对应（目录下标即列表下标）。
     */
    fun projectStars(
        wcs: WcsTransform,
        width: Int,
        height: Int,
        marginPx: Float = 140f,
    ): List<Star2D> {
        val out = ArrayList<Star2D>(StarCatalogData.stars.size)
        for (star in StarCatalogData.stars) {
            val p = wcs.skyToScreen(star.ra, star.dec, width, height)
            if (p[0].isNaN() || p[1].isNaN()) {
                out.add(Star2D(star, Float.NaN, Float.NaN, visible = false))
                continue
            }
            val visible = p[0] in -marginPx..(width + marginPx) &&
                p[1] in -marginPx..(height + marginPx)
            out.add(Star2D(star, p[0], p[1], visible))
        }
        return out
    }

    /** 提取两端点均可见的星座连线（要求传入 [projectStars] 的结果） */
    fun projectLines(stars: List<Star2D>): List<Line2D> {
        if (stars.size != StarCatalogData.stars.size) return emptyList()
        val res = ArrayList<Line2D>(64)
        for (seg in StarCatalogData.constellationLines) {
            val a = stars[seg[0]]
            val b = stars[seg[1]]
            if (a.visible && b.visible) res.add(Line2D(a, b))
        }
        return res
    }

    /** 按星座分组的可见星屏幕坐标质心 → 星座名标签（跳过无星座的增补星 §0.42） */
    fun constellationLabels(stars: List<Star2D>): List<Label2D> {
        val groups = HashMap<String, MutableList<Star2D>>()
        for (s in stars) {
            if (s.visible && s.entry.con.isNotEmpty()) {
                groups.getOrPut(s.entry.con) { mutableListOf() }.add(s)
            }
        }
        val labels = ArrayList<Label2D>(8)
        for ((con, list) in groups) {
            if (list.size < 2) continue
            val cx = list.sumOf { it.x.toDouble() }.toFloat() / list.size
            val cy = list.sumOf { it.y.toDouble() }.toFloat() / list.size
            labels.add(Label2D(Constellations.zhName(con), cx, cy))
        }
        return labels
    }

    /** §0.43c：梅西耶深空天体投影（画面内返回可见标记） */
    data class Messier2D(val obj: MessierObject, val x: Float, val y: Float, val visible: Boolean)

    fun projectMessier(wcs: WcsTransform, width: Int, height: Int): List<Messier2D> {
        val out = ArrayList<Messier2D>(16)
        for (obj in MessierCatalog.byNumber.values) {
            val p = wcs.skyToScreen(obj.ra, obj.dec, width, height)
            if (p[0].isNaN() || p[1].isNaN()) {
                out.add(Messier2D(obj, Float.NaN, Float.NaN, visible = false))
                continue
            }
            val visible = p[0] in -80f..(width + 80f) && p[1] in -80f..(height + 80f)
            out.add(Messier2D(obj, p[0], p[1], visible))
        }
        return out
    }
}
