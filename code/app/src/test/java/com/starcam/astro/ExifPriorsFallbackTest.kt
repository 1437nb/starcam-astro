package com.starcam.astro

import com.starcam.astro.astro.ExifPriors
import com.starcam.astro.astro.LocationSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §0.59 位置兜底：照片无 EXIF GPS 时用设备当前定位补全先验的 JVM 单测。
 *
 * 边界比正例更重要——兜底必须**永不覆盖** EXIF 定位，且在无拍摄时刻时**拒绝兜底**
 * （没有时刻就算不出天顶，补位置只会平白引入一个错误前提）。
 */
class ExifPriorsFallbackTest {

    /** 一个固定的拍摄时刻（具体值不重要，只要能被解析成天顶） */
    private val epoch = 1_756_000_000L

    @Test
    fun exifLocationIsNeverOverridden() {
        val priors = ExifPriors(30.0, 23.1, 113.3, epoch, true, LocationSource.EXIF)
        val merged = priors.withFallbackLocation(39.9, 116.4)

        assertEquals("EXIF 纬度不得被兜底覆盖", 23.1, merged.latDeg!!, 1e-9)
        assertEquals("EXIF 经度不得被兜底覆盖", 113.3, merged.lonDeg!!, 1e-9)
        assertEquals(LocationSource.EXIF, merged.locationSource)
        assertFalse("EXIF 定位不算兜底", merged.locationIsFallback)
    }

    @Test
    fun fallbackFillsMissingLocationWhenTimeIsKnown() {
        val priors = ExifPriors(30.0, null, null, epoch, false, LocationSource.NONE)
        val merged = priors.withFallbackLocation(23.1, 113.3)

        assertEquals(23.1, merged.latDeg!!, 1e-9)
        assertEquals(113.3, merged.lonDeg!!, 1e-9)
        assertEquals(LocationSource.CURRENT_FALLBACK, merged.locationSource)
        assertTrue("兜底后必须可被 UI 识别出来", merged.locationIsFallback)
    }

    @Test
    fun fallbackIsSkippedWithoutCaptureTime() {
        val priors = ExifPriors(30.0, null, null, null, false, LocationSource.NONE)
        val merged = priors.withFallbackLocation(23.1, 113.3)

        assertNull("无拍摄时刻时不得兜底", merged.latDeg)
        assertNull(merged.lonDeg)
        assertEquals(LocationSource.NONE, merged.locationSource)
        assertFalse(merged.locationIsFallback)
    }

    @Test
    fun fallbackCompletesHalfGpsPair() {
        // EXIF 只写了纬度（半套 GPS）——不可用，应整对补全
        val priors = ExifPriors(30.0, 23.1, null, epoch, true, LocationSource.NONE)
        val merged = priors.withFallbackLocation(39.9, 116.4)

        assertEquals(39.9, merged.latDeg!!, 1e-9)
        assertEquals(116.4, merged.lonDeg!!, 1e-9)
        assertTrue(merged.locationIsFallback)
    }

    @Test
    fun fallbackEnablesSkyPrior() {
        val priors = ExifPriors(30.0, null, null, epoch, true, LocationSource.NONE)
        assertNull("缺 GPS 时不应算出天区先验", priors.zenith)
        assertFalse(priors.hasSkyPrior)

        val merged = priors.withFallbackLocation(23.1, 113.3)
        assertNotNull("兜底后应能算出天顶", merged.zenith)
        assertTrue(merged.hasSkyPrior)
    }

    @Test
    fun fallbackWorksWithoutFov() {
        // 拿不到焦距（缺 FOV）不影响位置兜底：FOV 只用于 scale 区间，与位置无关
        val priors = ExifPriors(null, null, null, epoch, true, LocationSource.NONE)
        val merged = priors.withFallbackLocation(23.1, 113.3)

        assertTrue(merged.locationIsFallback)
        assertNotNull(merged.zenith)
    }
}
