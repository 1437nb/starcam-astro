package com.starcam.astro

import com.starcam.astro.astro.DetectedStar
import com.starcam.astro.astro.DetectorSelection
import com.starcam.astro.astro.LocalMatchResult
import com.starcam.astro.astro.LocalStarMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * 审计 P1-2.2：**公开演示照片的 CI 真值回归**（走生产编排路径）。
 *
 * ## 补的是哪个缺口
 *
 * 仓库自带 12 张公开演示 JPEG（`assets/offline_photos/`，随 APK 发布、无隐私问题），
 * 但此前的真值台（[Photo12RegressionTest] 等 8 类）读的是**不入库**的 .gray 素材，
 * CI 里被 `-PskipPhotoTests=true` 全部跳过 —— 真实照片这块在 CI 里只剩合成图回归
 * 兜着。本类改为**在测试内从公开 JPEG 现场解码出灰度**，因此 CI 照跑。
 *
 * ## 素材与真值
 *
 *  - 素材：仓库里的 12 张演示图（2200px 长边，与 app `decodeSampledBitmap(2200)` 同尺度）；
 *  - 灰度：[JpegLuma] 现场解码（系数与 [LocalStarMatcher.bitmapToGray] **同一套**），
 *    并打印 8 位灰度的 sha8（前 8 字节）——换 JDK / 换解码器导致像素漂移时先看它；
 *  - 真值：[DemoPhotoTruth]（与开发机真值台共用一份表，来源见该文件注释）。
 *
 * ## 为什么强调「生产编排路径」（审计原话：不只是直接调 matcher）
 *
 * §0.97b 的事故是**选表错**（喂给匹配器的主表被预览表顶掉、备用表与主表同一对象），
 * 而当时的回归台都是自己构造干净星表直接调 match，选表错了照样全绿。本类因此
 * **不自己拼列表**，而是把检出结果交给生产同一个 [DetectorSelection.plan]，
 * 再用它的 primary/fallback 去调 match —— 选的表与真机一致，出错的形态才拦得住。
 *
 * ## 判定（与开发机台同口径）
 *
 *  - **任何一张都不许是 FALSE-POSITIVE**（错误天区、尺度坍缩/膨胀 —— 这是硬线）；
 *  - **12 张必须全部 TRUE-SOLVE**（验证机实测 12/12，见 docs/91）。顺带说明：这条
 *    比开发机 12 张台更严 —— 那台允许 5031/5087/5092 MISS（口径不同：那边 maxStars=64
 *    + 私拍素材 .gray），本台用生产 maxStars=100 + 与真机同尺度像素，三张都真解。
 */
class OfflinePhotosTruthTest {

    /**
     * 生产口径的检出上限：`LocalStarMatcher.detectStars(bitmap)` 的默认 maxStars。
     * 若生产默认值改了，这里必须跟着改（本测试刻意用生产值，而不是
     * detectStarsGray 自己的默认 64 —— 那正是「测试与生产不同口径」的老毛病）。
     */
    private val productionMaxStars = 100

    private companion object {
        /**
         * 解码 + 检出 + 匹配都是秒级开销，缓存结果让两个测试方法只各跑一遍
         * （JUnit 每个方法新建实例，缓存挂在伴生对象上）。
         * 刻意**不缓存**整幅灰度（12 张 ≈ 130MB，512m 测试堆装不下）——
         * 缓存里只留判定行与星表（各几十 KB）。
         */
        val caseCache = HashMap<String, Case>()
    }

    /** 必须解出的样本：**全部 12 张**（验证机实测 12/12 真解，见 docs/91）。 */
    private val mustSolve: Set<String> = DemoPhotoTruth.byId.keys.toSet()

    // ------------------------------------------------------------------ 素材

    /** 公开演示照片目录（与 [MatchDiagnosticsTest] 共用同一处解析：见 [JpegLuma.demoPhotosDir]）。 */
    private fun assetsDir(): File = JpegLuma.demoPhotosDir()

    // ------------------------------------------------- 生产编排路径（JVM 可达形态）

    private class Case(
        val id: String,
        val w: Int,
        val h: Int,
        val detected: List<DetectedStar>,
        val verdict: DemoPhotoTruth.Verdict,
        val line: String,
        /** §0.97b 事故指纹：备用表与主表是同一个对象（生产里匹配器据此判定「无备用」） */
        val fallbackShadowed: Boolean,
        /** 喂给匹配器的主表是否就是检出列表本身（没被预览表顶掉） */
        val primaryIsDetected: Boolean,
    )

    /**
     * 检出 → **选表（生产同一函数）** → 匹配（生产同一入口/同一参数形态）。
     *
     * 演示图无 EXIF，生产对它们 `fovHintDeg = null`；SEP 是原生库能力，JVM 单测里
     * 不存在 —— 这正是真机原生库加载失败时生产走的那条路（SEP 不可用 → box-blur）。
     */
    private fun runCase(name: String): Case = caseCache.getOrPut(name) { runCaseUncached(name) }

