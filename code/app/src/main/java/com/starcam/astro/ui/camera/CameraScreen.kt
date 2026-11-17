package com.starcam.astro.ui.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.Looper
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.google.common.util.concurrent.ListenableFuture
import com.starcam.astro.astro.ArSkyProjector
import com.starcam.astro.astro.CameraMath
import com.starcam.astro.astro.PointingHintStore
import com.starcam.astro.data.SettingsRepository
import com.starcam.astro.ui.I18n
import com.starcam.astro.ui.result.ObjectInfoCard
import com.starcam.astro.ui.result.SkyObjectRef
import com.starcam.astro.util.DeviceOrientationTracker
import com.starcam.astro.util.LocationHelper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine

private suspend fun <T> ListenableFuture<T>.await(context: Context): T =
    suspendCancellableCoroutine { cont ->
        addListener(
            {
                try {
                    cont.resume(get())
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

/**
 * 相机页：拍照认星入口（§0.41 对焦辅助 + 曝光引导，§0.49 AR 星图，§0.50 Pro 曝光）。
 * - 点击对焦：轻点画面（最亮星）→ 对焦框 + AE/AF 锁定，提示星空对焦技巧；
 * - 曝光引导：EV 补偿滑块（±档）+ Pro 手动 ISO/快门 + 夜景快捷按钮；
 * - AR 星图：传感器驱动实时星座叠加 + FOV 自动标定 + 电子水平仪。
 */
// 注意：这里必须用 **androidx 的** @OptIn（Kotlin 内建的 `@OptIn` 只认
// kotlin.RequiresOptIn 标记，对 androidx 的 Java 标记无效 —— 本文件曾用 kotlin 版
// 而 lint 仍报 19 处 UnsafeOptInUsageError，2026-10-11 接入 lintDebug 门禁时改正）。
@androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
@Composable
fun CameraScreen(
    onBack: () -> Unit,
    onCaptured: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // §0.49：整幅预览可见（FIT_CENTER letterbox，比例真实）——AR 星图对齐的前提
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
    }
    var facing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var flashOn by remember { mutableStateOf(false) }
    var cameraControl by remember { mutableStateOf<CameraControl?>(null) }
    var maxEvIndex by remember { mutableStateOf(0) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }

    // §0.77：离开相机页必须显式 unbindAll —— ProcessCameraProvider 是进程级单例，
    // 而 bindToLifecycle 的 owner 是 Activity（本屏只是 Activity 内的一个页面），
    // 切到结果页/主页时 Activity 并未销毁，相机因此**从不自动解绑**。
    // 不解绑的三个后果：① 相机常开、状态栏隐私指示灯常亮、持续耗电；
    // ② ImageAnalysis 仍每 5~8s 投递帧，向已销毁的组合状态 post 写入；
    // ③ 投递命中已 shutdown 的 analysisExecutor（RejectedExecutionException），
    //    且未回收的 ImageProxy 可能让 KEEP_ONLY_LATEST 分析流挂死不再出帧。
    val cameraProviderRef = remember { AtomicReference<ProcessCameraProvider?>(null) }
    var hasPermission by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    // §0.41：曝光补偿（-max..+max，步长 1）
    var evIndex by remember { mutableStateOf(0) }
    // §0.41：点击对焦点（相对预览的归一化坐标，null=未对焦）
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var focusHint by remember { mutableStateOf(true) }
    var isCapturing by remember { mutableStateOf(false) }
    // §0.42：实时预览认星 MVP——分析流按间隔本地匹配，把星座线叠加到预览
    var livePreview by remember { mutableStateOf(true) }
    val livePreviewFlag = remember { AtomicBoolean(true) }
    var previewOverlay by remember { mutableStateOf<Bitmap?>(null) }
    var previewLabel by remember { mutableStateOf("") }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val lastAnalyzeMs = remember { AtomicLong(0) }
    val lastAnalyzeErrMs = remember { AtomicLong(0) }
    val analyzeIntervalMs = remember {
        val cores = Runtime.getRuntime().availableProcessors()
        if (cores <= 4) 8_000L else 5_000L
    }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    val settings = remember { SettingsRepository(context) }
    val locationHelper = remember { LocationHelper(context) }
    val orientationTracker = remember { DeviceOrientationTracker(context, locationHelper) }
    var pointingState by remember { mutableStateOf(DeviceOrientationTracker.DevicePointing()) }

    var arEnabled by remember { mutableStateOf(settings.arLiveStarMap) }
    val arFlag = remember { AtomicBoolean(settings.arLiveStarMap) }
    var arFovDeg by remember { mutableStateOf(settings.arFovDeg) }
    var fovAutoCalibrated by remember { mutableStateOf(false) }
    var fovUserOverridden by remember { mutableStateOf(false) }
    var sensorParams by remember { mutableStateOf<CameraSensorParams?>(null) }
    var proMode by remember { mutableStateOf(false) }
    var proIso by remember { mutableStateOf(1600) }
    var proShutterIdx by remember { mutableStateOf(4) } // 默认 1s 档
    var isoRangeState by remember { mutableStateOf<IntRange?>(null) }
    var exposureRangeState by remember { mutableStateOf<LongRange?>(null) }
    val latestPointing = remember { AtomicReference(DeviceOrientationTracker.DevicePointing()) }
    val arAzimuthOffsetDeg = remember { AtomicReference(0.0) }
    var arCalibrated by remember { mutableStateOf(false) }
    val arSky = remember { ArSkyProjector.ProjectedSky() }
    var arTarget by remember { mutableStateOf<ArTarget?>(null) }
    var arTargetPicker by remember { mutableStateOf(false) }
    var arCard by remember { mutableStateOf<SkyObjectRef?>(null) }
    val isPortrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT

    DisposableEffect(Unit) {
        if (settings.sensorAssistedPointing) {
            locationHelper.startListening()
            orientationTracker.onPointingChanged = { pt ->
                latestPointing.set(pt)
                mainHandler.post { pointingState = pt }
            }
            orientationTracker.startTracking()
        }
        onDispose {
            // §0.77：Pro 模式注入的是「AE 关 + 手动 ISO + 快门」，快门最长可到 30s。
            // 必须先清除再 unbindAll，否则相机解绑后 Camera2CameraControl 无法下发清除选项。
            if (proMode) {
                cameraControl?.let { ctl ->
                    try {
                        androidx.camera.camera2.interop.Camera2CameraControl.from(ctl)
                            .clearCaptureRequestOptions()
                    } catch (_: Throwable) {
                    }
                }
            }
            // §0.77：先解绑相机，再收线程池 —— 顺序不能反。分析流若还在投递而
            // executor 已 shutdown，会命中 RejectedExecutionException，且未被回收的
            // ImageProxy 可能让 KEEP_ONLY_LATEST 的分析流挂死、之后不再出帧。
            cameraProviderRef.get()?.unbindAll()
            orientationTracker.stopTracking()
            locationHelper.stopListening()
            analysisExecutor.shutdown()
            val oldOverlay = previewOverlay
            previewOverlay = null
            if (oldOverlay != null && !oldOverlay.isRecycled) {
                oldOverlay.recycle()
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        hasPermission = perms[Manifest.permission.CAMERA] == true
        if (!hasPermission) message = com.starcam.astro.ui.I18n.Camera.permissionRequired
        if (perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            if (settings.sensorAssistedPointing) {
                locationHelper.startListening()
            }
        }
    }

    LaunchedEffect(Unit) {
        val hasCam = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        hasPermission = hasCam
        val needed = mutableListOf<String>()
        if (!hasCam) needed.add(Manifest.permission.CAMERA)
        if (!locationHelper.hasPermission()) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    LaunchedEffect(facing, hasPermission) {
        if (!hasPermission) return@LaunchedEffect
        try {
            val provider = ProcessCameraProvider.getInstance(context).await(context)
            // §0.77：交给 onDispose 解绑用（provider 是局部变量，onDispose 拿不到）
            cameraProviderRef.set(provider)
            val preview = Preview.Builder()
                .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_4_3)
                .build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
            // §0.81：前置摄像头基本都没有闪光灯。若此时 flashOn 仍为 true，
            // setFlashMode(FLASH_MODE_ON) 会让 CameraX 在建链阶段抛
            // IllegalArgumentException，被外层 catch 兜成「相机启动失败」——
            // 用户看到红色提示、完全没有预览。
            //
            // 按镜头保守判定：**前置一律视为无闪光灯**。
            // 没有走 CameraX 的 hasFlashUnit() —— 它要已绑定的 Camera 实例，而此处
            // 必须在 build ImageCapture **之前**决定闪光灯模式；而
            // ProcessCameraProvider 在 1.3.x 也没有 getCameraInfo(selector)
            // （第一版就是误用了它，编译不过）。少数「后置却没有闪光灯」的机型
            // 仍由外层 catch (Throwable) 兜住，不会崩。
            val lensHasFlash = facing == CameraSelector.LENS_FACING_BACK
            if (!lensHasFlash && flashOn) flashOn = false

            imageCapture = ImageCapture.Builder()
                .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_4_3)
                // 质量优先：星空弱光场景下 MINIMIZE_LATENCY 会以画质换速度，
                // 识别对星点清晰度敏感（§0.15 提星质量是成功率关键）
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setFlashMode(
                    if (flashOn && lensHasFlash) {
                        ImageCapture.FLASH_MODE_ON
                    } else {
                        ImageCapture.FLASH_MODE_OFF
                    },
                )
                .build()
            // §0.42：实时预览认星 MVP——ImageAnalysis 每 5 秒对帧做本地匹配，
            // 命中后把星座连线叠加到预览（KEEP_ONLY_LATEST 丢帧，不阻塞相机）
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                // §0.66：YUV_420_888 的 Y 平面即亮度，单平面读取；
                // 原 RGBA_8888 要读 4 个平面再转灰度（4 倍搬运）。
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setTargetResolution(android.util.Size(800, 600))
                .build()
            analysis.setAnalyzer(
                analysisExecutor,
                createStarPreviewAnalyzer(
                    context = context,
                    settings = settings,
                    orientationTracker = orientationTracker,
                    livePreviewFlag = livePreviewFlag,
                    arFlag = arFlag,
                    analyzeIntervalMs = analyzeIntervalMs,
                    lastAnalyzeMs = lastAnalyzeMs,
                    lastAnalyzeErrMs = lastAnalyzeErrMs,
                    latestPointing = latestPointing,
                    mainHandler = mainHandler,
                    onArCalibrated = { label, solvedFov, isHighConfidence, deltaAz ->
                        previewOverlay = null
                        previewLabel = label
                        if (!fovUserOverridden && isHighConfidence && solvedFov in 35.0..85.0) {
                            arFovDeg = solvedFov.toFloat().coerceIn(40f, 90f)
                            fovAutoCalibrated = true
                            settings.arFovDeg = arFovDeg
                        }
                        if (isHighConfidence && abs(deltaAz) <= 45.0) {
                            arAzimuthOffsetDeg.set(deltaAz)
                            arCalibrated = true
                        }
                    },
                    onLiveOverlay = { overlay, label ->
                        val old = previewOverlay
                        previewOverlay = overlay
                        previewLabel = label
                        if (old != null && old !== overlay && !old.isRecycled) {
                            old.recycle()
                        }
                    },
                    onClearOverlay = {
                        val old = previewOverlay
                        previewOverlay = null
                        previewLabel = ""
                        if (old != null && !old.isRecycled) {
                            old.recycle()
                        }
                    },
                ),
            )
            provider.unbindAll()
            val camera = provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.Builder().requireLensFacing(facing).build(),
                preview,
                imageCapture,
                analysis,
            )
            cameraControl = camera.cameraControl
            maxEvIndex = camera.cameraInfo.exposureState.exposureCompensationRange.upper

            // §0.50/§0.56：Camera2 能力查询（Pro 手动曝光支持性）+ AR FOV 主摄优选标定
            try {
                val info2 = androidx.camera.camera2.interop.Camera2CameraInfo.from(camera.cameraInfo)
                val focals = info2.getCameraCharacteristic(
                    android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS,
                )
                val phys = info2.getCameraCharacteristic(
                    android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE,
                )
                if (focals != null && phys != null && focals.isNotEmpty()) {
                    val sensorRot = info2.getCameraCharacteristic(
                        android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION,
                    ) ?: 90
                    sensorParams = CameraSensorParams(
                        focals, phys.width.toDouble(), phys.height.toDouble(), sensorRot,
                    )
                } else {
                    sensorParams = null
                    fovAutoCalibrated = false
                }
                isoRangeState = info2.getCameraCharacteristic(
                    android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE,
                )?.let { it.lower..it.upper }
                exposureRangeState = info2.getCameraCharacteristic(
                    android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE,
                )?.let { it.lower..it.upper }
            } catch (_: Throwable) {
                fovAutoCalibrated = false
            }
            message = null
        } catch (e: Exception) {
            message = "${com.starcam.astro.ui.I18n.Camera.launchFailed}：${e.message}"
        }
    }

    // §0.123 修复 P1-2：横竖屏旋转时，按新方向动态重算 AR FOV，无需解绑相机，消除星图错位
    LaunchedEffect(isPortrait, sensorParams) {
        val params = sensorParams ?: return@LaunchedEffect
        val fov = com.starcam.astro.astro.CameraMath.computeCameraHorizontalFovDeg(
            params.focals, params.physWidth, params.physHeight, isPortrait, params.sensorRot,
        )
        if (fov != null && !fovUserOverridden) {
            arFovDeg = fov
            fovAutoCalibrated = true
        } else if (fov == null) {
            fovAutoCalibrated = false
        }
    }

    // §0.41：EV 滑块驱动曝光补偿（替代原"夜景固定 +2"）
    LaunchedEffect(evIndex, cameraControl) {
        val control = cameraControl ?: return@LaunchedEffect
        control.setExposureCompensationIndex(evIndex.coerceIn(-maxEvIndex, maxEvIndex))
    }

    LaunchedEffect(flashOn, imageCapture, facing) {
        val cap = imageCapture ?: return@LaunchedEffect
        cap.flashMode = if (flashOn && facing == CameraSelector.LENS_FACING_BACK) {
            ImageCapture.FLASH_MODE_ON
        } else {
            ImageCapture.FLASH_MODE_OFF
        }
    }

    // §0.50：Pro 手动曝光——Camera2 运行时注入（AE off + ISO + 快门），无需重绑
    LaunchedEffect(proMode, proIso, proShutterIdx, cameraControl) {
        val control = cameraControl ?: return@LaunchedEffect
        try {
            val c2 = androidx.camera.camera2.interop.Camera2CameraControl.from(control)
            if (!proMode) {
                // 清空注入项，恢复相机自动 AE
                c2.clearCaptureRequestOptions()
            } else {
                val stops = com.starcam.astro.astro.CameraMath.shutterStopsSec
                val sec = stops[proShutterIdx.coerceIn(0, stops.lastIndex)]
                val nanos = com.starcam.astro.astro.CameraMath.clampExposureNanos(
                    com.starcam.astro.astro.CameraMath.secToNanos(sec), exposureRangeState,
                )
                val iso = com.starcam.astro.astro.CameraMath.clampIso(proIso, isoRangeState)
                val opts = androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(
                        android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE,
                        android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE_OFF,
                    )
                    .setCaptureRequestOption(
                        android.hardware.camera2.CaptureRequest.SENSOR_SENSITIVITY, iso,
                    )
                    .setCaptureRequestOption(
                        android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME, nanos,
                    )
                    .build()
                c2.setCaptureRequestOptions(opts)
            }
        } catch (_: Throwable) {
        }
    }

    // §0.41：对焦框显示约 1.2 秒后消失
    LaunchedEffect(focusPoint) {
        if (focusPoint != null) {
            delay(1200)
            focusPoint = null
        }
    }

    fun takePhoto() {
        if (isCapturing) return
        val capture = imageCapture
        if (capture == null) {
            message = com.starcam.astro.ui.I18n.Camera.notReady
            return
        }
        isCapturing = true
        val file = File(context.cacheDir, "capture_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()

        val hint = if (settings.sensorAssistedPointing) {
            orientationTracker.createPointingHint(radiusDeg = 25.0)
        } else null

        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    isCapturing = false
                    if (hint != null) {
                        PointingHintStore.put(file.absolutePath, hint)
                        try {
                            val exif = androidx.exifinterface.media.ExifInterface(file.absolutePath)
                            exif.setLatLong(hint.latDeg, hint.lonDeg)
                            val sdfDate = SimpleDateFormat("yyyy:MM:dd", Locale.US).apply {
                                timeZone = TimeZone.getTimeZone("UTC")
                            }
                            val sdfTime = SimpleDateFormat("HH:mm:ss", Locale.US).apply {
                                timeZone = TimeZone.getTimeZone("UTC")
                            }
                            val d = Date(hint.epochSec * 1000L)
                            exif.setAttribute("GPSDateStamp", sdfDate.format(d))
                            exif.setAttribute("GPSTimeStamp", sdfTime.format(d))
                            exif.saveAttributes()
                        } catch (_: Throwable) {
                        }
                    }
                    onCaptured(file.absolutePath)
                }

                override fun onError(exception: ImageCaptureException) {
                    isCapturing = false
                    message = "${com.starcam.astro.ui.I18n.Camera.captureFailed}：${exception.message}"
                }
            },
        )
    }

    /** 点击对焦：归一化坐标 → MeteringPoint → AF+AE 锁定（§0.41） */
    fun focusAt(x: Float, y: Float) {
        val control = cameraControl ?: return
        val factory = previewView.meteringPointFactory
        val point = factory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
        ).build() // 默认 5 秒自动取消
        runCatching { control.startFocusAndMetering(action) }
        focusPoint = Offset(x, y)
        focusHint = false // 已对焦，收起提示
    }

    val topBannerVisible = focusHint || message != null

    Scaffold { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.Black),
        ) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize(),
            )

            // §0.49：AR 实时星图叠加层——传感器驱动，~60fps 跟手重绘
            if (arEnabled && settings.sensorAssistedPointing) {
                CameraArOverlay(
                    orientationTracker = orientationTracker,
                    pointingState = pointingState,
                    arSky = arSky,
                    arFovDeg = arFovDeg,
                    arCalibrated = arCalibrated,
                    arAzimuthOffsetDeg = arAzimuthOffsetDeg,
                    arTarget = arTarget,
                    isPortrait = isPortrait,
                    topBannerVisible = topBannerVisible,
                )
            }

            // §0.54b：AR 点击详情科普卡片（底部浮层，复用结果页卡片组件）
            arCard?.let { ref ->
                ObjectInfoCard(
                    ref = ref,
                    onDismiss = { arCard = null },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp),
                )
            }

            // §0.42 / §0.123：实时认星叠加层（FIT_CENTER 几何对齐 + 透明底）
            val currentOverlay = previewOverlay
            if (livePreview && currentOverlay != null) {
                CameraLivePreviewOverlay(
                    overlay = currentOverlay,
                    previewLabel = previewLabel,
                )
            }

            // §0.41 / §0.54b：点击对焦层 + AR 天体命中
            CameraTapFocusLayer(
                arOn = arEnabled && settings.sensorAssistedPointing,
                isPortrait = isPortrait,
                arSky = arSky,
                focusPoint = focusPoint,
                onSelectArCard = { arCard = it },
                onFocusAt = { x, y -> focusAt(x, y) },
            )

            // 顶部工具条
            CameraTopBar(
                arEnabled = arEnabled,
                sensorAssistedPointing = settings.sensorAssistedPointing,
                hasArTarget = arTarget != null,
                livePreview = livePreview,
                flashOn = flashOn,
                nightEvActive = evIndex > 0,
                onBack = onBack,
                onToggleAr = {
                    arEnabled = !arEnabled
                    arFlag.set(arEnabled)
                    settings.arLiveStarMap = arEnabled
                    if (!arEnabled) {
                        previewOverlay = null
                        arTarget = null
                        arCard = null
                        arCalibrated = false
                        arAzimuthOffsetDeg.set(0.0)
                    }
                },
                onArTargetAction = {
                    if (arTarget != null) {
                        arTarget = null
                    } else {
                        arTargetPicker = true
                    }
                },
                onToggleLivePreview = {
                    livePreview = !livePreview
                    livePreviewFlag.set(livePreview)
                    if (!livePreview) previewOverlay = null
                },
                onToggleFlash = { flashOn = !flashOn },
                onToggleNightEv = { evIndex = if (evIndex >= 2) 0 else 2 },
                onSwitchCamera = {
                    fovUserOverridden = false
                    proMode = false
                    facing = if (facing == CameraSelector.LENS_FACING_BACK) {
                        CameraSelector.LENS_FACING_FRONT
                    } else {
                        CameraSelector.LENS_FACING_BACK
                    }
                },
                arTargetLabel = arTarget?.label(com.starcam.astro.ui.theme.LocaleState.isEnglish),
            )

            // 提示信息与星空对焦提示
            CameraStatusBanners(
                message = message,
                focusHint = focusHint,
            )

            // 传感器粗定标与天球朝向实时指示条（HUD）
            if (settings.sensorAssistedPointing && pointingState.hasOrientation) {
                CameraPointingHud(
                    pointingState = pointingState,
                    topBannerVisible = topBannerVisible,
                )
            }

            // §0.50：电子水平仪（相机滚转角；|roll|<1° 水平变绿）
            if (pointingState.hasOrientation && pointingState.altDeg > 0.0) {
                CameraRollLevelIndicator(
                    pointingState = pointingState,
                    topBannerVisible = topBannerVisible,
                )
            }

            // §0.41 / §0.49 / §0.50：底部视场校准、Pro 手动曝光与 EV 引导
            CameraExposureControls(
                showArFovSlider = arEnabled && settings.sensorAssistedPointing,
                arFovDeg = arFovDeg,
                fovAutoCalibrated = fovAutoCalibrated,
                onArFovChange = {
                    arFovDeg = it
                    fovAutoCalibrated = false
                    fovUserOverridden = true
                },
                onArFovChangeFinished = { settings.arFovDeg = arFovDeg },
                isoRangeState = isoRangeState,
                exposureRangeState = exposureRangeState,
                proMode = proMode,
                proIso = proIso,
                proShutterIdx = proShutterIdx,
                onToggleProMode = { proMode = !proMode },
                onProIsoChange = { proIso = it },
                onProShutterChange = { proShutterIdx = it },
                evIndex = evIndex,
                maxEvIndex = maxEvIndex,
                onEvChange = { evIndex = it },
            )

            // 底部拍照按钮（带按压回弹与曝光呼吸动效）
            CameraShutterButton(
                isCapturing = isCapturing,
                onTakePhoto = { takePhoto() },
            )
        }
    }

    // §0.54b：找星目标选择弹窗
    if (arTargetPicker) {
        ArTargetPickerDialog(
            onPick = { target ->
                arTarget = target
                arTargetPicker = false
            },
            onDismiss = { arTargetPicker = false },
            latDeg = pointingState.latDeg,
            lonDeg = pointingState.lonDeg,
        )
    }
}
