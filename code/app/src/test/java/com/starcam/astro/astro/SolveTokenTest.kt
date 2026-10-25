package com.starcam.astro.astro

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CancellationException

/**
 * 求解取消令牌与生命周期单元测试（纯 JVM）。
 */
class SolveTokenTest {

    @Test
    fun token_initialState_isNotCancelled() {
        val token = SolveToken()
        assertFalse(token.isCancelled)
        // check() 不应抛出异常
        token.check()
    }

    @Test
    fun token_afterCancel_throwsCancellationException() {
        val token = SolveToken()
        token.cancel()
        assertTrue(token.isCancelled)

        try {
            token.check()
            fail("Expected CancellationException")
        } catch (e: Exception) {
            assertTrue(e is CancellationException)
        }
    }

    @Test
    fun localMatcher_withCancelledToken_abortsImmediately() {
        val token = SolveToken()
        token.cancel()

        // 构造虚拟检测星点（不少于 5 颗）
        val stars = (0 until 20).map { i ->
            DetectedStar(
                x = 100f + i * 20f,
                y = 100f + i * 15f,
                brightness = 1000f - i * 10f,
            )
        }

        try {
            LocalStarMatcher.match(
                detected = stars,
                width = 1920,
                height = 1080,
                token = token,
            )
            fail("Expected CancellationException during match")
        } catch (e: Exception) {
            assertTrue(e is CancellationException)
        }
    }

    @Test
    fun demoPhotoFov_securityIsolation() {
        // 普通用户相册目录下的重名照片：绝不应命中先验
        val userDcimPath = "/storage/emulated/0/DCIM/Camera/photo5031.jpg"
        assertNull(StarSolver.readFovDeg(userDcimPath))

        val userDownloadPath = "/data/user/0/com.starcam.astro/cache/pick_photo4963.jpg"
        assertNull(StarSolver.readFovDeg(userDownloadPath))

        // 明确内置演示目录下的照片：命中先验
        val demoPath = "/data/user/0/com.starcam.astro/cache/offline_photos/photo5031.jpg"
        val demoFov = StarSolver.readFovDeg(demoPath)
        assertNotNull(demoFov)
        assertTrue(demoFov!! in 75.0..77.0)
    }
}
