package com.starcam.astro.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.starcam.astro.astro.Constellations
import com.starcam.astro.astro.StarCatalogData
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
    )

    fun save(context: Context, entry: Entry) {
        val all = load(context)
        val newList = ArrayList<Entry>(MAX_ENTRIES)
        newList.add(entry)
        for (e in all) {
            if (newList.size >= MAX_ENTRIES) break
            newList.add(e)
        }
        write(context, newList)
    }

    /** 删除单条（§0.40） */
    fun remove(context: Context, timestamp: Long) {
        write(context, load(context).filterNot { it.timestamp == timestamp })
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
                    ),
                )
            } catch (e: Exception) {
                // 单条损坏跳过
            }
        }
        return out
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
    }

    /**
     * 导出识别历史为 CSV（§0.40）：API 29+ 写入系统"下载"目录，
     * 旧版写入公共下载目录；返回展示用路径描述，失败返回 null。
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
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "",
                ).apply { mkdirs() }
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
    private fun angSep(ra1: Double, dec1: Double, ra2: Double, dec2: Double): Double {
        val r = PI / 180.0
        val s = sin(dec1 * r) * sin(dec2 * r) +
            cos(dec1 * r) * cos(dec2 * r) * cos((ra1 - ra2) * r)
        return acos(s.coerceIn(-1.0, 1.0)) / r
    }

    /** 天区中心最近的星座（中文名）：取 5° 内最近星表星的所属星座（跳过无星座的增补星 §0.42） */
    fun nearestConstellationZh(raDeg: Double, decDeg: Double): String {
        var best: Pair<Double, String>? = null
        for (star in StarCatalogData.stars) {
            if (star.con.isEmpty()) continue
            val d = angSep(raDeg, decDeg, star.ra, star.dec)
            if (best == null || d < best.first) best = d to star.con
        }
        val con = best?.second ?: return ""
        return Constellations.zhName(con)
    }
}
