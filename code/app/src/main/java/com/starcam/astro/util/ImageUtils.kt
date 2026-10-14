package com.starcam.astro.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

/** 图片处理工具：采样解码、EXIF 旋转校正、上传压缩、URI 拷贝、保存到相册 */
object ImageUtils {

    /**
     * 采样解码，限制最长边不超过 [maxDim]，避免大图 OOM。
     *
     * §0.108 起按扩展名**分派**（三种解码器合流，9 个调用点全部受益，见 docs/93）：
     *  - `.fit` / `.fits` / `.fts` → [FitsImage]（纯 Kotlin 解码 + 拉伸）；
     *  - 相机 RAW（CR2/NEF/ARW/DNG/ORF/RW2/RAF…）→ [RawPreview] 取内嵌预览 JPEG 再解；
     *  - 其余（JPEG/PNG/WebP…）走原来的 BitmapFactory 路径，行为一字不变。
     */
    fun decodeSampledBitmap(path: String, maxDim: Int = 2048): Bitmap? {
        if (FitsImage.isFitsPath(path)) return decodeFits(path, maxDim)
        if (RawPreview.isRawPath(path)) return decodeRawPreview(path, maxDim)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        return decodeSampledFile(path, bounds.outWidth, bounds.outHeight, maxDim)
    }

    /**
     * FITS：解码 → 灰度浮点（已拉伸到 0..255）→ 位图。
     *
     * 为什么仍要出位图：显示、叠加渲染、原生引擎取样、上传压缩都吃 [Bitmap]，
     * 而 FITS 本身没有位图概念。8 位量化发生在**拉伸之后**（先浮点拉伸再取整），
     * 与 JPEG 素材的既有链路口径一致。
     */
    private fun decodeFits(path: String, maxDim: Int): Bitmap? {
        val frame = FitsImage.decode(path, maxDim) ?: return null
        return grayToBitmap(frame.gray, frame.w, frame.h)
    }

