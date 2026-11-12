package com.starcam.astro

import com.starcam.astro.astro.AdaptiveStarDetector
import com.starcam.astro.astro.CompactStarDetector
import com.starcam.astro.astro.LocalStarMatcher
import com.starcam.astro.astro.SolveToken
import com.starcam.astro.astro.SolveBudgetExceeded
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException
import kotlin.math.exp
import kotlin.random.Random

class AdaptiveStarDetectorTest {
    private fun source(g: FloatArray, w: Int, x: Int, y: Int, amplitude: Float, sx: Double, sy: Double = sx) {
        val h = g.size / w
        val r = (maxOf(sx, sy) * 4).toInt() + 1
        for (yy in maxOf(0, y-r)..minOf(h-1, y+r)) for (xx in maxOf(0,x-r)..minOf(w-1,x+r)) {
            val dx = xx-x; val dy = yy-y
            g[yy*w+xx] += (amplitude * exp(-0.5*(dx*dx/(sx*sx)+dy*dy/(sy*sy)))).toFloat()
        }
    }

    @Test fun faintStarsSurviveOtherRegionsNoiseAndLightGradient() {
        val w = 512; val h = 384
        val random = Random(20261108)
        val g = FloatArray(w*h) { i ->
            val x = i % w; val y = i / w
            15f + y * .15f + random.nextFloat() * if (x > 320) 100f else 2f
        }
        val points = listOf(45 to 45, 115 to 115, 185 to 185, 250 to 260)
        for ((x,y) in points) source(g,w,x,y,14f,1.6)
        val old = LocalStarMatcher.detectStarsGray(w,h,g)
        val stars = AdaptiveStarDetector.detect(w,h,g)
        val recovered = points.count { (x,y) -> stars.any { (it.x-x)*(it.x-x)+(it.y-y)*(it.y-y)<4 } }
        val originalHits = points.count { (x,y) -> old.any { (it.x-x)*(it.x-x)+(it.y-y)*(it.y-y)<4 } }
        println("FAINT old=${old.size} adaptive=${stars.size} truth=$recovered/${points.size}")
        assertEquals("Noisy foreground must not raise the threshold over the quiet sky", points.size, recovered)
        assertTrue("Must recover stars missed by the global threshold", recovered > originalHits)
    }

    @Test fun extendedNebulaeDoNotFillTheStarList() {
        val w = 512; val g = FloatArray(w*w) { 20f }
        val points = listOf(70 to 70, 180 to 70, 290 to 70, 400 to 70)
        for ((x,y) in points) source(g,w,x,y,35f,1.5)
        for (y in 180..420 step 70) for (x in 70..420 step 70) source(g,w,x,y,120f,10.0)
        val stars = AdaptiveStarDetector.detect(w,w,g, maxStars=8)
        assertEquals(4, points.count { (x,y) -> stars.any { (it.x-x)*(it.x-x)+(it.y-y)*(it.y-y)<4 } })
        assertTrue("Diffuse sources must not occupy triangle seeds", stars.size <= 5)
    }

    @Test fun hotPixelsAndLongTrailsAreRejectedButShortTrailingIsKept() {
        val w=384; val g=FloatArray(w*w) { 10f }
        for (y in 30..330 step 30) for (x in 30..330 step 30) g[y*w+x]=255f
        source(g,w,100,110,50f,2.4,1.3)
        source(g,w,245,210,100f,15.0,1.0)
        val stars=AdaptiveStarDetector.detect(w,w,g)
        assertTrue("Short trailing must remain usable",stars.any { (it.x-100)*(it.x-100)+(it.y-110)*(it.y-110)<4 })
        assertTrue("Single pixel defects/long trails must not masquerade as stars",stars.size<=2)
    }

    @Test fun uniformAndNonFiniteFramesDoNotProduceStars() {
        for (value in listOf(0f,120f,Float.NaN,Float.POSITIVE_INFINITY)) {
            assertTrue(AdaptiveStarDetector.detect(128,128,FloatArray(128*128){value}).isEmpty())
            assertTrue(CompactStarDetector.detect(128,128,FloatArray(128*128){value},SolveToken()).isEmpty())
        }
    }

    @Test fun compactDetectionRanksQuietStarsAheadOfHighVarianceTexture() {
        val width = 384
        val height = 256
        val random = Random(913)
        val gray = FloatArray(width * height) { i -> 35f + random.nextFloat() * if (i % width >= 180) 100f else 2f }
        val truth = listOf(46 to 50, 110 to 70, 65 to 134, 138 to 192)
        for ((x, y) in truth) source(gray, width, x, y, 26f, 1.4)
        val found = CompactStarDetector.detect(width, height, gray, SolveToken()).take(16)
        assertTrue(truth.all { (x,y) -> found.any { (it.x-x)*(it.x-x)+(it.y-y)*(it.y-y) < 4 } })
    }

    @Test fun cancellationIsCheckedBeforeLargeAllocations() {
        val token=SolveToken().also { it.cancel() }
        try {
            AdaptiveStarDetector.detect(64,64,FloatArray(64*64),token=token)
            fail("Cancelled detection must stop")
        } catch (_: CancellationException) { }
    }

