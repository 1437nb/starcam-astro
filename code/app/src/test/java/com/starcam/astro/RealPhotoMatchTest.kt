package com.starcam.astro

import com.starcam.astro.astro.LocalMatchResult
import com.starcam.astro.astro.LocalStarMatcher
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * 真实照片回归台（纯 JVM）：读取 PHOTO_DIR 下的 .gray 文件
 * （格式：int32 LE 宽 + int32 LE 高 + w*h 个 float32 LE 灰度，由外部 PIL 转换生成），
 * 走 detectStarsGray → LocalStarMatcher.match。
 *
 * 自 §0.11 起纳入常驻防回归：
 *  - apod4（北斗七星，34° 宽场）必须解出，且天区/视场落在真值窗口内（唯一真解样本）；
 *  - apod3 / apod5 / pleiades 必须保持 UNSOLVED —— 三者曾是假阳性（尺度坍缩/膨胀
 *    或内点稀疏），是门限收紧（inlier≥6 + FOV 0.05°~180°）的对照样本；
 *  - 其余（apod1/2、m44×4）为能力边界观察样本：窄场或疏散星团暗星区，919 亮星表
 *    覆盖不足属预期，暂不断言；星表扩充（HIP ≤7 等）后应转为真值断言。
 * 素材缺失时直接失败（回归保护不可静默跳过；素材齐备见 aa:/opt/realphotos）。
 */
class RealPhotoMatchTest {

    private val photoDir: String = System.getenv("PHOTO_DIR")
        ?: listOf(
            "../../testdata/realphotos",
            "../testdata/realphotos",
            "testdata/realphotos",
            "C:/starcam-bundle/testdata/realphotos",
            "/opt/realphotos",
        ).firstOrNull { File(it).isDirectory } ?: "/opt/realphotos"

    private fun loadGray(file: File): Triple<Int, Int, FloatArray> {
        val bytes = file.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val w = buf.int
        val h = buf.int
        val gray = FloatArray(w * h)
        val fb = buf.asFloatBuffer()
        fb.get(gray)
        return Triple(w, h, gray)
    }

    private fun photo(name: String): File {
        val f = File(photoDir, name)
        assertTrue("测试素材缺失：${f.absolutePath}（PHOTO_DIR=$photoDir）", f.isFile)
        return f
    }

    /** 与观察台同一管线：检测（前 60 星）→ 匹配 */
    private fun solve(file: File): LocalMatchResult? {
        val (w, h, gray) = loadGray(file)
        val stars = LocalStarMatcher.detectStarsGray(w, h, gray, 60)
        return LocalStarMatcher.match(stars, w, h)
    }

    private fun describe(res: LocalMatchResult?): String =
        res?.let {
            "SOLVED ra=${"%.3f".format(it.solve.raDeg)} dec=${"%.3f".format(it.solve.decDeg)} " +
                "inlier=${it.inlierCount} scale=${"%.2f".format(it.solve.pixScaleArcsec)}\""
        } ?: "UNSOLVED"

    /**
     * apod4 = 北斗七星 34°×24°（真值：astrometry.net demo/CREDITS；
     * 官方引擎交叉验证 RA 187.24 Dec 56.70，scale 170.6″ → FOV≈34°）。
     * 这是自研引擎当前唯一有真值锚点的真实照片，防"识别能力整体失效"回归。
     */
    @Test
    fun apod4BigDipperGroundTruth() {
        val res = solve(photo("apod4.gray"))
        println("PHOTO apod4.gray ${describe(res)}")
        assertNotNull("apod4 北斗场必须解出（当前 UNSOLVED —— 识别能力回归）", res)
        val s = res!!.solve
        assertTrue(
            "apod4 赤经偏出真值窗口：${s.raDeg}（期望 187.2±3）",
            abs(s.raDeg - 187.2) < 3.0,
        )
        assertTrue(
            "apod4 赤纬偏出真值窗口：${s.decDeg}（期望 56.7±3）",
            abs(s.decDeg - 56.7) < 3.0,
        )
        assertTrue(
            "apod4 视场偏出真值窗口：${s.fieldWidthDeg}°（期望 30°~40°）",
            s.fieldWidthDeg in 30.0..40.0,
        )
    }

    /**
     * 用户照片（小米 REDMI Turbo 5 Max 2026-09-12 于南宁拍摄，74° 秋季四边形）
     * ——§0.62 宽场打分轮的锚点样本。
     *
     * 真值来自 astrometry.net nova 解算（独立于本项目引擎）：
     * RA 0.676° / Dec 23.204° / 65.344″/px @4096px → 121.6″/px @2200px，
     * logodds 1806、nmatch 424、parity +1、index 4116。
     *
     * 修复前：逐星投票在 60°+ 宽场失效（真 HIP 仅 4~11 票，伪 HIP 14~19 票
     * 胜出）→ UNSOLVED。修复后：候选三角形逐个拟合打分（真候选对齐 16.8/25
     * 颗，伪候选 1.0/25）→ SOLVED。
     */
    @Test
    fun userPhotoNanningWideFieldGroundTruth() {
        val res = solve(photo("user-nanning-20260912.gray"))
        println("PHOTO user-nanning-20260912.gray ${describe(res)}")
        assertNotNull("南宁宽场照片必须解出（当前 UNSOLVED —— 宽场打分轮回归）", res)
        val s = res!!.solve
        // 真值窗口按 2°（nova 解与自研解的差异实测 <0.02°）
        assertTrue(
            "南宁照片赤经偏出真值窗口：${s.raDeg}（期望 0.68±2）",
            abs(s.raDeg - 0.68) < 2.0,
        )
        assertTrue(
            "南宁照片赤纬偏出真值窗口：${s.decDeg}（期望 23.20±2）",
            abs(s.decDeg - 23.20) < 2.0,
        )
        assertTrue(
            "南宁照片视场偏出真值窗口：${s.fieldWidthDeg}°（期望 68°~82°）",
            s.fieldWidthDeg in 68.0..82.0,
        )
    }

