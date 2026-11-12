package com.starcam.astro

import com.starcam.astro.astro.AdaptiveStarDetector
import com.starcam.astro.astro.CompactStarDetector
import com.starcam.astro.astro.DetectedStar
import com.starcam.astro.astro.LocalStarMatcher
import com.starcam.astro.astro.StarCatalogData
import org.junit.Assert.*
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Private-material truth regression; CI excludes this class, never silently skips missing files. */
class AdaptiveFieldProbeTest {
    @Test fun denseFieldComparison() {
        val dir = File("../../testdata/narrowfield")
        val truth = JSONObject(File(dir, "truth.json").readText())
        val regressionFields = setOf("cas15", "sco18", "sgr15", "summer20")
        var primaryOk=0; var solved=0; var wrong=0; var total=0; var targetSolved=0
        for (f in dir.listFiles { f -> f.extension == "gray" }!!.sorted()) {
            val id=f.nameWithoutExtension
            val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val w = b.int; val h = b.int
            val g = FloatArray(w * h); b.asFloatBuffer().get(g)
            val t = truth.getJSONObject(id)
            val positions = StarCatalogData.stars.filter { it.mag <= 6.5 }.mapNotNull {
                SyntheticSkyRenderer.project(it.ra, it.dec, t.getDouble("ra"), t.getDouble("dec"), t.getDouble("fov") / w, w, h)
            }
            val primary=LocalStarMatcher.detectStarsGray(w,h,g)
            var stars=primary
            // The original search runs first, exactly once. A successful non-recovery result
            // is also the baseline result; avoids repeating the entire expensive search.
            var compact: List<DetectedStar>? = null
            val attempt=LocalStarMatcher.matchWithDiagnostics(
                primary,w,h,fovHintDeg=t.getDouble("fov"),
                compactRecoveryDetected={ token -> CompactStarDetector.detect(w,h,g,token).also { compact=it } },
                recoveryDetected={ token -> AdaptiveStarDetector.detect(w,h,g,token=token).also { stars=it } },
            )
            if (attempt.diagnostics.usedCompactRecovery) stars = requireNotNull(compact)
            val result=attempt.result
            val hits=stars.count { s -> positions.any { p -> (p.first-s.x)*(p.first-s.x)+(p.second-s.y)*(p.second-s.y)<9 } }
            val s=result?.solve
            val ok=s!=null && separation(s.raDeg,s.decDeg,t.getDouble("ra"),t.getDouble("dec"))<t.getDouble("fov")*.25 &&
                kotlin.math.abs(maxOf(s.fieldWidthDeg,s.fieldHeightDeg)/t.getDouble("fov")-1)<.25
            if(ok && !attempt.diagnostics.usedRecovery) primaryOk++
            if(ok) {
                solved++
                if (t.getDouble("fov") >= 10.0) targetSolved++
            }
            if(s!=null && !ok) wrong++
            if (id in regressionFields) {
                assertNotNull("The narrow-field ladder must recover $id", s)
                assertTrue(separation(s!!.raDeg, s.decDeg, t.getDouble("ra"), t.getDouble("dec")) < .1)
                assertEquals(t.getDouble("fov"), s.fieldWidthDeg, .1)
            }
            println("COMPLEX-REAL $id hits=$hits/${stars.size} recovered=${attempt.diagnostics.usedRecovery} verdict=${if(ok) "TRUE" else if(s==null) "MISS" else "WRONG"} result=${s?.let { "${it.raDeg},${it.decDeg},${it.fieldWidthDeg} n=${result.inlierCount}" }}")
            total++
        }
        println("COMPLEX-SUMMARY primary=$primaryOk solved=$solved wrong=$wrong total=$total targetSolved=$targetSolved")
        assertEquals("All 32 observations must execute",32,total)
        assertEquals("Recovery must not add false solves",0,wrong)
        assertEquals("Every field in the supported range must solve in this observation set",16,targetSolved)
    }

    private fun separation(a:Double,b:Double,c:Double,d:Double):Double {
        val r=Math.PI/180
        return kotlin.math.acos((kotlin.math.sin(b*r)*kotlin.math.sin(d*r)+kotlin.math.cos(b*r)*kotlin.math.cos(d*r)*kotlin.math.cos((a-c)*r)).coerceIn(-1.0,1.0))/r
    }

    @Test fun trustedNarrowFovCanRecoverTheCygnusMilkyWay() {
        val f=File("../../testdata/narrowfield/cyg15.gray")
        val b=ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val w=b.int; val h=b.int; val g=FloatArray(w*h); b.asFloatBuffer().get(g)
        // Exercise the adaptive path independently, even if the primary search can solve it now.
        val attempt=LocalStarMatcher.matchWithDiagnostics(emptyList(),w,h,fovHintDeg=15.0,
            recoveryDetected={ AdaptiveStarDetector.detect(w,h,g,token=it) })
        println("TRUSTED-CYGNUS result=${attempt.result?.solve} diagnostics=${attempt.diagnostics}")
        assertNotNull(attempt.result)
        assertTrue(attempt.diagnostics.usedRecovery)
        val s=attempt.result!!.solve
        assertTrue(separation(s.raDeg,s.decDeg,305.0,40.0)<.1)
        assertEquals(15.0,s.fieldWidthDeg,.1)
    }

    @Test fun extraSearchMustNotReviveKnownFalsePositives() {
        for(id in listOf("apod5","pleiades")) {
            val b=ByteBuffer.wrap(File("../../testdata/realphotos/$id.gray").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val w=b.int; val h=b.int; val g=FloatArray(w*h); b.asFloatBuffer().get(g)
            val stars=LocalStarMatcher.detectStarsGray(w,h,g,100)
            val result=LocalStarMatcher.match(stars,w,h,recoveryDetected={ AdaptiveStarDetector.detect(w,h,g,token=it) })
            println("RECOVERY-NEGATIVE $id result=${result?.solve}")
            assertNull("Known false-positive control: $id",result)
        }
    }
}