    private fun runCaseUncached(name: String): Case {
        val id = DemoPhotoTruth.idOf(name) ?: error("$name 不在真值表里")
        val g = JpegLuma.read(File(assetsDir(), name))
        val t0 = System.currentTimeMillis()
        val detected = LocalStarMatcher.detectStarsGray(g.w, g.h, g.px, productionMaxStars)
        val plan = DetectorSelection.plan(
            boxBlur = detected,
            sep = null,
            sepTrustworthy = false,
            detectedTotal = detected.size,
        )
        val primary = plan.primary
        val res: LocalMatchResult? = if (primary != null && primary.size >= 5) {
            LocalStarMatcher.match(
                primary, g.w, g.h,
                pointingHint = null,
                fallbackDetected = plan.fallback,
                fovHintDeg = null,
            )
        } else {
            null
        }
        val ms = System.currentTimeMillis() - t0
        val s = res?.solve
        val solved = s?.let {
            DemoPhotoTruth.Solved(
                it.raDeg, it.decDeg, maxOf(it.fieldWidthDeg, it.fieldHeightDeg),
            )
        }
        val verdict = DemoPhotoTruth.classify(DemoPhotoTruth.byId.getValue(id), solved)
        val line = "CI-PHOTO %s %dx%d sha8=%s det=%-3d plan=%-3d totalMs=%-6d %s %s".format(
            id, g.w, g.h, g.sha8, detected.size, primary?.size ?: 0, ms,
            solved?.let {
                "SOLVED ra=%.2f dec=%.2f fov=%.1f inliers=%d".format(
                    it.ra, it.dec, it.fovDeg, res!!.inlierCount,
                )
            } ?: "UNSOLVED",
            verdict.label,
        )
        return Case(
            id, g.w, g.h, detected, verdict, line,
            plan.fallbackShadowedByPrimary, primary === detected,
        )
    }

    // ------------------------------------------------------------------ 测试

    /**
     * 主回归：全 12 张跑一遍（观察台 + 汇总）。
     *
     * 断言（三条，均为「不该发生」的形态）：
     *  ① 不许有假阳性；
     *  ② 选表接缝不许报「备用与主表同一对象」（§0.97b 事故指纹）；
     *  ③ 主表必须就是检出表本身（不许被预览表顶掉 —— 事故的另一半）。
     */
    @Test
    fun noFalsePositiveOnPublicDemoPhotos() {
        val files = assetsDir().listFiles { f ->
            f.name.startsWith("photo") && f.name.endsWith(".jpg")
        }?.sorted() ?: emptyList()
        assertEquals("公开演示照片应齐 12 张", 12, files.size)

        var trueSolves = 0
        var falsePositives = 0
        var misses = 0
        val failures = ArrayList<String>()
        for (f in files) {
            val c = runCase(f.name)
            println(c.line)
            when (c.verdict) {
                DemoPhotoTruth.Verdict.TRUE_SOLVE -> trueSolves++
                DemoPhotoTruth.Verdict.FALSE_POSITIVE -> falsePositives++
                DemoPhotoTruth.Verdict.MISS -> misses++
            }
            if (c.fallbackShadowed) failures.add("${c.id} 备用表退化（与主表同一对象，§0.97b 事故形态）")
            if (!c.primaryIsDetected) failures.add("${c.id} 主表不是检出表（被预览表顶掉）")
        }
        println("CI-SUMMARY true=$trueSolves false=$falsePositives miss=$misses total=${files.size}")
        assertEquals("假阳性（错误天区/尺度坍缩）必须为零：$failures", 0, falsePositives)
        assertTrue(failures.joinToString("；"), failures.isEmpty())
        // 下界防「能力整体失效」：实测真解数见 CI-SUMMARY（本断言只守地板）
        assertTrue(
            "真解数低于地板：$trueSolves（期望 ≥ ${mustSolve.size}，见 CI-SUMMARY）",
            trueSolves >= mustSolve.size,
        )
    }

    /** 必解样本逐个断言（12 张全在内）：任一张掉出真值窗口即红，明细看 CI-PHOTO 行。 */
    @Test
    fun mustSolvePhotosLandInsideTruthWindow() {
        val missing = ArrayList<String>()
        for (f in assetsDir().listFiles { f -> f.name.endsWith(".jpg") }?.sorted() ?: emptyList()) {
            val id = DemoPhotoTruth.idOf(f.name) ?: continue
            if (id !in mustSolve) continue
            val c = runCase(f.name)
            println("CI-MUST $id ${c.verdict.label}")
            if (c.verdict != DemoPhotoTruth.Verdict.TRUE_SOLVE) missing.add("$id=${c.verdict.label}")
        }
        assertTrue(
            "必解样本没有全部落在真值窗口：${missing.joinToString()}（明细见 CI-PHOTO 行）",
            missing.isEmpty(),
        )
    }

