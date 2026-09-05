package com.starcam.astro.ui.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.common.util.concurrent.ListenableFuture
import com.starcam.astro.astro.LocalStarMatcher
import com.starcam.astro.astro.PointingHint
import com.starcam.astro.astro.PointingHintStore
import com.starcam.astro.astro.StarCatalogData
import com.starcam.astro.astro.StarChartOverlay
import com.starcam.astro.astro.WcsTransform
import com.starcam.astro.data.HistoryStore
import com.starcam.astro.data.SettingsRepository
import com.starcam.astro.ui.AppIcons
import com.starcam.astro.util.DeviceOrientationTracker
import com.starcam.astro.util.LocationHelper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
 * 相机页：拍照认星入口（§0.41 对焦辅助 + 曝光引导）。
 * - 点击对焦：轻点画面（最亮星）→ 对焦框 + AE/AF 锁定，提示星空对焦技巧；
 * - 曝光引导：EV 补偿滑块（±档）+ 夜景快捷按钮 + 拍摄参数文字建议。
 */
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
    var hasPermission by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    // §0.41：曝光补偿（-max..+max，步长 1）
    var evIndex by remember { mutableStateOf(0) }
    // §0.41：点击对焦点（相对预览的归一化坐标，null=未对焦）
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var focusHint by remember { mutableStateOf(true) }
    // §0.42：实时预览认星 MVP——分析流每 5 秒本地匹配，把星座线叠加到预览
    var livePreview by remember { mutableStateOf(true) }
    val livePreviewFlag = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    var previewOverlay by remember { mutableStateOf<Bitmap?>(null) }
    var previewLabel by remember { mutableStateOf("") }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val lastAnalyzeMs = remember { AtomicLong(0) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    // 传感器粗定标与定位辅助
    val settings = remember { SettingsRepository(context) }
    val locationHelper = remember { LocationHelper(context) }
    val orientationTracker = remember { DeviceOrientationTracker(context, locationHelper) }
    var pointingState by remember { mutableStateOf(DeviceOrientationTracker.DevicePointing()) }

    // §0.49：AR 实时星图——传感器驱动连续投影，求解器只做校准
    var arEnabled by remember { mutableStateOf(settings.arLiveStarMap) }
    val arFlag = remember { java.util.concurrent.atomic.AtomicBoolean(settings.arLiveStarMap) }
    var arFovDeg by remember { mutableStateOf(settings.arFovDeg) }
    // 最新姿态（绘制线程直读，避免状态风暴）与求解器校准中心（天球单位向量）
    val latestPointing = remember {
        java.util.concurrent.atomic.AtomicReference(DeviceOrientationTracker.DevicePointing())
    }
    val arCorrectionVec = remember { java.util.concurrent.atomic.AtomicReference<FloatArray?>(null) }
    val arSky = remember { com.starcam.astro.astro.ArSkyProjector.ProjectedSky() }
    var arTick by remember { mutableStateOf(0) }
    val isPortrait = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT

    // ~60fps 重绘节拍：withFrameNanos 对齐 vsync；姿态走 AtomicReference 直读
    LaunchedEffect(arEnabled) {
        if (!arEnabled) return@LaunchedEffect
        while (isActive) {
            androidx.compose.runtime.withFrameNanos { }
            arTick++
        }
    }

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
            orientationTracker.stopTracking()
            locationHelper.stopListening()
            analysisExecutor.shutdown()
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
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                // 质量优先：星空弱光场景下 MINIMIZE_LATENCY 会以画质换速度，
                // 识别对星点清晰度敏感（§0.15 提星质量是成功率关键）
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setFlashMode(if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)
                .build()
            // §0.42：实时预览认星 MVP——ImageAnalysis 每 5 秒对帧做本地匹配，
            // 命中后把星座连线叠加到预览（KEEP_ONLY_LATEST 丢帧，不阻塞相机）
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setTargetResolution(android.util.Size(800, 600))
                .build()
            analysis.setAnalyzer(analysisExecutor) { image ->
                val now = System.currentTimeMillis()
                if (!livePreviewFlag.get() || now - lastAnalyzeMs.get() < 5000) {
                    image.close()
                    return@setAnalyzer
                }
                lastAnalyzeMs.set(now)
                try {
                    // RGBA_8888 输出模式：planes[0..3] 为 R/G/B/A 单通道 →
                    // 逐行拼装 Bitmap（CameraX 1.3.x 无 JPEG 输出格式）
                    val bmp = bitmapFromRgbaPlanes(image)
                    val rot = image.imageInfo.rotationDegrees
                    image.close()
                    if (bmp == null) return@setAnalyzer
                    val oriented = if (rot != 0) {
                        val m = Matrix().apply { postRotate(rot.toFloat()) }
                        Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                            .also { if (it !== bmp) bmp.recycle() }
                    } else bmp
                    val w = oriented.width
                    val h = oriented.height
                    val hint = if (settings.sensorAssistedPointing) orientationTracker.createPointingHint() else null
                    val stars = LocalStarMatcher.detectStars(oriented, 60)
                    val res = LocalStarMatcher.match(stars, w, h, hint)
                    if (res != null && res.solve.wcs != null) {
                        val isEn = com.starcam.astro.ui.theme.LocaleState.isEnglish
                        val label = HistoryStore.nearestConstellation(
                            res.solve.raDeg, res.solve.decDeg, isEn,
                        )
                        if (arFlag.get()) {
                            // §0.49：AR 模式——求解器只做校准（真解中心向量 → 屏幕平移修正），
                            // 不再渲染位图叠加（防双重绘制）
                            val corr = com.starcam.astro.astro.ArSkyProjector.unitVector(
                                res.solve.raDeg, res.solve.decDeg,
                            )
                            mainHandler.post {
                                previewOverlay = null
                                previewLabel = label
                                arCorrectionVec.set(corr)
                            }
                        } else {
                            val overlay = renderLiveOverlay(oriented, res.solve.wcs!!)
                            mainHandler.post {
                                previewOverlay = overlay
                                previewLabel = label
                            }
                        }
                    } else {
                        mainHandler.post { previewOverlay = null; previewLabel = "" }
                    }
                } catch (e: Exception) {
                    image.close()
                }
            }
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
            message = null
        } catch (e: Exception) {
            message = "${com.starcam.astro.ui.I18n.Camera.launchFailed}：${e.message}"
        }
    }

    // §0.41：EV 滑块驱动曝光补偿（替代原"夜景固定 +2"）
    LaunchedEffect(evIndex, cameraControl) {
        val control = cameraControl ?: return@LaunchedEffect
        control.setExposureCompensationIndex(evIndex.coerceIn(-maxEvIndex, maxEvIndex))
    }

    // §0.41：对焦框显示约 1.2 秒后消失
    LaunchedEffect(focusPoint) {
        if (focusPoint != null) {
            delay(1200)
            focusPoint = null
        }
    }

    fun takePhoto() {
        val capture = imageCapture
        if (capture == null) {
            message = com.starcam.astro.ui.I18n.Camera.notReady
            return
        }
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
                val isEn = com.starcam.astro.ui.theme.LocaleState.isEnglish
                val night = com.starcam.astro.ui.theme.ThemeState.mode ==
                    com.starcam.astro.ui.theme.AppThemeMode.NIGHT_RED
                val pxPerSp = with(LocalDensity.current) { 1.sp.toPx() }
                Canvas(modifier = Modifier.fillMaxSize()) {
                    // 读 arTick 触发逐帧重绘；姿态从 AtomicReference 直读（不触发重组）
                    @Suppress("UNUSED_EXPRESSION")
                    arTick
                    val p = latestPointing.get()
                    val canDraw = p.hasOrientation && p.latDeg != null && p.lonDeg != null && p.altDeg > 0.0
                    if (canDraw) {
                        // PreviewView FIT_CENTER：假设预览流 4:3（旋转后竖屏 3:4），
                        // 计算整幅可见的显示矩形，AR 星图精确覆盖该矩形
                        val streamAspect = if (isPortrait) 3f / 4f else 4f / 3f // 宽:高
                        val rectH = minOf(size.height, size.width / streamAspect)
                        val rectW = rectH * streamAspect
                        val rectL = (size.width - rectW) / 2f
                        val rectT = (size.height - rectH) / 2f

                        val lon = p.lonDeg ?: 0.0
                        val lat = p.latDeg ?: 0.0
                        val jd = com.starcam.astro.astro.SkyEphemeris.unixSecondsToJd(
                            (System.currentTimeMillis() / 1000).toDouble(),
                        )
                        val lst = com.starcam.astro.astro.SkyEphemeris.lstDeg(jd, lon)

                        com.starcam.astro.astro.ArSkyProjector.project(
                            right = p.right, up = p.up, axis = p.axis,
                            lstDeg = lst, latDeg = lat,
                            fovDeg = arFovDeg.toDouble(),
                            widthPx = rectW, heightPx = rectH,
                            correctionVec = arCorrectionVec.get(),
                            out = arSky,
                        )
                        val nc = drawContext.canvas.nativeCanvas
                        nc.save()
                        nc.clipRect(rectL, rectT, rectL + rectW, rectT + rectH)
                        nc.translate(rectL, rectT)
                        com.starcam.astro.astro.LayeredRenderer.draw(
                            nc,
                            com.starcam.astro.astro.LayeredRenderer.OverlayScene(
                                arSky.widthPx.toInt(), arSky.heightPx.toInt(),
                                arSky.stars, arSky.lines, arSky.labels, arSky.messier,
                            ),
                            com.starcam.astro.astro.LayerFlags.ALL,
                            isEnglish = isEn,
                            density = pxPerSp,
                            night = night,
                            drawWidth = rectW,
                            drawHeight = rectH,
                        )
                        nc.restore()
                    }
                }
                // AR 未就绪提示（无姿态/无定位/指向地平线以下）
                val hint = run {
                    val p = pointingState
                    when {
                        !p.hasOrientation -> com.starcam.astro.ui.I18n.Ar.needOrientation
                        p.latDeg == null -> com.starcam.astro.ui.I18n.Ar.needLocation
                        p.altDeg <= 0.0 -> com.starcam.astro.ui.I18n.Ar.belowHorizon
                        else -> null
                    }
                }
                hint?.let {
                    Text(
                        it,
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
                // 校准状态角标
                if (arCorrectionVec.get() != null) {
                    Text(
                        com.starcam.astro.ui.I18n.Ar.calibratedHint,
                        color = Color(0xFFB9F6CA),
                        fontSize = 10.sp,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 12.dp)
                            .padding(top = if (focusHint || message != null) 156.dp else 104.dp)
                            .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
            }

            // §0.42：实时认星叠加层（星座线标注帧 + 识别标签）
            if (livePreview && previewOverlay != null) {
                Image(
                    bitmap = previewOverlay!!.asImageBitmap(),
                    contentDescription = com.starcam.astro.ui.I18n.Camera.liveOverlayDesc,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                )
                if (previewLabel.isNotEmpty()) {
                    Surface(
                        color = Color(0xCC1A237E),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 64.dp),
                    ) {
                        Text(
                            com.starcam.astro.ui.I18n.Camera.identifiedLabel(previewLabel),
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            // §0.41：点击对焦层 + 对焦框（透明覆盖预览，归一化坐标与 PreviewView 对齐）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { pos ->
                            focusAt(pos.x / size.width, pos.y / size.height)
                        }
                    },
            ) {
                focusPoint?.let { p ->
                    Canvas(Modifier.fillMaxSize()) {
                        // 对焦框：以点击点为中心的小方框（金色，四角）
                        val w = 56.dp.toPx()
                        val cx = p.x * size.width
                        val cy = p.y * size.height
                        val l = w / 2
                        val stroke = 2.dp.toPx()
                        val c = Color(0xFFFFC24B)
                        val corners = listOf(
                            listOf(Offset(cx - l, cy - l), Offset(cx - l + l * 0.35f, cy - l)),
                            listOf(Offset(cx + l - l * 0.35f, cy - l), Offset(cx + l, cy - l)),
                            listOf(Offset(cx - l, cy + l), Offset(cx - l + l * 0.35f, cy + l)),
                            listOf(Offset(cx + l - l * 0.35f, cy + l), Offset(cx + l, cy + l)),
                            listOf(Offset(cx - l, cy - l), Offset(cx - l, cy - l + l * 0.35f)),
                            listOf(Offset(cx + l, cy - l), Offset(cx + l, cy - l + l * 0.35f)),
                            listOf(Offset(cx - l, cy + l - l * 0.35f), Offset(cx - l, cy + l)),
                            listOf(Offset(cx + l, cy + l - l * 0.35f), Offset(cx + l, cy + l)),
                        )
                        for ((a, b) in corners) {
                            drawLine(color = c, start = a, end = b, strokeWidth = stroke)
                        }
                    }
                }
            }

            // 顶部工具条
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, start = 8.dp, end = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = Color.White,
                    )
                }
                Row {
                    // §0.49：AR 实时星图开关（传感器驱动连续叠加 + 求解器校准）
                    IconButton(onClick = {
                        arEnabled = !arEnabled
                        arFlag.set(arEnabled)
                        settings.arLiveStarMap = arEnabled
                        if (!arEnabled) previewOverlay = null
                    }) {
                        Text(
                            "🌌",
                            fontSize = 18.sp,
                            color = if (arEnabled) MaterialTheme.colorScheme.primary else Color.White,
                        )
                    }
                    // §0.42：实时认星开关（每 5 秒本地匹配，星座线叠加预览）
                    IconButton(onClick = {
                        livePreview = !livePreview
                        livePreviewFlag.set(livePreview)
                        if (!livePreview) previewOverlay = null
                    }) {
                        Text(
                            if (livePreview) "🛰️" else "🔭",
                            fontSize = 18.sp,
                            color = if (livePreview) MaterialTheme.colorScheme.primary else Color.White,
                        )
                    }
                    IconButton(onClick = { flashOn = !flashOn }) {
                        Icon(
                            AppIcons.FlashOn,
                            contentDescription = "闪光灯",
                            tint = if (flashOn) MaterialTheme.colorScheme.primary else Color.White,
                        )
                    }
                    // §0.41：夜景快捷按钮（EV+2），滑块可精细调节
                    IconButton(onClick = {
                        evIndex = if (evIndex >= 2) 0 else 2
                    }) {
                        Icon(
                            AppIcons.Night,
                            contentDescription = "夜景增强（EV+2）",
                            tint = if (evIndex > 0) MaterialTheme.colorScheme.primary else Color.White,
                        )
                    }
                    IconButton(
                        onClick = {
                            facing = if (facing == CameraSelector.LENS_FACING_BACK) {
                                CameraSelector.LENS_FACING_FRONT
                            } else {
                                CameraSelector.LENS_FACING_BACK
                            }
                        }
                    ) {
                        Icon(
                            AppIcons.CameraSwitch,
                            contentDescription = "切换镜头",
                            tint = Color.White,
                        )
                    }
                }
            }

            // 提示信息
            message?.let {
                Surface(
                    color = Color(0xCC000000),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 64.dp),
                ) {
                    Text(
                        it,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            // §0.41：星空对焦提示（前几次进入时显示，点击即知道怎么用）
            if (focusHint) {
                Surface(
                    color = Color(0xCC1A237E),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 64.dp),
                ) {
                    Text(
                        com.starcam.astro.ui.I18n.Camera.focusHint,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            // 传感器粗定标与天球朝向实时指示条（HUD）
            if (settings.sensorAssistedPointing && pointingState.hasOrientation) {
                val isEn = com.starcam.astro.ui.theme.LocaleState.isEnglish
                val isSky = pointingState.altDeg > 0.0
                Surface(
                    color = if (isSky) Color(0xAA111827) else Color(0xAA7F1D1D),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = if (focusHint || message != null) 116.dp else 64.dp),
                ) {
                    val dir = when (((pointingState.azDeg + 22.5) % 360 / 45).toInt()) {
                        0 -> if (isEn) "N" else "北"
                        1 -> if (isEn) "NE" else "东北"
                        2 -> if (isEn) "E" else "东"
                        3 -> if (isEn) "SE" else "东南"
                        4 -> if (isEn) "S" else "南"
                        5 -> if (isEn) "SW" else "西南"
                        6 -> if (isEn) "W" else "西"
                        else -> if (isEn) "NW" else "西北"
                    }
                    val text = if (!isSky) {
                        if (isEn) "🧭 Pointing below horizon (Alt %.0f°)".format(pointingState.altDeg)
                        else "🧭 手机未朝向星空（仰角 %.0f°）".format(pointingState.altDeg)
                    } else if (pointingState.raDeg != null && pointingState.decDeg != null) {
                        val raH = (pointingState.raDeg ?: 0.0) / 15.0
                        val raM = ((raH - raH.toInt()) * 60).toInt()
                        val dec = pointingState.decDeg ?: 0.0
                        if (isEn) {
                            "🧭 Az %.0f°(%s) · Alt %.0f° · Hint RA %02dh%02dm Dec %+.0f°".format(
                                pointingState.azDeg, dir, pointingState.altDeg, raH.toInt(), raM, dec,
                            )
                        } else {
                            "🧭 方位 %.0f°(%s) · 仰角 %.0f° · 预估 RA %02dh%02dm Dec %+.0f°".format(
                                pointingState.azDeg, dir, pointingState.altDeg, raH.toInt(), raM, dec,
                            )
                        }
                    } else {
                        if (isEn) {
                            "🧭 Az %.0f°(%s) · Alt %.0f°".format(pointingState.azDeg, dir, pointingState.altDeg)
                        } else {
                            "🧭 方位 %.0f°(%s) · 仰角 %.0f°".format(pointingState.azDeg, dir, pointingState.altDeg)
                        }
                    }
                    Text(
                        text,
                        color = if (isSky) Color(0xFFE0E7FF) else Color(0xFFFECACA),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }

            // §0.41：曝光引导（EV 滑块 + 拍摄参数建议）+ §0.49 FOV 校准
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 128.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // §0.49：AR 星图视场校准滑块（对齐真实镜头 FOV）
                if (arEnabled && settings.sensorAssistedPointing) {
                    Text(
                        // %.0f 必须传浮点参数（传 Int 会抛 IllegalFormatException 导致
                        // 相机页组合即闪退，v1.5.35 实测）
                        com.starcam.astro.ui.I18n.Ar.fovLabel.format(arFovDeg),
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Slider(
                        value = arFovDeg,
                        onValueChange = {
                            arFovDeg = it
                        },
                        onValueChangeFinished = { settings.arFovDeg = arFovDeg },
                        valueRange = 40f..90f,
                    )
                }
                Text(
                    com.starcam.astro.ui.I18n.Camera.evLabel(evIndex),
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (maxEvIndex > 0) {
                    Slider(
                        value = evIndex.toFloat(),
                        onValueChange = { evIndex = it.roundToInt() },
                        valueRange = -maxEvIndex.toFloat()..maxEvIndex.toFloat(),
                        steps = (maxEvIndex * 2 - 1).coerceAtLeast(0),
                    )
                }
                // 夜景增强提示条：星点拖尾预警 + 拍摄参数建议
                if (evIndex > 0) {
                    Surface(
                        color = Color(0xCC1A237E),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    ) {
                        Text(
                            com.starcam.astro.ui.I18n.Camera.nightGuidance,
                            color = Color.White,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            // 底部拍照按钮
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp),
            ) {
                Surface(
                    onClick = { takePhoto() },
                    shape = CircleShape,
                    color = Color.White,
                    modifier = Modifier.size(72.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Surface(
                            shape = CircleShape,
                            color = Color.Transparent,
                            modifier = Modifier.size(58.dp),
                            border = BorderStroke(4.dp, Color.Black.copy(alpha = 0.3f)),
                        ) {}
                    }
                }
            }
        }
    }
}



/**
 * §0.42：实时预览认星叠加层——把星座连线和星座名画到取景帧上。
 * 运行在 ImageAnalysis 后台线程，返回值经 mainHandler.post 赋给 previewOverlay。
 * 使用全限定类名，避免改动 import 区。
 */
private fun renderLiveOverlay(frame: Bitmap, wcs: WcsTransform): Bitmap {
    val out = frame.copy(Bitmap.Config.ARGB_8888, true) ?: return frame
    val w = out.width
    val h = out.height
    val canvas = AndroidCanvas(out)
    val linePaint = Paint().apply {
        color = 0xCCFFC24B.toInt()
        style = Paint.Style.STROKE
        strokeWidth = maxOf(2f, w / 400f)
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f, 9f), 0f)
        isAntiAlias = true
    }
    val labelPaint = Paint().apply {
        color = 0xFFFFFFFF.toInt()
        textSize = maxOf(22f, w / 30f)
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
        setShadowLayer(5f, 0f, 0f, 0xAA000000.toInt())
    }
    val isEn = com.starcam.astro.ui.theme.LocaleState.isEnglish
    val stars = StarChartOverlay.projectStars(wcs, w, h)
    for (line in StarChartOverlay.projectLines(stars)) {
        canvas.drawLine(line.a.x, line.a.y, line.b.x, line.b.y, linePaint)
    }
    // §0.43c：亮星星名（支持中英双语，mag≤3.2）
    val namePaint = Paint().apply {
        color = 0xFFF7EDCB.toInt()
        textSize = maxOf(16f, w / 45f)
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
        setShadowLayer(4f, 1f, 1f, 0xFF000000.toInt())
    }
    for (s in stars) {
        if (!s.visible || s.entry.mag > 3.2) continue
        val nm = com.starcam.astro.astro.StarNames.displayName(s.entry.hip, s.entry.name, isEn)
        if (nm.isEmpty()) continue
        canvas.drawText(nm, s.x + 6f, s.y - 6f, namePaint)
    }
    // §0.43c：梅西耶深空天体标注（支持中英双语）
    val ringPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = maxOf(2f, w / 700f)
        isAntiAlias = true
    }
    val messierPaint = Paint().apply {
        color = 0xFFF7EDCB.toInt()
        textSize = maxOf(15f, w / 48f)
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
        setShadowLayer(4f, 1f, 1f, 0xFF000000.toInt())
    }
    for (m in StarChartOverlay.projectMessier(wcs, w, h)) {
        if (!m.visible) continue
        ringPaint.color = com.starcam.astro.astro.MessierCatalog.typeColor(m.obj.type)
        canvas.drawCircle(m.x, m.y, maxOf(6f, w / 160f), ringPaint)
        canvas.drawText(m.obj.label(isEn), m.x + 8f, m.y - 6f, messierPaint)
    }
    for (label in StarChartOverlay.constellationLabels(stars, isEn)) {
        canvas.drawText(label.text, label.x, label.y, labelPaint)
    }
    return out
}


/**
 * §0.42：把 ImageAnalysis 的 RGBA_8888 四平面帧拼装为 Bitmap。
 * CameraX 保证 planes[0..3] 为 R/G/B/A 单通道，pixelStride=1；
 * 仅做 rowStride 对齐处理，容量不足时返回 null 跳过该帧。
 */
private fun bitmapFromRgbaPlanes(proxy: ImageProxy): Bitmap? {
    val w = proxy.width
    val h = proxy.height
    if (proxy.planes.size < 4) return null
    val planes = proxy.planes
    if (planes[0].pixelStride != 1) return null
    val bufs = arrayOf(planes[0].buffer, planes[1].buffer, planes[2].buffer, planes[3].buffer)
    val rowStrides = intArrayOf(planes[0].rowStride, planes[1].rowStride, planes[2].rowStride, planes[3].rowStride)
    for (i in 0 until 4) {
        if (bufs[i].capacity() < rowStrides[i] * (h - 1) + w) return null
    }
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(w * h)
    var idx = 0
    for (row in 0 until h) {
        bufs[0].position(row * rowStrides[0])
        bufs[1].position(row * rowStrides[1])
        bufs[2].position(row * rowStrides[2])
        bufs[3].position(row * rowStrides[3])
        for (col in 0 until w) {
            pixels[idx++] = ((bufs[3].get().toInt() and 0xFF) shl 24) or
                ((bufs[0].get().toInt() and 0xFF) shl 16) or
                ((bufs[1].get().toInt() and 0xFF) shl 8) or
                (bufs[2].get().toInt() and 0xFF)
        }
    }
    bmp.setPixels(pixels, 0, w, 0, 0, w, h)
    return bmp
}
