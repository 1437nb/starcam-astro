package com.starcam.astro.util

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import com.starcam.astro.astro.LayerFlags
import com.starcam.astro.astro.LayeredRenderer
import com.starcam.astro.astro.PhotoExportGeometry
import com.starcam.astro.astro.PhotoOrientation
import com.starcam.astro.astro.SolarSystemEphemeris
import com.starcam.astro.astro.SolveResult
import com.starcam.astro.astro.StarChartOverlay
import java.io.File
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class PhotoExportFailure { SOURCE_MISSING, CANNOT_READ, MEMORY_LIMIT, UNKNOWN_BASIS, SAVE_FAILED }
class PhotoExportException(val reason: PhotoExportFailure) : Exception(reason.name)

data class PhotoExportInput(
    val path: String,
    val solve: SolveResult,
    /** null 表示旧批量历史没有记录引擎 / 坐标方向，需重新识别。 */
    val solveUsesUprightPixels: Boolean?,
    val solar: List<SolarSystemEphemeris.SolarPosition>,
    val previewWidth: Int,
    val previewHeight: Int,
)

data class PhotoSourceInfo(
    val width: Int,
    val height: Int,
    val kind: PhotoSourceKind,
    val orientation: PhotoOrientation,
    val uploadOrientation: PhotoOrientation,
    val fileBytes: Long,
) {
    val outputWidth: Int get() = orientation.width(width, height)
    val outputHeight: Int get() = orientation.height(width, height)
    val estimatedBytes: Long get() = PhotoExportPolicy.estimateBytes(width, height, kind, fileBytes, orientation.value != 1)
}

data class ExportedPhoto(val location: String, val width: Int, val height: Int)

/** 两种保存共用全局串行入口，避免同时点击两个按钮造成多张全尺寸位图并发驻留。 */
object PhotoExporter {
    private val exportLock = Mutex()

    private fun memoryBudget(context: Context): Long {
        val runtime = Runtime.getRuntime()
        val heap = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
        val memory = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        return if (memory.lowMemory) 0 else PhotoExportPolicy.budget(heap, memory.availMem)
    }

