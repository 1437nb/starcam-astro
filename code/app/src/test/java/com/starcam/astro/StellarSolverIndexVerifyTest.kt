package com.starcam.astro

import com.starcam.astro.astro.FitsIndexValidator
import com.starcam.astro.astro.NativeSolverSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * §0.123 修复 P2-1：FITS 索引完整性与自愈判据单元测试。
 */
class StellarSolverIndexVerifyTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun nonExistentFile_returnsFalse() {
        val nonExistent = File(tempFolder.root, "does_not_exist.fits")
        assertFalse(FitsIndexValidator.isValidFitsIndex(nonExistent))
    }

    @Test
    fun zeroLengthOrTooSmall_returnsFalse() {
        val empty = tempFolder.newFile("empty.fits")
        assertFalse(FitsIndexValidator.isValidFitsIndex(empty))

        val small = tempFolder.newFile("small.fits")
        small.writeBytes(ByteArray(2879))
        assertFalse(FitsIndexValidator.isValidFitsIndex(small))
    }

    @Test
    fun notMultipleOf2880_returnsFalse() {
        val truncated = tempFolder.newFile("truncated.fits")
        truncated.writeBytes(ByteArray(2980))
        assertFalse(FitsIndexValidator.isValidFitsIndex(truncated))
    }

    @Test
    fun missingSimpleCardHeader_returnsFalse() {
        val invalidHeader = tempFolder.newFile("invalid_header.fits")
        val data = ByteArray(2880 * 2) { 0 }
        invalidHeader.writeBytes(data)
        assertFalse(FitsIndexValidator.isValidFitsIndex(invalidHeader))
    }

    @Test
    fun validHeaderAndSize_returnsTrue() {
        val valid = tempFolder.newFile("valid.fits")
        val data = ByteArray(2880 * 2) { 0x20 }
        val header = "SIMPLE  =                    T / file does conform to FITS standard             "
        System.arraycopy(header.toByteArray(Charsets.US_ASCII), 0, data, 0, 80)
        valid.writeBytes(data)
        assertTrue(FitsIndexValidator.isValidFitsIndex(valid))
    }

    @Test
    fun bundledAssetIndexes_areAllValidFits() {
        val assetsDir = File("src/main/assets/indexes")
        val indexFiles = assetsDir.listFiles { _, name -> name.endsWith(".fits") } ?: emptyArray()
        assertTrue("必须包含随包打包的离线索引", indexFiles.isNotEmpty())
        for (f in indexFiles) {
            assertTrue("离线索引 ${f.name} 必须为合法的 FITS 文件", FitsIndexValidator.isValidFitsIndex(f))
        }
    }

    private fun writeValidStubFits(file: File) {
        val data = ByteArray(2880 * 2) { 0x20 }
        val header = "SIMPLE  =                    T / file does conform to FITS standard             "
        System.arraycopy(header.toByteArray(Charsets.US_ASCII), 0, data, 0, 80)
        file.writeBytes(data)
    }

    @Test
    fun extraIndexes_discoverSortedValidAndSkipInvalidOrDuplicate() {
        val extraDir = tempFolder.newFolder("extra")
        writeValidStubFits(File(extraDir, "index-4111.fits"))
        writeValidStubFits(File(extraDir, "index-4110.fits"))
        writeValidStubFits(File(extraDir, "index-4112.fits")) // duplicate with bundled
        writeValidStubFits(File(extraDir, "other-4111.fits")) // non-index prefix
        File(extraDir, "index-4109.fits").writeBytes(ByteArray(123)) // invalid FITS

        val seen = mutableSetOf("index-4112.fits", "index-4113.fits")
        val out = mutableListOf("/base/index-4112.fits", "/base/index-4113.fits")
        NativeSolverSupport.appendValidatedExtraIndexes(extraDir, seen, out)

        assertEquals(4, out.size)
        assertTrue(out[2].endsWith("index-4110.fits"))
        assertTrue(out[3].endsWith("index-4111.fits"))
    }

    @Test
    fun extraIndexes_respectSixteenCacheCap() {
        val extraDir = tempFolder.newFolder("extra-cap")
        for (i in 0 until 20) {
            writeValidStubFits(File(extraDir, "index-%04d.fits".format(4100 + i)))
        }
        val seen = mutableSetOf<String>()
        val out = mutableListOf<String>()
        NativeSolverSupport.appendValidatedExtraIndexes(extraDir, seen, out)
        assertEquals(NativeSolverSupport.MAX_NATIVE_INDEX_CACHE, out.size)
    }
}
