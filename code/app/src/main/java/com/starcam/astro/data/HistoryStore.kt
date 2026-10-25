package com.starcam.astro.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.starcam.astro.astro.Constellations
import com.starcam.astro.astro.SolveResult
import com.starcam.astro.astro.StarCatalogData
import com.starcam.astro.astro.WcsTransform
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/**
 * 识别历史存储（SharedPreferences + JSON，免新依赖）。
 * 上限 [MAX_ENTRIES] 条，新的在前；图片路径指向应用缓存目录，
 * 系统清理缓存后该条目仍保留（打开时按路径失效提示）。
 */
object HistoryStore {

    private const val PREFS = "starcam_history"
    private const val KEY = "entries"
    private const val MAX_ENTRIES = 50

    data class Entry(
        val timestamp: Long,
        val imagePath: String,
        val raDeg: Double,
        val decDeg: Double,
        val fovDeg: Double,
        val engine: String,
        val constellation: String,
        // WCS & 求解结果元数据（用于历史记录 0ms 即开，免重复求解）
        val pixScaleArcsec: Double? = null,
        val orientationDeg: Double? = null,
        val parity: Int? = null,
        val imageWidth: Int? = null,
        val imageHeight: Int? = null,
        val wcsCrpix1: Double? = null,
        val wcsCrpix2: Double? = null,
        val wcsCrval1: Double? = null,
        val wcsCrval2: Double? = null,
        val wcsCd11: Double? = null,
        val wcsCd12: Double? = null,
        val wcsCd21: Double? = null,
        val wcsCd22: Double? = null,
        val nMatch: Int? = null,
        val logodds: Double? = null,
    ) {
        /** 从存储的元数据还原 [SolveResult]（含 WCS 投影），数据不全返回 null */
        fun toSolveResult(): SolveResult? {
            val w = imageWidth ?: return null
            val h = imageHeight ?: return null
            val scale = pixScaleArcsec ?: return null
            val orient = orientationDeg ?: 0.0
            val par = parity ?: 1
            val wcs = if (
                wcsCrpix1 != null && wcsCrpix2 != null &&
                wcsCrval1 != null && wcsCrval2 != null &&
                wcsCd11 != null && wcsCd12 != null &&
                wcsCd21 != null && wcsCd22 != null
            ) {
                WcsTransform(
                    crpix1 = wcsCrpix1,
                    crpix2 = wcsCrpix2,
                    crval1 = wcsCrval1,
                    crval2 = wcsCrval2,
                    cd11 = wcsCd11,
                    cd12 = wcsCd12,
                    cd21 = wcsCd21,
                    cd22 = wcsCd22,
                )
            } else null
            return SolveResult(
                raDeg = raDeg,
                decDeg = decDeg,
                pixScaleArcsec = scale,
                orientationDeg = orient,
                parity = par,
                imageWidth = w,
                imageHeight = h,
                wcs = wcs,
                nMatch = nMatch,
                logodds = logodds,
            )
        }

        companion object {
            /** 便捷工厂方法：从 [SolveResult] 完整打包历史条目 */
            fun fromSolve(
                timestamp: Long,
                imagePath: String,
                solve: SolveResult,
                engine: String,
                constellation: String,
            ): Entry {
                val wcs = solve.wcs
                return Entry(
                    timestamp = timestamp,
                    imagePath = imagePath,
                    raDeg = solve.raDeg,
                    decDeg = solve.decDeg,
                    fovDeg = solve.fieldWidthDeg,
                    engine = engine,
                    constellation = constellation,
                    pixScaleArcsec = solve.pixScaleArcsec,
                    orientationDeg = solve.orientationDeg,
                    parity = solve.parity,
                    imageWidth = solve.imageWidth,
                    imageHeight = solve.imageHeight,
                    wcsCrpix1 = wcs?.crpix1,
                    wcsCrpix2 = wcs?.crpix2,
                    wcsCrval1 = wcs?.crval1,
                    wcsCrval2 = wcs?.crval2,
                    wcsCd11 = wcs?.cd11,
                    wcsCd12 = wcs?.cd12,
                    wcsCd21 = wcs?.cd21,
                    wcsCd22 = wcs?.cd22,
                    nMatch = solve.nMatch,
                    logodds = solve.logodds,
                )
            }
        }
    }

    /**
     * 将图片文件持久化复制到应用私有目录 `filesDir/history`。
     * 防止系统或用户清理缓存目录 `cacheDir` 导致历史条目图片失效。
     */
    private fun persistImageForHistory(context: Context, originalPath: String): String {
        return try {
            val src = File(originalPath)
            if (!src.exists()) return originalPath
            val historyDir = File(context.filesDir, "history").apply { mkdirs() }
            if (src.parentFile?.canonicalPath == historyDir.canonicalPath) {
                return src.absolutePath
            }
            val targetName = "${System.currentTimeMillis()}_${src.name}"
            val targetFile = File(historyDir, targetName)
            src.copyTo(targetFile, overwrite = true)
            targetFile.absolutePath
        } catch (_: Exception) {
            originalPath
        }
    }

