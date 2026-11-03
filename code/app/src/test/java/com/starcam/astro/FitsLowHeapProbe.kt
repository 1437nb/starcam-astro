package com.starcam.astro

import com.starcam.astro.util.FitsImage
import java.io.File

/** Independent -Xmx128m process; no Android dependencies or full-image fixture allocation. */
object FitsLowHeapProbe {
    @JvmStatic fun main(args: Array<String>) {
        val target = File(args[0]); check(!target.exists())
        val cards = listOf("SIMPLE  =                    T", "BITPIX  =                   16", "NAXIS   =                    2", "NAXIS1  =                 6000", "NAXIS2  =                 4000", "END")
        target.outputStream().buffered().use { output ->
            output.write(cards.joinToString("") { it.padEnd(80) }.padEnd(2880).toByteArray(Charsets.US_ASCII))
            val row = ByteArray(12000)
            for (y in 0 until 4000) {
                for (x in 0 until 6000) {
                    val value = (x + y) % 32768
                    row[x*2] = (value ushr 8).toByte(); row[x*2+1] = value.toByte()
                }
                output.write(row)
            }
            output.write(ByteArray((2880 - 48_000_000 % 2880) % 2880))
        }
        val result = requireNotNull(FitsImage.decode(target.path, 2200))
        check(result.w == 2000 && result.h == 1333 && result.gray.size == 2_666_000)
        check(result.gray.all { it.isFinite() && it in 0f..255f })
        check(result.gray.maxOrNull()!! > result.gray.minOrNull()!!)
        try {
            FitsImage.decode(target.path, 6000)
            error("Full size should be rejected before allocating in a 128MiB heap")
        } catch (_: com.starcam.astro.util.ImageMemoryLimitException) { }
        println("LOW_HEAP_FITS_PASS 6000x4000 -> ${result.w}x${result.h}")
    }
}