    @Test fun complexSyntheticSkyHasTheCorrectSolution() {
        val w=900
        val rendered=SyntheticSkyRenderer.render(30.0,0.0,65.0,w,4.0)
        val g=rendered.gray
        val random=Random(117)
        for (i in g.indices) {
            val x=i%w; val y=i/w
            g[i] += 15f + y*.08f + random.nextFloat() * if(x>700) 80f else 2f
        }
        for(y in 100..700 step 120) for(x in 80..650 step 110) source(g,w,x,y,65f,11.0)
        repeat(500) { g[random.nextInt(g.size)]=255f }
        val stars=AdaptiveStarDetector.detect(w,w,g)
        val result=LocalStarMatcher.match(stars,w,w,allowDeepRetry=false)
        println("COMPLEX stars=${stars.size} solution=${result?.solve} inliers=${result?.inlierCount}")
        assertNotNull("Contaminated synthetic sky must solve",result)
        assertEquals(30.0,result!!.solve.raDeg,1.0)
        assertEquals(0.0,result.solve.decDeg,1.0)
        assertTrue(result.solve.fieldWidthDeg in 58.0..72.0)
    }

    @Test fun recoveryIsLazyAndWorksEvenWhenTheOriginalDetectorHasTooFewStars() {
        val w=700
        val rendered=SyntheticSkyRenderer.render(30.0,0.0,65.0,w,4.0)
        val clean=AdaptiveStarDetector.detect(w,w,rendered.gray)
        val ordinary=LocalStarMatcher.matchWithDiagnostics(clean,w,w,allowDeepRetry=false,
            recoveryDetected={ error("Recovery must not run for a successful primary") })
        assertNotNull(ordinary.result)
        assertFalse(ordinary.diagnostics.usedRecovery)
        val recovered=LocalStarMatcher.matchWithDiagnostics(emptyList(),w,w,allowDeepRetry=false,
            recoveryDetected={ clean })
        assertNotNull(recovered.result)
        assertTrue(recovered.diagnostics.usedRecovery)
        assertEquals(30.0,recovered.result!!.solve.raDeg,1.0)
    }

    @Test fun recoveryRetainsTheInlierAndTrustedFovSafetyGates() {
        val w=700
        val r=SyntheticSkyRenderer.render(30.0,0.0,65.0,w,4.0)
        val stars=AdaptiveStarDetector.detect(w,w,r.gray)
        val result=LocalStarMatcher.match(stars,w,w,allowDeepRetry=false)!!
        assertFalse(LocalStarMatcher.acceptableRecovery(result.copy(inlierCount=9),65.0))
        assertFalse(LocalStarMatcher.acceptableRecovery(result,15.0))
        assertTrue(LocalStarMatcher.acceptableRecovery(result,65.0))
    }

    @Test fun thinCloudAndMildBlurKeepTheCorrectSkyPattern() {
        val w=900
        val rendered=SyntheticSkyRenderer.render(30.0,0.0,65.0,w,4.0)
        val gray=FloatArray(w*w)
        // Horizontal integration simulates mild trailing; the veil attenuates the stars
        // and adds a smooth cloud background, without inventing catalog positions.
        for(y in 0 until w) for(x in 2 until w-2) {
            var v=0f
            for(dx in -2..2) v+=rendered.gray[y*w+x+dx]
            gray[y*w+x]=v*.13f+20f+y*.04f
        }
        source(gray,w,400,400,90f,150.0)
        val detected=AdaptiveStarDetector.detect(w,w,gray)
        val result=LocalStarMatcher.match(detected,w,w,allowDeepRetry=false)
        assertNotNull("Thin cloud/mild trailing must retain a correct solution",result)
        assertEquals(30.0,result!!.solve.raDeg,1.0)
        assertEquals(0.0,result.solve.decDeg,1.0)
    }

    @Test fun pureNoiseDoesNotBecomeASkySolutionThroughRecovery() {
        val w=384
        val random=Random(3137)
        val gray=FloatArray(w*w) { 20f+random.nextFloat()*50f }
        val result=LocalStarMatcher.match(emptyList(),w,w,allowDeepRetry=false,
            recoveryDetected={ AdaptiveStarDetector.detect(w,w,gray,token=it) })
        assertNull("Extra detection attempts must not create a solve from noise",result)
    }

    @Test fun recoveryCannotExtendTheParentTimeBudget() {
        val parent=SolveToken(timeoutMillis=30)
        try {
            LocalStarMatcher.match(emptyList(),64,64,token=parent,recoveryDetected={ child ->
                val end=System.nanoTime()+1_000_000_000
                while(System.nanoTime()<end) { child.check(); Thread.yield() }
                emptyList()
            })
            fail("Parent deadline must escape the optional recovery handler")
        } catch (_: SolveBudgetExceeded) { }
    }

    @Test fun parentCancellationDuringRecoveryPropagates() {
        val parent=SolveToken()
        try {
            LocalStarMatcher.match(emptyList(),64,64,token=parent,recoveryDetected={
                parent.cancel()
                emptyList()
            })
            fail("Cancellation must not become a normal unsolved result")
        } catch (_: CancellationException) { }
    }

    @Test fun knownTelescopeFieldsDoNotStartTheExtraSearch() {
        assertNull(LocalStarMatcher.match(emptyList(),64,64,fovHintDeg=5.0,
            recoveryDetected={ error("Known fields below 10 degrees are outside the extra recovery scope") }))
    }
}