    /**
     * §0.92：写入必须串行化。
     *
     * 三条写路径（单张识别在结果页、批量识别在 IO 线程、历史页删除）都会
     * load→改→写回；并发时后写覆盖先写，历史条目会**静默丢失**。
     * 底层是 SharedPreferences（`edit().apply()` 自身原子），所以只需保证
     * 「读-改-写」整段互斥：加在对象上的监视器即可（同 JVM 内串行）。
     *
     * 支持去重（同一图片路径不重复插入）与缓存保护（自动转存至 filesDir/history）。
     */
    @Synchronized
    fun save(context: Context, entry: Entry): Entry {
        val persistentPath = persistImageForHistory(context, entry.imagePath)
        val persistentEntry = if (persistentPath != entry.imagePath) {
            entry.copy(imagePath = persistentPath)
        } else {
            entry
        }
        val all = load(context)
        val newList = ArrayList<Entry>(MAX_ENTRIES)
        newList.add(persistentEntry)
        for (e in all) {
            if (newList.size >= MAX_ENTRIES) break
            // 去重：同一图片路径（含转存前与转存后路径）跳过
            if (e.imagePath == persistentEntry.imagePath || e.imagePath == entry.imagePath) continue
            newList.add(e)
        }
        write(context, newList)
        return persistentEntry
    }

    /** 删除单条（§0.40）；§0.92：与 [save] 共用同一把锁，避免丢更新；清理转存文件 */
    @Synchronized
    fun remove(context: Context, timestamp: Long) {
        val all = load(context)
        val toRemove = all.find { it.timestamp == timestamp }
        val remaining = all.filterNot { it.timestamp == timestamp }
        write(context, remaining)
        if (toRemove != null) {
            val file = File(toRemove.imagePath)
            val historyDir = File(context.filesDir, "history")
            if (file.parentFile?.canonicalPath == historyDir.canonicalPath) {
                if (remaining.none { it.imagePath == toRemove.imagePath }) {
                    runCatching { file.delete() }
                }
            }
        }
    }

    private fun write(context: Context, entries: List<Entry>) {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(
                JSONObject().apply {
                    put("ts", e.timestamp)
                    put("path", e.imagePath)
                    put("ra", e.raDeg)
                    put("dec", e.decDeg)
                    put("fov", e.fovDeg)
                    put("engine", e.engine)
                    put("con", e.constellation)
                    e.pixScaleArcsec?.let { put("scale", it) }
                    e.orientationDeg?.let { put("orient", it) }
                    e.parity?.let { put("parity", it) }
                    e.imageWidth?.let { put("w", it) }
                    e.imageHeight?.let { put("h", it) }
                    e.wcsCrpix1?.let { put("crpix1", it) }
                    e.wcsCrpix2?.let { put("crpix2", it) }
                    e.wcsCrval1?.let { put("crval1", it) }
                    e.wcsCrval2?.let { put("crval2", it) }
                    e.wcsCd11?.let { put("cd11", it) }
                    e.wcsCd12?.let { put("cd12", it) }
                    e.wcsCd21?.let { put("cd21", it) }
                    e.wcsCd22?.let { put("cd22", it) }
                    e.nMatch?.let { put("nmatch", it) }
                    e.logodds?.let { put("logodds", it) }
                },
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun load(context: Context): List<Entry> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = try {
            JSONArray(prefs.getString(KEY, "[]") ?: "[]")
        } catch (e: Exception) {
            return emptyList()
        }
        val out = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            try {
                val o = arr.getJSONObject(i)
                out.add(
                    Entry(
                        timestamp = o.getLong("ts"),
                        imagePath = o.getString("path"),
                        raDeg = o.getDouble("ra"),
                        decDeg = o.getDouble("dec"),
                        fovDeg = o.getDouble("fov"),
                        engine = o.optString("engine", ""),
                        constellation = o.optString("con", ""),
                        pixScaleArcsec = if (o.has("scale") && !o.isNull("scale")) o.getDouble("scale") else null,
                        orientationDeg = if (o.has("orient") && !o.isNull("orient")) o.getDouble("orient") else null,
                        parity = if (o.has("parity") && !o.isNull("parity")) o.getInt("parity") else null,
                        imageWidth = if (o.has("w") && !o.isNull("w")) o.getInt("w") else null,
                        imageHeight = if (o.has("h") && !o.isNull("h")) o.getInt("h") else null,
                        wcsCrpix1 = if (o.has("crpix1") && !o.isNull("crpix1")) o.getDouble("crpix1") else null,
                        wcsCrpix2 = if (o.has("crpix2") && !o.isNull("crpix2")) o.getDouble("crpix2") else null,
                        wcsCrval1 = if (o.has("crval1") && !o.isNull("crval1")) o.getDouble("crval1") else null,
                        wcsCrval2 = if (o.has("crval2") && !o.isNull("crval2")) o.getDouble("crval2") else null,
                        wcsCd11 = if (o.has("cd11") && !o.isNull("cd11")) o.getDouble("cd11") else null,
                        wcsCd12 = if (o.has("cd12") && !o.isNull("cd12")) o.getDouble("cd12") else null,
                        wcsCd21 = if (o.has("cd21") && !o.isNull("cd21")) o.getDouble("cd21") else null,
                        wcsCd22 = if (o.has("cd22") && !o.isNull("cd22")) o.getDouble("cd22") else null,
                        nMatch = if (o.has("nmatch") && !o.isNull("nmatch")) o.getInt("nmatch") else null,
                        logodds = if (o.has("logodds") && !o.isNull("logodds")) o.getDouble("logodds") else null,
                    ),
                )
            } catch (e: Exception) {
                // 单条损坏跳过
            }
        }
        return out
    }

