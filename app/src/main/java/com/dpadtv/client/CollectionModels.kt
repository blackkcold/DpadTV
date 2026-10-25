package com.dpadtv.client

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
