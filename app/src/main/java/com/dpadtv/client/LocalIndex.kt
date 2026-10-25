package com.dpadtv.client

import android.content.Context
import org.json.JSONObject
import java.util.zip.GZIPInputStream

/**
 * 本地榜单索引：把「切标签 = 一次豆瓣搜索」换成「切标签 = 本地集合求交」。
 *
 * 为什么这么做：榜单是周级变化的**公共元数据**，不是实时接口。把它预生成成
 * `assets/douban-index.json.gz`（由 `tools/build_index.py` 生成），
 * 交互路径就变成 0 请求、0 限流、离线可用。封面仍走服务端代理，播放地址仍走聚合搜索。
 *
 * 文件结构（见 build_index.py）：
 *   { "meta": {...}, "items": { "<id>": [title, cover, rate, year] },
 *     "tags":  { "日本": ["<id>", ...] } }
 *
 * 过滤逻辑放在 [IndexCore] 里，不依赖 Android，能直接跑 JVM 单测。
 */
class LocalIndex(ctx: Context) {

    private companion object {
        const val ASSET = "douban-index.json.gz"
    }

    private val appCtx = ctx.applicationContext
    private var loaded = false

    private val idsByTag = HashMap<String, List<String>>()
    private val itemsById = HashMap<String, MovieItem>()

    /** 索引生成日期（yyyy-MM-dd），UI 上要标出来，用户才知道数据多新 */
    var builtAt: String = ""
        private set
    var itemCount: Int = 0
        private set

    val available: Boolean get() = loaded && itemCount > 0

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        runCatching {
            // aapt 会把 `*.gz` 资源解开并去掉后缀，所以两种形态都要读：
            // assets/douban-index.json（打包后） / douban-index.json.gz（源码里）
            val text = runCatching {
                appCtx.assets.open("douban-index.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            }.getOrNull() ?: runCatching {
                appCtx.assets.open(ASSET).use { raw ->
                    GZIPInputStream(raw).bufferedReader(Charsets.UTF_8).use { it.readText() }
                }
            }.getOrNull() ?: return@runCatching
            run {
                run {
                    val root = JSONObject(text)
                    builtAt = root.optJSONObject("meta")?.optString("built").orEmpty()
                    val items = root.optJSONObject("items") ?: return@runCatching
                    items.keys().forEach { id ->
                        val a = items.optJSONArray(id) ?: return@forEach
                        val title = a.optString(0)
                        if (title.isEmpty()) return@forEach
                        itemsById[id] = MovieItem(
                            title = title,
                            cover = a.optString(1),
                            rating = a.optString(2).toDoubleOrNull() ?: 0.0,
                            doubanId = id,
                            year = a.optString(3),
                        )
                    }
                    val tagsJson = root.optJSONObject("tags") ?: return@runCatching
                    tagsJson.keys().forEach { tag ->
                        val arr = tagsJson.optJSONArray(tag) ?: return@forEach
                        val ids = ArrayList<String>(arr.length())
                        for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotEmpty() }?.let { ids += it }
                        idsByTag[tag] = ids
                    }
                    itemCount = itemsById.size
                }
            }
        }
    }

    fun query(
        tags: List<String>,
        year: String? = null,
        minRate: Double = 0.0,
        start: Int = 0,
        size: Int = 24,
    ): List<MovieItem> {
        ensureLoaded()
        if (!available) return emptyList()
        return IndexCore.query(idsByTag, itemsById, tags, year, minRate, start, size)
    }

    /** 这个标签在索引里有多少条：用来判断「本地够不够，不够再联网」 */
    fun tagCount(tag: String): Int = idsByTag[tag]?.size ?: 0
}

/**
 * 纯函数过滤层。**不碰 Context / 不碰 JSON**，所以 `LocalIndexTest` 能在 JVM 上直接跑。
 *
 * 语义：
 *  - 多个标签取**交集**（`日本` ∩ `悬疑`）
 *  - 只有年份时按「条目自带年份 或 标题里带年份」匹配
 *  - 评分降序（索引是按评分抓的，别的排序在本地没有信息量）
 */
object IndexCore {

    fun query(
        idsByTag: Map<String, List<String>>,
        itemsById: Map<String, MovieItem>,
        tags: List<String>,
        year: String?,
        minRate: Double,
        start: Int,
        size: Int,
    ): List<MovieItem> {
        // 索引里缺任何一个标签 → 直接返回空，让调用方走网络。
        // 不能退化成"全部条目"：部分索引会因此把《千与千寻》当成"悬疑片"返回。
        val missing = tags.filter { it !in idsByTag }
        if (tags.isNotEmpty() && missing.isNotEmpty()) return emptyList()
        val known = tags.mapNotNull { t -> idsByTag[t]?.let { t to it } }
        val base: Collection<String> = when {
            known.isEmpty() -> itemsById.keys
            else -> {
                var acc: Set<String>? = null
                for ((_, ids) in known) {
                    val s = ids.toSet()
                    acc = acc?.let { it intersect s } ?: s
                }
                acc ?: emptySet()
            }
        }
        val filtered = base.asSequence()
            .mapNotNull { itemsById[it] }
            .filter { minRate <= 0.0 || it.rating >= minRate }
            .filter { year.isNullOrEmpty() || it.year == year || it.title.contains(year) }
            .sortedByDescending { it.rating }
            .toList()
        if (start <= 0 && size >= filtered.size) return filtered
        if (start >= filtered.size) return emptyList()
        return filtered.subList(start, minOf(start + size, filtered.size))
    }
}
