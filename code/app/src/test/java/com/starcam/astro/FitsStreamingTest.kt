package com.starcam.astro

import com.starcam.astro.util.FitsImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class FitsStreamingTest {
    private fun directory() = File("build/stability-fixtures/${UUID.randomUUID()}").apply { mkdirs() }
    private fun fixture(bitpix: Int, extension: Boolean = false): ByteArray {
        fun header(vararg fields: String) = (fields.toList()+"END").joinToString("") { it.padEnd(80) }.padEnd(2880).toByteArray(Charsets.US_ASCII)
        val bytes = ByteArrayOutputStream()
        if (extension) bytes.write(header("SIMPLE  =                    T", "BITPIX  =                    8", "NAXIS   =                    0"))
        bytes.write(header(if (extension) "XTENSION= 'IMAGE'" else "SIMPLE  =                    T",
            "BITPIX  = $bitpix", "NAXIS   = 2", "NAXIS1  = 17", "NAXIS2  = 11", "BSCALE  = -1.25", "BZERO   = 17.0"))
        val pixels = ByteBuffer.allocate(17*11*kotlin.math.abs(bitpix)/8).order(ByteOrder.BIG_ENDIAN)
        repeat(17*11) { i ->
            val v = ((i*53)%255-120).toFloat()
            when(bitpix) {
                8 -> pixels.put(v.toInt().toByte())
                16 -> pixels.putShort(v.toInt().toShort())
                32 -> pixels.putInt(v.toInt())
                -32 -> pixels.putFloat(if(i==7) Float.NaN else v/3)
                -64 -> pixels.putDouble(if(i==7) Double.POSITIVE_INFINITY else v.toDouble()/3)
            }
        }
        bytes.write(pixels.array()); bytes.write(ByteArray((2880-pixels.capacity()%2880)%2880))
        return bytes.toByteArray()
    }

    @Test fun allBitDepthsMatchTheSortBasedReferenceBeforeAndAfterSampling() {
        for (bitpix in listOf(8,16,32,-32,-64)) for (limit in listOf(7,8,100)) {
            val bytes=fixture(bitpix); val file=File(directory(),"sample.fits").apply { writeBytes(bytes) }
            val expected=requireNotNull(FitsImage.decodeBytes(bytes,limit)); val actual=requireNotNull(FitsImage.decode(file.path,limit))
            assertEquals(expected.w,actual.w); assertEquals(expected.h,actual.h)
            assertArrayEquals(expected.gray,actual.gray,0.0001f)
        }
    }

    @Test fun imageExtensionOffsetAndHeaderMetaArePreserved() {
        val bytes=fixture(-32,true); val file=File(directory(),"extension.fits").apply{writeBytes(bytes)}
        val expected=requireNotNull(FitsImage.decodeBytes(bytes,7)); val actual=requireNotNull(FitsImage.decode(file.path,7))
        assertEquals(expected.meta,actual.meta); assertArrayEquals(expected.gray,actual.gray,0.0001f)
    }

    @Test fun truncatedOrOverflowingDimensionsAreRejectedWithoutAllocation() {
        val file=File(directory(),"bad.fits").apply { writeBytes(fixture(16).copyOf(2880)) }
        assertNull(FitsImage.decode(file.path))
        val head=listOf("SIMPLE  = T","BITPIX  = -64","NAXIS   = 2","NAXIS1  = 2147483647","NAXIS2  = 2147483647","END").joinToString(""){it.padEnd(80)}.padEnd(2880)
        val huge=File(directory(),"huge.fits").apply{writeText(head,Charsets.US_ASCII)}
        assertNull(FitsImage.decode(huge.path)); assertNull(FitsImage.readMeta(huge.path))
    }

    @Test fun cancellationEscapesTheDecoderInsteadOfBecomingAnUnreadableFile() {
        val file=File(directory(),"cancel.fits").apply{writeBytes(fixture(16))};var checks=0
        try { FitsImage.decode(file.path,7) { if(++checks>2) throw CancellationException() }; fail("Expected cancellation") }
        catch (_: CancellationException) { }
    }

    @Test fun twentyFourMegapixelsDecodeInAnIndependent128MiBHeap() {
        val output=File(directory(),"large.fits")
        val classes=listOf(FitsLowHeapProbe::class.java,FitsImage::class.java,kotlin.Unit::class.java)
        val cp=classes.map{File(requireNotNull(it.protectionDomain).codeSource.location.toURI()).path}.distinct().joinToString(File.pathSeparator)
        val java=File(requireNotNull(System.getProperty("java.home")),if(System.getProperty("os.name").orEmpty().startsWith("Windows")) "bin/java.exe" else "bin/java")
        val process=ProcessBuilder(java.path,"-Xmx128m","-cp",cp,FitsLowHeapProbe::class.java.name,output.absolutePath).redirectErrorStream(true).start()
        if(!process.waitFor(60,TimeUnit.SECONDS)){process.destroyForcibly();fail("Low heap FITS process timed out")}
        val text=process.inputStream.bufferedReader().readText()
        assertEquals(text,0,process.exitValue()); assertTrue(text,text.contains("LOW_HEAP_FITS_PASS"))
    }
}
