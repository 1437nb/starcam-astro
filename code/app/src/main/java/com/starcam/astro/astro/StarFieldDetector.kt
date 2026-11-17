package com.starcam.astro.astro

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 星点检测与量纲自适应投票亮度阈值计算（§0.142 模块化解耦）。
 *
 * 包含：
 * - Bitmap → 灰度缓冲转换（BT.601 系数）
 * - 滑动窗口 Box Blur 背景减除 + 8 邻域连通域质心提取 + 大面积亮源掩蔽
 * - 跨检测器（Box-blur / SEP）量纲自适应双通道投票阈值计算
 */
internal object StarFieldDetector {

    /** 精锐档 900/4154（§0.32.3 演示校准） */
    private const val SHARP_TOP_RATIO = 0.2167f

    /** 相对下限 60/4154 */
    private const val ADAPTIVE_FLOOR_RATIO = 0.0144f

    /** 弱星轮下限 25/4154 */
    internal const val WEAK_FLOOR_RATIO = 0.0060f

    fun voteBrightnessThreshold(brightness: List<Float>, forceThreshold: Float? = null): Float {
        forceThreshold?.let { return it }
        return voteThresholds(brightness, null).first
    }

    /**
     * 双阈值方案（§0.32.3 / §0.71 量纲自适应）：返回 (主阈值, 备用阈值)。
     */
    fun voteThresholds(brightness: List<Float>, forceThreshold: Float? = null): Pair<Float, Float> {
        forceThreshold?.let { return it to it }
        val sortedDesc = brightness.map { it.toFloat() }.sortedDescending()
        val anchor20 = sortedDesc[minOf(sortedDesc.size - 1, 19)]
        val top = sortedDesc.firstOrNull() ?: 0f
        if (top <= 0f) return Float.MAX_VALUE to Float.MAX_VALUE

        val sharp = top * SHARP_TOP_RATIO
        val adaptiveFloor = minOf(top * ADAPTIVE_FLOOR_RATIO, 60f)
        val adaptive = maxOf(adaptiveFloor, anchor20 * 0.35f)

        val steepAbs = sharp > 0f && sortedDesc.count { it >= sharp } >= 10 &&
            anchor20 >= sharp * 0.85f
        val steepRel = anchor20 >= top * 0.55f
        if (steepAbs || steepRel) {
            return sharp to adaptive
        }
        return adaptive to sharp
    }

    /** 从 Bitmap 检测星点（默认上限 100，与 SEP 路径同量级） */
    fun detectStars(bitmap: Bitmap, maxStars: Int = 100): List<DetectedStar> {
        val w = bitmap.width
        val h = bitmap.height
        val gray = bitmapToGray(bitmap)
        return detectStarsGray(w, h, gray, maxStars)
    }