    /**
     * 备用星表机制在**真实照片**上的接缝回归（[FallbackStarListTest] 的机制，但那台读
     * 不入库素材、CI 跑不到）。选表走生产同一个 [DetectorSelection.plan]。
     *
     * 两组对照，都用 5031 的真实检出表：
     *  - 同量级噪声（§0.88 真机形态）：噪声挤进 box-blur 表的前排 → 与 SEP 表的
     *    头部重合率跌破 [DetectorSelection.OVERLAP_TRUST_MIN] → §0.72 撤销 SEP 信任，
     *    **不得**把它当备用（宁可没有备用，也不让不可信的列表参与）；
     *  - 暗噪声（噪声不挤前排）：重合率达标 → SEP 被选为备用，匹配器拿到备用表后
     *    仍必须解出真值天区（备用路径的管道是否真的接通）。
     *
     * 两组都在钉同一件事的两面：**选表结果必须由数据决定，且与事故形态无关**。
     */
    @Test
    fun fallbackSelectionOnRealPhoto() {
        val name = "photo5031.jpg"
        val id = DemoPhotoTruth.idOf(name)!!
        // 复用主回归缓存里的检出结果（本测试只需要星表，不需要像素）
        val c = runCase(name)
        val w = c.w
        val h = c.h
        val clean = c.detected
        assertTrue("前提：干净检出表应可解（5031 仙后座场）", clean.size >= 5)
        println(
            "CI-FALLBACK clean=${clean.size} " +
                "bright=${"%.0f".format(clean.minOf { it.brightness })}" +
                "..${"%.0f".format(clean.maxOf { it.brightness })}",
        )

        // ---- 组①：同量级噪声（150~500）100 颗，固定种子保证可复现
        val sameMag = (clean + noise(w, h, seed = 31100, lo = 150f, hi = 500f))
            .sortedByDescending { it.brightness }
        val planSame = DetectorSelection.plan(
            boxBlur = sameMag, sep = clean, sepTrustworthy = true, detectedTotal = sameMag.size,
        )
        val ovSame = DetectorSelection.overlap(sameMag, clean)
        println(
            "CI-FALLBACK ①sameMag ${clean.size}+100 ov=${ovSame.hits}/${ovSame.of} " +
                "fallback=${planSame.fallback?.size ?: -1} note=${planSame.note}",
        )
        assertSame("主表必须是污染列表（生产 select 结果）", sameMag, planSame.primary)
        assertFalse("备用表退化：与主表同一对象（§0.97b 事故形态）", planSame.fallbackShadowedByPrimary)
        assertNull(
            "重合率 ${ovSame.hits}/${ovSame.of} 低于门限时不得把 SEP 当备用（§0.72 撤销信任）",
            planSame.fallback,
        )

        // ---- 组②：暗噪声（lo=15/hi=60，明显不挤前排）→ 重合率达标 → SEP 作备用
        val dimMag = (clean + noise(w, h, seed = 31101, lo = 15f, hi = 60f))
            .sortedByDescending { it.brightness }
        val planDim = DetectorSelection.plan(
            boxBlur = dimMag, sep = clean, sepTrustworthy = true, detectedTotal = dimMag.size,
        )
        val ovDim = DetectorSelection.overlap(dimMag, clean)
        println(
            "CI-FALLBACK ②dim ${clean.size}+100 ov=${ovDim.hits}/${ovDim.of} " +
                "fallback=${planDim.fallback?.size ?: -1} note=${planDim.note}",
        )
        assertSame("主表仍是检出列表本身", dimMag, planDim.primary)
        assertTrue(
            "重合率 ${ovDim.hits}/${ovDim.of} 达标时 SEP 应被选为备用（备用路径的管道）",
            planDim.fallback === clean,
        )
        assertFalse(planDim.fallbackShadowedByPrimary)

        val res = LocalStarMatcher.match(
            planDim.primary!!, w, h,
            pointingHint = null,
            fallbackDetected = planDim.fallback,
            fovHintDeg = null,
        )
        val t = DemoPhotoTruth.byId.getValue(id)
        val solved = res?.solve?.let {
            DemoPhotoTruth.Solved(it.raDeg, it.decDeg, maxOf(it.fieldWidthDeg, it.fieldHeightDeg))
        }
        val verdict = DemoPhotoTruth.classify(t, solved)
        println(
            "CI-FALLBACK ②dim → " +
                (
                    res?.solve?.let {
                        "ra=%.2f dec=%.2f inliers=%d usedFallback=%s".format(
                            it.raDeg, it.decDeg, res.inlierCount, LocalStarMatcher.lastMatchUsedFallback,
                        )
                    } ?: "UNSOLVED"
                    ) + " " + verdict.label,
        )
        assertEquals("带备用表的这次匹配必须解出真值天区", DemoPhotoTruth.Verdict.TRUE_SOLVE, verdict)
    }

    /** 固定种子的噪声星：位置随机、亮度落在 [lo, hi]（与真星同量级时用来挤坏候选生成）。 */
    private fun noise(w: Int, h: Int, seed: Int, lo: Float, hi: Float): List<DetectedStar> {
        val rnd = Random(seed)
        return List(100) {
            DetectedStar(
                x = rnd.nextFloat() * (w - 20f) + 10f,
                y = rnd.nextFloat() * (h - 20f) + 10f,
                brightness = lo + rnd.nextFloat() * (hi - lo),
            )
        }
    }
}
