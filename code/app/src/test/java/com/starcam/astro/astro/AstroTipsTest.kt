package com.starcam.astro.astro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AstroTipsTest {

    @Test
    fun astroTips_hasEqualCountsAndNonEmptyEntries() {
        assertEquals(20, AstroTips.allZh.size)
        assertEquals(20, AstroTips.allEn.size)
        for (i in 0 until 20) {
            val zh = AstroTips.allZh[i]
            val en = AstroTips.allEn[i]
            assertTrue(zh.isNotBlank())
            assertTrue(en.isNotBlank())
            // 确保中文版含有中文字符，英文版不含中文字符
            assertTrue(zh.any { it.code in 0x4E00..0x9FA5 })
            assertFalse(en.any { it.code in 0x4E00..0x9FA5 })
        }
    }

    @Test
    fun astroTips_randomReturnsCorrectLanguage() {
        val zhTip = AstroTips.random(isEnglish = false, seed = 42)
        val enTip = AstroTips.random(isEnglish = true, seed = 42)

        assertTrue(zhTip.any { it.code in 0x4E00..0x9FA5 })
        assertFalse(enTip.any { it.code in 0x4E00..0x9FA5 })
    }
}
