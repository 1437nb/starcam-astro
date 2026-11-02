package com.starcam.astro

import android.app.Activity
import android.app.ActivityManager
import android.app.Instrumentation
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.starcam.astro.astro.LayerFlags
import com.starcam.astro.astro.NativeEngineProbe
import com.starcam.astro.astro.SolveResult
import com.starcam.astro.astro.WcsTransform
import com.starcam.astro.util.ExportedPhoto
import com.starcam.astro.util.PhotoExportException
import com.starcam.astro.util.PhotoExportFailure
import com.starcam.astro.util.PhotoExportInput
import com.starcam.astro.util.PhotoExporter
import com.starcam.astro.util.PhotoSourceKind
import com.starcam.astro.ui.batch.BatchViewModel
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** 真正运行在 Android 上；只生成合成素材、读取本次输出，不读取用户相册或私人照片。 */
class DeviceAcceptanceInstrumentation : Instrumentation() {
    private val results = JSONArray()
    private val noLayers = LayerFlags(false, false, false, false, false)
    private lateinit var fixtures: File

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        fixtures = File(targetContext.filesDir, "device-acceptance/${UUID.randomUUID()}").apply { mkdirs() }
        val report = JSONObject()
        try {
            val am = targetContext.getSystemService(Activity.ACTIVITY_SERVICE) as ActivityManager
            report.put("device", JSONObject().apply {
                put("manufacturer", Build.MANUFACTURER); put("model", Build.MODEL)
                put("api", Build.VERSION.SDK_INT); put("android", Build.VERSION.RELEASE)
                put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
                put("memoryClassMiB", am.memoryClass); put("lowRam", am.isLowRamDevice)
                put("nativeSolverAvailable", NativeEngineProbe.available)
            })
            val source = jpeg("source.jpg", 1600, 1000)
            case("mixed-photo-raw-fits-import-preserves-formats") {
                val files = listOf(source, fits(), rawPreview(source.readBytes()))
                val uris = files.map {
                    androidx.core.content.FileProvider.getUriForFile(targetContext, "${targetContext.packageName}.acceptance.files", it)
                }
                val importer = com.starcam.astro.ui.home.PhotoImportViewModel(targetContext.applicationContext as android.app.Application)
                try {
                    runOnMainSync { importer.importPhotos(uris) }
                    val deadline = SystemClock.elapsedRealtime() + 15000
                    while (!importer.state.finished && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
                    val state = importer.state
                    check(state.finished && state.failed == 0 && state.paths.size == 3)
                    state.paths.zip(files).forEach { (path, original) ->
                        check(File(path).extension == original.extension)
                        check(File(path).readBytes().contentEquals(original.readBytes()))
                        requireNotNull(com.starcam.astro.util.ImageUtils.decodeSampledBitmap(path)).useBitmap { bitmap ->
                            check(bitmap.width > 0 && bitmap.height > 0)
                        }
                    }
                } finally { runOnMainSync { importer.cancel() } }
            }
            case("mixed-import-over-limit-rejects-entire-selection") {
                val uri = androidx.core.content.FileProvider.getUriForFile(targetContext, "${targetContext.packageName}.acceptance.files", source)
                val importer = com.starcam.astro.ui.home.PhotoImportViewModel(targetContext.applicationContext as android.app.Application)
                runOnMainSync { importer.importPhotos(List(10) { uri }) }
                check(importer.state.finished && importer.state.overLimit && importer.state.paths.isEmpty())
                runOnMainSync { importer.cancel() }
            }
            case("original-size-jpeg-roundtrip") {
                val out = export(source, original = true)
                savedBitmap(out).useBitmap { bmp ->
                    check(bmp.width == 1600 && bmp.height == 1000)
                    check(Color.red(bmp.getPixel(100, 100)) > 180)
                }
            }
            case("preview-size-is-independent") {
                val out = export(source, original = false)
                savedBitmap(out).useBitmap { check(it.width == 400 && it.height == 250) }
            }
            for (orientation in 1..8) {
                case("exif-$orientation-bitmap-orientation") {
                    val file = jpeg("orientation-$orientation.jpg", 800, 600)
                    ExifInterface(file).apply {
                        setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString()); saveAttributes()
                    }
                    val out = export(file, original = true)
                    savedBitmap(out).useBitmap { bmp ->
                        val swapped = orientation >= 5
                        check(bmp.width == if (swapped) 600 else 800)
                        check(bmp.height == if (swapped) 800 else 600)
                        // 左上红色象限在 EXIF 八方向下应落到的已知输出角。
                        val corner = when (orientation) {
                            2, 6 -> (bmp.width - 40) to 40
                            3, 7 -> (bmp.width - 40) to (bmp.height - 40)
                            4, 8 -> 40 to (bmp.height - 40)
                            else -> 40 to 40
                        }
                        val color = bmp.getPixel(corner.first, corner.second)
                        check(Color.red(color) > 180 && Color.green(color) < 80 && Color.blue(color) < 80)
                    }
                }
            }
            case("online-rotated-photo-original-export") {
                val file = jpeg("online-portrait.jpg", 800, 600)
                ExifInterface(file).apply { setAttribute(ExifInterface.TAG_ORIENTATION, "6"); saveAttributes() }
                val out = export(file, original = true, online = true)
                savedBitmap(out).useBitmap { check(it.width == 600 && it.height == 800) }
            }
            case("layer-flags-change-actual-canvas-output") {
                val plain = savedBitmap(export(source, original = true))
                try {
                    savedBitmap(export(source, original = true, flags = LayerFlags.LINES_ONLY)).useBitmap { annotated ->
                        var differences = 0
                        for (y in 0 until plain.height step 4) for (x in 0 until plain.width step 4) {
                            val a = plain.getPixel(x, y); val b = annotated.getPixel(x, y)
                            if (kotlin.math.abs(Color.red(a) - Color.red(b)) + kotlin.math.abs(Color.green(a) - Color.green(b)) + kotlin.math.abs(Color.blue(a) - Color.blue(b)) > 30) differences++
                        }
                        check(differences > 20) { "Enabled constellation lines must change the saved pixels" }
                    }
                } finally { plain.recycle() }
            }
            case("fits-full-dimensions") {
                val file = fits()
                val info = runBlocking { PhotoExporter.inspect(targetContext, file.path) }
                check(info.kind == PhotoSourceKind.FITS && info.width == 320 && info.height == 240)
                savedBitmap(export(file, original = true)).useBitmap { check(it.width == 320 && it.height == 240) }
            }
            case("raw-embedded-preview-full-dimensions") {
                val file = rawPreview(source.readBytes())
                val info = runBlocking { PhotoExporter.inspect(targetContext, file.path) }
                check(info.kind == PhotoSourceKind.RAW_PREVIEW && info.width == 1600 && info.height == 1000)
                savedBitmap(export(file, original = true)).useBitmap { check(it.width == 1600 && it.height == 1000) }
            }
            case("missing-source-original-is-rejected") {
                expectFailure(PhotoExportFailure.SOURCE_MISSING) { export(File(fixtures, "missing.jpg"), true) }
            }
            case("missing-source-preview-still-saves") {
                savedBitmap(export(File(fixtures, "missing.jpg"), false)).useBitmap { check(it.width == 400) }
            }
            case("legacy-unknown-pixel-basis-is-rejected") {
                expectFailure(PhotoExportFailure.UNKNOWN_BASIS) { export(source, true, online = null) }
            }
            case("oversized-source-rejected-before-pixel-decode") {
                val bytes = source.readBytes()
                var i = 2
                var patched = false
                while (i + 9 < bytes.size) {
                    if (bytes[i] != 0xff.toByte()) { i++; continue }
                    val marker = bytes[i + 1].toInt() and 255
                    if (marker in listOf(0xc0, 0xc1, 0xc2)) {
                        // 合成超大尺寸头，不分配 20000×20000 的位图；预算应在真正解码前拒绝。
                        bytes[i + 5] = 0x4e; bytes[i + 6] = 0x20
                        bytes[i + 7] = 0x4e; bytes[i + 8] = 0x20
                        patched = true; break
                    }
                    val len = ((bytes[i + 2].toInt() and 255) shl 8) or (bytes[i + 3].toInt() and 255)
                    if (len < 2) break
                    i += 2 + len
                }
                check(patched)
                val file = File(fixtures, "oversized-header.jpg").apply { writeBytes(bytes) }
                expectFailure(PhotoExportFailure.MEMORY_LIMIT) { export(file, true) }
            }
            case("batch-public-photos-solve-without-auto-export") {
                val paths = listOf("photo4963.jpg", "photo4974.jpg").map { name ->
                    File(fixtures, name).apply {
                        targetContext.assets.open("offline_photos/$name").use { input -> outputStream().use { input.copyTo(it) } }
                    }.path
                }
                val batch = BatchViewModel(targetContext.applicationContext as android.app.Application)
                try {
                    runOnMainSync { batch.start(paths, com.starcam.astro.data.SettingsRepository(targetContext)) }
                    val deadline = SystemClock.elapsedRealtime() + 150000
                    while (!batch.finished && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
                    check(batch.finished && batch.counts.solved == 2 && batch.counts.saved == 0) { "Batch counts: ${batch.counts}" }
                } finally { runOnMainSync { batch.cancel() } }
            }
        } catch (e: Throwable) {
            results.put(JSONObject().put("name", "runner").put("status", "failed").put("error", e.toString()))
        }
        val failed = (0 until results.length()).count { results.getJSONObject(it).getString("status") == "failed" }
        report.put("tests", results).put("passed", results.length() - failed).put("failed", failed)
        report.put("scope", "Android Bitmap/Canvas/EXIF/export/storage; no claim of physical camera, AR sensors or native performance validation")
        val file = File(fixtures, "report.json").apply { writeText(report.toString(2)) }
        finish(if (failed == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("stream", "\nDEVICE_ACCEPTANCE_REPORT=${file.relativeTo(targetContext.filesDir).path}\n${report}\n")
        })
    }

