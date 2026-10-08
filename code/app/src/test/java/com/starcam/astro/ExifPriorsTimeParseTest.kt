package com.starcam.astro

import com.starcam.astro.astro.ExifPriorsReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * P0-2.1 回归（外部审计 2026-10-06）：EXIF 时间解析 —— **语义锁定 + 线程安全**。
 *
 * 背景：解析器原用 object 级共享的 `SimpleDateFormat`（非线程安全，内部 Calendar
 * 是可变状态），而批量识别的两个 worker 会并发调用（`BatchScreen` →
 * `solarSystemForPhoto`）。现已改为不可变、线程安全的 `DateTimeFormatter`。
 *
 * 本测试做三件事：
 *  1. 锁住 private 解析函数的既有语义（UTC 秒值 / 本地时区 / '-' 分隔容错 / 坏输入 null）
 *     —— 反射调用，固定值断言不依赖 java.time 计算（避免同源循环论证）；
 *  2. 多线程并发调用，防"改回非线程安全实现"的静默回归；
 *  3. 反射检查 `ExifPriorsReader` 不再持有任何 `SimpleDateFormat` 字段。
 */
class ExifPriorsTimeParseTest {

    private val utcParse: Method =
        ExifPriorsReader::class.java
            .getDeclaredMethod("parseUtcEpoch", String::class.java, String::class.java)
            .apply { isAccessible = true }

    private val localParse: Method =
        ExifPriorsReader::class.java
            .getDeclaredMethod("parseLocalEpoch", String::class.java)
            .apply { isAccessible = true }

    /** 2024-08-12T22:30:15Z 的 Unix 秒（独立手算常量，不用 java.time 现算）。 */
    private val expectedUtc2024 = 1723501815L

    @Test
    fun utcEpochParsesGpsTimestampToSecond() {
        val epoch = utcParse.invoke(ExifPriorsReader, "2024:08:12", "22/1,30/1,15/1") as Long?
        assertEquals(expectedUtc2024, epoch)
    }

    @Test
    fun utcEpochToleratesDashSeparatedDate() {
        val epoch = utcParse.invoke(ExifPriorsReader, "2024-08-12", "22/1,30/1,15/1") as Long?
        assertEquals(expectedUtc2024, epoch)
    }

    @Test
    fun utcEpochParsesIntegerGpsTimeComponents() {
        // 部分机型把 GPSTimeStamp 写成整数字符串（无 "num/den" 分式）
        val epoch = utcParse.invoke(ExifPriorsReader, "2024:08:12", "22,30,15") as Long?
        assertEquals(expectedUtc2024, epoch)
    }

    /**
     * 本地解析必须按**系统默认时区**解释墙上时间：与 UTC 解析的差值应恰好等于
     * 该时刻的时区偏移（测试在 UTC 与 Asia/Shanghai 下都应通过）。
     */
    @Test
    fun localEpochUsesSystemZone() {
        val utc = utcParse.invoke(ExifPriorsReader, "2024:08:12", "22/1,30/1,15/1") as Long?
        val local = localParse.invoke(ExifPriorsReader, "2024:08:12 22:30:15") as Long?
        val offsetSeconds = ZoneId.systemDefault().rules
            .getOffset(Instant.ofEpochSecond(expectedUtc2024)).totalSeconds.toLong()
        assertEquals(expectedUtc2024 + offsetSeconds, local)
        // 同值再走一遍 UTC 入口，确认两入口对同一墙上时间的解释只差时区
        assertEquals(expectedUtc2024, utc)
    }

    @Test
    fun localEpochToleratesDashSeparatedInput() {
        val viaDash = localParse.invoke(ExifPriorsReader, "2024-08-12 22:30:15") as Long?
        val viaColon = localParse.invoke(ExifPriorsReader, "2024:08:12 22:30:15") as Long?
        assertEquals(viaColon, viaDash)
    }

    @Test
    fun malformedInputReturnsNullInsteadOfThrowing() {
        assertNull(localParse.invoke(ExifPriorsReader, null))
        assertNull(localParse.invoke(ExifPriorsReader, ""))
        assertNull(localParse.invoke(ExifPriorsReader, "   "))
        assertNull(localParse.invoke(ExifPriorsReader, "不是时间"))
        assertNull(localParse.invoke(ExifPriorsReader, "2024:08:12"))          // 缺时刻
        assertNull(utcParse.invoke(ExifPriorsReader, null, "22/1,30/1,15/1"))
        assertNull(utcParse.invoke(ExifPriorsReader, "", "22/1,30/1,15/1"))
        assertNull(utcParse.invoke(ExifPriorsReader, "不是日期", "22/1,30/1,15/1"))
    }

    @Test
    fun trailingWhitespaceIsTolerated() {
        val a = localParse.invoke(ExifPriorsReader, "2024:08:12 22:30:15") as Long?
        val b = localParse.invoke(ExifPriorsReader, "2024:08:12 22:30:15  ") as Long?
        assertEquals(a, b)
    }

    /**
     * 线程安全回归：并发解析必须全部得到正确值、且不抛异常。
     * 旧实现（共享 SimpleDateFormat）在真机批量并发下会污染解析结果或抛
     * ArrayIndexOutOfBoundsException；本测试对修复后的实现是确定性断言。
     */
    @Test
    fun concurrentParsingIsThreadSafeAndCorrect() {
        val threads = 8
        val iters = 200
        val pool = Executors.newFixedThreadPool(threads)
        val errors = AtomicInteger(0)
        val latch = CountDownLatch(threads)
        repeat(threads) {
            pool.execute {
                try {
                    repeat(iters) {
                        val u = utcParse.invoke(ExifPriorsReader, "2024:08:12", "22/1,30/1,15/1") as Long?
                        if (u != expectedUtc2024) errors.incrementAndGet()
                        val l = localParse.invoke(ExifPriorsReader, "2024:08:12 22:30:15") as Long?
                        if (l == null) errors.incrementAndGet()
                    }
                } catch (t: Throwable) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }
        assertTrue("并发解析未在 60 秒内完成（疑似死锁/卡死）", latch.await(60, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertEquals("并发解析出现错误结果或异常 —— 线程安全回归", 0, errors.get())
    }

    /** 守卫：解析器不得再持有 SimpleDateFormat 字段（防回退到非线程安全实现）。 */
    @Test
    fun readerHasNoSimpleDateFormatField() {
        val bad = ExifPriorsReader::class.java.declaredFields
            .filter { java.text.SimpleDateFormat::class.java.isAssignableFrom(it.type) }
            .map { it.name }
        assertTrue("ExifPriorsReader 仍存在非线程安全的 SimpleDateFormat 字段：$bad", bad.isEmpty())
    }

    /** 顺带锁定格式串本身：解析结果必须与"yyyy:MM:dd HH:mm:ss"一致（回归防漂移）。 */
    @Test
    fun parsedValueMatchesIsoComposition() {
        val epoch = localParse.invoke(ExifPriorsReader, "2024:08:12 22:30:15") as Long?
        val sameInstant = LocalDateTime.of(2024, 8, 12, 22, 30, 15)
            .atZone(ZoneId.systemDefault()).toEpochSecond()
        assertEquals(sameInstant, epoch)
        // 反向锚定：该墙上时间若按 UTC 解释，必须等于手算常量
        assertEquals(expectedUtc2024, LocalDateTime.of(2024, 8, 12, 22, 30, 15)
            .toEpochSecond(ZoneOffset.UTC))
    }
}
