package com.starcam.astro.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeTest {

    @Test
    fun appThemeMode_hasFourModesWithUniqueKeys() {
        val modes = AppThemeMode.entries
        assertEquals(4, modes.size)
        val keys = modes.map { it.key }.toSet()
        assertEquals(4, keys.size)
        assertTrue(keys.contains("deep_sky"))
        assertTrue(keys.contains("pure_black"))
        assertTrue(keys.contains("pure_white"))
        assertTrue(keys.contains("night_red"))
    }

    @Test
    fun appThemeMode_labelsAreNonEmptyInBothLanguages() {
        for (mode in AppThemeMode.entries) {
            val zh = mode.label(isEnglish = false)
            val en = mode.label(isEnglish = true)
            assertTrue(zh.isNotBlank())
            assertTrue(en.isNotBlank())
            assertEquals(mode.label, zh)
            assertEquals(mode.labelEn, en)
        }
    }
}
