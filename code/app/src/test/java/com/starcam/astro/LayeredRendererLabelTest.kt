package com.starcam.astro

import com.starcam.astro.astro.LayerFlags
import com.starcam.astro.astro.LayeredRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §0.120：星空标注渲染器（LayeredRenderer）标签防重叠与防越界测试。
 */
class LayeredRendererLabelTest {

    @Test
    fun `LayerFlags_LINES_ONLY仅保留星座连线`() {
        val flags = LayerFlags.LINES_ONLY
        assertTrue("应开启星座连线", flags.lines)
        assertFalse("不应包含星星名字", flags.starNames)
        assertFalse("不应包含星座名称", flags.constellationNames)
        assertFalse("不应包含梅西耶天体", flags.messier)
        assertFalse("不应包含行星与月亮", flags.planets)
    }

    @Test
    fun `自定义选择连线与星座名时LayerFlags状态匹配`() {
        val flags = LayerFlags(
            lines = true,
            starNames = false,
            constellationNames = true,
            messier = false,
            planets = false,
        )
        assertTrue(flags.lines)
        assertFalse(flags.starNames)
        assertTrue(flags.constellationNames)
        assertFalse(flags.messier)
        assertFalse(flags.planets)
    }

    @Test
    fun `BoundingBox重叠判断正确性`() {
        val b1 = LayeredRenderer.BoundingBox(10f, 10f, 50f, 50f)
        val b2 = LayeredRenderer.BoundingBox(40f, 40f, 80f, 80f)
        val b3 = LayeredRenderer.BoundingBox(60f, 60f, 100f, 100f)

        assertTrue("b1 与 b2 应当相交", b1.intersects(b2))
        assertTrue("b2 与 b1 应当相交", b2.intersects(b1))
        assertFalse("b1 与 b3 不应相交", b1.intersects(b3))
        assertFalse("b3 与 b1 不应相交", b3.intersects(b1))
    }

    @Test
    fun `靠顶部边缘星名自动下移避免超出画幅顶部`() {
        // 模拟 1000x1000 画布，星点靠近顶部 (px = 500, py = 8)
        // 默认右上放置由于 ascentAbs = 14，会导致 top < margin (12f)
        val density = 2f
        val textWidth = 60f
        val ascentAbs = 14f
        val descent = 4f
        val drawW = 1000f
        val drawH = 1000f
        val margin = 6f * density

        val placement = LayeredRenderer.computeStarNamePlacement(
            name = "卷舌二",
            px = 500f,
            py = 8f,
            mag = 2.5,
            textWidth = textWidth,
            ascentAbs = ascentAbs,
            descent = descent,
            drawWidth = drawW,
            drawHeight = drawH,
            density = density,
            occupiedBoxes = emptyList(),
        )

        assertNotNull("应当成功放置标签", placement)
        assertTrue(
            "标签顶部必须在画幅边界之内 (top=${placement!!.box.top}, margin=$margin)",
            placement.box.top >= margin,
        )
        assertTrue(
            "标签底部必须在画幅边界之内 (bottom=${placement.box.bottom})",
            placement.box.bottom <= drawH - margin,
        )
        // 应该选择了象限 2 (右下)，即 y 基线位于星点下方
        assertTrue("基线 y 应该在星点下方", placement.y > 8f)
    }

    @Test
    fun `靠右侧边缘星名自动向左放置避免超出右边界`() {
        // 模拟 1000x1000 画布，星点靠近右边缘 (px = 980, py = 500)
        // 默认右上放置会导致 right > drawWidth - margin
        val density = 2f
        val textWidth = 70f
        val ascentAbs = 14f
        val descent = 4f
        val drawW = 1000f
        val drawH = 1000f
        val margin = 6f * density

        val placement = LayeredRenderer.computeStarNamePlacement(
            name = "天船三",
            px = 980f,
            py = 500f,
            mag = 2.8,
            textWidth = textWidth,
            ascentAbs = ascentAbs,
            descent = descent,
            drawWidth = drawW,
            drawHeight = drawH,
            density = density,
            occupiedBoxes = emptyList(),
        )

        assertNotNull("应当成功放置标签", placement)
        assertTrue(
            "标签右边缘必须在画幅边界内 (right=${placement!!.box.right}, limit=${drawW - margin})",
            placement.box.right <= drawW - margin,
        )
        assertTrue(
            "标签左边缘必须在画幅边界内 (left=${placement.box.left})",
            placement.box.left >= margin,
        )
        // 应该选择了象限 3 (左上)，文字在星点左侧
        assertTrue("标签起始 x 应该在星点左侧", placement.x < 980f)
    }

    @Test
    fun `发生重叠时避开已有标签`() {
        // 星点在 (200, 200)
        // 假设象限 1 (右上) 已经被占用了
        val density = 2f
        val textWidth = 50f
        val ascentAbs = 14f
        val descent = 4f
        val offset = 7f * density
        val drawW = 1000f
        val drawH = 1000f

        val obstacle = LayeredRenderer.BoundingBox(
            200f + offset - 10f,
            200f - offset - ascentAbs - 10f,
            200f + offset + textWidth + 10f,
            200f - offset + descent + 10f,
        )

        val placement = LayeredRenderer.computeStarNamePlacement(
            name = "参宿七",
            px = 200f,
            py = 200f,
            mag = 0.1,
            textWidth = textWidth,
            ascentAbs = ascentAbs,
            descent = descent,
            drawWidth = drawW,
            drawHeight = drawH,
            density = density,
            occupiedBoxes = listOf(obstacle),
        )

        assertNotNull("亮星应当成功避让放置", placement)
        assertFalse(
            "放置的标签框不应与障碍相交",
            placement!!.box.intersects(obstacle),
        )
    }
}
