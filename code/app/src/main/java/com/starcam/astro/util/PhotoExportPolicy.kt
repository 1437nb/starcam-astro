package com.starcam.astro.util

enum class PhotoSourceKind { IMAGE, FITS, RAW_PREVIEW }

/** 解码前的保守峰值预算，禁止自动缩小后仍声称导出原图。 */
object PhotoExportPolicy {
    const val RESERVE = 16L * 1024 * 1024
    const val MAX_BUDGET = 384L * 1024 * 1024

    fun fitsFootprint(w: Int, h: Int, decodeLimit: Int): Pair<Int, Int> {
        require(w > 0 && h > 0 && decodeLimit > 0)
        var sample = 1
        while (maxOf(w, h) / sample > decodeLimit) sample++
        return (w / sample * sample) to (h / sample * sample)
    }

    fun estimateBytes(w: Int, h: Int, kind: PhotoSourceKind, fileBytes: Long, rotated: Boolean): Long {
        if (w !in 1..65500 || h !in 1..65500 || fileBytes < 0) return Long.MAX_VALUE
        val pixels = w.toLong() * h
        if (pixels > Int.MAX_VALUE) return Long.MAX_VALUE
        val pixelCost = when (kind) {
            PhotoSourceKind.FITS -> 40L
            else -> if (rotated) 8L else 4L
        }
        val fileCost = when (kind) {
            PhotoSourceKind.IMAGE -> 0L
            PhotoSourceKind.FITS -> fileBytes
            PhotoSourceKind.RAW_PREVIEW -> if (fileBytes > Long.MAX_VALUE / 3) return Long.MAX_VALUE else fileBytes * 3
        }
        val fixed = pixels * pixelCost + RESERVE
        return if (fileCost > Long.MAX_VALUE - fixed) Long.MAX_VALUE else fixed + fileCost
    }

    fun budget(heapHeadroom: Long, availableSystemMemory: Long): Long =
        minOf(MAX_BUDGET, heapHeadroom.coerceAtLeast(0) / 4 * 3, availableSystemMemory.coerceAtLeast(0) / 4)
}
