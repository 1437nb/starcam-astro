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
     * 假阳性对照样本（修复前形态见验证报告 §0.4）：
     *  - apod3（船帆座 SNR，8.4°×6.3° 窄场）：曾解出 FOV≈627°（尺度坍缩）；
     *  - apod5（Sedna 发现图，~45°）：曾以 5 内点错误锁定不符天区；
     *  - pleiades（昴星团 demo）：曾解出 FOV≈1900°（尺度膨胀）。
     * 三者当前都必须 UNSOLVED；若未来星表/算法升级使其中某张能正确解出，
     * 应把该样本改为真值断言而不是删掉本测试。
     */
    @Test
    fun knownFalsePositivesRemainUnsolved() {
        val failures = ArrayList<String>()
        for (name in listOf("apod3.gray", "apod5.gray", "pleiades.gray")) {
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