    /** 灰度浮点（0..255）→ ARGB_8888 位图（灰度三通道同值）。 */
    fun grayToBitmap(gray: FloatArray, w: Int, h: Int): Bitmap? {
        if (w <= 0 || h <= 0 || gray.size < w * h) return null
        val px = IntArray(w * h)
        for (i in 0 until w * h) {
            val v = gray[i].toInt().coerceIn(0, 255)
            px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * 相机 RAW：取**内嵌预览 JPEG**（相机 ISP 渲染过的预览，星点质量足以识别），
     * 之后走与普通 JPEG 完全相同的采样解码路径。
     *
     * 不按 EXIF Orientation 旋转：各家 RAW 的内嵌预览通常已是"正"方向，而 TIFF 的
     * Orientation 标签语义与外层 JPEG 的 EXIF 不完全一致 —— 二次旋转会把好图转歪。
     */
    private fun decodeRawPreview(path: String, maxDim: Int): Bitmap? {
        val jpeg = RawPreview.extractJpeg(path) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxDim)
        }
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) ?: return null
        return scaleDownTo(bmp, maxDim)
    }

    /** 采样率：与既有实现同口径（长边不超过 maxDim 的 2 倍，再交给等比缩放）。 */
    private fun sampleSizeFor(w: Int, h: Int, maxDim: Int): Int {
        if (w <= 0 || h <= 0) return 1
        var sample = 1
        val maxSide = maxOf(w, h)
        while (maxSide / sample > maxDim * 2) sample *= 2
        return sample
    }

    /** 解码后仍超目标尺寸时做一次等比缩放（中间位图立即回收，压低峰值内存）。 */
    private fun scaleDownTo(bmp: Bitmap, maxDim: Int): Bitmap {
        val side = maxOf(bmp.width, bmp.height)
        if (side <= maxDim) return bmp
        val scale = maxDim.toFloat() / side
        val scaled = Bitmap.createScaledBitmap(
            bmp,
            (bmp.width * scale).toInt().coerceAtLeast(1),
            (bmp.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== bmp) bmp.recycle()
        return scaled
    }

    /** BitmapFactory 文件解码（原路径：采样 + 等比缩放）。 */
    private fun decodeSampledFile(path: String, outW: Int, outH: Int, maxDim: Int): Bitmap? {
        if (outW <= 0 || outH <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(outW, outH, maxDim) }
        val bmp = BitmapFactory.decodeFile(path, opts) ?: return null
        return scaleDownTo(bmp, maxDim)
    }

    /** 按 EXIF 方向信息旋转位图，使照片正向显示 */
    fun rotateByExif(path: String, bitmap: Bitmap): Bitmap {
        val orientation = try {
            ExifInterface(path).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated != bitmap) bitmap.recycle()
        return rotated
    }

    /** 压缩为适合上传的 JPEG（最长边 [maxDim]，质量 [quality]），输出到 [out] */
    fun compressForUpload(path: String, out: File, maxDim: Int = 2000, quality: Int = 88): File? {
        val bmp = decodeSampledBitmap(path, maxDim) ?: return null
        val upright = rotateByExif(path, bmp)
        FileOutputStream(out).use { upright.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        if (upright !== bmp) upright.recycle()
        bmp.recycle()
        return out
    }

    /** 把相册选择的 URI 拷贝到应用缓存目录 */
    fun copyUriToCache(context: Context, uri: Uri, name: String): File? {
        return try {
            val dest = File(context.cacheDir, name)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { input.copyTo(it) }
            }
            dest
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 从系统文档选择器（SAF）拿到的 URI 拷贝到缓存目录，**保留原文件名与扩展名**（§0.108）。
     *
     * 为什么必须保留扩展名：解码分派按扩展名走（`.fits` / `.cr2` …）。统一改名成 `.jpg`
     * 会让 FITS/RAW 落到 BitmapFactory 路径上，直接解不出来。
     *
     * 安全：显示名来自外部提供方，先做**文件名净化**（去掉路径分隔符与不可见字符，
     * 只留字母数字与 `._-`），避免 `../` 之类的越界写入。
     */
    fun copyUriToCacheKeepingName(context: Context, uri: Uri, prefix: String): File? {
        return try {
            val display = queryDisplayName(context, uri)
            val safe = sanitizeFileName(
                display ?: (prefix + "." + extFromMime(context, uri)),
            )
            val dest = File(context.cacheDir, "${prefix}_$safe")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { input.copyTo(it) }
            }
            dest
        } catch (e: Exception) {
            null
        }
    }

    /** 查询 SAF 文档的显示名（拿不到返回 null）。 */
    private fun queryDisplayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(
            uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (e: Exception) {
        null
    }

    /** 按 MIME 猜扩展名（显示名缺扩展名时的兜底）。 */
    private fun extFromMime(context: Context, uri: Uri): String {
        val mime = try {
            context.contentResolver.getType(uri)
        } catch (e: Exception) {
            null
        }
        val ext = mime?.let { android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        return (ext ?: "bin").lowercase()
    }

    /** 文件名净化：只留字母/数字/`._-`（含中文等宽字符会破坏解码分派，故一律替换为 `_`）。 */
    internal fun sanitizeFileName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base.map { c ->
            if (c.isLetterOrDigit() && c.code < 128 || c == '.' || c == '_' || c == '-') c else '_'
        }.joinToString("")
        return cleaned.trimStart('.').take(96).ifEmpty { "unnamed.bin" }
    }

    /**
     * 保存位图到本地相册（Pictures/StarCam/），返回保存位置描述；失败返回 null。
     *
     * - Android 10+（API 29+）：MediaStore 直写相册，无需任何权限
     * - Android 8/8.1（API 26-28）：写入应用专属 Pictures 目录（免存储权限），
     *   文件管理器/传输工具中可见
     */
    fun saveBitmapToGallery(context: Context, bitmap: Bitmap, name: String): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/StarCam")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values,
                ) ?: return null
                val wrote = context.contentResolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                } ?: false
                if (!wrote) {
                    context.contentResolver.delete(uri, null, null)
                    return null
                }
                context.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null,
                )
                "相册 Pictures/StarCam/$name"
            } else {
                val dir = File(
                    context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "StarCam",
                ).apply { mkdirs() }
                val file = File(dir, name)
                val wrote = FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                if (!wrote) return null
                "应用目录 Pictures/StarCam/$name"
            }
        } catch (e: Exception) {
            null
        }
    }
}
