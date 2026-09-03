package com.starcam.astro.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 识别打点统计（§0.40）：本地记录每次识别的结果/引擎/耗时（脱敏，无照片、
 * 无天区坐标），用于成功率与性能趋势。SharedPreferences + JSON，上限 500 条。
 */
object StatsStore {

    private const val PREFS = "starcam_stats"
    private const val KEY = "records"
    private const val MAX_RECORDS = 500

    data class Record(
        val timestamp: Long,
        val ok: Boolean,
        val engine: String,
        val ms: Long,
    )

    fun record(context: Context, ok: Boolean, engine: String, ms: Long) {
        val all = load(context)
        val newList = ArrayList<Record>(MAX_RECORDS)
        newList.add(Record(System.currentTimeMillis(), ok, engine, ms))
        for (r in all) {
            if (newList.size >= MAX_RECORDS) break
            newList.add(r)
        }
        val arr = JSONArray()
        for (r in newList) {
            arr.put(
                JSONObject().apply {
                    put("ts", r.timestamp)
                    put("ok", r.ok)
                    put("engine", r.engine)
                    put("ms", r.ms)
                },
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun load(context: Context): List<Record> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = try {
            JSONArray(prefs.getString(KEY, "[]") ?: "[]")
        } catch (e: Exception) {
            return emptyList()
        }
        val out = ArrayList<Record>(arr.length())
        for (i in 0 until arr.length()) {
            try {
                val o = arr.getJSONObject(i)
                out.add(
                    Record(
                        timestamp = o.getLong("ts"),
                        ok = o.optBoolean("ok", false),
                        engine = o.optString("engine", ""),
                        ms = o.getLong("ms"),
                    ),
                )
            } catch (e: Exception) {
                // 单条损坏跳过
            }
        }
        return out
    }

    /** 汇总（§0.40）：(总次数, 成功率 0..1, 平均耗时 ms, 最近一次耗时 ms) */
    fun summary(context: Context): Summary {
        val all = load(context)
        if (all.isEmpty()) return Summary(0, 0.0, 0L, 0L)
        val ok = all.count { it.ok }
        val avgMs = all.map { it.ms }.average().toLong()
        return Summary(all.size, ok.toDouble() / all.size, avgMs, all.first().ms)
    }

    data class Summary(val count: Int, val successRate: Double, val avgMs: Long, val lastMs: Long)
}
