package io.github.blackkcold.librepro

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
import kotlin.random.Random

/** 一次取数为什么空手：UI 只在这个原因上写文案，不再拿「N 分钟后恢复」骗人 */
enum class FetchFailure { NONE, RATE_LIMITED, OFFLINE, UPSTREAM }

/**
 * 榜单取数：**本地索引优先，联网只跟着用户动作走**。
 *
 *   App → assets/douban-index.json.gz（本地求交，0 请求）     ← 首页 / 切标签 / 翻页
 *   App → 豆瓣直连（点「查看更多」/「重试」/滚动到底才发生）  ← 按需，受门控
 *
 * 为什么改成「按需」：首页一进来是 3 行 × 4 个榜 = 最多 11 次请求，
 * 等于「打开电视」就先向豆瓣要 11 次数据，最容易吃风控。
 * 现在的纪律：
 *   1. **启动 0 请求**：所有行先吃本地索引，本地没有就显示「点这里联网取」；
 *   2. **滚动才分页**：loadMore 只在滑到底时取下一页，不预取；
 *   3. **门控**：最小间隔 3s（+抖动）、10 分钟 20 次预算、403 指数退避（10m→20m→40m→80m，上限 2h）；
 *   4. **失败原因跟着这一次调用走**，不被并发请求串味（审计 R8）。
 */
class CollectionRepo(private val ctx: Context) {

    /** 本地榜单索引：优先命中它，命中就完全不发请求 */
    private val index by lazy { LocalIndex(ctx) }

    /** 上一次结果来自哪里：UI 用来标"本地索引 · 2026-10-25"还是"已联网刷新" */
    @Volatile
    var lastSource: String = ""
        private set

    /** 最近一次取数结果。UI 只在「列表为空」时读它决定文案。 */
    @Volatile
    var lastFailure: FetchFailure = FetchFailure.NONE
        private set

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    /** 节奏/配额/退避：规则全在 RequestGate 里，可单测（见 RequestGateTest） */
    private val throttle = RequestGate()
    private val mutex = Mutex()

    /** 一次取数的结果：失败原因跟着这一次走，别被并发请求覆盖 */
    private class Outcome {
        var failure: FetchFailure = FetchFailure.NONE
        var source: String = ""
    }

    fun indexBuiltAt(): String = runCatching { index.builtAt }.getOrDefault("")

    /** 索引距今多少天（解析不出来返回 -1）：过期了 UI 要敢说 */
    fun indexAgeDays(): Long = runCatching {
        val d = index.builtAt
        if (d.isEmpty()) return@runCatching -1L
        val t = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(d)?.time
            ?: return@runCatching -1L
        (System.currentTimeMillis() - t) / 86_400_000L
    }.getOrDefault(-1L)

    /** 启动时在后台把索引解析掉，避免第一次查询在主线程解 52KB gzip（审计 R10） */
    fun warmUp() {
        runCatching { index.itemCount }
    }

    fun blockedSecondsLeft(): Long = throttle.cooldownLeft(System.currentTimeMillis()) / 1000

    /** 本地索引能不能回答这一年：UI 用它给年份 chip 标「联网」 */
    fun localYearKnown(year: String): Boolean =
        runCatching { index.yearsAvailable().contains(year) }.getOrDefault(false)

    /**
     * 取一个榜单（首页一行里的一个来源）。
     *
     * @param allowNetwork 只有用户明确要看（点「查看更多」/「重试」）才允许联网。
     *                     首页自动渲染传 false → 冷启动 0 请求。
     */
    suspend fun fetch(
        ref: CollectionRef,
        ttlMs: Long = TTL,
        allowNetwork: Boolean = false,
    ): List<MovieItem> = withContext(Dispatchers.IO) {
        val out = Outcome()
        val local = localFor(ref)
        if (local.size >= LOCAL_ENOUGH || !allowNetwork) {
            publish(out, if (local.isNotEmpty()) "index" else "")
            return@withContext local
        }
        val net = networkFor(ref, ttlMs, out)
        if (net.isNotEmpty()) {
            publish(out, out.source.ifEmpty { "network" })
            return@withContext net
        }
        // 联网失败：本地近似也比空屏强
        publish(out, if (local.isNotEmpty()) "index" else "")
        local
    }

