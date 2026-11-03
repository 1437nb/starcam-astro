package com.starcam.astro.util

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.floor

/** Three bounded passes: exact float quantiles, then stretch BEFORE area averaging. */
internal object FitsPixels {
    private fun interface PixelVisitor { fun accept(x: Int, y: Int, value: Float) }
    fun read(
        file: RandomAccessFile, offset: Long, w: Int, h: Int, bitpix: Int,
        bscale: Double, bzero: Double, maxDim: Int, checkActive: () -> Unit,
    ): FloatArray? {
        if (w <= 0 || h <= 0 || maxDim <= 0 || bitpix !in setOf(8, 16, 32, -32, -64)) return null
        val count = w.toLong() * h
        val bytesPer = kotlin.math.abs(bitpix) / 8
        if (count > Int.MAX_VALUE || offset < 0 || offset > file.length() ||
            count * bytesPer > file.length() - offset) return null
        val factor = (maxOf(w, h).toLong() / (maxDim.toLong() + 1) + 1).toInt()
        val ow = w / factor
        val oh = h / factor
        if (ow <= 0 || oh <= 0) return null
        val outputCount = ow.toLong() * oh
        val runtime = Runtime.getRuntime()
        val available = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory()) - 4L * 1024 * 1024
        // Leave room for the subsequent ARGB pixels, Bitmap and temporary allocations.
        if (outputCount * 4L > available.coerceAtLeast(0L) / 4L) throw ImageMemoryLimitException()
        val buffer = ByteArray(65536)
        val values = ByteBuffer.wrap(buffer).order(ByteOrder.BIG_ENDIAN)
        val scale = bscale.toFloat()
        val zero = bzero.toFloat()
        fun scan(topDown: Boolean = false, visit: PixelVisitor) {
            for (y in 0 until h) {
                checkActive()
                val row = if (topDown) h - 1 - y else y
                file.seek(offset + row.toLong() * w * bytesPer)
                var x = 0
                while (x < w) {
                    checkActive()
                    val n = minOf(w - x, buffer.size / bytesPer)
                    file.readFully(buffer, 0, n * bytesPer)
                    values.position(0)
                    repeat(n) { dx ->
                        var v = when (bitpix) {
                            8 -> (values.get().toInt() and 255).toFloat()
                            16 -> values.short.toFloat()
                            32 -> values.int.toFloat()
                            -32 -> values.float
                            else -> values.double.toFloat()
                        }
                        if (bscale != 1.0 || bzero != 0.0) v = zero + scale * v
                        if (!v.isFinite()) v = 0f
                        visit.accept(x + dx, y, v)
                    }
                    x += n
                }
            }
        }
        fun key(v: Float): Int {
            val bits = java.lang.Float.floatToRawIntBits(v)
            return if (bits < 0) bits.inv() else bits xor Int.MIN_VALUE
        }
        fun value(k: Int): Float = java.lang.Float.intBitsToFloat(if (k < 0) k xor Int.MIN_VALUE else k.inv())
        val upper = IntArray(65536)
        var minimum = Float.POSITIVE_INFINITY
        var maximum = Float.NEGATIVE_INFINITY
        scan { _, _, v ->
            upper[key(v) ushr 16]++
            minimum = minOf(minimum, v)
            maximum = maxOf(maximum, v)
        }
        val ranks = doubleArrayOf(.01 * (count - 1), .995 * (count - 1))
        val positions = intArrayOf(floor(ranks[0]).toInt(), ceil(ranks[0]).toInt(), floor(ranks[1]).toInt(), ceil(ranks[1]).toInt())
        val buckets = IntArray(4)
        val inside = IntArray(4)
        for (i in positions.indices) {
            var before = 0
            for (high in upper.indices) {
                if (positions[i] < before + upper[high]) {
                    buckets[i] = high
                    inside[i] = positions[i] - before
                    break
                }
                before += upper[high]
            }
        }
        val selected = buckets.distinct().toIntArray()
        val lower = Array(selected.size) { IntArray(65536) }
        scan { _, _, v ->
            val k = key(v)
            for (i in selected.indices) if (selected[i] == k ushr 16) {
                lower[i][k and 65535]++
                break
            }
        }
        val picked = FloatArray(4)
        for (i in positions.indices) {
            val histogram = lower[selected.indexOf(buckets[i])]
            var before = 0
            for (low in histogram.indices) {
                if (inside[i] < before + histogram[low]) {
                    picked[i] = value((buckets[i] shl 16) or low)
                    break
                }
                before += histogram[low]
            }
        }
        fun interpolate(a: Float, b: Float, rank: Double): Float {
            val fraction = (rank - floor(rank)).toFloat()
            return if (fraction == 0f) a else a * (1f - fraction) + b * fraction
        }
        var low = interpolate(picked[0], picked[1], ranks[0])
        var high = interpolate(picked[2], picked[3], ranks[1])
        if (high - low <= 0f) { low = minimum; high = maximum }
        val span = high - low
        val output = FloatArray(outputCount.toInt())
        scan(topDown = true) { x, y, v ->
            if (x < ow * factor && y < oh * factor && span > 0f) {
                val index = (y / factor) * ow + x / factor
                output[index] += ((v - low) * (255f / span)).coerceIn(0f, 255f)
            }
        }
        if (factor > 1) {
            val area = (factor * factor).toFloat()
            for (i in output.indices) output[i] /= area
        }
        return output
    }
}