    /** Bitmap → 灰度数组（0..255，与 [detectStars] 内部同一套系数） */
    fun bitmapToGray(bitmap: Bitmap): FloatArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val gray = FloatArray(w * h)
        for (i in pixels.indices) {
            val c = pixels[i]
            gray[i] = 0.299f * ((c shr 16) and 0xFF) +
                0.587f * ((c shr 8) and 0xFF) +
                0.114f * (c and 0xFF)
        }
        return gray
    }

    /** 核心检测（纯 JVM，可单测）：亮度数组 → 星点列表 */
    fun detectStarsGray(width: Int, height: Int, gray: FloatArray, maxStars: Int = 64): List<DetectedStar> {
        val n = width * height
        require(gray.size == n) { "灰度数组尺寸不匹配" }

        // 1) 背景估计（box blur）——去除光污染/月晕渐变
        val r = max(3, min(width, height) / 80)
        val bg = boxBlur(gray, width, height, r)

        // 2) 减背景，统计残差噪声水平
        val sub = FloatArray(n)
        var mean = 0.0
        var m2 = 0.0
        for (i in 0 until n) {
            val v = gray[i] - bg[i]
            sub[i] = v
            mean += v
            m2 += v * v
        }
        mean /= n
        m2 = m2 / n - mean * mean
        val sigma = sqrt(max(m2, 1.0)).toFloat()
        val thr = max(8f, 3.5f * sigma)

        // 3) 连通域（8 邻域）→ 亮度加权质心
        val visited = BooleanArray(n)
        val maxArea = max(64, n / 300) // 月亮/路灯等大亮斑上限
        val stackX = IntArray(maxArea + 16)
        val stackY = IntArray(maxArea + 16)
        val stars = ArrayList<DetectedStar>()

        for (i in 0 until n) {
            if (visited[i] || sub[i] <= thr) continue
            var sp = 0
            var sumV = 0.0
            var sumVx = 0.0
            var sumVy = 0.0
            var count = 0
            var maxV = 0f
            var minX = Int.MAX_VALUE
            var minY = Int.MAX_VALUE
            var maxX = 0
            var maxY = 0
            stackX[sp] = i % width
            stackY[sp] = i / width
            sp++
            visited[i] = true
            var overflow = false
            while (sp > 0) {
                sp--
                val x = stackX[sp]
                val y = stackY[sp]
                val v = sub[y * width + x]
                sumV += v
                sumVx += v * x
                sumVy += v * y
                count++
                if (v > maxV) maxV = v
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (count > maxArea) {
                    overflow = true
                    break
                }
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        val ny = y + dy
                        if (nx in 0 until width && ny in 0 until height) {
                            val ni = ny * width + nx
                            if (!visited[ni] && sub[ni] > thr) {
                                visited[ni] = true
                                if (sp >= stackX.size) {
                                    overflow = true
                                    break
                                }
                                stackX[sp] = nx
                                stackY[sp] = ny
                                sp++
                            }
                        }
                    }
                    if (overflow) break
                }
                if (overflow) break
            }
            if (overflow || count < 2) continue
            if (maxV > 250f && (maxX - minX) * (maxY - minY) > 64) continue // 饱和大斑（路灯等）
            val cx = (sumVx / sumV).toFloat()
            val cy = (sumVy / sumV).toFloat()
            if (cx < 3 || cy < 3 || cx > width - 3 || cy > height - 3) continue
            stars.add(DetectedStar(cx, cy, sumV.toFloat()))
        }
        val ranked = stars.sortedByDescending { it.brightness }.take(maxStars)
        // §0.43b：亮源掩蔽——灯光/月亮区域内的检测星点是伪影
        val grid = maskBrightSource(width, height, gray)
        var masked = 0
        for (g in grid) if (g) masked++
        if (masked == 0) return ranked
        val gridW = (width + 29) / 30
        return ranked.filter {
            val gx = it.x.toInt().coerceIn(0, width - 1) / 30
            val gy = it.y.toInt().coerceIn(0, height - 1) / 30
            !grid[gy * gridW + gx]
        }
    }

    /** §0.43b / §0.95：大面积亮源（灯光/月亮）滑动窗口掩码 */
    private fun maskBrightSource(width: Int, height: Int, gray: FloatArray): BooleanArray {
        val n = width * height
        var mean = 0.0
        for (v in gray) mean += v
        mean /= n
        var varr = 0.0
        for (v in gray) {
            val d = v - mean
            varr += d * d
        }
        varr /= n
        val thr = mean + 6.0 * sqrt(varr)
        val win = 60
        val gridW = (width + 29) / 30
        val gridH = (height + 29) / 30
        val grid = BooleanArray(gridW * gridH)
        val colSums = DoubleArray(gridW)
        for (gy in 0 until gridH) {
            val y0 = gy * 30
            val y1 = min(height - 1, y0 + win - 1)
            java.util.Arrays.fill(colSums, 0.0)
            for (y in y0..y1) {
                val base = y * width
                for (gx in 0 until gridW) {
                    val x0 = gx * 30
                    val x1 = min(width - 1, x0 + win - 1)
                    var s = 0.0
                    for (x in x0..x1) s += gray[base + x]
                    colSums[gx] += s
                }
            }
            val rows = y1 - y0 + 1
            for (gx in 0 until gridW) {
                val x0 = gx * 30
                val x1 = min(width - 1, x0 + win - 1)
                val area = (x1 - x0 + 1) * rows
                if (colSums[gx] / area > thr) {
                    grid[gy * gridW + gx] = true
                }
            }
        }
        return grid
    }

    /** 滑动窗口 box blur（水平 + 垂直两趟，O(n)） */
    private fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(w * h)
        val out = FloatArray(w * h)
        val ws = (2 * r + 1).toDouble()
        val line = DoubleArray(maxOf(w, h))
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val v = src[row + x]
                line[x] = if (v.isFinite()) v.toDouble() else 0.0
            }
            var acc = 0.0
            for (x in -r..r) acc += line[x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[row + x] = (acc / ws).toFloat()
                acc += line[(x + r + 1).coerceIn(0, w - 1)] - line[(x - r).coerceIn(0, w - 1)]
            }
        }
        for (x in 0 until w) {
            for (y in 0 until h) line[y] = tmp[y * w + x].toDouble()
            var acc = 0.0
            for (y in -r..r) acc += line[y.coerceIn(0, h - 1)]
            for (y in 0 until h) {
                out[y * w + x] = (acc / ws).toFloat()
                acc += line[(y + r + 1).coerceIn(0, h - 1)] - line[(y - r).coerceIn(0, h - 1)]
            }
        }
        return out
    }
}
