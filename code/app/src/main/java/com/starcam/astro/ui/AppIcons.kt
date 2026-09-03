package com.starcam.astro.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** 自定义矢量图标（避免引入 material-icons-extended 增大包体） */
object AppIcons {

    /** 相机图标（Material photo_camera 轮廓） */
    val Camera: ImageVector by lazy {
        ImageVector.Builder(
            name = "PhotoCamera",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                pathFillType = androidx.compose.ui.graphics.PathFillType.NonZero,
            ) {
                moveTo(12f, 15.2f)
                arcTo(3.2f, 3.2f, 0f, false, false, 15.2f, 12f)
                arcTo(3.2f, 3.2f, 0f, false, false, 12f, 8.8f)
                arcTo(3.2f, 3.2f, 0f, false, false, 8.8f, 12f)
                arcTo(3.2f, 3.2f, 0f, false, false, 12f, 15.2f)
                moveTo(9f, 2f)
                lineTo(7.17f, 4f)
                horizontalLineTo(4f)
                arcTo(2f, 2f, 0f, false, false, 2f, 6f)
                verticalLineTo(18f)
                arcTo(2f, 2f, 0f, false, false, 4f, 20f)
                horizontalLineTo(20f)
                arcTo(2f, 2f, 0f, false, false, 22f, 18f)
                verticalLineTo(6f)
                arcTo(2f, 2f, 0f, false, false, 20f, 4f)
                horizontalLineTo(16.83f)
                lineTo(15f, 2f)
                horizontalLineTo(9f)
                moveTo(12f, 17f)
                arcTo(5f, 5f, 0f, false, true, 7f, 12f)
                arcTo(5f, 5f, 0f, false, true, 12f, 7f)
                arcTo(5f, 5f, 0f, false, true, 17f, 12f)
                arcTo(5f, 5f, 0f, false, true, 12f, 17f)
                close()
            }
        }.build()
    }

    /** 闪光灯图标（Material flash_on） */
    val FlashOn: ImageVector by lazy {
        ImageVector.Builder(
            name = "FlashOn",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(7f, 2f)
                verticalLineTo(13f)
                horizontalLineTo(10f)
                verticalLineTo(22f)
                lineTo(17f, 10f)
                horizontalLineTo(13f)
                lineTo(17f, 2f)
                close()
            }
        }.build()
    }

    /** 夜景增强图标（Material nightlight 月牙） */
    val Night: ImageVector by lazy {
        ImageVector.Builder(
            name = "Night",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(14f, 2f)
                curveTo(15.82f, 2f, 17.53f, 2.55f, 19f, 3.5f)
                curveTo(16.88f, 5.55f, 15.56f, 8.53f, 15.56f, 12f)
                curveTo(15.56f, 15.47f, 16.88f, 18.45f, 19f, 20.5f)
                curveTo(17.53f, 21.45f, 15.82f, 22f, 14f, 22f)
                curveTo(8.48f, 22f, 4f, 17.52f, 4f, 12f)
                curveTo(4f, 6.48f, 8.48f, 2f, 14f, 2f)
                close()
            }
        }.build()
    }

    /** 切换镜头图标（Material cameraswitch） */
    val CameraSwitch: ImageVector by lazy {
        ImageVector.Builder(
            name = "CameraSwitch",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(16f, 7f)
                horizontalLineTo(15f)
                lineTo(14f, 6f)
                horizontalLineTo(10f)
                lineTo(9f, 7f)
                horizontalLineTo(8f)
                curveTo(6.9f, 7f, 6f, 7.9f, 6f, 9f)
                verticalLineTo(19f)
                curveTo(6f, 20.1f, 6.9f, 21f, 8f, 21f)
                horizontalLineTo(16f)
                curveTo(17.1f, 21f, 18f, 20.1f, 18f, 19f)
                verticalLineTo(9f)
                curveTo(18f, 7.9f, 17.1f, 7f, 16f, 7f)
                moveTo(9.8f, 12.5f)
                horizontalLineTo(7.5f)
                curveTo(7.6f, 11f, 8.9f, 9.8f, 10.4f, 9.8f)
                lineTo(10f, 11.3f)
                curveTo(9.5f, 11.3f, 9.2f, 11.9f, 9.8f, 12.5f)
                moveTo(13f, 16.5f)
                curveTo(11.5f, 16.5f, 10.3f, 15.3f, 10.1f, 14f)
                horizontalLineTo(12.4f)
                curveTo(12.5f, 14.6f, 12.9f, 15.2f, 13.6f, 15.2f)
                verticalLineTo(16.5f)
                curveTo(13.4f, 16.5f, 13.2f, 16.5f, 13f, 16.5f)
                moveTo(12.2f, 13.2f)
                lineTo(10.7f, 11.7f)
                lineTo(12.2f, 10.2f)
                lineTo(13.7f, 11.7f)
                lineTo(12.2f, 13.2f)
                moveTo(13f, 13.2f)
                lineTo(14.5f, 11.7f)
                lineTo(13f, 10.2f)
                lineTo(11.5f, 11.7f)
                lineTo(13f, 13.2f)
                moveTo(17.5f, 9.5f)
                lineTo(19f, 9.5f)
                curveTo(21.2f, 9.5f, 23f, 11.3f, 23f, 13.5f)
                curveTo(23f, 14f, 22.9f, 14.5f, 22.8f, 14.9f)
                horizontalLineTo(21.2f)
                curveTo(21.3f, 14.4f, 21.4f, 14f, 21.4f, 13.5f)
                curveTo(21.4f, 11.9f, 20.2f, 10.6f, 18.6f, 10.5f)
                lineTo(19.5f, 10.5f)
                lineTo(17.5f, 9.5f)
                moveTo(20.8f, 15.9f)
                horizontalLineTo(22.4f)
                curveTo(22.5f, 16.4f, 22.6f, 16.9f, 22.6f, 17.4f)
                curveTo(22.6f, 17.9f, 22.5f, 18.4f, 22.4f, 18.8f)
                horizontalLineTo(20.8f)
                curveTo(20.9f, 18.3f, 21f, 17.9f, 21f, 17.4f)
                curveTo(21f, 16.9f, 20.9f, 16.4f, 20.8f, 15.9f)
                moveTo(16f, 2.5f)
                lineTo(13.5f, 5f)
                lineTo(16f, 7.5f)
                verticalLineTo(6f)
                curveTo(17.5f, 6f, 18.8f, 7f, 19.2f, 8.3f)
                lineTo(20.8f, 8.1f)
                curveTo(20.2f, 5.8f, 18.3f, 4f, 16f, 4f)
                verticalLineTo(2.5f)
                moveTo(4.5f, 12.5f)
                curveTo(4.5f, 10.9f, 5.7f, 9.6f, 7.3f, 9.5f)
                lineTo(6.4f, 9.5f)
                lineTo(8.4f, 8.5f)
                lineTo(6.4f, 7.5f)
                lineTo(8f, 7.5f)
                verticalLineTo(6.5f)
                curveTo(5.8f, 6.5f, 4f, 8.3f, 4f, 10.5f)
                curveTo(4f, 11f, 4.1f, 11.5f, 4.2f, 11.9f)
                horizontalLineTo(5.8f)
                curveTo(5.6f, 11.4f, 5.5f, 11f, 5.5f, 10.5f)
                curveTo(5.5f, 9.8f, 5.9f, 9.3f, 6.5f, 9f)
                verticalLineTo(9.4f)
                lineTo(4.5f, 12.5f)
                close()
            }
        }.build()
    }

    /** 相册图标（Material photo_library 轮廓） */
    val Gallery: ImageVector by lazy {
        ImageVector.Builder(
            name = "PhotoLibrary",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                pathFillType = androidx.compose.ui.graphics.PathFillType.NonZero,
            ) {
                moveTo(22f, 16f)
                verticalLineTo(4f)
                curveTo(22f, 2.9f, 21.1f, 2f, 20f, 2f)
                horizontalLineTo(8f)
                curveTo(6.9f, 2f, 6f, 2.9f, 6f, 4f)
                verticalLineTo(16f)
                curveTo(6f, 17.1f, 6.9f, 18f, 8f, 18f)
                horizontalLineTo(20f)
                curveTo(21.1f, 18f, 22f, 17.1f, 22f, 16f)
                moveTo(11f, 12f)
                lineTo(13.03f, 14.71f)
                lineTo(16f, 11f)
                lineTo(20f, 16f)
                horizontalLineTo(8f)
                lineTo(11f, 12f)
                moveTo(2f, 6f)
                verticalLineTo(20f)
                curveTo(2f, 21.1f, 2.9f, 22f, 4f, 22f)
                horizontalLineTo(18f)
                verticalLineTo(20f)
                horizontalLineTo(4f)
                verticalLineTo(6f)
                horizontalLineTo(2f)
                close()
            }
        }.build()
    }
}
