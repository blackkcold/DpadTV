package com.dpadtv.client

import kotlin.math.ceil

/**
 * 把「多个榜单的第 1 页」合成「首页的一行」。
 *
 * 为什么需要它：豆瓣的每个榜都是单一维度，直接平铺会变成几十行目录；
 * 合并后一行既有多样性又有质量下限。三个约束：
 *   1. 标题归一化去重（同一部片在 3 个榜里出现只留一条）
 *   2. 来源权重（Top250/豆列 1.0 > 热门 0.7），避免一行被"热门"刷成流量剧
 *   3. 单榜配额 ≤ 40%，防止某个榜把整行占满
 *
 * 纯函数，无 Android 依赖 —— 用 JVM 单测覆盖（MergerTest）。
 */
object Merger {

    private val YEAR_SUFFIX = Regex("""[（(]\s*(19|20)\d{2}\s*[）)]""")
    private val SEASON_SUFFIX = Regex("""第\s*[0-9一二三四五六七八九十]+\s*季""")
    private val CJK = Regex("""[\u4e00-\u9fa5]""")

    /** 去重用的键：去空白/全角空格，剥掉年份与季数后缀 */
    fun normalize(title: String): String {
        var s = title.replace('\u3000', ' ').trim()
        s = YEAR_SUFFIX.replace(s, "")
        s = SEASON_SUFFIX.replace(s, "")
        s = s.replace(Regex("""\s+"""), "")
        return s.lowercase()
    }

    /**
     * 显示/搜索用的标题：豆瓣常给「肖申克的救赎 The Shawshank Redemption」，
     * 采集站多是纯中文，留着英文名会让聚合搜索落空。
     */
    fun cleanTitle(raw: String): String {
        var s = raw.replace('\u3000', ' ').trim()
        s = s.substringBefore(" / ").trim()
        // 「大侦探 第九季」这种名字直接搜会打空，剥掉季数再交给聚合搜索
        s = SEASON_SUFFIX.replace(s, "").trim()
        // 找到"中文之后第一次出现拉丁字母"的位置，从这里截断
        var cjkSeen = false
        for (i in s.indices) {
            val c = s[i]
            if (CJK.matches(c.toString())) {
                cjkSeen = true
            } else if (cjkSeen && c.isLetter()) {
                return s.substring(0, i).trim().ifEmpty { s }
            }
        }
        return s
    }

    private val ANIMATION_HINTS = listOf("动画", "番剧", "剧场版", "OVA", "动漫", "奥特曼", "假面骑士")

    /** 豆瓣的「日剧/韩剧」标签会混进番剧与剧场版；剧集货架里把它们挑出来 */
    fun isAnimation(title: String): Boolean = ANIMATION_HINTS.any { title.contains(it) }

    private fun freshness(year: String, newest: Int): Double = when {
        year.isEmpty() -> 0.45
        else -> {
            val y = year.toIntOrNull() ?: return 0.45
            when {
                y >= newest -> 1.0
                y == newest - 1 -> 0.85
                y == newest - 2 -> 0.7
                y == newest - 3 -> 0.55
                else -> 0.4
            }
        }
    }

    fun score(item: MovieItem, ref: CollectionRef, newest: Int = 2025): Double {
        val rating = (item.rating / 10.0).coerceIn(0.0, 1.0)
        return 0.5 * rating + 0.3 * ref.weight + 0.2 * freshness(item.year, newest)
    }

    /**
     * @param groups ref -> 该榜拉到的条目（顺序无所谓）
     * @return 合并后的行内容，长度 ≤ size
     */
    fun merge(groups: List<Pair<CollectionRef, List<MovieItem>>>, size: Int, newest: Int = 2025): List<MovieItem> {
        if (size <= 0) return emptyList()
        val best = LinkedHashMap<String, Pair<MovieItem, CollectionRef>>()
        for ((ref, items) in groups) {
            for (raw in items) {
                val title = cleanTitle(raw.title)
                if (title.isEmpty()) continue
                val item = raw.copy(title = title, badge = ref.badge, from = ref.id)
                val key = normalize(title)
                val prev = best[key]
                if (prev == null) {
                    best[key] = item to ref
                } else {
                    // 同一部片：**权重高的榜决定角标**（TOP250 比"9分+"更能解释它为什么在这），评分取两者较高
                    val winner = when {
                        ref.weight > prev.second.weight + 1e-9 -> item to ref
                        ref.weight < prev.second.weight - 1e-9 -> prev
                        item.rating > prev.first.rating + 1e-6 -> item to ref
                        else -> prev
                    }
                    val topRating = maxOf(prev.first.rating, item.rating)
                    best[key] = winner.first.copy(rating = topRating) to winner.second
                }
            }
        }
        val ranked = best.values
            .map { (item, ref) -> Triple(item, ref, score(item, ref, newest)) }
            .sortedWith(compareByDescending<Triple<MovieItem, CollectionRef, Double>> { it.third }
                .thenByDescending { it.first.rating })

        val quota = ceil(size * 0.4).toInt().coerceAtLeast(1)
        val perRef = HashMap<String, Int>()
        val out = ArrayList<MovieItem>(size)
        val overflow = ArrayList<MovieItem>()
        for ((item, ref, _) in ranked) {
            val used = perRef[ref.id] ?: 0
            if (used < quota && out.size < size) {
                out.add(item)
                perRef[ref.id] = used + 1
            } else {
                overflow.add(item)
            }
            if (out.size >= size && overflow.size >= size) break
        }
        // 配额把整行占不满时，用剩下的按分数补齐，保证一行不空着
        if (out.size < size) {
            for (item in overflow) {
                if (out.size >= size) break
                if (out.any { normalize(it.title) == normalize(item.title) }) continue
                out.add(item)
            }
        }
        return out
    }
}
