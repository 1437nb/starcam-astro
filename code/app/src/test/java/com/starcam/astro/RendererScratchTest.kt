package com.starcam.astro

import com.starcam.astro.astro.LayeredRenderer
import org.junit.Assert.*
import org.junit.Test

class RendererScratchTest {
    @Test
    fun frameResetReusesBoxesWithoutAliasingThePlacementBuffer() {
        val scratch = LayeredRenderer.FrameScratch()
        scratch.occupy(1f, 2f, 3f, 4f)
        val previous = scratch.boxes.first()
        scratch.reset()
        assertTrue(scratch.boxes.isEmpty())
        scratch.occupy(10f, 20f, 30f, 40f)
        assertSame(previous, scratch.boxes.first())
        scratch.placement.box.set(99f, 99f, 99f, 99f)
        assertEquals(10f, previous.left, 0f)
    }

    @Test
    fun reusedPlacementStillFitsAtRightEdge() {
        val out = LayeredRenderer.PlacementBuffer()
        assertTrue(LayeredRenderer.computeStarNamePlacementInto(
            out, 980f, 200f, 1.0, 120f, 20f, 5f, 1000f, 600f, 1f, emptyList(),
        ))
        assertTrue(out.x < 980f)
        assertTrue(out.box.right <= 994f)
    }

    @Test
    fun warmedUpLayoutDoesNotAllocatePerLabelOrPerFrame() {
        // Android's compile bootclasspath omits java.management; the test worker is a real JDK.
        val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val api = Class.forName("com.sun.management.ThreadMXBean")
        org.junit.Assume.assumeTrue(api.getMethod("isThreadAllocatedMemorySupported").invoke(bean) as Boolean)
        api.getMethod("setThreadAllocatedMemoryEnabled", Boolean::class.javaPrimitiveType).invoke(bean, true)
        val allocatedBytes = api.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        val scratch = LayeredRenderer.FrameScratch()
        fun frame() {
            scratch.reset()
            scratch.occupy(5f, 5f, 20f, 20f)
            LayeredRenderer.computeStarNamePlacementInto(
                scratch.placement, 300f, 200f, 1.0, 80f, 20f, 5f, 1000f, 600f, 1f, scratch.boxes,
            )
            val box = scratch.placement.box
            scratch.occupy(box.left, box.top, box.right, box.bottom)
        }
        repeat(40_000) { frame() }
        val id = Thread.currentThread().id
        val before = allocatedBytes.invoke(bean, id) as Long
        repeat(20_000) { frame() }
        val allocated = (allocatedBytes.invoke(bean, id) as Long) - before
        assertTrue("Warmed layout allocated $allocated bytes for 20,000 frames", allocated < 4096L)
    }
}
