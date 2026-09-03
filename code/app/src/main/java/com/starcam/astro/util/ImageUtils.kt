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
     */
    fun decodeSampledBitmap(path: String, maxDim: Int = 2048): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxSide / sample > maxDim * 2) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = BitmapFactory.decodeFile(path, opts)
        if (bmp == null) return null

        // 若解码后仍超出目标尺寸，做一次等比缩放
        val side = maxOf(bmp.width, bmp.height)
        if (side > maxDim) {
            val scale = maxDim.toFloat() / side
            bmp = Bitmap.createScaledBitmap(
                bmp,
                (bmp.width * scale).toInt().coerceAtLeast(1),
                (bmp.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        }
        return bmp
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