    /** §0.92：与 [save]/[remove] 同一把锁 —— 避免「清空」与「保存」交错后条目复活；清空持久化图片目录 */
    @Synchronized
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
        val historyDir = File(context.filesDir, "history")
        runCatching { historyDir.deleteRecursively() }
    }

    /**
     * 导出识别历史为 CSV（§0.40）：API 29+ 经 MediaStore 写入系统「下载」目录；
     * API 26~28 写入**应用外部私有目录**（§0.78 修正 —— 公共目录需
     * WRITE_EXTERNAL_STORAGE，本应用未声明、也不必为此声明写权限）。
     * 返回展示用路径描述，失败返回 null。
     */
    fun exportCsv(context: Context, entries: List<Entry>): String? {
        val sb = StringBuilder("timestamp,constellation,raDeg,decDeg,fovDeg,engine,imagePath\n")
        for (e in entries) {
            sb.append(e.timestamp).append(',')
                .append(csv(e.constellation)).append(',')
                .append(e.raDeg).append(',')
                .append(e.decDeg).append(',')
                .append(e.fovDeg).append(',')
                .append(csv(e.engine)).append(',')
                .append(csv(e.imagePath)).append('\n')
        }
        val fileName = "starcam_history_${System.currentTimeMillis()}.csv"
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values,
                ) ?: return null
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(sb.toString().toByteArray())
                }
                "下载目录/$fileName"
            } else {
                // §0.78：API 26~28 写公共下载目录需要 WRITE_EXTERNAL_STORAGE，
                // 而 manifest 只声明了 READ_EXTERNAL_STORAGE —— 必然抛 SecurityException，
                // 再被外层 catch 吞成「导出失败」，功能在 Android 8/8.1 上完全不可用。
                // 改写到**应用外部私有目录**：免权限，且 Android 8/9 上该路径
                // 对文件管理器可见（与 ImageUtils 保存图片的做法一致）。
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: context.filesDir
                dir.mkdirs()
                File(dir, fileName).writeText(sb.toString())
                dir.absolutePath + "/" + fileName
            }
        } catch (e: Exception) {
            null
        }
    }

    /** CSV 字段转义（逗号/引号/换行包裹为带引号字段） */
    private fun csv(v: String): String =
        "\"" + v.replace("\"", "\"\"") + "\""

    /** 天球角距（度） */
    internal fun angSep(ra1: Double, dec1: Double, ra2: Double, dec2: Double): Double {
        val r = PI / 180.0
        val s = sin(dec1 * r) * sin(dec2 * r) +
            cos(dec1 * r) * cos(dec2 * r) * cos((ra1 - ra2) * r)
        return acos(s.coerceIn(-1.0, 1.0)) / r
    }

    /** 天区中心最近的星座：取 5° 内最近星表星的所属星座（支持中英双语，跳过无星座的增补星 §0.42） */
    fun nearestConstellation(raDeg: Double, decDeg: Double, isEnglish: Boolean = false): String {
        var best: Pair<Double, String>? = null
        for (star in StarCatalogData.stars) {
            if (star.con.isEmpty()) continue
            val d = angSep(raDeg, decDeg, star.ra, star.dec)
            if (best == null || d < best.first) best = d to star.con
        }
        val con = best?.second ?: return ""
        return Constellations.name(con, isEnglish)
    }

    /** 天区中心最近的星座（中文名，兼容旧接口）：取 5° 内最近星表星的所属星座 */
    fun nearestConstellationZh(raDeg: Double, decDeg: Double): String =
        nearestConstellation(raDeg, decDeg, isEnglish = false)
}
