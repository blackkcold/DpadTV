package com.dpadtv.client

/**
 * 首页货架注册表。
 *
 * 原则：**首页只放内容，不放目录**。
 * 一行 = 3–4 个榜单合并去重；想换口味去行尾「查看更多」，
 * 那里有 chip 条 + 筛选器（M2 实现），几十个榜单不占首屏。
 */
object Shelves {

    private const val Y = "2025"

    val movie = Shelf(
        "movie", "电影",
        listOf(
            CollectionRef("mv_hot", "热门", "热门", RefKind.HOT, weight = 0.7),
            CollectionRef("mv_top250", "Top250", "TOP250", RefKind.TOP250, weight = 1.0),
            CollectionRef("mv_rate", "9 分以上", "9分+", RefKind.SEARCH, listOf("电影"), minRate = 9.0, weight = 0.9),
            CollectionRef("mv_new", "$Y 新片", Y, RefKind.SEARCH, listOf("电影"), year = Y, weight = 0.6),
        ),
    )

    val tv = Shelf(
        "tv", "剧集",
        listOf(
            CollectionRef("tv_cn", "国产剧", "国产", RefKind.SEARCH, listOf("国产剧"), year = Y, weight = 0.8),
            CollectionRef("tv_kr", "韩剧 $Y", "韩剧", RefKind.SEARCH, listOf("韩剧"), year = Y, weight = 0.75),
            CollectionRef("tv_jp", "日剧 $Y", "日剧", RefKind.SEARCH, listOf("日剧"), year = Y, weight = 0.75),
            CollectionRef("tv_us", "美剧", "美剧", RefKind.SEARCH, listOf("美剧"), weight = 0.7),
        ),
    )

    val variety = Shelf(
        "variety", "综艺 · 纪录片",
        listOf(
            CollectionRef("va_hot", "综艺热门", "综艺", RefKind.SEARCH, listOf("综艺"), weight = 0.7),
            CollectionRef("va_doc", "纪录片高分", "纪录片", RefKind.SEARCH, listOf("纪录片"), minRate = 8.5, weight = 0.8),
        ),
    )

    /** 榜单中心（M2 的浏览页）用它列 chip，首页不渲染 */
    val presets: List<CollectionRef> = listOf(
        CollectionRef("mv_hot", "热门", "热门", RefKind.HOT, weight = 0.7),
        CollectionRef("mv_top250", "Top250", "TOP250", RefKind.TOP250, weight = 1.0),
        CollectionRef("mv_oscar", "奥斯卡", "奥斯卡", RefKind.DOULIST, doulistId = "240962", weight = 1.0),
        CollectionRef("mv_rate", "9 分以上", "9分+", RefKind.SEARCH, listOf("电影"), minRate = 9.0, weight = 0.9),
        CollectionRef("mv_2025", "2025 新片", "2025", RefKind.SEARCH, listOf("电影"), year = "2025", weight = 0.6),
        CollectionRef("mv_2024", "2024", "2024", RefKind.SEARCH, listOf("电影"), year = "2024", weight = 0.6),
        CollectionRef("mv_cn", "华语", "", RefKind.SEARCH, listOf("华语"), minRate = 7.5, weight = 0.8),
        CollectionRef("mv_kr", "韩国", "", RefKind.SEARCH, listOf("韩国"), minRate = 7.5, weight = 0.75),
        CollectionRef("mv_jp", "日本", "", RefKind.SEARCH, listOf("日本"), minRate = 7.5, weight = 0.75),
        CollectionRef("mv_susp", "悬疑", "", RefKind.SEARCH, listOf("悬疑"), minRate = 7.5, weight = 0.7),
        CollectionRef("tv_2025", "国产剧 2025", "2025", RefKind.SEARCH, listOf("国产剧"), year = "2025", weight = 0.8),
        CollectionRef("tv_kr", "韩剧 2025", "韩剧", RefKind.SEARCH, listOf("韩剧"), year = "2025", weight = 0.75),
        CollectionRef("tv_jp", "日剧 2025", "日剧", RefKind.SEARCH, listOf("日剧"), year = "2025", weight = 0.75),
        CollectionRef("tv_us", "美剧", "美剧", RefKind.SEARCH, listOf("美剧"), weight = 0.7),
        CollectionRef("va_hot", "综艺热门", "综艺", RefKind.SEARCH, listOf("综艺"), weight = 0.7),
        CollectionRef("va_doc", "纪录片高分", "纪录片", RefKind.SEARCH, listOf("纪录片"), minRate = 8.5, weight = 0.8),
    )

    val home: List<Shelf> = listOf(movie, tv, variety)

    fun byId(id: String): Shelf? = home.firstOrNull { it.id == id }
}
