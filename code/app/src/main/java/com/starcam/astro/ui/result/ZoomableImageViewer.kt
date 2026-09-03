package com.starcam.astro.ui.result

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 全屏图片查看器（§0.34，§0.37 增加原图/标注切换）：
 * 捏合缩放 1~5x、单指拖拽平移、双击放大 3x/还原；右上角可一键在
 * 「原图」与「标注图」（星座线/星名或诊断星点圈）之间切换。
 */
@Composable
fun ZoomableImageViewer(
    original: Bitmap,
    annotated: Bitmap,
    title: String,
    onClose: () -> Unit,
) {
    var showAnnotated by remember { mutableStateOf(true) }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val shown = if (showAnnotated) annotated else original

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
            ) {
                // 原图 / 标注 切换（§0.37/§0.38）：保持当前缩放与平移，不跳回 1 倍。
                // 两张图均按 ContentScale.Fit 适配同一显示框且宽高比一致，切换后观感不变。
                Text(
                    if (showAnnotated) "原图" else "标注",
                    color = Color.White,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.55f))
                        .clickable { showAnnotated = !showAnnotated }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
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

            // 底部操作提示
            Text(
                "双指缩放 · 拖动查看 · 双击放大",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 20.dp)
                    .background(Color.Black.copy(alpha = 0.4f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}