    suspend fun inspect(context: Context, path: String): PhotoSourceInfo = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.isFile) throw PhotoExportException(PhotoExportFailure.SOURCE_MISSING)
        val kind = when {
            FitsImage.isFitsPath(path) -> PhotoSourceKind.FITS
            RawPreview.isRawPath(path) -> PhotoSourceKind.RAW_PREVIEW
            else -> PhotoSourceKind.IMAGE
        }
        val length = file.length()
        val exifOrientation = runCatching {
            ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1).takeIf { it in 1..8 } ?: 1
        }.getOrDefault(1)
        val orientation = PhotoOrientation(if (kind == PhotoSourceKind.IMAGE) exifOrientation else 1)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val w: Int
        val h: Int
        if (kind == PhotoSourceKind.FITS) {
            val meta = FitsImage.readMeta(path) ?: throw PhotoExportException(PhotoExportFailure.CANNOT_READ)
            w = meta.naxis1; h = meta.naxis2
        } else if (kind == PhotoSourceKind.RAW_PREVIEW) {
            // 提取器会读容器并复制 JPEG，必须在提取之前限制文件内存。
            val minimum = PhotoExportPolicy.estimateBytes(1, 1, kind, length, false)
            if (minimum > memoryBudget(context)) throw PhotoExportException(PhotoExportFailure.MEMORY_LIMIT)
            val jpeg = RawPreview.extractJpeg(path) ?: throw PhotoExportException(PhotoExportFailure.CANNOT_READ)
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
            w = bounds.outWidth; h = bounds.outHeight
        } else {
            BitmapFactory.decodeFile(path, bounds)
            w = bounds.outWidth; h = bounds.outHeight
        }
        if (w <= 0 || h <= 0) throw PhotoExportException(PhotoExportFailure.CANNOT_READ)
        PhotoSourceInfo(w, h, kind, orientation, PhotoOrientation(exifOrientation).uploadOrientation(), length)
    }

    suspend fun save(
        context: Context,
        input: PhotoExportInput,
        flags: LayerFlags,
        isEnglish: Boolean,
        original: Boolean,
        previewRenderer: () -> Bitmap,
    ): ExportedPhoto = exportLock.withLock {
        withContext(Dispatchers.IO) {
            var output: Bitmap? = null
            try {
                coroutineContext.ensureActive()
                val rendered = if (original) renderOriginal(context, input, flags, isEnglish) else previewRenderer()
                output = rendered
                coroutineContext.ensureActive()
                val name = "StarCam_${if (original) "original" else "preview"}_${UUID.randomUUID()}.jpg"
                val location = ImageUtils.saveBitmapToGallery(context, rendered, name)
                    ?: throw PhotoExportException(PhotoExportFailure.SAVE_FAILED)
                ExportedPhoto(location, rendered.width, rendered.height)
            } finally {
                output?.recycle()
            }
        }
    }

    private suspend fun renderOriginal(
        context: Context, input: PhotoExportInput, flags: LayerFlags, english: Boolean,
    ): Bitmap {
        val online = input.solveUsesUprightPixels ?: throw PhotoExportException(PhotoExportFailure.UNKNOWN_BASIS)
        val info = inspect(context, input.path)
        if (info.estimatedBytes > memoryBudget(context)) throw PhotoExportException(PhotoExportFailure.MEMORY_LIMIT)
        val geometry = PhotoExportGeometry(
            info.width, info.height, if (online) info.uploadOrientation else PhotoOrientation(), info.orientation,
        )
        val options = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
            inSampleSize = 1
        }
        var decoded: Bitmap? = null
        var output: Bitmap? = null
        try {
            coroutineContext.ensureActive()
            decoded = when (info.kind) {
                PhotoSourceKind.FITS -> {
                    val workContext = coroutineContext
                    ImageUtils.decodeSampledBitmap(input.path, maxOf(info.width, info.height)) { workContext.ensureActive() }
                }
                PhotoSourceKind.RAW_PREVIEW -> {
                    val jpeg = RawPreview.extractJpeg(input.path) ?: throw PhotoExportException(PhotoExportFailure.CANNOT_READ)
                    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
                }
                PhotoSourceKind.IMAGE -> BitmapFactory.decodeFile(input.path, options)
            } ?: throw PhotoExportException(PhotoExportFailure.CANNOT_READ)
            val raw = requireNotNull(decoded)
            if (raw.width != info.width || raw.height != info.height) throw PhotoExportException(PhotoExportFailure.CANNOT_READ)
            coroutineContext.ensureActive()
            if (info.orientation.value == 1 && raw.isMutable) {
                output = raw
                decoded = null
            } else {
                val upright = Bitmap.createBitmap(geometry.width, geometry.height, Bitmap.Config.ARGB_8888)
                output = upright
                val matrix = Matrix().apply { setValues(info.orientation.canvasMatrix(info.width, info.height)) }
                Canvas(upright).drawBitmap(raw, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
            }
            decoded?.recycle(); decoded = null
            val target = requireNotNull(output)
            val footprint = if (info.kind == PhotoSourceKind.FITS) {
                PhotoExportPolicy.fitsFootprint(info.width, info.height, if (online) 2000 else 2200)
            } else geometry.basisWidth to geometry.basisHeight
            val wcs = geometry.wcs(input.solve, footprint.first, footprint.second)
            val stars = StarChartOverlay.projectStars(wcs, geometry.basisWidth, geometry.basisHeight)
            stars.forEach { star ->
                val p = geometry.outputPoint(star.x, star.y)
                star.x = p[0]; star.y = p[1]
            }
            val messier = StarChartOverlay.projectMessier(wcs, geometry.basisWidth, geometry.basisHeight)
            messier.forEach { item ->
                val p = geometry.outputPoint(item.x, item.y)
                item.x = p[0]; item.y = p[1]
            }
            val solar = StarChartOverlay.projectSolarSystem(wcs, geometry.basisWidth, geometry.basisHeight, input.solar)
            solar.forEach { item ->
                val p = geometry.outputPoint(item.x, item.y)
                item.x = p[0]; item.y = p[1]
            }
            val scene = LayeredRenderer.OverlayScene(
                geometry.width, geometry.height, stars, StarChartOverlay.projectLines(stars),
                StarChartOverlay.constellationLabels(stars, english), messier, solar,
            )
            val annotationScale = maxOf(geometry.width, geometry.height).toFloat() / maxOf(input.previewWidth, input.previewHeight)
            val degPerPx = (input.solve.fieldWidthDeg / footprint.first).toFloat()
            coroutineContext.ensureActive()
            LayeredRenderer.draw(
                Canvas(target), scene, flags, english, density = 2f * annotationScale,
                degPerPx = degPerPx, lineGapScale = annotationScale,
            )
            return target.also { output = null }
        } finally {
            decoded?.recycle()
            output?.recycle()
        }
    }
}
