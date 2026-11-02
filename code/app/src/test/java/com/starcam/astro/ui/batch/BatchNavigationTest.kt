package com.starcam.astro.ui.batch

import com.starcam.astro.ui.AppViewModel
import com.starcam.astro.ui.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BatchNavigationTest {
    @Test
    fun selectingSamePhotosAgainCreatesNewBatch() {
        val paths = listOf("/photos/a.jpg", "/photos/b.jpg")
        val first = Screen.Batch(paths)
        val second = Screen.Batch(paths)
        assertEquals(first.paths, second.paths)
        assertNotEquals("新选择不能复用已完成或已取消的任务", first.sessionId, second.sessionId)
    }

    @Test
    fun returningFromRetakeKeepsOriginalBatchSession() {
        val app = AppViewModel()
        val batch = Screen.Batch(listOf("/photos/a.jpg", "/photos/b.jpg"))
        app.navigate(batch)
        app.navigate(Screen.Camera)
        app.goBack()
        assertSame("返回批次应保留原任务及其结果", batch, app.screen)
        assertEquals(batch.sessionId, (app.screen as Screen.Batch).sessionId)
    }
}
