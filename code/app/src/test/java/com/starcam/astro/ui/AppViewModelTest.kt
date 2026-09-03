package com.starcam.astro.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 导航栈行为（§0.32）：压栈/弹栈/根页边界 */
class AppViewModelTest {

    @Test
    fun initialScreenIsHomeAndCannotGoBack() {
        val vm = AppViewModel()
        assertEquals(Screen.Home, vm.screen)
        assertFalse(vm.canGoBack)
    }

    @Test
    fun navigatePushesAndGoBackPopsToParent() {
        val vm = AppViewModel()
        vm.navigate(Screen.History)
        vm.navigate(Screen.Result("photo.jpg"))
        assertEquals(Screen.Result("photo.jpg"), vm.screen)
        assertTrue(vm.canGoBack)

        vm.goBack()
        assertEquals(Screen.History, vm.screen) // 回到上级（历史列表），而非主页
        vm.goBack()
        assertEquals(Screen.Home, vm.screen)
        assertFalse(vm.canGoBack)
    }

    @Test
    fun goBackOnRootIsNoOp() {
        val vm = AppViewModel()
        vm.goBack()
        assertEquals(Screen.Home, vm.screen)
        assertFalse(vm.canGoBack)
    }

    @Test
    fun deepStackBacksThroughAllLevels() {
        val vm = AppViewModel()
        // 主页 → 相机 → 结果 → 相机（重拍）→ 结果
        vm.navigate(Screen.Camera)
        vm.navigate(Screen.Result("a.jpg"))
        vm.navigate(Screen.Camera)
        vm.navigate(Screen.Result("b.jpg"))

        vm.goBack()
        assertEquals(Screen.Camera, vm.screen)
        vm.goBack()
        assertEquals(Screen.Result("a.jpg"), vm.screen)
        vm.goBack()
        assertEquals(Screen.Camera, vm.screen)
        vm.goBack()
        assertEquals(Screen.Home, vm.screen)
        assertFalse(vm.canGoBack)
    }

    @Test
    fun galleryAndBatchBackToTheirParents() {
        val vm = AppViewModel()
        vm.navigate(Screen.Gallery)
        assertEquals(Screen.Gallery, vm.screen)
        vm.goBack()
        assertEquals(Screen.Home, vm.screen)

        vm.navigate(Screen.Batch(listOf("1.jpg", "2.jpg")))
        vm.goBack()
        assertEquals(Screen.Home, vm.screen)
    }
}