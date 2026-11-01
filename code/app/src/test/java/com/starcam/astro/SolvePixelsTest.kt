package com.starcam.astro

import com.starcam.astro.astro.SolvePixels
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class SolvePixelsTest {
    @Test
    fun concurrentEnginesDecodeOnceAndShareTheSameReadOnlyInput() {
        val decoded = AtomicInteger()
        val ready = CountDownLatch(8)
        val release = CountDownLatch(1)
        val pixels = SolvePixels(2, 2) {
            decoded.incrementAndGet()
            check(release.await(5, TimeUnit.SECONDS))
            floatArrayOf(1f, 2f, 3f, 4f)
        }
        val workers = Executors.newFixedThreadPool(8)
        try {
            val results = (0 until 8).map {
                workers.submit<FloatArray> { ready.countDown(); pixels.gray }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            release.countDown()
            val first = results.first().get(5, TimeUnit.SECONDS)
            for (result in results) assertSame(first, result.get(5, TimeUnit.SECONDS))
            assertEquals(1, decoded.get())
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }

    @Test
    fun nativeCopiesCannotChangeDetectorOrDiagnosticPixels() {
        val pixels = SolvePixels(2, 1) { floatArrayOf(4f, 8f) }
        val native = pixels.nativeCopy()
        native[0] = -100f
        assertArrayEquals(floatArrayOf(4f, 8f), pixels.gray, 0f)
        assertNotSame(native, pixels.nativeCopy())
    }

    @Test(expected = IllegalArgumentException::class)
    fun incorrectImageDimensionsAreRejected() {
        SolvePixels(4, 4) { FloatArray(4) }.gray
    }
}
