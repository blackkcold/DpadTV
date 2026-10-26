package io.github.blackkcold.librepro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubanUrlsTest {

    @Test
    fun `标签与年份合并成一个 tags 参数`() {
        val url = DoubanUrls.search(Query(tags = listOf("日剧"), year = "2025", minRate = 9.0))
        assertTrue(url.contains("tags=%E6%97%A5%E5%89%A7%2C2025"))
        assertTrue(url.contains("range=9.0,10"))
        assertTrue(url.contains("sort=T"))
        assertTrue(url.contains("start=0"))
    }

    @Test
    fun `没选年份时 tags 不带空串`() {
        val url = DoubanUrls.search(Query(tags = listOf("电影")))
        assertTrue(url.contains("tags=%E7%94%B5%E5%BD%B1&"))
    }

    @Test
    fun `排序映射到豆瓣的 sort 代码`() {
        assertTrue(DoubanUrls.search(Query(sort = Sort.RATING)).contains("sort=T"))
        assertTrue(DoubanUrls.search(Query(sort = Sort.HOT)).contains("sort=S"))
        assertTrue(DoubanUrls.search(Query(sort = Sort.LATEST)).contains("sort=U"))
    }

    @Test
    fun `chip 名字可读`() {
        assertEquals("悬疑 · 韩国 · 2025 · 9分+",
            DoubanUrls.label(Query(tags = listOf("悬疑", "韩国"), year = "2025", minRate = 9.0)))
        assertEquals("全部", DoubanUrls.label(Query()))
    }
}
