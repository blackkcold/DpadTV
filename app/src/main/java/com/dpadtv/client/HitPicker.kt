package com.dpadtv.client

/**
 * 同一部片子在不同采集站会有多个命中，甚至混进同名的短剧/解说/预告。
 * 服务端的返回顺序是**源的顺序**，不是「跟你要的那部片有多像」——
 * 实测「控方证人」22 条命中里，第一条是 2025 短剧（360资源 全75集），
 * 而用户点的是 1957 年的电影（豆瓣条目）。旧逻辑取 first()，于是详情页
 * 年份、集数、简介全错位。
 *
 * 这里只做一件事：给命中打分，把最像用户想要的那条排在前面。
 * 纯函数，规则可单测（HitPickerTest）。
 */
object HitPicker {

    /** 明显不是正片的类型：短剧、电影解说、预告、花絮 */
    private val OFF_BRANCH = listOf("短剧", "解说", "预告", "花絮", "片段")

    fun score(hit: SearchItem, wantTitle: String, wantYear: String): Int {
        var s = 0
        val name = hit.name.trim()
        val want = wantTitle.trim()

        // 片名：完全相同 > 前缀/包含 > 其它
        s += when {
            want.isEmpty() -> 0
            Merger.normalize(name) == Merger.normalize(want) -> 30
            name.startsWith(want) -> 16
            name.contains(want) -> 10
            else -> 0
        }

        // 年份是区分「同名不同片」最硬的判据
        val hy = hit.year.take(4)
        if (wantYear.isNotEmpty() && hy.isNotEmpty()) {
            s += if (hy == wantYear) 45 else -30
        }

        // 短剧/解说一律降权：它们几乎总是同名不同物
        if (OFF_BRANCH.any { hit.typeName.contains(it) } || OFF_BRANCH.any { name.contains(it) }) s -= 35
        if (name.contains("[")) s -= 5

        // 正片/高清优先，全集其次
        if (hit.remarks.contains("正片") || hit.remarks.contains("HD")) s += 6
        if (hit.year.isEmpty()) s -= 4

        return s
    }

    /** 按匹配度排序；同分时保持原顺序（服务端的源优先级仍然有意义）。 */
    fun rank(hits: List<SearchItem>, wantTitle: String, wantYear: String): List<SearchItem> =
        hits.sortedWith(
            compareByDescending<SearchItem> { score(it, wantTitle, wantYear) }
                .thenBy { hits.indexOf(it) },
        )

    fun best(hits: List<SearchItem>, wantTitle: String, wantYear: String): SearchItem? =
        if (hits.isEmpty()) null else rank(hits, wantTitle, wantYear).first()
}