    /**
     * 浏览页用：按 Query + 页码取一页（24 条）。
     *
     * 本地能凑够半页就先用本地；不够才联网 —— 但 start>0 时**不再短路**，
     * 否则联网兜底之后永远只有第一页（审计 P1-1）。
     */
    suspend fun fetchQuery(
        q: Query,
        ttlMs: Long = TTL,
        allowNetwork: Boolean = true,
    ): List<MovieItem> = withContext(Dispatchers.IO) {
        val out = Outcome()
        val local = index.query(q.tags, q.year, q.minRate, q.start, PAGE, q.sort)
        if (local.size >= LOCAL_ENOUGH || !allowNetwork) {
            publish(out, if (local.isNotEmpty()) "index" else "")
            return@withContext local
        }
        val url = DoubanUrls.search(q)
        val net = cached("q_" + md5(url), ttlMs, out) { request(url, out)?.let { parseNewSearch(it) } ?: emptyList() }
        // 来源要诚实：缓存命中不是「联网刷新」（旧版统一标 network，等于骗用户）
        val src = when {
            net.isNotEmpty() -> out.source.ifEmpty { "network" }
            local.isNotEmpty() -> "index"
            else -> ""
        }
        publish(out, src)
        if (net.isNotEmpty()) net else local
    }

    // ---------- 本地索引分派 ----------

    /** 索引没有「热门」维度：用 id 的稳定哈希打散评分序，避免和「9 分+」整行重复 */
    private fun shuffled(items: List<MovieItem>): List<MovieItem> =
        items.sortedBy { it.doubanId.hashCode() }

    private fun localFor(ref: CollectionRef): List<MovieItem> = when (ref.kind) {
        RefKind.SEARCH -> index.query(ref.tags, ref.year, ref.minRate, 0, 60)
        RefKind.HOT -> shuffled(index.query(listOf("电影"), null, 0.0, 0, 60))
        // TOP250 / 豆列是策展顺序：构建索引时按抓取顺序存成独立标签（build_index --lists）
        RefKind.TOP250 -> if (index.hasTag(TOP250_TAG)) index.queryOrdered(TOP250_TAG, 0, 50) else emptyList()
        RefKind.DOULIST -> if (ref.doulistId == OSCAR_DOULIST && index.hasTag(OSCAR_TAG)) {
            index.queryOrdered(OSCAR_TAG, 0, 40)
        } else {
            emptyList()
        }
    }

    private suspend fun networkFor(ref: CollectionRef, ttlMs: Long, out: Outcome): List<MovieItem> = when (ref.kind) {
        RefKind.SEARCH -> cached("q_" + md5(DoubanUrls.search(ref.toQuery())), ttlMs, out) {
            request(DoubanUrls.search(ref.toQuery()), out)?.let { parseNewSearch(it) } ?: emptyList()
        }
        RefKind.HOT -> cached("hot", ttlMs, out) {
            request("https://movie.douban.com/j/search_subjects?type=movie&tag=" +
                enc("热门") + "&sort=recommend&page_limit=40&page_start=0", out)
                ?.let { parseHot(it) } ?: emptyList()
        }
        RefKind.TOP250 -> (0 until 2).flatMap { page ->
            cached("top250_$page", ttlMs, out) {
                request("https://movie.douban.com/top250?start=${page * 25}", out)
                    ?.let { parseTop250(it) } ?: emptyList()
            }
        }
        RefKind.DOULIST -> cached("doulist_${ref.doulistId}", ttlMs, out) {
            request("https://www.douban.com/doulist/${ref.doulistId}/?sort=seq&playable=0", out)
                ?.let { parseDoulist(it) } ?: emptyList()
        }
    }

    private fun publish(out: Outcome, source: String) {
        lastFailure = out.failure
        lastSource = source.ifEmpty { out.source }
    }

    // ---------- 取数：直连（门控都在这里） ----------

