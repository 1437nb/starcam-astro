package com.starcam.astro.util

import java.io.InputStream
import java.io.OutputStream

/** Bounded import buffer; cancellation is checked between chunks, including before the first read. */
internal object CancellableStreams {
    fun copy(input: InputStream, output: OutputStream, checkActive: () -> Unit): Long {
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            checkActive()
            val count = input.read(buffer)
            if (count < 0) return total
            checkActive()
            output.write(buffer, 0, count)
            total += count
        }
    }
}
