package io.github.blackkcold.librepro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendTest {

    private fun entry(title: String, source: String, state: LibState, daysAgo: Long = 0) = LibEntry(
        id = LibKey.legacyTitleKey(title), title = title, cover = "", doubanId = "", rating = 8.0, state = state,
        updatedAt = System.currentTimeMillis() - daysAgo * 86_400_000, source = source,
    )

    private fun item(title: String, from: String, rating: Double = 8.5, year: String = "2025") =
        MovieItem(title = title, rating = rating, year = year, from = from)

    @Test
    fun `收藏比弃剧权重高`() {
        assertTrue(Recommend.stateWeight(LibState.FAV) > Recommend.stateWeight(LibState.WANT))
        assertTrue(Recommend.stateWeight(LibState.DROPPED) < 0)
    }

    @Test
    fun `喜欢的来源亲和度更高，弃剧来源被扣分`() {
        val taste = Recommend.taste(
            listOf(
                entry("A", "mv_susp", LibState.FAV),
                entry("B", "mv_susp", LibState.FAV),
                entry("C", "tv_kr", LibState.DROPPED),
            ),
        )
        assertTrue((taste["mv_susp"] ?: 0.0) > 0)
        assertTrue((taste["tv_kr"] ?: 0.0) < 0)

        val a = Recommend.score(item("X", "mv_susp"), taste, newest = 2025)
        val b = Recommend.score(item("Y", "tv_kr"), taste, newest = 2025)
        assertTrue(a > b)
    }

    @Test
    fun `时间衰减：30 天前的收藏权重减半左右`() {
        val fresh = Recommend.taste(listOf(entry("A", "s1", LibState.FAV, daysAgo = 0)))
        val old = Recommend.taste(listOf(entry("A", "s1", LibState.FAV, daysAgo = 30)))
        val ratio = (old["s1"] ?: 0.0) / (fresh["s1"] ?: 1.0)
        assertTrue("ratio=$ratio", ratio in 0.30..0.45)
    }

    @Test
    fun `MMR 不会把同一年同一评分档刷满`() {
        val pool = (1..6).map { item("同质$it", "s1", 8.6, "2025") } +
            listOf(item("异质A", "s1", 8.4, "2019"), item("异质B", "s1", 7.2, "2016"))
        val taste = mapOf("s1" to 1.0)
        val out = Recommend.mmr(pool, taste, k = 4, lambda = 0.6)
        assertEquals(4, out.size)
        val sameYear = out.count { it.year == "2025" }
        assertTrue("同年条目 = $sameYear，应被多样性压制", sameYear <= 2)
    }

    @Test
    fun `MMR 取不满时返回全部，不抛异常`() {
        val out = Recommend.mmr(listOf(item("A", "s1")), mapOf("s1" to 1.0), k = 10)
        assertEquals(1, out.size)
    }

    @Test
    fun `信号门槛`() {
        assertFalse(Recommend.enough(7))
        assertTrue(Recommend.enough(8))
    }
}
