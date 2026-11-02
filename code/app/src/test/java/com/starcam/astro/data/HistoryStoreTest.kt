package com.starcam.astro.data

import com.starcam.astro.astro.SolveResult
import com.starcam.astro.astro.WcsTransform
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 识别历史记录序列化与 WCS 恢复单元测试（纯 JVM）。
 */
class HistoryStoreTest {

    @Test
    fun fromSolve_and_toSolveResult_roundTrip() {
        val wcs = WcsTransform(
            crpix1 = 1100.5,
            crpix2 = 825.5,
            crval1 = 83.82,
            crval2 = -5.39,
            cd11 = -0.015,
            cd12 = 0.002,
            cd21 = 0.002,
            cd22 = 0.015,
        )
        val originalSolve = SolveResult(
            raDeg = 83.82,
            decDeg = -5.39,
            pixScaleArcsec = 54.0,
            orientationDeg = 12.5,
            parity = -1,
            imageWidth = 2200,
            imageHeight = 1650,
            wcs = wcs,
            nMatch = 25,
            logodds = 120.0,
            subId = 123456L,
        )

        val entry = HistoryStore.Entry.fromSolve(
            timestamp = 1700000000000L,
            imagePath = "/data/user/0/com.starcam.astro/cache/sample.jpg",
            solve = originalSolve,
            engine = "内置星表匹配",
            constellation = "猎户座",
        )

        assertEquals(83.82, entry.raDeg, 1e-4)
        assertEquals(-5.39, entry.decDeg, 1e-4)
        assertEquals(54.0, entry.pixScaleArcsec!!, 1e-4)
        assertEquals(12.5, entry.orientationDeg!!, 1e-4)
        assertEquals(-1, entry.parity)
        assertEquals(2200, entry.imageWidth)
        assertEquals(1650, entry.imageHeight)
        assertEquals(1100.5, entry.wcsCrpix1!!, 1e-4)
        assertEquals(825.5, entry.wcsCrpix2!!, 1e-4)
        assertEquals(83.82, entry.wcsCrval1!!, 1e-4)
        assertEquals(-5.39, entry.wcsCrval2!!, 1e-4)
        assertEquals(-0.015, entry.wcsCd11!!, 1e-6)
        assertEquals(0.002, entry.wcsCd12!!, 1e-6)
        assertEquals(0.002, entry.wcsCd21!!, 1e-6)
        assertEquals(0.015, entry.wcsCd22!!, 1e-6)
        assertEquals(25, entry.nMatch)
        assertEquals(120.0, entry.logodds!!, 1e-4)
        assertEquals(123456L, entry.subId)

        val restored = entry.toSolveResult()
        assertNotNull(restored)
        assertEquals(originalSolve.raDeg, restored!!.raDeg, 1e-4)
        assertEquals(originalSolve.decDeg, restored.decDeg, 1e-4)
        assertEquals(originalSolve.pixScaleArcsec, restored.pixScaleArcsec, 1e-4)
        assertEquals(originalSolve.orientationDeg, restored.orientationDeg, 1e-4)
        assertEquals(originalSolve.parity, restored.parity)
        assertEquals(originalSolve.imageWidth, restored.imageWidth)
        assertEquals(originalSolve.imageHeight, restored.imageHeight)
        assertNotNull(restored.wcs)
        assertEquals(originalSolve.subId, restored.subId)
        assertEquals(wcs.crpix1, restored.wcs!!.crpix1, 1e-4)
        assertEquals(wcs.crpix2, restored.wcs!!.crpix2, 1e-4)
        assertEquals(wcs.crval1, restored.wcs!!.crval1, 1e-4)
        assertEquals(wcs.crval2, restored.wcs!!.crval2, 1e-4)
        assertEquals(wcs.cd11, restored.wcs!!.cd11, 1e-6)
        assertEquals(wcs.cd12, restored.wcs!!.cd12, 1e-6)
        assertEquals(wcs.cd21, restored.wcs!!.cd21, 1e-6)
        assertEquals(wcs.cd22, restored.wcs!!.cd22, 1e-6)
    }

    @Test
    fun legacyEntryWithoutWcs_returnsNullSolveResult() {
        // 旧版本没有持久化 WCS 字段的历史条目
        val legacyEntry = HistoryStore.Entry(
            timestamp = 1600000000000L,
            imagePath = "/data/user/0/com.starcam.astro/cache/old.jpg",
            raDeg = 10.0,
            decDeg = 20.0,
            fovDeg = 65.0,
            engine = "官方引擎",
            constellation = "仙女座",
        )
        assertNull(legacyEntry.toSolveResult())
    }

