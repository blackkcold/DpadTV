package com.dpadtv.client

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 用户自建的榜单 chip（「存为榜单」）。
 * 只存"查询条件"，不存结果 —— 结果由 CollectionRepo 按需拉 + 缓存。
 */
class ChipStore(ctx: Context) {

    private val sp = ctx.applicationContext.getSharedPreferences("dpadtv_chips", Context.MODE_PRIVATE)

    data class Chip(val id: String, val label: String, val group: String, val query: Query)

    fun list(group: String): List<Chip> = runCatching {
        val arr = JSONArray(sp.getString("chips_" + group, "[]"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            Chip(
                id = o.getString("id"),
                label = o.getString("label"),
                group = group,
                query = Query(
                    tags = o.getJSONArray("tags").let { t -> (0 until t.length()).map { t.getString(it) } },
                    year = o.optString("year").ifEmpty { null },
                    minRate = o.optDouble("min", 0.0),
                    sort = Sort.values().firstOrNull { it.code == o.optString("sort") } ?: Sort.RATING,
                ),
            )
        }
    }.getOrDefault(emptyList())

    /** 同名不重复添加；返回是否新增 */
    fun add(group: String, label: String, q: Query): Boolean {
        val cur = list(group).toMutableList()
        if (cur.any { it.label == label }) return false
        cur.add(Chip("c_" + System.currentTimeMillis(), label, group, q))
        save(group, cur)
        return true
    }

    fun remove(group: String, id: String) {
        save(group, list(group).filterNot { it.id == id })
    }

    private fun save(group: String, chips: List<Chip>) {
        val arr = JSONArray()
        chips.forEach { c ->
            arr.put(JSONObject().apply {
                put("id", c.id); put("label", c.label)
                put("tags", JSONArray(c.query.tags))
                put("year", c.query.year ?: "")
                put("min", c.query.minRate)
                put("sort", c.query.sort.code)
            })
        }
        sp.edit().putString("chips_" + group, arr.toString()).apply()
    }
}
