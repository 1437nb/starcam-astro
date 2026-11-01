package com.starcam.astro

import com.starcam.astro.util.CancellableStreams
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class CancellableStreamsTest {
    @Test
    fun largeImportPreservesAllBytesAndReportsSize() {
        val bytes = ByteArray(100_003) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        val size = CancellableStreams.copy(ByteArrayInputStream(bytes), out) {}
        assertEquals(bytes.size.toLong(), size)
        assertArrayEquals(bytes, out.toByteArray())
    }

    @Test
    fun cancellationStopsBeforeTheNextChunkIsWritten() {
        val out = ByteArrayOutputStream()
        try {
            CancellableStreams.copy(ByteArrayInputStream(ByteArray(200_000)), out) {
                if (out.size() >= 32 * 1024) throw CancellationException()
            }
            fail("Cancellation must propagate to the import job")
        } catch (_: CancellationException) {
            assertEquals(32 * 1024, out.size())
        }
    }

    @Test
    fun cancelledImportDoesNotReadOrWriteTheFirstChunk() {
        val input = ByteArrayInputStream(ByteArray(100))
        val output = ByteArrayOutputStream()
        try {
            CancellableStreams.copy(input, output) { throw CancellationException() }
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(100, input.available())
            assertEquals(0, output.size())
        }
    }
}
