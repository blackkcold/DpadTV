package io.github.blackkcold.librepro

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
        sort: Sort = Sort.RATING,
    ): List<MovieItem> {
        ensureLoaded()
        if (!available) return emptyList()
        // 年份标签（2025/2024…）的成员关系是「这一年」的权威信号：
        // 条目的 year 字段只覆盖被年份组合抓到的部分（实测 453/1601），
        // 单看字段会让带年份的查询大面积落空。
        val yearIds = year?.takeIf { it.isNotEmpty() }?.let { idsByTag[it]?.toSet() }
        return IndexCore.query(idsByTag, itemsById, tags, year, minRate, start, size, yearIds, sort)
    }

    /** 这个标签在索引里有多少条：用来判断「本地够不够，不够再联网」 */
    fun tagCount(tag: String): Int = idsByTag[tag]?.size ?: 0

    fun hasTag(tag: String): Boolean = idsByTag[tag]?.isNotEmpty() == true

    /**
     * 按**标签原始顺序**取：TOP250 / 豆列这类策展榜的顺序就是语义，
     * 不能像搜索榜那样按评分重排（否则第 1 名和第 200 名混在一起）。
     */
    fun queryOrdered(tag: String, start: Int = 0, size: Int = 50): List<MovieItem> {
        ensureLoaded()
        if (!available) return emptyList()
        return IndexCore.ordered(idsByTag, itemsById, tag, start, size)
    }

    /** 索引里能本地回答的年份（有年份标签就有成员） */
    fun yearsAvailable(): Set<String> = idsByTag.keys.filter { it.matches(Regex("^(19|20)\\d{2}$")) }.toSet()
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

    /** 按标签原始顺序取（策展榜的顺序就是语义）；纯函数，单测覆盖 */
    fun ordered(
        idsByTag: Map<String, List<String>>,
        itemsById: Map<String, MovieItem>,
        tag: String,
        start: Int,
        size: Int,
    ): List<MovieItem> {
        val ids = idsByTag[tag] ?: return emptyList()
        if (start >= ids.size || size <= 0) return emptyList()
        return ids.subList(start, minOf(start + size, ids.size)).mapNotNull { itemsById[it] }
    }

    fun query(
        idsByTag: Map<String, List<String>>,
        itemsById: Map<String, MovieItem>,
        tags: List<String>,
        year: String?,
        minRate: Double,
        start: Int,
        size: Int,
        yearIds: Set<String>? = null,
        sort: Sort = Sort.RATING,
    ): List<MovieItem> {
        // 没有标签 = 一次「全部条目」查询。索引给不出有意义的排序，
        // 直接交回调用方走网络，别把 1600 条全库当成一次命中（旧版定时炸弹）。
        if (tags.isEmpty()) return emptyList()
        // 索引里缺任何一个标签 → 直接返回空，让调用方走网络。
        // 不能退化成"全部条目"：部分索引会因此把《千与千寻》当成"悬疑片"返回。
        val missing = tags.filter { it !in idsByTag }
        if (missing.isNotEmpty()) return emptyList()
        val known = tags.mapNotNull { t -> idsByTag[t]?.let { t to it } }
        val base: Collection<String> = run {
            var acc: Set<String>? = null
            for ((_, ids) in known) {
                val s = ids.toSet()
                acc = acc?.let { it intersect s } ?: s
            }
            acc ?: emptySet()
        }
        val filtered = base.asSequence()
            .mapNotNull { id -> itemsById[id]?.let { id to it } }
            .filter { (_, item) -> minRate <= 0.0 || item.rating >= minRate }
            .filter { (id, item) ->
                year.isNullOrEmpty() ||
                    item.year == year ||
                    item.title.contains(year) ||
                    yearIds?.contains(id) == true
            }
            .sortedWith(ordering(sort))
            .map { it.second }
            .toList()
        if (start <= 0 && size >= filtered.size) return filtered
        if (start >= filtered.size) return emptyList()
        return filtered.subList(start, minOf(start + size, filtered.size))
    }

    /**
     * 排序：**必须全序**（每个分支都拿片名兜底），否则翻页时同分条目的相对顺序
     * 可能变，第 2 页会和第 1 页重叠或漏项。
     *
     *  · RATING  评分降序（用户预期最直接）
     *  · HOT     按 id 稳定哈希打散 —— 索引没有热度维度，但至少和「9 分+」不重样
     *  · LATEST  年份降序（空年份排最后）→ 评分降序
     */
    private fun ordering(sort: Sort): Comparator<Pair<String, MovieItem>> = when (sort) {
        Sort.RATING -> compareByDescending<Pair<String, MovieItem>> { it.second.rating }
            .thenBy { it.second.title }
        Sort.HOT -> compareBy<Pair<String, MovieItem>> { it.first.hashCode() }
            .thenBy { it.second.title }
        Sort.LATEST -> compareByDescending<Pair<String, MovieItem>> { it.second.year }
            .thenByDescending { it.second.rating }
            .thenBy { it.second.title }
    }
}
