package com.starcam.astro.astro

/** 一次成功识别返回的结果（对应 astrometry.net calibration） */
data class SolveResult(
    val raDeg: Double,             // 图像中心赤经 J2000（度）
    val decDeg: Double,            // 图像中心赤纬（度）
    val pixScaleArcsec: Double,    // 像素比例尺（角秒/像素）
    val orientationDeg: Double,    // 方向角（度）
    val parity: Int,               // 1 或 -1
    val imageWidth: Int,           // 定标使用的图像宽（像素）
    val imageHeight: Int,
    val subId: Long? = null,       // astrometry.net 任务号
    val fieldRadiusArcmin: Double? = null, // 视场半径（角分）
    val nMatch: Int? = null,       // 匹配星数（本地引擎）
    val nStars: Int? = null,       // 提取星点总数（官方引擎诊断，§0.20 起）
    val indexId: Int? = null,      // 命中索引编号（4112 等，官方引擎）
    val logodds: Double? = null,   // 求解对数似然比（官方引擎）
    val wcs: WcsTransform? = null, // 精确 WCS（来自 wcs.txt），叠加渲染首选
) {
    /** 视场宽度（度） */
    val fieldWidthDeg: Double get() = imageWidth * pixScaleArcsec / 3600.0

    /** 视场高度（度） */
    val fieldHeightDeg: Double get() = imageHeight * pixScaleArcsec / 3600.0
}

/** 离线演示用的天空区域 */
data class SkyRegion(val name: String, val raDeg: Double, val decDeg: Double, val fovDeg: Double)
