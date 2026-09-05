package com.starcam.astro

import com.starcam.astro.astro.MessierCatalog
import com.starcam.astro.astro.ObjectInfo
import com.starcam.astro.astro.StarCatalogData
import com.starcam.astro.astro.StarNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §0.47 天体科普卡片数据完整性测试：
 * ObjectInfo 的 HIP 编号必须与星名表/星表严格对齐，梅西耶编号必须存在于目录。
 * （防"科普数据挂错星"回归：任何编号漂移都会让用户点开的卡片张冠李戴）
 */
class ObjectInfoTest {

    @Test
    fun starInfoHipsExistInCatalog() {
        val catalogHips = StarCatalogData.stars.map { it.hip }.toSet()
        for ((hip, info) in ObjectInfo.stars) {
            assertTrue(
                "ObjectInfo 中的 HIP $hip 不在星表中（编号漂移）",
                hip in catalogHips,
            )
            assertFalse("中文距离不应为空: HIP $hip", info.distLyZh.isBlank())
            assertFalse("英文距离不应为空: HIP $hip", info.distLyEn.isBlank())
            assertFalse("中文描述不应为空: HIP $hip", info.descZh.isBlank())
            assertFalse("英文描述不应为空: HIP $hip", info.descEn.isBlank())
            // 中英描述都不应包含汉字以外的引号错误（简单防呆：不含换行）
            assertFalse("描述不应含换行: HIP $hip", info.descZh.contains('\n'))
        }
    }

    @Test
    fun starInfoHipsResolveToNames() {
        // 有科普的亮星必须能解析出显示名（专名或 Bayer 标号），中英皆然
        for (hip in ObjectInfo.stars.keys) {
            val zh = StarNames.displayName(hip, "", isEnglish = false)
            val en = StarNames.displayName(hip, "", isEnglish = true)
            assertTrue("HIP $hip 中文名解析为空", zh.isNotEmpty())
            assertTrue("HIP $hip 英文名解析为空", en.isNotEmpty())
            assertFalse("英文模式不应含汉字: HIP $hip → $en", en.any { it.code in 0x4e00..0x9fff })
        }
    }

    @Test
    fun messierInfoNumbersMatchCatalog() {
        for ((number, info) in ObjectInfo.messier) {
            val obj = MessierCatalog.byNumber[number]
            assertNotNull("ObjectInfo 中的 M$number 不在梅西耶目录（编号漂移）", obj)
            assertTrue("M$number 视星等应在 -2..12: ${info.mag}", info.mag in -2.0..12.0)
            assertFalse("中文距离不应为空: M$number", info.distLyZh.isBlank())
            assertFalse("英文距离不应为空: M$number", info.distLyEn.isBlank())
            assertFalse("中文描述不应为空: M$number", info.descZh.isBlank())
            assertFalse("英文描述不应为空: M$number", info.descEn.isBlank())
        }
    }

    @Test
    fun catalogMessierFullyCovered() {
        // 科普数据应完整覆盖梅西耶标注目录（每个可标注天体都有卡片可看）
        for (number in MessierCatalog.byNumber.keys) {
            assertTrue("M$number 缺少科普数据（应全覆盖）", number in ObjectInfo.messier)
        }
    }

    @Test
    fun typeLabelsBilingual() {
        assertEquals("星系", ObjectInfo.typeLabel("G", isEnglish = false))
        assertEquals("Galaxy", ObjectInfo.typeLabel("G", isEnglish = true))
        assertEquals("行星状星云", ObjectInfo.typeLabel("PN", isEnglish = false))
        assertEquals("Planetary Nebula", ObjectInfo.typeLabel("PN", isEnglish = true))
        assertEquals("疏散星团", ObjectInfo.typeLabel("OC", isEnglish = false))
        assertEquals("Open Cluster", ObjectInfo.typeLabel("OC", isEnglish = true))
    }

    @Test
    fun keyStarCardsAreCorrect() {
        // 抽查关键亮星：确保没有"挂错星"（历史上曾出现 HIP 编号漂移回归）
        val sirius = ObjectInfo.stars.getValue(32349)
        assertTrue(sirius.descEn.contains("Brightest"))
        val polaris = ObjectInfo.stars.getValue(11767)
        assertTrue(polaris.descEn.contains("North Star"))
        val betelgeuse = ObjectInfo.stars.getValue(27989)
        assertTrue(betelgeuse.descEn.contains("supergiant"))
        val pleiades = ObjectInfo.messier.getValue(45)
        assertEquals(1.6, pleiades.mag, 0.01)
        val andromeda = ObjectInfo.messier.getValue(31)
        assertEquals(3.4, andromeda.mag, 0.01)
    }
}
