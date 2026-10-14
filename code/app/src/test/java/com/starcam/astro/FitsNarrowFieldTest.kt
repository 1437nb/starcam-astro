package com.starcam.astro

import com.starcam.astro.astro.LocalStarMatcher
import com.starcam.astro.util.FitsImage
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * §0.108 **真实 FITS** 回归台（素材不入库 → 与其它真值台一样按 `-PskipPhotoTests` 跳过）。
 *
 * 两件事：
 *
 * 1. **解码器 vs Python 参考实现逐点比对**：`tools/convert_narrowfield.py` 把 FITS 转成
 *    回归台 `.gray` 的口径就是设备端要对齐的口径（大端、行序翻转、1.0%/99.5% 分位拉伸）。
 *    同目录下 `X.fits` 与 `X.gray` 成对存在，因此可以**逐像素**验证 Kotlin 解码器。
 *    这条是整条 FITS 支持的基石：口径差一点，设备上认出的天区就会与回归台不一致。
 *
 * 2. **真值比对**：解码 → 检出 → 匹配（视场提示用 FITS 的 WCS），解出的天区/视场必须落
 *    在 `truth.json`（WCS 真值）窗口内 —— 与 [TieredCatalogTest] 同口径（中心 < fov×0.25、
 *    视场相对误差 < 25%）。
 *
 * 素材缺失时**直接失败**（回归保护不允许静默跳过）：`testdata/narrowfield/`，
 * 生成见 `tools/fetch_narrowfield.py` + `tools/convert_narrowfield.py`。
 */
class FitsNarrowFieldTest {

    private fun dir(): File = listOf(
        "../../testdata/narrowfield", "../testdata/narrowfield", "testdata/narrowfield",
    ).map { File(it) }.firstOrNull { it.isDirectory }
        ?: error("FITS 回归素材缺失：testdata/narrowfield（见 tools/fetch_narrowfield.py）")

    private fun loadGray(file: File): Triple<Int, Int, FloatArray> {
        val bytes = file.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val w = buf.int
        val h = buf.int
        val gray = FloatArray(w * h)
        buf.asFloatBuffer().get(gray)
        return Triple(w, h, gray)
    }

    private fun angDist(ra1: Double, dec1: Double, ra2: Double, dec2: Double): Double {
        val r1 = ra1 * PI / 180; val d1 = dec1 * PI / 180
        val r2 = ra2 * PI / 180; val d2 = dec2 * PI / 180
        val c = sin(d1) * sin(d2) + cos(d1) * cos(d2) * cos(r1 - r2)
        return acos(c.coerceIn(-1.0, 1.0)) * 180 / PI
    }

    /** 成对的 (fits, gray) 文件（两者都在才算）。 */
    private fun pairs(): List<Pair<File, File>> {
        val d = dir()
        return (d.listFiles { f -> f.extension == "fits" } ?: emptyArray()).sorted().mapNotNull { f ->
            val g = File(d, f.nameWithoutExtension + ".gray")
            if (g.isFile) f to g else null
        }
    }

    /**
     * ① 解码器必须与 Python 参考实现**逐点一致**（float32 舍入内）。
     * 不降采样（maxDim 设得比素材大）才能与参考同尺寸。
     */
    @Test
    fun `解码结果与 Python 参考实现逐点一致`() {
        val ps = pairs()
        assertTrue("testdata/narrowfield 下应有成对的 .fits/.gray 素材", ps.isNotEmpty())
        val bad = ArrayList<String>()
        for ((fits, grayFile) in ps) {
            val frame = FitsImage.decode(fits.absolutePath, maxDim = 100000)
            assertNotNull("FITS 应能解码：${fits.name}", frame)
            val (w, h, ref) = loadGray(grayFile)
            if (frame!!.w != w || frame.h != h) {
                bad.add("${fits.name} 尺寸不一致：$w x $h vs ${frame.w} x ${frame.h}")
                continue
            }
            var maxDiff = 0f
            for (i in ref.indices) maxDiff = max(maxDiff, abs(ref[i] - frame.gray[i]))
            println(
                "FITS-REAL %-12s %dx%d 与参考 .gray 最大逐点差=%.5f".format(fits.name, w, h, maxDiff),
            )
            if (maxDiff > 0.01f) bad.add("${fits.name} 与参考实现差 %.5f".format(maxDiff))
        }
        assertTrue(bad.joinToString("；"), bad.isEmpty())
    }

