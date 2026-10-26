package io.github.blackkcold.librepro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MergerTest {

    private fun ref(id: String, weight: Double, badge: String = "") =
        CollectionRef(id = id, label = id, badge = badge, weight = weight)

    private fun item(title: String, rating: Double, year: String = "") =
        MovieItem(title = title, rating = rating, year = year)

    @Test
    fun `normalize 去掉年份与季数后缀`() {
        assertEquals("肖申克的救赎", Merger.normalize("肖申克的救赎 (1994)"))
        assertEquals("庆余年", Merger.normalize("庆余年 第二季"))
        assertEquals("繁花", Merger.normalize(" 繁花 "))
    }

    @Test
    fun `cleanTitle 砍掉英文原名`() {
        assertEquals("肖申克的救赎", Merger.cleanTitle("肖申克的救赎 The Shawshank Redemption"))
        assertEquals("繁花", Merger.cleanTitle("繁花 / Blossoms Shanghai"))
        assertEquals("Coco", Merger.cleanTitle("Coco"))
    }

    @Test
    fun `同一部片只留评分更高的那条`() {
        val a = ref("hot", 0.7)
        val b = ref("top", 1.0, "TOP250")
        val out = Merger.merge(
            listOf(
                a to listOf(item("繁花", 8.1)),
                b to listOf(item("繁花 (2023)", 8.7)),
            ),
            size = 10,
        )
        assertEquals(1, out.size)
        assertEquals(8.7, out[0].rating, 0.001)
        assertEquals("TOP250", out[0].badge)
    }

    @Test
    fun `单榜配额不超过 40%`() {
        val a = ref("hot", 0.7)
        val many = (1..20).map { item("热门剧$it", 9.5) }
        val out = Merger.merge(listOf(a to many), size = 10)
        assertEquals(10, out.size)
        // 单榜 4 条配额 + 补齐（唯一来源时允许补齐，但不重复）
        assertTrue(out.map { it.title }.distinct().size == 10)
    }

    @Test
    fun `高分榜压过热门榜`() {
        val hot = ref("hot", 0.7)
        val top = ref("top", 1.0)
        val out = Merger.merge(
            listOf(
                hot to listOf(item("流量片", 7.0)),
                top to listOf(item("冷门佳作", 7.0)),
            ),
            size = 10,
        )
        assertEquals("冷门佳作", out[0].title)
    }

    @Test
    fun `空输入不崩`() {
        assertEquals(0, Merger.merge(emptyList(), 10).size)
        assertEquals(0, Merger.merge(listOf(ref("x", 0.7) to emptyList()), 10).size)
        assertEquals(0, Merger.merge(listOf(ref("x", 0.7) to listOf(item(" ", 8.0))), 10).size)
    }

    @Test
    fun `角标跟权重走，评分取两家较高`() {
        val hot = ref("hot", 0.7, "热门")
        val top = ref("top", 1.0, "TOP250")
        val out = Merger.merge(
            listOf(
                hot to listOf(item("霸王别姬", 9.8)),
                top to listOf(item("霸王别姬", 9.6)),
            ),
            size = 10,
        )
        assertEquals(1, out.size)
        assertEquals("TOP250", out[0].badge)
        assertEquals(9.8, out[0].rating, 0.001)
    }
}
