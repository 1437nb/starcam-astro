package com.starcam.astro.ui.result

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.starcam.astro.astro.LayerFlags

/**
 * 全屏图片查看器（§0.34，§0.37 增加原图/标注切换，§0.47 增加图层开关面板）：
 * 捏合缩放 1~5x、单指拖拽平移、双击放大 3x/还原；右上角可一键在
 * 「原图」与「标注图」（星座线/星名或诊断星点圈）之间切换。
 *
 * §0.47：传入 [renderAnnotated] 时显示「图层」按钮，展开 4 个独立图层开关
 * （连线/星名/星座名/梅西耶），切换后即时重渲染标注位图并保持缩放位置。
 *
 * §0.48：传入 [hitTest] 时支持在放大图内轻点天体（梅西耶/亮星）弹出科普卡片。
 * 回调参数为位图像素坐标与"屏幕像素/位图像素"比（供容差换算），可在任意缩放级别命中。
 */
@Composable
fun ZoomableImageViewer(
    original: Bitmap,
    annotated: Bitmap,
    title: String,
    onClose: () -> Unit,
    renderAnnotated: ((LayerFlags) -> Bitmap)? = null,
    initialFlags: LayerFlags = LayerFlags.ALL,
    hitTest: ((x: Float, y: Float, screenPxPerBitmapPx: Float) -> SkyObjectRef?)? = null,
) {
    var showAnnotated by remember { mutableStateOf(true) }
    // §0.47：图层开关与当前标注位图（开关变化时经 renderAnnotated 重渲染）
    var flags by remember { mutableStateOf(initialFlags) }
    var annotatedBitmap by remember { mutableStateOf(annotated) }
    var showLayerPanel by remember { mutableStateOf(false) }
    // §0.48：点击命中的天体 → 底部科普卡片
    var cardObject by remember { mutableStateOf<SkyObjectRef?>(null) }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val shown = if (showAnnotated) annotatedBitmap else original

    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(1f, 5f)
        scale = newScale
        // 平移限制在图片边缘内（放大后允许拖动查看）
        if (boxSize.width > 0) {
            val maxX = boxSize.width * (newScale - 1f) / 2f
            val maxY = boxSize.height * (newScale - 1f) / 2f
            // 拖动速度 ×2（§0.38）：放大后滑动更跟手；1 倍时偏移被钳制为 0，无副作用
            offset = Offset(
                (offset.x + panChange.x * 2f).coerceIn(-maxX, maxX),
                (offset.y + panChange.y * 2f).coerceIn(-maxY, maxY),
            )
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            Image(
                bitmap = shown.asImageBitmap(),
                contentDescription = title,
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { boxSize = it }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
                    .transformable(transformableState)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { pos ->
                                // §0.48：轻点天体 → 换算位图坐标做命中测试（graphicsLayer 的
                                // 视觉缩放/平移不改变本地坐标，pointerInput 收到的即本地坐标）
                                val ht = hitTest
                                if (ht != null && boxSize.width > 0) {
                                    val bw = original.width.toFloat()
                                    val bh = original.height.toFloat()
                                    val fit = minOf(
                                        boxSize.width / bw, boxSize.height / bh,
                                    )
                                    if (fit > 0f) {
                                        val bx = (pos.x - (boxSize.width - bw * fit) / 2f) / fit
                                        val by = (pos.y - (boxSize.height - bh * fit) / 2f) / fit
                                        val hit = ht(bx, by, fit * scale)
                                        if (hit != null) cardObject = hit
                                    }
                                }
                            },
                            onDoubleTap = { pos ->
                                if (scale > 1f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    val newScale = 3f
                                    scale = newScale
                                    // 以点击点为中心放大：把点击点移到画面中心
                                    val maxX = boxSize.width * (newScale - 1f) / 2f
                                    val maxY = boxSize.height * (newScale - 1f) / 2f
                                    offset = Offset(
                                        ((boxSize.width / 2f - pos.x) * (newScale - 1f))
                                            .coerceIn(-maxX, maxX),
                                        ((boxSize.height / 2f - pos.y) * (newScale - 1f))
                                            .coerceIn(-maxY, maxY),
                                    )
                                }
                            },
                        )
                    },
                contentScale = ContentScale.Fit,
            )

            // 顶部：标题 + 右上角切换/关闭（§0.37）
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 14.dp),
                color = Color.Black.copy(alpha = 0.55f),
            ) {
                Text(
                    title,
                    color = Color.White,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 10.dp, end = 12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                // 原图 / 标注 切换（§0.37/§0.38，支持双语）：保持当前缩放与平移，不跳回 1 倍。
                // 两张图均按 ContentScale.Fit 适配同一显示框且宽高比一致，切换后观感不变。
                Text(
                    if (showAnnotated) com.starcam.astro.ui.I18n.original else com.starcam.astro.ui.I18n.annotation,
                    color = Color.White,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.55f))
                        .clickable { showAnnotated = !showAnnotated }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
                // §0.47：图层开关（需 renderAnnotated 回调才显示）
                if (renderAnnotated != null) {
                    Text(
                        "≡",
                        color = Color.White,
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                            .clickable { showLayerPanel = !showLayerPanel }
                            .padding(horizontal = 12.dp, vertical = 3.dp),
                    )
                }
                Text(
                    "✕",
                    color = Color.White,
                    fontSize = 22.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .clickable(onClick = onClose)
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                )
            }

            // §0.47：图层开关面板（右上，与结果页同款 4 开关）
            if (showLayerPanel && renderAnnotated != null) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 56.dp, end = 12.dp),
                    color = Color.Black.copy(alpha = 0.72f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(
                            com.starcam.astro.ui.I18n.Layers.panelTitle,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(4.dp))
                        ViewerLayerToggle(com.starcam.astro.ui.I18n.Layers.lines, flags.lines) {
                            flags = flags.copy(lines = it)
                            annotatedBitmap = renderAnnotated.invoke(flags)
                        }
                        ViewerLayerToggle(com.starcam.astro.ui.I18n.Layers.starNames, flags.starNames) {
                            flags = flags.copy(starNames = it)
                            annotatedBitmap = renderAnnotated.invoke(flags)
                        }
                        ViewerLayerToggle(com.starcam.astro.ui.I18n.Layers.constellationNames, flags.constellationNames) {
                            flags = flags.copy(constellationNames = it)
                            annotatedBitmap = renderAnnotated.invoke(flags)
                        }
                        ViewerLayerToggle(com.starcam.astro.ui.I18n.Layers.messier, flags.messier) {
                            flags = flags.copy(messier = it)
                            annotatedBitmap = renderAnnotated.invoke(flags)
                        }
                    }
                }
            }

            // §0.48：天体科普卡片（放大图底部浮层）
            cardObject?.let { ref ->
                ObjectInfoCard(
                    ref = ref,
                    onDismiss = { cardObject = null },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp),
                )
            }

            // 底部操作提示
            Text(
                com.starcam.astro.ui.I18n.Viewer.gestureHint,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (cardObject != null) 150.dp else 20.dp)
                    .background(Color.Black.copy(alpha = 0.4f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun ViewerLayerToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clickable { onChange(!checked) }
            .padding(vertical = 3.dp),
    ) {
        Text(
            if (checked) "☑" else "☐",
            color = if (checked) Color(0xFF7FD0FF) else Color.White.copy(alpha = 0.55f),
            fontSize = 15.sp,
        )
        Spacer(Modifier.width(6.dp))
        Text(label, color = Color.White, fontSize = 12.sp)
    }
}
