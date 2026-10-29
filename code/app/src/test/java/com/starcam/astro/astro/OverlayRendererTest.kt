package com.starcam.astro.astro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 叠加渲染器（[OverlayRenderer]）单元测试（纯 JVM）。
 */
class OverlayRendererTest {

    @Test
    fun `shrink 与 LayeredRenderer_shrink 行为完全一致`() {
        val ax = 100f
        val ay = 100f
        val magA = 1.0
        val bx = 400f
        val by = 500f
        val magB = 2.0

        val res1 = OverlayRenderer.shrink(ax, ay, magA, bx, by, magB)
        val res2 = LayeredRenderer.shrink(ax, ay, magA, bx, by, magB)

        assertNotNull("足够长的线段应当成功收缩", res1)
        assertNotNull(res2)
        assertEquals(4, res1!!.size)
        assertEquals(4, res2!!.size)
        for (i in 0 until 4) {
            assertEquals("两渲染器收缩坐标必须完全一致", res2[i], res1[i], 1e-5f)
        }
    }

    @Test
    fun `两星距离过近时 shrink 返回 null`() {
        // 距离仅 5 像素，小于 gapA + gapB + 2f
        val res = OverlayRenderer.shrink(10f, 10f, 1.0, 13f, 14f, 1.0)
        assertNull("星距不足时应当返回 null 避免线段反转", res)
    }

    @Test
    fun `不同星等收缩比例亮星留空更大`() {
        // 亮星 (mag 0.0) vs 暗星 (mag 4.0)
        // 0.0 等星留空应当大于 4.0 等星
        val rBright = OverlayRenderer.shrink(0f, 0f, 0.0, 100f, 0f, 4.0)!!
        val startGap = rBright[0] - 0f
        val endGap = 100f - rBright[2]
        assertTrue("亮星端留空 ($startGap) 应大于暗星端 ($endGap)", startGap > endGap)
    }
}
