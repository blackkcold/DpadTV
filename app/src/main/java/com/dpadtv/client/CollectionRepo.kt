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

/** 一次取数为什么空手：UI 只在这个原因上写文案，不再拿「N 分钟后恢复」骗人 */
enum class FetchFailure { NONE, RATE_LIMITED, OFFLINE, UPSTREAM }

/**
 * 榜单抓取 + 本地缓存 + 侧车优先。
 *
 * 数据链路：
 *   App → 侧车（路由 :8898，全屋共享缓存 + 预热 + 熔断）→ 豆瓣
 *   App → 豆瓣 直连只在侧车不可达时兜底，且失败后长退避，不反复撞风控。
 *
 * v1 的教训：侧车在 `ALLOW_UPSTREAM=0` 时返回 `{"msg":"offline mode...","r":1}`，
 * 客户端靠 `body.contains("\"r\":1")` 猜状态 → 把「侧车没开上游」当成「豆瓣限流」，
 * 于是永远显示「豆瓣限流，N 分钟后自动恢复」。v2 改为**读 X-Cache 头判定**，body 不参与判态。
 *
 * 三条纪律：
 *   1. 侧车返回的 200 才当真数据；429 才是限流；503 是配置/上游问题
 *   2. 只有真限流才设退避（按 Retry-After），offline-miss 不设
 *   3. 每页结果单独缓存 7 天（与侧车 TTL 对齐），滚动分页不重复打接口
 */
class CollectionRepo(private val ctx: Context) {

    /**
     * 豆瓣缓存 sidecar（路由上跑）：同一个 URL 全屋共享缓存 + 单飞 + 全局串行 + 每日预热。
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

    /** 最近一次取数结果。UI 只在「列表为空」时读它决定文案。 */
    @Volatile
    var lastFailure: FetchFailure = FetchFailure.NONE
        private set

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

    // ---------- 取数：侧车优先，直连兜底 ----------

    /** https://movie.douban.com/xxx → Pair("movie.douban.com", "/xxx") */
    private fun parseDouban(url: String): Pair<String, String>? = when {
        url.startsWith("https://movie.douban.com/") -> "movie.douban.com" to url.removePrefix("https://movie.douban.com")
        url.startsWith("https://www.douban.com/") -> "www.douban.com" to url.removePrefix("https://www.douban.com")
        else -> null
    }

    private suspend fun request(url: String): String? = gate.withLock {
        val douban = parseDouban(url)

        // 1) 侧车优先：命中时上游 0 请求，局域网毫秒级
        if (douban != null && sidecar != null) {
            val (host, path) = douban
            val resp = runCatching {
                getRaw(sidecar + "/douban?path=" + enc(path) + "&host=" + host)
            }.getOrNull()

            if (resp != null) {
                val verdict = resp.cache.orEmpty()
                val usable = resp.code == 200 && resp.body.isNotBlank() &&
                    verdict != "offline-miss" && verdict != "rate-limited" && verdict != "upstream-error"
                if (usable) {
                    lastFailure = FetchFailure.NONE
                    Log.i("DpadTVDouban", "sidecar ${verdict.ifEmpty { "v1" }} ${resp.body.length}B ${path.take(52)}")
                    return@withLock resp.body
                }
                when (verdict) {
                    "rate-limited" -> {
                        val retry = resp.retryAfter.takeIf { it > 0 } ?: 600L
                        blockedUntil = System.currentTimeMillis() + retry * 1000
                        lastFailure = FetchFailure.RATE_LIMITED
                        Log.w("DpadTVDouban", "侧车限流，退避 ${retry}s：${path.take(52)}")
                        return@withLock null
                    }
                    "offline-miss" -> {
                        // 侧车没开上游（配置态），不是限流 —— 不设退避，也不直连补刀
                        lastFailure = FetchFailure.OFFLINE
                        Log.w("DpadTVDouban", "侧车 offline-miss（ALLOW_UPSTREAM=0）：${path.take(52)}")
                        return@withLock null
                    }
                    else -> {
                        lastFailure = FetchFailure.UPSTREAM
                        Log.w("DpadTVDouban", "侧车 upstream-error(${resp.code})：${path.take(52)}")
                        return@withLock null
                    }
                }
            }
            Log.w("DpadTVDouban", "sidecar 不可达，直连一次：${path.take(52)}")
        }

        // 2) 直连兜底：只在侧车不可达 / 没有侧车时走；失败即长退避，不反复撞风控
        if (System.currentTimeMillis() < blockedUntil) return@withLock null
        val gap = 2500 - (System.currentTimeMillis() - lastRequestAt)
        if (gap > 0) delay(gap)
        lastRequestAt = System.currentTimeMillis()
        val resp = runCatching { getRaw(url) }
            .onFailure { Log.w("DpadTVDouban", "GET 失败 ${url.take(90)} → ${it.javaClass.simpleName}: ${it.message}") }
            .getOrNull()
        if (resp == null) {
            lastFailure = FetchFailure.UPSTREAM
            return@withLock null
        }
        if (resp.body.contains("检测到有异常请求") || resp.body.contains("\"r\":1")) {
            // 直连被风控：一小时别再试，等下次侧车恢复
            blockedUntil = System.currentTimeMillis() + 60 * 60_000
            lastFailure = FetchFailure.RATE_LIMITED
            Log.w("DpadTVDouban", "直连触发风控，退避 60 分钟：${resp.body.take(80)}")
            return@withLock null
        }
        lastFailure = FetchFailure.NONE
        Log.i("DpadTVDouban", "direct OK ${resp.body.length}B ${url.substringAfter("?").take(70)}")
        resp.body
    }

    private suspend fun cached(key: String, block: suspend () -> List<MovieItem>): List<MovieItem> {
        readCache(key)?.let { return it }
        val items = block()
        if (items.isNotEmpty()) {
            writeCache(key, items)
            return items
        }
        // 拉不到（限流/断网/缓存未预热）：用过期的旧缓存兜底，总比空屏好
        return readCache(key, ttlMs = Long.MAX_VALUE).orEmpty()
    }

    // ---------- 网络 ----------

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private data class Resp(
        val code: Int,
        val body: String,
        val cache: String?,
        val retryAfter: Long,
    )

    private fun getRaw(url: String): Resp {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 12000
            setRequestProperty("User-Agent", ua)
            setRequestProperty("Referer", "https://movie.douban.com/")
            setRequestProperty("Accept", "application/json, text/html;q=0.9,*/*;q=0.8")
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            Resp(
                code = code,
                body = body,
                cache = conn.getHeaderField("X-Cache"),
                retryAfter = conn.getHeaderField("Retry-After")?.toLongOrNull() ?: 0L,
            )
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
        /** 与侧车 TTL 对齐：榜单按天变化，7 天内的数据比一次风控值钱 */
        const val TTL = 7 * 24 * 3600_000L
    }
}