    private fun case(name: String, body: () -> Unit) {
        val start = SystemClock.elapsedRealtime()
        val result = JSONObject().put("name", name)
        try { body(); result.put("status", "passed") }
        catch (e: Throwable) { result.put("status", "failed").put("error", e.toString()) }
        result.put("elapsedMs", SystemClock.elapsedRealtime() - start)
        result.put("nativeHeapAfterBytes", Debug.getNativeHeapAllocatedSize())
        results.put(result)
        sendStatus(0, Bundle().apply { putString("stream", "${name}: ${result.getString("status")}\n") })
    }

    private fun export(file: File, original: Boolean, online: Boolean? = false, flags: LayerFlags = noLayers): ExportedPhoto = runBlocking {
        val solveW = if (online == true) 250 else 400
        val solveH = if (online == true) 400 else 250
        val solve = SolveResult(66.0, 20.0, 288.0, 0.0, -1, solveW, solveH,
            wcs = WcsTransform(solveW / 2.0 + .5, solveH / 2.0 + .5, 66.0, 20.0, -.08, 0.0, 0.0, .08))
        val input = PhotoExportInput(file.path, solve, online, emptyList(), 400, 250)
        PhotoExporter.save(targetContext, input, flags, false, original) {
            check(!original) { "Original export must not enlarge the preview" }
            Bitmap.createBitmap(400, 250, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        }
    }

    private fun savedBitmap(saved: ExportedPhoto): Bitmap {
        val name = saved.location.substringAfterLast('/')
        val bitmap = if (Build.VERSION.SDK_INT >= 29) {
            val resolver = targetContext.contentResolver
            resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.Images.Media.DISPLAY_NAME}=?", arrayOf(name), null)?.use { c ->
                check(c.moveToFirst()) { "Saved photo missing from MediaStore" }
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            }
        } else {
            BitmapFactory.decodeFile(File(targetContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "StarCam/$name").path)
        }
        return requireNotNull(bitmap).also { check(it.width == saved.width && it.height == saved.height) }
    }

    private inline fun Bitmap.useBitmap(body: (Bitmap) -> Unit) { try { body(this) } finally { recycle() } }
    private fun expectFailure(reason: PhotoExportFailure, block: () -> Unit) {
        try { block(); error("Expected $reason") }
        catch (e: PhotoExportException) { check(e.reason == reason) { "Expected $reason, got ${e.reason}" } }
    }

    private fun jpeg(name: String, w: Int, h: Int): File {
        val file = File(fixtures, name)
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).useBitmap { bmp ->
            val canvas = Canvas(bmp); val paint = Paint()
            val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
            for (index in colors.indices) {
                paint.color = colors[index]
                val x = (index % 2) * (w / 2f); val y = (index / 2) * (h / 2f)
                canvas.drawRect(x, y, x + w / 2f, y + h / 2f, paint)
            }
            file.outputStream().use { check(bmp.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
        }
        return file
    }

    private fun fits(): File {
        val cards = listOf("SIMPLE  =                    T", "BITPIX  =                    8", "NAXIS   =                    2", "NAXIS1  =                  320", "NAXIS2  =                  240", "END")
        val header = cards.joinToString("") { it.padEnd(80) }.padEnd(2880).toByteArray(Charsets.US_ASCII)
        return File(fixtures, "gradient.fits").apply {
            outputStream().use { out -> out.write(header); out.write(ByteArray(320 * 240) { (it % 256).toByte() }) }
        }
    }

    private fun rawPreview(jpeg: ByteArray): File {
        val buffer = ByteBuffer.allocate(38 + jpeg.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8).putShort(2)
        buffer.putShort(0x0201).putShort(4).putInt(1).putInt(38)
        buffer.putShort(0x0202).putShort(4).putInt(1).putInt(jpeg.size)
        buffer.putInt(0).put(jpeg)
        return File(fixtures, "embedded.dng").apply { writeBytes(buffer.array()) }
    }
}
