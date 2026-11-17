package com.starcam.astro.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** 自定义矢量图标（避免引入 material-icons-extended 增大包体） */
object AppIcons {

    private fun PathBuilder.circleSubpath(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcTo(r, r, 0f, false, true, cx + r, cy)
        arcTo(r, r, 0f, false, true, cx - r, cy)
        close()
    }

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
                pathFillType = PathFillType.NonZero,
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
                pathFillType = PathFillType.NonZero,
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

    /** 显示凭据图标（Material visibility） */
    val Visibility: ImageVector by lazy {
        ImageVector.Builder(
            name = "Visibility",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.EvenOdd,
            ) {
                moveTo(12f, 4.5f)
                curveTo(7f, 4.5f, 2.73f, 7.61f, 1f, 12f)
                curveTo(2.73f, 16.39f, 7f, 19.5f, 12f, 19.5f)
                curveTo(17f, 19.5f, 21.27f, 16.39f, 23f, 12f)
                curveTo(21.27f, 7.61f, 17f, 4.5f, 12f, 4.5f)
                close()
                circleSubpath(12f, 12f, 5f)
                circleSubpath(12f, 12f, 3f)
            }
        }.build()
    }

    /** 隐藏凭据图标（Material visibility_off） */
    val VisibilityOff: ImageVector by lazy {
        ImageVector.Builder(
            name = "VisibilityOff",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.NonZero,
            ) {
                moveTo(12f, 7f)
                curveTo(14.76f, 7f, 17f, 9.24f, 17f, 12f)
                curveTo(17f, 12.65f, 16.87f, 13.26f, 16.64f, 13.83f)
                lineTo(19.56f, 16.75f)
                curveTo(21.07f, 15.49f, 22.26f, 13.86f, 22.99f, 12f)
                curveTo(21.26f, 7.61f, 16.99f, 4.5f, 11.99f, 4.5f)
                curveTo(10.59f, 4.5f, 9.25f, 4.75f, 8.01f, 5.2f)
                lineTo(10.17f, 7.36f)
                curveTo(10.74f, 7.13f, 11.35f, 7f, 12f, 7f)
                close()
                moveTo(2f, 4.27f)
                lineTo(4.28f, 6.55f)
                lineTo(4.74f, 7.01f)
                curveTo(3.08f, 8.3f, 1.78f, 10.02f, 1f, 12f)
                curveTo(2.73f, 16.39f, 7f, 19.5f, 12f, 19.5f)
                curveTo(13.55f, 19.5f, 15.03f, 19.2f, 16.38f, 18.66f)
                lineTo(16.8f, 19.08f)
                lineTo(19.73f, 22f)
                lineTo(21f, 20.73f)
                lineTo(3.27f, 3f)
                lineTo(2f, 4.27f)
                close()
                moveTo(7.53f, 9.8f)
                lineTo(9.08f, 11.35f)
                curveTo(9.03f, 11.56f, 9f, 11.78f, 9f, 12f)
                curveTo(9f, 13.66f, 10.34f, 15f, 12f, 15f)
                curveTo(12.22f, 15f, 12.44f, 14.97f, 12.65f, 14.92f)
                lineTo(14.2f, 16.47f)
                curveTo(13.53f, 16.8f, 12.79f, 17f, 12f, 17f)
                curveTo(9.24f, 17f, 7f, 14.76f, 7f, 12f)
                curveTo(7f, 11.21f, 7.2f, 10.47f, 7.53f, 9.8f)
                close()
                moveTo(11.84f, 9.02f)
                lineTo(14.99f, 12.17f)
                lineTo(15.01f, 12.01f)
                curveTo(15.01f, 10.35f, 13.67f, 9.01f, 12.01f, 9.01f)
                lineTo(11.84f, 9.02f)
                close()
            }
        }.build()
    }

    /** 外观调色板图标（Material palette） */
    val Palette: ImageVector by lazy {
        ImageVector.Builder(
            name = "Palette",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.EvenOdd,
            ) {
                moveTo(12f, 3f)
                curveTo(7.03f, 3f, 3f, 7.03f, 3f, 12f)
                curveTo(3f, 16.97f, 7.03f, 21f, 12f, 21f)
                curveTo(12.83f, 21f, 13.5f, 20.33f, 13.5f, 19.5f)
                curveTo(13.5f, 19.11f, 13.35f, 18.76f, 13.11f, 18.49f)
                curveTo(12.88f, 18.23f, 12.73f, 17.88f, 12.73f, 17.5f)
                curveTo(12.73f, 16.67f, 13.4f, 16f, 14.23f, 16f)
                horizontalLineTo(16f)
                curveTo(18.76f, 16f, 21f, 13.76f, 21f, 11f)
                curveTo(21f, 6.58f, 16.97f, 3f, 12f, 3f)
                close()
                circleSubpath(6.5f, 11.5f, 1.5f)
                circleSubpath(9.5f, 7.5f, 1.5f)
                circleSubpath(14.5f, 7.5f, 1.5f)
                circleSubpath(17.5f, 11.5f, 1.5f)
            }
        }.build()
    }

    /** 星芒引擎图标（Material auto_awesome） */
    val Engine: ImageVector by lazy {
        ImageVector.Builder(
            name = "EngineSparkles",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(19f, 9f)
                lineTo(20.25f, 6.25f)
                lineTo(23f, 5f)
                lineTo(20.25f, 3.75f)
                lineTo(19f, 1f)
                lineTo(17.75f, 3.75f)
                lineTo(15f, 5f)
                lineTo(17.75f, 6.25f)
                close()
                moveTo(19f, 15f)
                lineTo(17.75f, 17.75f)
                lineTo(15f, 19f)
                lineTo(17.75f, 20.25f)
                lineTo(19f, 23f)
                lineTo(20.25f, 20.25f)
                lineTo(23f, 19f)
                lineTo(20.25f, 17.75f)
                close()
                moveTo(11.5f, 9.5f)
                lineTo(9f, 4f)
                lineTo(6.5f, 9.5f)
                lineTo(1f, 12f)
                lineTo(6.5f, 14.5f)
                lineTo(9f, 20f)
                lineTo(11.5f, 14.5f)
                lineTo(17f, 12f)
                close()
            }
        }.build()
    }

    /** 天球罗盘图标（Material explore） */
    val Compass: ImageVector by lazy {
        ImageVector.Builder(
            name = "ExploreCompass",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.EvenOdd,
            ) {
                circleSubpath(12f, 12f, 10f)
                moveTo(6.5f, 17.5f)
                lineTo(9.8f, 9.8f)
                lineTo(17.5f, 6.5f)
                lineTo(14.2f, 14.2f)
                close()
                circleSubpath(12f, 12f, 1.15f)
            }
        }.build()
    }

    /** 云端服务图标（Material cloud） */
    val Cloud: ImageVector by lazy {
        ImageVector.Builder(
            name = "OnlineCloud",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(19.35f, 10.04f)
                curveTo(18.67f, 6.59f, 15.64f, 4f, 12f, 4f)
                curveTo(9.11f, 4f, 6.6f, 5.64f, 5.35f, 8.04f)
                curveTo(2.34f, 8.36f, 0f, 10.91f, 0f, 14f)
                curveTo(0f, 17.31f, 2.69f, 20f, 6f, 20f)
                horizontalLineTo(19f)
                curveTo(21.76f, 20f, 24f, 17.76f, 24f, 15f)
                curveTo(24f, 12.36f, 21.95f, 10.22f, 19.35f, 10.04f)
                close()
            }
        }.build()
    }

    /** 加密安全锁图标（Material lock） */
    val Lock: ImageVector by lazy {
        ImageVector.Builder(
            name = "SecureLock",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.EvenOdd,
            ) {
                moveTo(18f, 8f)
                horizontalLineTo(17f)
                verticalLineTo(6f)
                curveTo(17f, 3.24f, 14.76f, 1f, 12f, 1f)
                curveTo(9.24f, 1f, 7f, 3.24f, 7f, 6f)
                verticalLineTo(8f)
                horizontalLineTo(6f)
                curveTo(4.9f, 8f, 4f, 8.9f, 4f, 10f)
                verticalLineTo(20f)
                curveTo(4f, 21.1f, 4.9f, 22f, 6f, 22f)
                horizontalLineTo(18f)
                curveTo(19.1f, 22f, 20f, 21.1f, 20f, 20f)
                verticalLineTo(10f)
                curveTo(20f, 8.9f, 19.1f, 8f, 18f, 8f)
                close()
                moveTo(9f, 8f)
                verticalLineTo(6f)
                curveTo(9f, 4.34f, 10.34f, 3f, 12f, 3f)
                curveTo(13.66f, 3f, 15f, 4.34f, 15f, 6f)
                verticalLineTo(8f)
                horizontalLineTo(9f)
                close()
                circleSubpath(12f, 15f, 2f)
            }
        }.build()
    }

    /** 诊断日志图标（Material description） */
    val Diagnostics: ImageVector by lazy {
        ImageVector.Builder(
            name = "DiagnosticsLog",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.EvenOdd,
            ) {
                moveTo(14f, 2f)
                horizontalLineTo(6f)
                curveTo(4.9f, 2f, 4f, 2.9f, 4f, 4f)
                verticalLineTo(20f)
                curveTo(4f, 21.1f, 4.9f, 22f, 6f, 22f)
                horizontalLineTo(18f)
                curveTo(19.1f, 22f, 20f, 21.1f, 20f, 20f)
                verticalLineTo(8f)
                lineTo(14f, 2f)
                close()
                moveTo(13f, 9f)
                verticalLineTo(3.5f)
                lineTo(18.5f, 9f)
                horizontalLineTo(13f)
                close()
                moveTo(8f, 13f)
                horizontalLineTo(16f)
                verticalLineTo(14.8f)
                horizontalLineTo(8f)
                close()
                moveTo(8f, 16.5f)
                horizontalLineTo(14f)
                verticalLineTo(18.3f)
                horizontalLineTo(8f)
                close()
            }
        }.build()
    }
}
