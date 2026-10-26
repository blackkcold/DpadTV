package io.github.blackkcold.librepro

/** 统一条目：来自任意豆瓣榜单。点击后按片名走服务端聚合搜索，不依赖豆瓣 ID。 */
data class MovieItem(
    val title: String,
    val cover: String = "",
    val rating: Double = 0.0,
    val doubanId: String = "",
    val year: String = "",
    /** 来源角标：TOP250 / 9分+ / 2025 / 奥斯卡 —— 首页最多标一个，说明"它为什么在这个位置" */
    val badge: String = "",
    /** 来自哪个 CollectionRef，用于合并时做配额 */
    val from: String = "",
)

enum class RefKind { HOT, SEARCH, TOP250, DOULIST }

/** 一个预设榜单 = 一次豆瓣查询。加榜只是加一条配置。 */
data class CollectionRef(
    val id: String,
    val label: String,
    val badge: String = "",
    val kind: RefKind = RefKind.SEARCH,
    val tags: List<String> = emptyList(),
    val year: String? = null,
    val minRate: Double = 0.0,
    val doulistId: String? = null,
    /** 合并打分时的来源权重：Top250/豆列最高，热门最低 */
    val weight: Double = 0.7,
)

/** 首页一行 = 若干榜单合并去重后的结果 */
data class Shelf(val id: String, val title: String, val refs: List<CollectionRef>, val size: Int = 18)

enum class Sort(val label: String, val code: String) {
    RATING("评分", "T"),
    HOT("热门", "S"),
    LATEST("最新", "U"),
}

/**
 * 浏览页的一次查询：标签（可多个）+ 年份 + 最低分 + 排序 + 起始偏移。
 * chip 与筛选器最终都折叠成这一个对象，URL 只有一处拼装（DoubanUrls）。
 */
data class Query(
    val tags: List<String> = emptyList(),
    val year: String? = null,
    val minRate: Double = 0.0,
    val sort: Sort = Sort.RATING,
    val start: Int = 0,
) {
    /** 用于缓存键与「存为榜单」的稳定标识 */
    fun key(): String = "${tags.joinToString("+")}|${year ?: ""}|$minRate|${sort.code}"
}

/** chip（预设榜）落到查询条件：热门榜走 HOT 排序，其余按评分 */
fun CollectionRef.toQuery(): Query = when (kind) {
    RefKind.HOT -> Query(tags = listOf("电影"), sort = Sort.HOT)
    RefKind.SEARCH -> Query(tags = tags, year = year, minRate = minRate, sort = Sort.RATING)
    RefKind.TOP250 -> Query(tags = listOf("电影"), minRate = 8.5, sort = Sort.RATING)
    RefKind.DOULIST -> Query(tags = listOf("电影"), minRate = 0.0, sort = Sort.RATING)
}
