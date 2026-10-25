package com.dpadtv.client

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 榜单抓取 + 本地缓存。
 *
 * 直连豆瓣（不走服务端）：一台电视、请求量极小；服务端 LibreTV 只透传 type+tag，
 * 拿不到「年份 + 评分区间 + 评分排序」这些维度。
 * 失败一律降级成空列表 —— 首页坏一行，不坏整页。
 */
class CollectionRepo(private val ctx: Context) {

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    suspend fun fetch(ref: CollectionRef, ttlMs: Long = 24 * 3600_000L): List<MovieItem> =
        withContext(Dispatchers.IO) {
            readCache(ref.id, ttlMs)?.let { return@withContext it }
            val fresh = runCatching { fetchRemote(ref) }.getOrDefault(emptyList())
            if (fresh.isNotEmpty()) writeCache(ref.id, fresh)
            fresh
        }

    private fun fetchRemote(ref: CollectionRef): List<MovieItem> = when (ref.kind) {
        RefKind.HOT -> parseHot(get("https://movie.douban.com/j/search_subjects?type=movie&tag=" +
            enc("热门") + "&sort=recommend&page_limit=40&page_start=0"))
        RefKind.SEARCH -> parseNewSearch(get(newSearchUrl(ref)))
        RefKind.TOP250 -> (0 until 2).flatMap { page ->
            parseTop250(get("https://movie.douban.com/top250?start=${page * 25}"))
        }
        RefKind.DOULIST -> parseDoulist(
            get("https://www.douban.com/doulist/${ref.doulistId}/?sort=seq&playable=0")
        )
    }

    private fun newSearchUrl(ref: CollectionRef): String {
        val tags = (ref.tags + listOfNotNull(ref.year)).joinToString(",")
        val min = if (ref.minRate > 0) ref.minRate else 0.0
        return "https://movie.douban.com/j/new_search_subjects?sort=T&range=$min,10&tags=${enc(tags)}&start=0"
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 12000
            setRequestProperty("User-Agent", ua)
            setRequestProperty("Referer", "https://movie.douban.com/")
            setRequestProperty("Accept", "application/json, text/html;q=0.9,*/*;q=0.8")
        }
        return try {
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // ---------- 解析 ----------

    private fun parseNewSearch(body: String): List<MovieItem> {
        val arr = JSONObject(body).optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            MovieItem(
                title = o.optString("title"),
                cover = o.optString("cover"),
                rating = o.optString("rate").toDoubleOrNull() ?: 0.0,
                doubanId = o.optString("id"),
            )
        }
    }

    private fun parseHot(body: String): List<MovieItem> {
        val arr = JSONObject(body).optJSONArray("subjects") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            MovieItem(
                title = o.optString("title"),
                cover = o.optString("cover"),
                rating = o.optString("rate").toDoubleOrNull() ?: 0.0,
                doubanId = o.optString("id"),
            )
        }
    }

    private fun parseTop250(html: String): List<MovieItem> =
        html.split("<div class=\"item\">").drop(1).mapNotNull { block ->
            val title = Regex("""class="title">([^<]+)<""").find(block)?.groupValues?.get(1)?.trim()
                ?: return@mapNotNull null
            MovieItem(
                title = title,
                cover = Regex("""src="([^"]+)"""").find(block)?.groupValues?.get(1).orEmpty(),
                rating = Regex("""class="">([\d.]+)</em>""").find(block)?.groupValues?.get(1)
                    ?.toDoubleOrNull() ?: 0.0,
            )
        }

    private fun parseDoulist(html: String): List<MovieItem> =
        html.split("class=\"doulist-item\"").drop(1).mapNotNull { block ->
            val title = Regex("""class="title">\s*<a[^>]*>\s*([^<]+?)\s*</a>""", RegexOption.DOT_MATCHES_ALL)
                .find(block)?.groupValues?.get(1)?.trim() ?: return@mapNotNull null
            MovieItem(
                title = title,
                cover = Regex("""<img src="([^"]+)"""").find(block)?.groupValues?.get(1).orEmpty(),
                rating = Regex("""class="rating_nums">([\d.]+)<""").find(block)?.groupValues?.get(1)
                    ?.toDoubleOrNull() ?: 0.0,
            )
        }

    // ---------- 缓存 ----------

    private fun dir(): File = File(ctx.cacheDir, "collections").apply { mkdirs() }

    private fun readCache(id: String, ttlMs: Long): List<MovieItem>? {
        val f = File(dir(), "$id.json")
        if (!f.exists()) return null
        if (System.currentTimeMillis() - f.lastModified() > ttlMs) return null
        return runCatching {
            val root = JSONObject(f.readText())
            val arr = root.getJSONArray("items")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                MovieItem(
                    title = o.getString("t"),
                    cover = o.optString("c"),
                    rating = o.optDouble("r", 0.0),
                    doubanId = o.optString("id"),
                    year = o.optString("y"),
                )
            }
        }.getOrNull()
    }

    private fun writeCache(id: String, items: List<MovieItem>) {
        runCatching {
            val arr = JSONArray()
            items.forEach {
                arr.put(JSONObject().apply {
                    put("t", it.title); put("c", it.cover); put("r", it.rating)
                    put("id", it.doubanId); put("y", it.year)
                })
            }
            File(dir(), "$id.json").writeText(JSONObject().put("items", arr).toString())
        }
    }
}
