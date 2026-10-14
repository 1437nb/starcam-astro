package com.starcam.astro

import java.io.File
import java.security.MessageDigest

/**
 * 公开演示 JPEG → 灰度浮点数组（测试专用，供 [OfflinePhotosTruthTest] 用）。
 *
 * ## 为什么要反射，而不是直接 `import javax.imageio.ImageIO`
 *
 * Android 单测**编译**时用的是 android.jar —— Android 的 API 子集，里面没有
 * `javax.imageio`（那属于 JDK 的 java.desktop 模块，Android 根本没有），
 * 直接 import 会编译失败。而单测**运行**在完整 JDK 上，ImageIO 就在那里。
 * 编译期取不到、运行期存在，于是走反射。
 *
 * ## 边界
 *
 *  - 只取「整幅 getRGB 一次取完」的批量重载，避免 2.2M 次反射调用；
 *  - 运行环境真缺 java.desktop 时**直接失败**（素材在仓库里，回归保护不允许静默跳过）；
 *  - 灰度系数与 [com.starcam.astro.astro.LocalStarMatcher.bitmapToGray] 完全一致
 *    （0.299/0.587/0.114），保证本类给出的像素与生产检测器吃到的口径相同。
 */
internal object JpegLuma {

    /**
     * 公开演示照片目录（仓库自带 `assets/offline_photos/`，随 APK 发布）——
     * 各测试台共用同一处解析逻辑，避免"两台测试对素材位置各写一份"。
     */
    fun demoPhotosDir(): File {
        val candidates = listOf(
            File("src/main/assets/offline_photos"),           // 单测工作目录 = code/app
            File("code/app/src/main/assets/offline_photos"),  // 仓库根工作目录
            File("../../code/app/src/main/assets/offline_photos"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("找不到公开演示照片目录（找过：${candidates.joinToString { it.path }}）")
    }

    class Gray(val w: Int, val h: Int, val px: FloatArray, private val luma8: ByteArray) {
        /**
         * 8 位灰度前 8 字节的 sha256（打印用）：换 JDK、换解码器导致像素漂移时，
         * 先看它变没变 —— 变的只可能是解码，不是我们的数据。
         */
        val sha8: String =
            MessageDigest.getInstance("SHA-256").digest(luma8)
                .joinToString("") { "%02x".format(it) }.take(8)
    }

    fun read(file: File): Gray {
        val imageIoClass = try {
            Class.forName("javax.imageio.ImageIO")
        } catch (e: ClassNotFoundException) {
            throw AssertionError(
                "单测运行环境缺 java.desktop（javax.imageio），无法解码公开演示图 ${file.name}。" +
                    "素材在仓库里，回归保护不允许静默跳过 —— 请用完整 JDK 跑单测。",
            )
        }
        val img = try {
            imageIoClass.getMethod("read", File::class.java).invoke(null, file)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw AssertionError("ImageIO 解不开 ${file.name}：${e.cause}", e)
        } ?: throw AssertionError("ImageIO 解不开 ${file.name}（返回 null，格式不支持？）")

        // 运行期是 java.awt.image.BufferedImage，编译期不可见 —— 全程反射
        val imgClass = img.javaClass
        val w = imgClass.getMethod("getWidth").invoke(img) as Int
        val h = imgClass.getMethod("getHeight").invoke(img) as Int
        val argb = IntArray(w * h)
        imgClass.getMethod(
            "getRGB",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            IntArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        ).invoke(img, 0, 0, w, h, argb, 0, w)

        val gray = FloatArray(w * h)
        val luma8 = ByteArray(w * h)
        for (i in argb.indices) {
            val c = argb[i]
            val v = 0.299f * ((c shr 16) and 0xFF) +
                0.587f * ((c shr 8) and 0xFF) +
                0.114f * (c and 0xFF)
            gray[i] = v
            luma8[i] = v.toInt().coerceIn(0, 255).toByte()
        }
        return Gray(w, h, gray, luma8)
    }
}
