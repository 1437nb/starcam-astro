package com.starcam.astro.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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

    // 页面转场（§0.40 / 动效升级）：依页面类型自适应沉浸式动效；返回手势沿用 BackHandler
    AnimatedContent(
        targetState = viewModel.screen,
        transitionSpec = {
            if (targetState is Screen.Camera || initialState is Screen.Camera) {
                // 相机进入/退出：使用沉浸式渐隐渐入与微缩放，消除硬件取景画面横向撕裂
                (fadeIn(animationSpec = tween(280, easing = FastOutSlowInEasing)) +
                    scaleIn(initialScale = 0.94f, animationSpec = tween(280, easing = FastOutSlowInEasing))) togetherWith
                    (fadeOut(animationSpec = tween(220, easing = FastOutLinearInEasing)) +
                        scaleOut(targetScale = 1.04f, animationSpec = tween(220, easing = FastOutLinearInEasing)))
            } else {
                // 常规页面层级导航：流畅的 Material 3 弹性水平滑动与微缩放
                val isForward = when {
                    initialState is Screen.Home -> true
                    targetState is Screen.Home -> false
                    else -> true
                }
                if (isForward) {
                    (slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { it / 3 } +
                        fadeIn(tween(260, easing = FastOutSlowInEasing))) togetherWith
                        (slideOutHorizontally(tween(240, easing = FastOutLinearInEasing)) { -it / 4 } +
                            fadeOut(tween(200, easing = FastOutLinearInEasing)))
                } else {
                    (slideInHorizontally(tween(260, easing = FastOutSlowInEasing)) { -it / 4 } +
                        fadeIn(tween(240, easing = FastOutSlowInEasing))) togetherWith
                        (slideOutHorizontally(tween(260, easing = FastOutSlowInEasing)) { it / 3 } +
                            fadeOut(tween(200, easing = FastOutLinearInEasing)))
                }
            }
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
            cachedSolve = s.cachedSolve,
            cachedEngine = s.cachedEngine,
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
            onOpenEntry = { entry ->
                viewModel.navigate(
                    Screen.Result(
                        imagePath = entry.imagePath,
                        cachedSolve = entry.toSolveResult(),
                        cachedEngine = entry.engine,
                    )
                )
            },
        )

        is Screen.Batch -> BatchScreen(
            paths = s.paths,
            settings = settings,
            onBack = { viewModel.goBack() },
        )
        }
    }
}
