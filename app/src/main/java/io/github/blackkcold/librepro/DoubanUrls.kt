package io.github.blackkcold.librepro

import java.net.URLEncoder

/** 纯函数：Query → 豆瓣 URL / 人类可读名字。放在这里是为了能 JVM 单测。 */
object DoubanUrls {

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun search(q: Query): String {
        val tags = (q.tags + listOfNotNull(q.year)).joinToString(",")
        val min = if (q.minRate > 0) q.minRate else 0.0
        return "https://movie.douban.com/j/new_search_subjects?sort=${q.sort.code}" +
            "&range=$min,10&tags=${enc(tags)}&start=${q.start}"
    }

    /** 「悬疑 · 韩国 · 2025 · 9分+」这种名字，直接拿来当 chip 文案 */
    fun label(q: Query): String {
        val parts = ArrayList<String>()
        parts += q.tags
        if (q.year != null) parts += q.year
        if (q.minRate >= 9.0) parts += "9分+"
        else if (q.minRate > 0) parts += "${q.minRate}分+"
        if (parts.isEmpty()) parts += "全部"
        return parts.joinToString(" · ")
    }
}
