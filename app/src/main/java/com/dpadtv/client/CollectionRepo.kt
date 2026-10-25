package com.dpadtv.client

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * 榜单抓取 + 本地缓存 + **全局限速**。
 *
 * 直连豆瓣（不走服务端）：一台电视、请求量小；服务端 LibreTV 只透传 type+tag，
 * 拿不到「年份 + 评分区间 + 评分排序」。
 *
 * 限速是必须的：豆瓣按 IP 触发风控，触发后返回
 * `{"msg":"检测到有异常请求从您的IP发出…","r":1}`（HTTP 仍是 200）。
 * 三条纪律：
 *   1. 所有请求串行 + 至少间隔 650ms（Mutex 全局互斥）
 *   2. 一旦识别到风控，整体退避 10 分钟，期间只读缓存
 *   3. 每页结果单独缓存 24h，滚动分页不重复打接口
 */
class CollectionRepo(private val ctx: Context) {

    /**
     * 豆瓣缓存 sidecar（路由上跑）：同一个 URL 全屋共享 24h 缓存 + 单飞 + 全局 2.5s 串行。
     * 有它以后客户端可以放心并发，不会再被豆瓣按 IP 风控。
     * 地址由服务器地址推导：http://192.168.0.14:8899 → http://192.168.0.14:8898
     */
    private val sidecar: String? = runCatching {
        val host = java.net.URI(Prefs(ctx).serverUrl).host ?: return@runCatching null
        "http://$host:8898"
    }.getOrNull()

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    private val gate = Mutex()
    private var lastRequestAt = 0L
    private var blockedUntil = 0L

    fun blockedSecondsLeft(): Long {
        val left = blockedUntil - System.currentTimeMillis()
        return if (left > 0) left / 1000 else 0
    }

    suspend fun fetch(ref: CollectionRef, ttlMs: Long = TTL): List<MovieItem> = withContext(Dispatchers.IO) {
        when (ref.kind) {
        RefKind.SEARCH -> cached(ref.id) {
            val q = Query(ref.tags, ref.year, if (ref.minRate > 0) ref.minRate else 0.0, Sort.RATING, 0)
            request(DoubanUrls.search(q))?.let { parseNewSearch(it) } ?: emptyList()
        }
        RefKind.HOT -> cached("hot") {
            request("https://movie.douban.com/j/search_subjects?type=movie&tag=" +
                enc("热门") + "&sort=recommend&page_limit=40&page_start=0")
                ?.let { parseHot(it) } ?: emptyList()
        }
        RefKind.TOP250 -> (0 until 2).flatMap { page ->
            cached("top250_$page") {
                request("https://movie.douban.com/top250?start=${page * 25}")
                    ?.let { parseTop250(it) } ?: emptyList()
            }
        }
        RefKind.DOULIST -> cached("doulist_${ref.doulistId}") {
            request("https://www.douban.com/doulist/${ref.doulistId}/?sort=seq&playable=0")
                ?.let { parseDoulist(it) } ?: emptyList()
        }
        }
    }

    /** 浏览页用：按 Query + 页码取一页（20 条） */
    suspend fun fetchQuery(q: Query, ttlMs: Long = TTL): List<MovieItem> = withContext(Dispatchers.IO) {
        val url = DoubanUrls.search(q)
        cached("q_" + md5(url)) { request(url)?.let { parseNewSearch(it) } ?: emptyList() }
    }

    // ---------- 限速 + 缓存 ----------

    private suspend fun request(url: String): String? = gate.withLock {
        // 1) 先走 sidecar（不受直连"风控退避"影响）（局域网，毫秒级；缓存命中时上游 0 请求）
        if (sidecar != null && url.startsWith("https://movie.douban.com/")) {
            val path = url.removePrefix("https://movie.douban.com")
            val body = runCatching {
                get(sidecar + "/douban?path=" + java.net.URLEncoder.encode(path, "UTF-8"))
            }.getOrNull()
            when {
                // 拿到了真数据
                body != null && !body.contains("检测到有异常请求") && !body.contains("\"r\":1") -> {
                    Log.i("DpadTVDouban", "sidecar ${body.length}B ${path.take(60)}")
                    return@withLock body
                }
                // 侧车在，但它说上游被限流：**不要再回退直连**，否则等于再补一刀
                body != null -> {
                    blockedUntil = System.currentTimeMillis() + 10 * 60_000
                    Log.w("DpadTVDouban", "sidecar 报限流，本次不走直连：${path.take(60)}")
                    return@withLock null
                }
                // 侧车连不上（没部署/局域网不通）才回退直连
                else -> Log.w("DpadTVDouban", "sidecar 不可达，回退直连：${path.take(60)}")
            }
        }

        // 2) 回退：直连豆瓣（限速 2.5s 是硬要求）
        if (System.currentTimeMillis() < blockedUntil) return@withLock null
        val gap = 2500 - (System.currentTimeMillis() - lastRequestAt)
        if (gap > 0) delay(gap)
        lastRequestAt = System.currentTimeMillis()
        val body = runCatching { get(url) }
            .onFailure { Log.w("DpadTVDouban", "GET 失败 ${url.take(90)} → ${it.javaClass.simpleName}: ${it.message}") }
            .getOrNull() ?: return@withLock null
        if (body.contains("检测到有异常请求") || body.contains("\"r\":1")) {
            blockedUntil = System.currentTimeMillis() + 10 * 60_000
            Log.w("DpadTVDouban", "触发风控，退避 10 分钟：${body.take(80)}")
            null
        } else {
            Log.i("DpadTVDouban", "OK ${body.length}B ${url.substringAfter("?").take(70)}")
            body
        }
    }

    private suspend fun cached(key: String, block: suspend () -> List<MovieItem>): List<MovieItem> {
        readCache(key)?.let { return it }
        val items = block()
        if (items.isNotEmpty()) {
            writeCache(key, items)
            return items
        }
        // 拉不到（限流/断网）：用过期的旧缓存兜底，总比空屏好
        return readCache(key, ttlMs = Long.MAX_VALUE).orEmpty()
    }

    // ---------- 网络 ----------

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
        val arr = runCatching { JSONObject(body).optJSONArray("data") }.getOrNull() ?: return emptyList()
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
        val arr = runCatching { JSONObject(body).optJSONArray("subjects") }.getOrNull() ?: return emptyList()
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

    // ---------- 磁盘缓存 ----------

    private fun dir(): File = File(ctx.cacheDir, "collections").apply { mkdirs() }

    private fun readCache(key: String, ttlMs: Long = TTL): List<MovieItem>? {
        val f = File(dir(), "$key.json")
        if (!f.exists()) return null
        if (System.currentTimeMillis() - f.lastModified() > ttlMs) return null
        return runCatching {
            val arr = JSONObject(f.readText()).getJSONArray("items")
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

    private fun writeCache(key: String, items: List<MovieItem>) {
        runCatching {
            val arr = JSONArray()
            items.forEach {
                arr.put(JSONObject().apply {
                    put("t", it.title); put("c", it.cover); put("r", it.rating)
                    put("id", it.doubanId); put("y", it.year)
                })
            }
            File(dir(), "$key.json").writeText(JSONObject().put("items", arr).toString())
        }
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    companion object {
        const val TTL = 24 * 3600_000L
    }
}
