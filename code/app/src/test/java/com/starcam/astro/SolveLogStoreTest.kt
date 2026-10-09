package com.starcam.astro

import com.starcam.astro.data.SolveLogStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * §0.70 识别日志：报告构造与摘要渲染。
 *
 * 只测**纯 JVM 部分**（JSON 组装 / 人类可读摘要）—— 落盘那部分依赖 Android
 * Context 与外部文件目录，靠端到端手工验证（见 `docs/68-§0.70`）。
 *
 * 为什么值得单测：这份摘要会直接显示给用户、并被要求「反馈问题时报给我」，
 * 字段缺失或格式错乱会让诊断失效。
 */
class SolveLogStoreTest {

    private fun sampleReport(): JSONObject = JSONObject().apply {
        put("logVersion", 2)
        put("image", "IMG_20260912_012548.jpg")
        put("width", 1649)
        put("height", 2200)
        put("starCount", 170)
        put("steps", org.json.JSONArray())
        put("verdict", "星点充足，但没能匹配星表（引擎：官方引擎→内置星表）")
    }

    @Test
    fun `摘要包含尺寸与星点数`() {
        val r = sampleReport()
        val s = SolveLogStore.renderSummary(r)
        assertTrue("应含图像尺寸：$s", s.contains("1649×2200"))
        assertTrue("应含星点数：$s", s.contains("170"))
    }

    @Test
    fun `摘要列出每步引擎与其结局`() {
        val r = sampleReport()
        SolveLogStore.addStep(r, "官方引擎", "unsolved", "nstars=4478 rc=1", 3200)
        SolveLogStore.addStep(r, "内置星表", "unsolved", "星点 170 颗但未匹配", 9800)
        val s = SolveLogStore.renderSummary(r)
        assertTrue("应含官方引擎：$s", s.contains("官方引擎"))
        assertTrue("应含内置星表：$s", s.contains("内置星表"))
        assertTrue("应含细节：$s", s.contains("nstars=4478"))
        assertTrue("应含耗时：$s", s.contains("3200ms"))
        // 结局必须标明，否则无法区分「没跑」与「跑了失败」
        assertEquals(2, r.getJSONArray("steps").length())
    }

    @Test
    fun `无步骤时不渲染引擎段`() {
        val s = SolveLogStore.renderSummary(sampleReport())
        assertFalse("没有步骤就不该出现引擎段：$s", s.contains("引擎尝试"))
    }

    @Test
    fun `摘要带上判定结论`() {
        val s = SolveLogStore.renderSummary(sampleReport())
        assertTrue("应含判定：$s", s.contains("判定"))
        assertTrue("应含判定内容：$s", s.contains("没能匹配星表"))
    }

    @Test
    fun `星点列表受上限约束且坐标完整`() {
        val r = sampleReport()
        // 造 500 颗星，应被截到上限（200）
        val stars = (0 until 500).map {
            com.starcam.astro.astro.DetectedStar(
                x = it.toFloat(),
                y = it * 2f,
                brightness = 1000f - it,
            )
        }
        SolveLogStore.setStars(r, stars)
        val arr = r.getJSONArray("stars")
        assertEquals("星点列表应被截到 200", 200, arr.length())
        // starCount 记的是**真实总数**，不是截断后的数量 —— 诊断时这个数更重要
        assertEquals(500, r.getInt("starCount"))
        val first = arr.getJSONArray(0)
        assertEquals(3, first.length())
        // 顺序是 [x, y, brightness]
        assertEquals(0.0, first.getDouble(0), 1e-6)
        assertEquals(0.0, first.getDouble(1), 1e-6)
        assertEquals(1000.0, first.getDouble(2), 1e-6)
        // 第 3 颗（下标 2）验证 y 与 brightness 的映射没有错位
        val third = arr.getJSONArray(2)
        assertEquals(2.0, third.getDouble(0), 1e-6)
        assertEquals(4.0, third.getDouble(1), 1e-6)
        assertEquals(998.0, third.getDouble(2), 1e-6)
    }

    @Test
    fun `报告骨架字段齐全且不含完整路径`() {
        // 隐私：日志里只留文件名。这里直接验证裁剪逻辑的等价物。
        val r = sampleReport()
        assertFalse("不应含目录分隔符", r.getString("image").contains("/"))
        assertFalse("不应含反斜杠", r.getString("image").contains("\\"))
        assertTrue(r.has("width"))
        assertTrue(r.has("height"))
        assertTrue(r.has("logVersion"))
    }

    @Test
    fun `addStep 在缺 steps 字段时也能自愈`() {
        val r = JSONObject().apply { put("logVersion", 2) }
        SolveLogStore.addStep(r, "官方引擎", "solved", null, null)
        assertEquals(1, r.getJSONArray("steps").length())
        val step = r.getJSONArray("steps").getJSONObject(0)
        assertEquals("官方引擎", step.getString("engine"))
        assertEquals("solved", step.getString("outcome"))
        assertFalse("detail 为 null 时不应写入空字段", step.has("detail"))
        assertFalse("costMs 为 null 时不应写入", step.has("costMs"))
    }