    @Test
    fun jsonSerializationCompatibility() {
        val json = JSONObject().apply {
            put("ts", 1700000000000L)
            put("path", "/data/photo.jpg")
            put("ra", 45.0)
            put("dec", 30.0)
            put("fov", 75.0)
            put("engine", "测试引擎")
            put("con", "金牛座")
            put("scale", 50.0)
            put("orient", 0.0)
            put("parity", 1)
            put("w", 1920)
            put("h", 1080)
            put("crpix1", 960.5)
            put("crpix2", 540.5)
            put("crval1", 45.0)
            put("crval2", 30.0)
            put("cd11", -0.01)
            put("cd12", 0.0)
            put("cd21", 0.0)
            put("cd22", 0.01)
            put("nmatch", 15)
            put("logodds", 88.0)
        }

        val entry = HistoryStore.Entry(
            timestamp = json.getLong("ts"),
            imagePath = json.getString("path"),
            raDeg = json.getDouble("ra"),
            decDeg = json.getDouble("dec"),
            fovDeg = json.getDouble("fov"),
            engine = json.optString("engine", ""),
            constellation = json.optString("con", ""),
            pixScaleArcsec = if (json.has("scale") && !json.isNull("scale")) json.getDouble("scale") else null,
            orientationDeg = if (json.has("orient") && !json.isNull("orient")) json.getDouble("orient") else null,
            parity = if (json.has("parity") && !json.isNull("parity")) json.getInt("parity") else null,
            imageWidth = if (json.has("w") && !json.isNull("w")) json.getInt("w") else null,
            imageHeight = if (json.has("h") && !json.isNull("h")) json.getInt("h") else null,
            wcsCrpix1 = if (json.has("crpix1") && !json.isNull("crpix1")) json.getDouble("crpix1") else null,
            wcsCrpix2 = if (json.has("crpix2") && !json.isNull("crpix2")) json.getDouble("crpix2") else null,
            wcsCrval1 = if (json.has("crval1") && !json.isNull("crval1")) json.getDouble("crval1") else null,
            wcsCrval2 = if (json.has("crval2") && !json.isNull("crval2")) json.getDouble("crval2") else null,
            wcsCd11 = if (json.has("cd11") && !json.isNull("cd11")) json.getDouble("cd11") else null,
            wcsCd12 = if (json.has("cd12") && !json.isNull("cd12")) json.getDouble("cd12") else null,
            wcsCd21 = if (json.has("cd21") && !json.isNull("cd21")) json.getDouble("cd21") else null,
            wcsCd22 = if (json.has("cd22") && !json.isNull("cd22")) json.getDouble("cd22") else null,
            nMatch = if (json.has("nmatch") && !json.isNull("nmatch")) json.getInt("nmatch") else null,
            logodds = if (json.has("logodds") && !json.isNull("logodds")) json.getDouble("logodds") else null,
        )

        val restored = entry.toSolveResult()
        assertNotNull(restored)
        assertEquals(45.0, restored!!.raDeg, 1e-4)
        assertEquals(30.0, restored.decDeg, 1e-4)
        assertEquals(1920, restored.imageWidth)
        assertEquals(1080, restored.imageHeight)
        assertNotNull(restored.wcs)
        assertEquals(960.5, restored.wcs!!.crpix1, 1e-4)
    }

    @Test
    fun angSep_computesCorrectDistance() {
        val d = HistoryStore.angSep(0.0, 0.0, 0.0, 45.0)
        assertEquals(45.0, d, 1e-4)
    }

    @Test
    fun computeFileFingerprint_stableAndDeterministic() {
        val temp = java.io.File.createTempFile("test_hist", ".jpg")
        try {
            temp.writeBytes("Hello StarCam Astronomy Photo Data".toByteArray(Charsets.UTF_8))
            val fp1 = HistoryStore.computeFileFingerprint(temp)
            val fp2 = HistoryStore.computeFileFingerprint(temp)
            assertEquals("相同内容的文件指纹必须绝对确定一致", fp1, fp2)
            assertEquals(16, fp1.length)
        } finally {
            temp.delete()
        }
    }

    @Test
    fun nearestConstellation_handlesValidCoordsNanAndCutoff() {
        // 猎户座腰带中心附近（RA=83.8°, Dec=-1.2°）
        assertEquals("猎户座", HistoryStore.nearestConstellation(83.8, -1.2, isEnglish = false))
        assertEquals("Orion", HistoryStore.nearestConstellation(83.8, -1.2, isEnglish = true))

        // NaN / Infinite 输入必须安全返回空串（修复旧版 best==null 首轮把星表第 0 颗星写入的缺陷）
        assertEquals("", HistoryStore.nearestConstellation(Double.NaN, 0.0))
        assertEquals("", HistoryStore.nearestConstellation(0.0, Double.NaN))
        assertEquals("", HistoryStore.nearestConstellation(Double.POSITIVE_INFINITY, 0.0))

        // 超出角距上界时返回空串
        assertEquals("", HistoryStore.nearestConstellation(180.0, 0.0, maxSepDeg = 0.001))
    }

    @Test
    fun entry_localizedConstellation_supportsBilingualAndFallback() {
        // 1. 包含有效坐标的条目：优先按坐标解算对应语言星座
        val entryWithCoords = HistoryStore.Entry(
            timestamp = 1000L,
            imagePath = "/dummy/orion.jpg",
            raDeg = 83.8,
            decDeg = -1.2,
            fovDeg = 25.0,
            engine = "DEMO",
            constellation = "猎户座",
        )
        assertEquals("猎户座", entryWithCoords.localizedConstellation(isEnglish = false))
        assertEquals("Orion", entryWithCoords.localizedConstellation(isEnglish = true))

        // 2. 坐标无效但带有存量中文星座名的历史条目：通过字典平滑反查
        val legacyZhEntry = HistoryStore.Entry(
            timestamp = 2000L,
            imagePath = "/dummy/andromeda.jpg",
            raDeg = Double.NaN,
            decDeg = Double.NaN,
            fovDeg = 30.0,
            engine = "DEMO",
            constellation = "仙女座",
        )
        assertEquals("仙女座", legacyZhEntry.localizedConstellation(isEnglish = false))
        assertEquals("Andromeda", legacyZhEntry.localizedConstellation(isEnglish = true))

        // 3. 坐标无效但带有存量英文星座名的历史条目：反查中文
        val legacyEnEntry = HistoryStore.Entry(
            timestamp = 3000L,
            imagePath = "/dummy/cygnus.jpg",
            raDeg = Double.NaN,
            decDeg = Double.NaN,
            fovDeg = 30.0,
            engine = "DEMO",
            constellation = "Cygnus",
        )
        assertEquals("天鹅座", legacyEnEntry.localizedConstellation(isEnglish = false))
        assertEquals("Cygnus", legacyEnEntry.localizedConstellation(isEnglish = true))
    }
}
