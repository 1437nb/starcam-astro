package com.starcam.astro.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.starcam.astro.astro.DemoSolver
import com.starcam.astro.data.SettingsRepository
import com.starcam.astro.ui.camera.CameraScreen
import com.starcam.astro.ui.batch.BatchScreen
import com.starcam.astro.ui.demo.OfflineDemoScreen
import com.starcam.astro.ui.gallery.GalleryScreen
import com.starcam.astro.ui.history.HistoryScreen
import com.starcam.astro.ui.home.HomeScreen
import com.starcam.astro.ui.result.ResultScreen
import com.starcam.astro.ui.settings.SettingsScreen
import com.starcam.astro.util.ImageUtils
import java.io.File

/** 应用根组件：按路由切换页面 */
@Composable
fun StarCamApp(viewModel: AppViewModel = viewModel()) {
    val context = LocalContext.current
    val settings = remember { SettingsRepository(context) }

    // 全面屏手势/返回键接管（§0.30.5）：栈式返回，逐级回退上一页；
    // 根页（Home）不拦截，保持系统默认退出行为。
    BackHandler(enabled = viewModel.canGoBack) {
        viewModel.goBack()
    }

    // 页面转场（§0.40）：滑动 + 淡入淡出；返回手势/按钮沿用 BackHandler
    AnimatedContent(
        targetState = viewModel.screen,
        transitionSpec = {
            (slideInHorizontally(tween(260)) { it / 5 } + fadeIn(tween(260))) togetherWith
                (slideOutHorizontally(tween(200)) { -it / 5 } + fadeOut(tween(200)))
        },
        label = "screen",
    ) { s ->
        when (s) {
        is Screen.Home -> HomeScreen(
            hasApiKey = settings.hasApiKey,
            onCapture = { viewModel.navigate(Screen.Camera) },
            onPickImage = { path -> viewModel.navigate(Screen.Result(path)) },
            onPickImages = { paths -> viewModel.navigate(Screen.Batch(paths)) },
            onOpenGallery = { viewModel.navigate(Screen.Gallery) },
            onDemo = { viewModel.navigate(Screen.OfflineDemo) },
            onOpenSettings = { viewModel.navigate(Screen.Settings) },
            onOpenHistory = { viewModel.navigate(Screen.History) },
        )

        is Screen.OfflineDemo -> OfflineDemoScreen(
            onBack = { viewModel.goBack() },
            onPickImage = { path -> viewModel.navigate(Screen.Result(path)) },
            onRandomDemo = {
                val region = DemoSolver.regions.random()
                val file = DemoSolver.generateDemoImage(context, region)
                viewModel.navigate(Screen.Result(file.absolutePath, region))
            },
        )

        is Screen.Gallery -> GalleryScreen(
            onBack = { viewModel.goBack() },
            onPickImage = { path -> viewModel.navigate(Screen.Result(path)) },
            onPickImages = { paths -> viewModel.navigate(Screen.Batch(paths)) },
        )

        is Screen.Camera -> CameraScreen(
            onBack = { viewModel.goBack() },
            onCaptured = { path -> viewModel.navigate(Screen.Result(path)) },
        )

        is Screen.Result -> ResultScreen(
            imagePath = s.imagePath,
            demoRegion = s.demoRegion,
            settings = settings,
            onBack = { viewModel.goBack() },
            onRetake = { viewModel.navigate(Screen.Camera) },
        )

        is Screen.Settings -> SettingsScreen(
            settings = settings,
            onBack = { viewModel.goBack() },
        )

        is Screen.History -> HistoryScreen(
            onBack = { viewModel.goBack() },
            onOpenImage = { path -> viewModel.navigate(Screen.Result(path)) },
        )

        is Screen.Batch -> BatchScreen(
            paths = s.paths,
            settings = settings,
            onBack = { viewModel.goBack() },
        )
        }
    }
}