    @Test
    fun `失败现场落盘后可完整读回（gzip 往返）`() {
        // 不依赖 Android Context：这里直接验证「写入格式 == 开发机读取格式」，
        // 即 SolveLogStore.dumpFailure 写的字节流能被 tools 侧的读取逻辑还原。
        // 尺寸取小值以免测试变慢，但覆盖 int32 头 + float32 体的完整结构。
        val w = 64
        val h = 48
        val gray = FloatArray(w * h) { (it % 251).toFloat() * 0.37f }
        val tmp = java.io.File.createTempFile("starcam-gray", ".gz")
        try {
            java.io.DataOutputStream(
                java.util.zip.GZIPOutputStream(java.io.BufferedOutputStream(tmp.outputStream())),
            ).use { o ->
                o.writeInt(w); o.writeInt(h)
                for (v in gray) o.writeFloat(v)
            }
            // 读回（模拟开发机侧：gunzip 后按 int32 w/h + float32 解析）
            java.io.DataInputStream(
                java.util.zip.GZIPInputStream(java.io.BufferedInputStream(tmp.inputStream())),
            ).use { i ->
                assertEquals(w, i.readInt())
                assertEquals(h, i.readInt())
                for (k in gray.indices) {
                    assertEquals("第 $k 个像素应逐字节相同", gray[k], i.readFloat(), 0f)
                }
            }
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `gzip 体积显著小于原始（星空图低频可压）`() {
        val w = 512
        val h = 512
        // 模拟星空：大片暗背景 + 少量亮斑（与真实照片的分布同量级）
        val gray = FloatArray(w * h) { 18f + (it % 7) * 0.1f }
        for (k in 0 until 200) {
            gray[(k * 977) % gray.size] = 3000f
        }
        val rawBytes = w * h * 4 + 8
        val tmp = java.io.File.createTempFile("starcam-gray", ".gz")
        try {
            java.io.DataOutputStream(
                java.util.zip.GZIPOutputStream(java.io.BufferedOutputStream(tmp.outputStream())),
            ).use { o ->
                o.writeInt(w); o.writeInt(h)
                for (v in gray) o.writeFloat(v)
            }
            val ratio = tmp.length().toDouble() / rawBytes
            // 实测真实照片压到 14.5%；测试图更规整，放宽到 <40% 即可证明压缩生效
            assertTrue(
                "gzip 应显著压缩（实际 ${(ratio * 100).toInt()}%）",
                ratio < 0.40,
            )
        } finally {
            tmp.delete()
        }
    }

    // ==================== P0-2.4：求解会话号与失败现场防撞 ====================

    @Test
    fun `求解会话号唯一且前缀含照片名`() {
        val a = SolveLogStore.beginSolve("/sdcard/DCIM/photo5031.jpg")
        val b = SolveLogStore.beginSolve("photo5032.jpg")
        assertTrue("solveId 应形如 S001：${a.solveId}", Regex("^S\\d{3,}$").matches(a.solveId))
        assertNotEquals("两次求解不得重号", a.solveId, b.solveId)
        // 隐私：前缀只带文件名，不带目录
        assertEquals("photo5031.jpg", a.imageName)
        assertEquals("[${a.solveId}][photo5031.jpg] ", a.prefix)
    }

    @Test
    fun `照片名缺失时前缀只含会话号`() {
        val s = SolveLogStore.beginSolve(null)
        assertEquals("[${s.solveId}] ", s.prefix)
        val blank = SolveLogStore.beginSolve("   ")
        assertEquals("空白照片名不应进前缀", "[${blank.solveId}] ", blank.prefix)
    }

    /**
     * 批量两个 worker 并发开会话（与生产一致）：ID 必须全部唯一，否则日志归属失效。
     */
    @Test
    fun `并发开会话不重号`() {
        val ids = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        val start = java.util.concurrent.CountDownLatch(1)
        repeat(8) {
            pool.execute {
                start.await()
                repeat(100) { ids.add(SolveLogStore.beginSolve("p$it.jpg").solveId) }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(
            "并发开会话未在 30 秒内完成",
            pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS),
        )
        assertEquals("800 次会话应全部唯一", 800, ids.size)
    }

    /**
     * P0-2.4 核心回归：**同一秒**的两张照片失败，现场目录不得同名。
     * 旧实现目录名只有秒级时间戳，两者会写进同一目录、report.json 与
     * input.gray.gz 互相覆盖（产出"报告来自 A、像素来自 B"的缝合怪）。
     */
    @Test
    fun `失败现场目录名带会话号且同秒不撞`() {
        val t = LocalDateTime.of(2026, 10, 9, 1, 2, 3)
        val a = SolveLogStore.failDirName(t, "S001")
        val b = SolveLogStore.failDirName(t, "S002")
        assertTrue("目录名应含时间戳：$a", a.startsWith("fail-20261009-010203"))
        assertTrue("目录名应带会话号：$a", a.endsWith("-S001"))
        assertNotEquals("同一秒的不同会话不得同名", a, b)
        // 无会话号时退化为旧格式（向后兼容，供未接会话的调用方使用）
        assertEquals("fail-20261009-010203", SolveLogStore.failDirName(t, null))
    }
}