    private suspend fun request(url: String, out: Outcome): String? = mutex.withLock {
        // 1) 领票：冷却中 / 配额用尽 → 本次不联网
        var permit = throttle.acquire(System.currentTimeMillis())
        if (permit.verdict != RequestGate.Verdict.ALLOW) {
            out.failure = FetchFailure.RATE_LIMITED
            Log.w("LibreProNet", "门控拦截：${permit.verdict}，剩余冷却 ${permit.cooldownMs / 1000}s")
            return@withLock null
        }
        if (permit.waitMs > 0) delay(permit.waitMs)
        // 2) 等完之后再确认一次（等待期间可能被别的请求推进了窗口/冷却）
        permit = throttle.acquire(System.currentTimeMillis())
        if (permit.verdict != RequestGate.Verdict.ALLOW) {
            out.failure = FetchFailure.RATE_LIMITED
            return@withLock null
        }
        throttle.markSent(System.currentTimeMillis())

        val resp = try {
            getRaw(url)
        } catch (e: java.io.IOException) {
            Log.w("LibreProNet", "GET 网络失败 ${url.take(90)} → ${e.javaClass.simpleName}: ${e.message}")
            out.failure = FetchFailure.OFFLINE
            return@withLock null
        } catch (e: Exception) {
            Log.w("LibreProNet", "GET 失败 ${url.take(90)} → ${e.javaClass.simpleName}: ${e.message}")
            out.failure = FetchFailure.UPSTREAM
            return@withLock null
        }

        when {
            resp.code == 403 || resp.code == 418 || resp.code == 429 ||
                resp.body.contains("检测到有异常请求") || resp.body.contains("\"r\":1") -> {
                // 429 也是限流，而且**优先听服务端的 Retry-After**：
                // 以前 429 落进「普通服务器错误」只冷却 60s，文案却写「等 10 分钟」，自相矛盾
                val backoff = throttle.onRateLimited(System.currentTimeMillis(), resp.retryAfter)
                out.failure = FetchFailure.RATE_LIMITED
                Log.w("LibreProNet", "触发风控（HTTP ${resp.code}，第 ${throttle.strikes()} 次），退避 ${backoff / 60_000} 分钟")
                null
            }
            resp.code !in 200..299 -> {
                throttle.onServerError(System.currentTimeMillis())
                out.failure = FetchFailure.UPSTREAM
                Log.w("LibreProNet", "HTTP ${resp.code} ${url.take(80)}")
                null
            }
            resp.body.isBlank() -> {
                out.failure = FetchFailure.UPSTREAM
                null
            }
            else -> {
                throttle.onSuccess()
                out.failure = FetchFailure.NONE
                out.source = "network"
                Log.i("LibreProNet", "direct OK ${resp.body.length}B ${url.substringAfter("?").take(70)}")
                resp.body
            }
        }
    }

    /**
     * 缓存包装。
     *
     * `ttlMs` 必须透传到 [readCache]：以前形参收了却恒用 7 天 TTL，
     * 于是「按 OK 重试」「重新加载」永远读到同一份旧缓存，等于假重试。
     * `ttlMs <= 0` = 跳过读缓存（用户明确要求强刷），但网络失败时仍用旧缓存兜底。
     */
    private suspend fun cached(
        key: String,
        ttlMs: Long = TTL,
        out: Outcome,
        block: suspend () -> List<MovieItem>,
    ): List<MovieItem> {
        if (ttlMs > 0) readCache(key, ttlMs)?.let {
            out.source = "cache"      // 命中缓存 ≠ 联网，状态栏要分开写
            return it
        }
        val items = block()
        if (items.isNotEmpty()) {
            writeCache(key, items)
            return items
        }
        // 拉不到（限流/断网/缓存未预热）：用过期的旧缓存兜底，总比空屏好
        return readCache(key, ttlMs = Long.MAX_VALUE).orEmpty().also {
            if (it.isNotEmpty()) out.source = "cache"
        }
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
                rating = Regex("""class="rating_num"[^>]*>([\d.]+)<""").find(block)?.groupValues?.get(1)
                    ?.toDoubleOrNull()
                    ?: Regex("""class="">([\d.]+)</em>""").find(block)?.groupValues?.get(1)?.toDoubleOrNull()
                    ?: 0.0,
                doubanId = Regex("""/subject/(\d+)/""").find(block)?.groupValues?.get(1).orEmpty(),
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
                doubanId = Regex("""/subject/(\d+)/""").find(block)?.groupValues?.get(1).orEmpty(),
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

        /** 本地凑够半页就不再联网 */
        private const val LOCAL_ENOUGH = 6
        private const val PAGE = 24

        /** 构建期预生成的策展榜标签（build_index.py --lists） */
        const val TOP250_TAG = "Top250"
        const val OSCAR_TAG = "奥斯卡"
        const val OSCAR_DOULIST = "240962"
    }
}