    /**
     * apod3 = 船帆座超新星遗迹（Vela SNR）窄场，8.4°×6.3°。
     *
     * §0.75 之前它必须保持 UNSOLVED —— 那时它只会以尺度坍缩的错解出现
     * （FOV≈627°）。深星表域兜底（mag<=6.5）落地后，它被**正确**解出：
     *   RA 130.27 / Dec -43.88 / FOV 8.5° / 20 内点。
     *
     * 真值依据（三条独立证据）：
     *  ① 视场 8.5° 与注释记载的 8.4°×6.3° 相差 1.2%；
     *  ② 解的银道坐标 l=263.1° b=-1.2°，船帆座 SNR 标准位 l=263.9° b=-3.1°，
     *     角距 2.02°（在 8.5° 视场内，偏离中心合理）；
     *  ③ **交叉验证**：按该解算坐标从 SkyView 拉的 DSS 巡天图（完全不同的图像）
     *     用同一引擎独立解出，28 内点，位置一致。
     * 旧失败形态（FOV≈627° 尺度坍缩）已消失，故按测试自身规则转为真值断言。
     */
    @Test
    fun apod3VelaNarrowFieldGroundTruth() {
        val res = solve(photo("apod3.gray"))
        println("PHOTO apod3.gray ${describe(res)}")
        assertNotNull("apod3 船帆座窄场必须解出（当前 UNSOLVED —— 深星表域兜底回归）", res)
        val s = res!!.solve
        assertTrue(
            "apod3 赤经偏出真值窗口：${s.raDeg}（期望 130.3±3）",
            abs(s.raDeg - 130.3) < 3.0,
        )
        assertTrue(
            "apod3 赤纬偏出真值窗口：${s.decDeg}（期望 -43.9±3）",
            abs(s.decDeg - (-43.9)) < 3.0,
        )
        assertTrue(
            "apod3 视场偏出真值窗口：${s.fieldWidthDeg}°（期望 6°~11°，真值 8.4°×6.3°）",
            s.fieldWidthDeg in 6.0..11.0,
        )
    }

    /**
     * 假阳性对照样本（修复前形态见验证报告 §0.4）：
     *  - apod5（Sedna 发现图，~45°）：曾以 5 内点错误锁定不符天区；
     *  - pleiades（昴星团 demo）：曾解出 FOV≈1900°（尺度膨胀）。
     * 两者当前都必须 UNSOLVED；若未来星表/算法升级使其中某张能正确解出，
     * 应把该样本改为真值断言而不是删掉本测试。
     *
     * 注：apod3 已于 §0.75 移出本列表 —— 深星表域兜底让它被**正确**解出
     * （船帆座 SNR 天区，见 [apod3VelaNarrowFieldGroundTruth]）。
     */
    @Test
    fun knownFalsePositivesRemainUnsolved() {
        val failures = ArrayList<String>()
        for (name in listOf("apod5.gray", "pleiades.gray")) {
            val res = solve(photo(name))
            println("PHOTO $name ${describe(res)}")
            if (res != null) {
                failures.add(
                    "$name 意外解出 ${describe(res)} " +
                        "fov=${"%.1f".format(res.solve.fieldWidthDeg)}°（假阳性回归）",
                )
            }
        }
        assertTrue(failures.joinToString("; "), failures.isEmpty())
    }

    /** 观察台（无断言）：全量 .gray 跑一遍并打印，结果从 XML <system-out> 的 PHOTO 行读 */
    @Test
    fun matchRealPhotos() {
        val dir = File(photoDir)
        val photos = dir.listFiles { f ->
            f.extension.lowercase() == "gray"
        }?.sorted() ?: emptyList()
        println("PHOTO-DIR $photoDir count=${photos.size}")
        for (ph in photos) {
            try {
                val t0 = System.currentTimeMillis()
                val res = solve(ph)
                val t1 = System.currentTimeMillis()
                val (w, h, _) = loadGray(ph)
                println(
                    "PHOTO %-24s %dx%d totalMs=%d %s".format(
                        ph.name, w, h, t1 - t0, describe(res),
                    ),
                )
            } catch (e: Exception) {
                println("PHOTO ${ph.name} ERROR ${e.message}")
            }
        }
        println("PHOTO-ALL-DONE")
    }
}