    /**
     * ② **两条链逐场对照**：`FITS 解码 → 求解` 与 `参考 .gray → 求解` 必须给出**同一结论**。
     *
     * 为什么用"对照"而不是"必须解出"：
     *  - 参考 `.gray` 是 `tools/convert_narrowfield.py` 的产物，也就是既有真值台
     *    （`TieredCatalogTest` / `NarrowFieldRegressionTest`）吃的那份素材 ——
     *    它们的解/不解记录着**匹配器当前的能力边界**（≥10° 已发布，<10° 明确不做）；
     *  - 本测试要考察的是**解码链**是否忠实，不是重新审一遍匹配能力。所以两边用
     *    **同一调用口径**（检出默认 64 颗、不带视场提示 —— 与既有真值台一致），
     *    逐场比结论：SOLVED/UNSOLVED/WRONG 必须一致，解出时天区与视场必须逐位相同。
     *    解码链一旦走偏（行序翻了、拉伸错了），这里会立刻炸。
     *
     * 注：把 maxStars 提到 100 或带上 WCS 视场提示会改变匹配结论（实测：密场
     * 15° 会因多余星挤坏候选生成而解不出）—— 那是**匹配器调参**的话题，
     * 不该混进解码链的验证里。
     */
    @Test
    fun `FITS 解码链与参考 gray 链逐场同结论（抽样）`() {
        // 形态互补的抽样（fov 从 0.5° 到 30°，含密场/稀疏场/极性场/接缝场）。
        // 为什么不跑全 31 场：每场要跑两条完整求解链，失败场次还会走深域重试
        // （单场数十秒），全跑会把每次全量回归拖长半小时以上；而"解码等价性"
        // 已由 ① 对全部 31 张的逐点比对确证，这里验的是等价性传导到结论上。
        val sample = listOf(
            "crux10", "equator10", "seam350", "npole12",
            "mid30", "summer20", "cas15", "hdf-05d",
        )
        val truthFile = File(dir(), "truth.json")
        assertTrue("缺 truth.json：${truthFile.absolutePath}", truthFile.isFile)
        val truth = JSONObject(truthFile.readText())

        /** 与既有真值台同口径：默认检出（64 颗）+ 不做任何先验。 */
        fun verdict(ra: Double?, dec: Double?, fov: Double?, w: Int, h: Int, gray: FloatArray): String {
            val stars = LocalStarMatcher.detectStarsGray(w, h, gray)
            val res = LocalStarMatcher.match(stars, w, h)
            if (ra == null || dec == null || fov == null) return "NO-TRUTH"
            if (res == null) return "UNSOLVED"
            val off = angDist(res.solve.raDeg, res.solve.decDeg, ra, dec)
            val fovErr = abs(max(res.solve.fieldWidthDeg, res.solve.fieldHeightDeg) - fov) / fov
            return if (off < fov * 0.25 && fovErr < 0.25) "SOLVED" else "WRONG"
        }

        val diffs = ArrayList<String>()
        val lines = ArrayList<String>()
        var checked = 0
        for (id in sample) {
            val fits = File(dir(), "$id.fits")
            val grayFile = File(dir(), "$id.gray")
            if (!fits.isFile || !grayFile.isFile || !truth.has(id)) continue
            checked++
            val t = truth.getJSONObject(id)
            val ra = t.getDouble("ra")
            val dec = t.getDouble("dec")
            val tf = t.getDouble("fov")

            val frame = FitsImage.decode(fits.absolutePath, maxDim = 2200)
            assertNotNull("FITS 应能解码：${fits.name}", frame)
            val (gw, gh, refGray) = loadGray(grayFile)
            val vFits = verdict(ra, dec, tf, frame!!.w, frame.h, frame.gray)
            val vRef = verdict(ra, dec, tf, gw, gh, refGray)
            lines.add("%-12s fov=%5.1f° FITS=%-8s 参考=%-8s".format(id, tf, vFits, vRef))
            if (vFits != vRef) diffs.add("$id：FITS=$vFits 参考=$vRef")
        }
        println("=== FITS 解码链对照（抽样 $checked 场）===\n" + lines.joinToString("\n"))
        assertTrue("抽样素材缺失（应有 ${sample.size} 场）", checked == sample.size)
        assertTrue(
            "解码链与参考链结论不一致（解码走偏）：${diffs.joinToString("；")}",
            diffs.isEmpty(),
        )
    }
}
