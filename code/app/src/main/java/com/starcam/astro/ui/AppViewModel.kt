package com.starcam.astro.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.starcam.astro.astro.SkyRegion

/** 界面路由 */
sealed interface Screen {
    data object Home : Screen
    data object Camera : Screen
    data object Settings : Screen
    data object History : Screen

    /** 内置相册（原图模式）：应用自选照片，绕开厂商选择器的 EXIF 脱敏（§0.31） */
    data object Gallery : Screen

    /** 离线演示（§0.39）：内置 8 张已验证可离线识别的真实星空照片 */
    data object OfflineDemo : Screen

    /** 批量识别导出页；paths 为缓存目录中的照片路径列表 */
    data class Batch(val paths: List<String>) : Screen

    /** 识别结果页；demoRegion 非空表示离线演示模式（使用内置生成的星空照片） */
    data class Result(val imagePath: String, val demoRegion: SkyRegion? = null) : Screen
}

/**
 * 全局界面状态（导航栈，§0.32）。
 *
 * 由"单页状态"改为"栈式导航"：navigate 压栈、goBack 弹栈，返回键/手势
 * 逐级回退。修复场景：主页 → 识别历史 → 打开结果 → 返回应回到历史列表，
 * 而不是直接回主页（旧实现所有 onBack 都 navigate(Home)）。
 * 根为 Home（弹栈到剩 Home 即停，Home 上的系统返回仍为退出应用）。
 */
class AppViewModel : ViewModel() {
    private val stack = mutableStateListOf<Screen>(Screen.Home)

    /** 当前页（Compose 可观察：mutableStateListOf 的读取被快照追踪） */
    val screen: Screen get() = stack[stack.size - 1]

    /** 是否可返回上级（根页 Home 返回 false） */
    val canGoBack: Boolean get() = stack.size > 1

    /** 打开新页面（压栈） */
    fun navigate(target: Screen) {
        stack.add(target)
    }

    /** 返回上一级（根页空操作） */
    fun goBack() {
        if (stack.size > 1) stack.removeAt(stack.size - 1)
    }
}