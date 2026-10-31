package io.github.blackkcold.librepro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地索引的过滤语义。
 *
 * 这层是纯函数（不碰 Context / JSON），所以能在 JVM 上直接跑。
 * 重点守两条：**多标签必须求交**、**索引里缺标签时必须返回空**（否则部分索引会给出错结果）。
 */
class LocalIndexTest {

    private fun item(title: String, rate: Double, year: String = "") =
        MovieItem(title = title, rating = rate, year = year)

    private val items = mapOf(
        "1" to item("千与千寻", 9.4),
        "2" to item("告白", 8.7),
        "3" to item("寄生虫", 8.8),
        "4" to item("唐人街探案", 7.2, "2025"),
        "5" to item("沙丘2", 8.3, "2024"),
    )

    private val idsByTag = mapOf(
        "日本" to listOf("1", "2"),
        "悬疑" to listOf("2"),
        "韩国" to listOf("3"),
        "电影" to listOf("1", "2", "3", "4", "5"),
        "2025" to listOf("4"),
    )

    @Test
    fun single_tag_returns_that_tag_only() {
        val got = IndexCore.query(idsByTag, items, listOf("日本"), null, 0.0, 0, 10)
        assertEquals(listOf("千与千寻", "告白"), got.map { it.title })
    }

    @Test
    fun multiple_tags_intersect() {
        val got = IndexCore.query(idsByTag, items, listOf("日本", "悬疑"), null, 0.0, 0, 10)
        assertEquals(listOf("告白"), got.map { it.title })
        // 求交顺序不该影响结果
        val reversed = IndexCore.query(idsByTag, items, listOf("悬疑", "日本"), null, 0.0, 0, 10)
        assertEquals(got.map { it.title }, reversed.map { it.title })
    }

    @Test
    fun unknown_tag_returns_empty_instead_of_everything() {
        // 索引是部分的时候，缺标签必须返回空（交给联网），不能把全库当成该标签的结果
        val got = IndexCore.query(idsByTag, items, listOf("日本", "科幻"), null, 0.0, 0, 10)
        assertTrue(got.isEmpty())
    }

    @Test
    fun min_rate_filters() {
        val got = IndexCore.query(idsByTag, items, listOf("电影"), null, 8.5, 0, 10)
        assertEquals(listOf("千与千寻", "寄生虫", "告白"), got.map { it.title })
    }

    @Test
    fun year_matches_entry_or_title() {
        val byTag = IndexCore.query(idsByTag, items, listOf("2025"), null, 0.0, 0, 10)
        assertEquals(listOf("唐人街探案"), byTag.map { it.title })
        // 年份既可以来自条目字段，也可以来自标题里带的年份
        val byField = IndexCore.query(idsByTag, items, listOf("电影"), "2024", 0.0, 0, 10)
        assertEquals(listOf("沙丘2"), byField.map { it.title })
    }

    @Test
    fun sorted_by_rating_desc() {
        val got = IndexCore.query(idsByTag, items, listOf("电影"), null, 0.0, 0, 10)
        assertEquals(listOf("千与千寻", "寄生虫", "告白", "沙丘2", "唐人街探案"), got.map { it.title })
    }

    @Test
    fun pagination_is_stable_and_bounded() {
        val page2 = IndexCore.query(idsByTag, items, listOf("电影"), null, 0.0, 2, 2)
        assertEquals(listOf("告白", "沙丘2"), page2.map { it.title })
        val beyond = IndexCore.query(idsByTag, items, listOf("电影"), null, 0.0, 99, 2)
        assertTrue(beyond.isEmpty())
        // 最后一页不足 size 也不能越界
        val tail = IndexCore.query(idsByTag, items, listOf("电影"), null, 0.0, 4, 10)
        assertEquals(listOf("唐人街探案"), tail.map { it.title })
    }

    @Test
    fun empty_tag_list_is_rejected() {
        // 空标签 = 「全部条目」：索引给不出有意义的排序，必须交回网络，
        // 不能把整个库当成一次命中（旧行为，审计 P2-9 的定时炸弹）
        val got = IndexCore.query(idsByTag, items, emptyList(), null, 0.0, 0, 10)
        assertTrue(got.isEmpty())
    }

    @Test
    fun ordered_query_keeps_curated_order() {
        val tag = mapOf("Top250" to listOf("3", "1", "4"))
        val got = IndexCore.ordered(tag, items, "Top250", 0, 2)
        assertEquals(listOf("寄生虫", "千与千寻"), got.map { it.title })
        val page2 = IndexCore.ordered(tag, items, "Top250", 2, 5)
        assertEquals(listOf("唐人街探案"), page2.map { it.title })
        assertTrue(IndexCore.ordered(tag, items, "不存在", 0, 5).isEmpty())
        assertTrue(IndexCore.ordered(tag, items, "Top250", 9, 5).isEmpty())
    }

    @Test
    fun year_matches_by_year_tag_membership() {
        // 条目自带 year 为空时，年份标签的成员关系必须仍然生效
        val yearIds = setOf("2", "3")
        val got = IndexCore.query(idsByTag, items, listOf("电影"), "2025", 0.0, 0, 10, yearIds)
        // 唐人街探案自带 year=2025，也在这批里 —— 字段命中与标签命中是「或」的关系
        assertEquals(listOf("寄生虫", "告白", "唐人街探案"), got.map { it.title })
    }

    /* ---------------- 排序与翻页稳定性（浏览页「划不动」的回归测试） ---------------- */

    @Test
    fun sort_latest_orders_by_year_desc_then_rating() {
        val got = IndexCore.query(idsByTag, items, listOf("电影"), null, 0.0, 0, 10, null, Sort.LATEST)
        // 2025 → 2024 → 无年份（无年份内部按评分降序）
        assertEquals(listOf("唐人街探案", "沙丘2", "千与千寻", "寄生虫", "告白"), got.map { it.title })
    }

    @Test
    fun sort_hot_is_stable_across_pages() {
        // 分页必须「整页拼起来 == 一次取全」，否则翻页会重复或漏项
        val all = IndexCore.query(idsByTag, items, listOf("电影"), null, 0.0, 0, 10, null, Sort.HOT)
        val paged = (0 until 3).flatMap { p ->
            IndexCore.query(idsByTag, items, listOf("电影"), null, 0.0, p * 2, 2, null, Sort.HOT)
        }
        assertEquals(all.map { it.title }, paged.map { it.title })
        assertEquals(all.size, all.map { it.title }.distinct().size)
    }

    @Test
    fun equal_ratings_have_total_order() {
        // 同分条目必须有稳定的次级排序（片名），否则第 2 页可能和第 1 页重叠
        val tied = mapOf("1" to item("B片", 8.0), "2" to item("A片", 8.0), "3" to item("C片", 8.0))
        val tag = mapOf("电影" to listOf("1", "2", "3"))
        val all = IndexCore.query(tag, tied, listOf("电影"), null, 0.0, 0, 10)
        assertEquals(listOf("A片", "B片", "C片"), all.map { it.title })
    }
}
