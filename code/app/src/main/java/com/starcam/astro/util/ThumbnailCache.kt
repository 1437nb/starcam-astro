package com.starcam.astro.util

import android.graphics.Bitmap
import android.util.LruCache
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Shared, byte-bounded cache. Eviction drops ownership without recycling a bitmap still on screen. */
object ThumbnailCache {
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val stripes = Array(16) { Mutex() }

    suspend fun load(path: String, maxDim: Int = 256): Bitmap? = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.isFile) return@withContext null
        val key = "$path:${file.length()}:${file.lastModified()}:$maxDim"
        stripes[(key.hashCode() and Int.MAX_VALUE) % stripes.size].withLock {
            cache.get(key)?.takeUnless { it.isRecycled } ?: ImageUtils.decodeSampledBitmap(path, maxDim)?.also {
                cache.put(key, it)
            }
        }
    }

    fun clear() { cache.evictAll() }
}
