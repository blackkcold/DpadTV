package com.dpadtv.client

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 片单四态。收藏是「独立的一态」而不是布尔标记 —— 电视上的心理模型是
 * 「我把它放进哪个抽屉」，而不是「打没打星」。
 */
enum class LibState(val code: Int, val label: String) {
    WANT(0, "想看"),
    WATCHING(1, "在看"),
    WATCHED(2, "看过"),
    FAV(3, "收藏"),
    DROPPED(4, "弃剧");

    companion object {
        fun of(code: Int): LibState = entries.firstOrNull { it.code == code } ?: WANT
    }
}

data class LibEntry(
    /** 行主键：d:豆瓣 / r:源|vodId / t:片名（见 LibKey） */
    val id: String,
    val title: String,
    val cover: String,
    val doubanId: String,
    val rating: Double,
    val state: LibState,
    val updatedAt: Long,
    /** 来自哪个榜单（ref id），推荐画像的唯一特征 */
    val source: String = "",
    /** 解析到的播放源身份（sourceUrl|vodId），没有就是空 */
    val refKey: String = "",
) {
    fun toItem(): MovieItem = MovieItem(
        title = title,
        cover = cover,
        rating = rating,
        doubanId = doubanId,
        badge = state.label,
        // from 必须带回去：片单页改一次状态就会 put 回库，
        // 丢了这个字段等于把推荐画像的来源清空（审计 P1-16）
        from = source,
    )
}

/**
 * 条目身份。旧版拿片名当主键，同名不同片会互相覆盖
 * （实测「控方证人」：1957 电影 / 1982 版 / 2025 短剧 全叫同一个名字）。
 *
 * 优先级：
 *   1. `d:<豆瓣 id>` —— 豆瓣条目的真身份，跨源稳定；
 *   2. `r:<源 URL>|<vodId>` —— 已解析到具体播放源时的稳定身份；
 *   3. `t:<归一化片名>` —— 兜底（老数据 / 手输关键词 / 采集站没给 id）。
 *
 * 纯函数，单测在 LibKeyTest。
 */
object LibKey {
    fun normalize(title: String): String = Merger.normalize(title).lowercase()

    fun of(doubanId: String, refKey: String, title: String): String = when {
        doubanId.isNotBlank() -> "d:" + doubanId.trim()
        refKey.isNotBlank() -> "r:" + refKey.trim()
        else -> "t:" + normalize(title)
    }

    fun ref(sourceUrl: String, vodId: String): String =
        if (sourceUrl.isBlank() || vodId.isBlank()) "" else "$sourceUrl|$vodId"

    /** 老库里只有 t: 行；升级后第一次写入要把它们认出来 */
    fun legacyTitleKey(title: String): String = "t:" + normalize(title)
}

/**
 * 片单存储：手写 SQLite（**不引 Room**：项目零注解处理，加 KSP 的构建风险大于收益）。
 *
 * v4 起主键是 `id`（见 LibKey），`title` 只是显示名：同名不同片可以共存，
 * 同一部片从两个源加进来仍然只有一行（按 douban_id / ref_key 归并）。
 */
class LibraryStore(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, DB, null, 4) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE lib(
                 id TEXT PRIMARY KEY,
                 title TEXT NOT NULL,
                 cover TEXT NOT NULL DEFAULT '',
                 douban_id TEXT NOT NULL DEFAULT '',
                 rating REAL NOT NULL DEFAULT 0,
                 state INTEGER NOT NULL DEFAULT 0,
                 updated_at INTEGER NOT NULL DEFAULT 0,
                 source TEXT NOT NULL DEFAULT '',
                 ref_key TEXT NOT NULL DEFAULT ''
               )""",
        )
        db.execSQL("CREATE INDEX idx_lib_state ON lib(state, updated_at DESC)")
        db.execSQL("CREATE INDEX idx_lib_douban ON lib(douban_id)")
        db.execSQL("CREATE INDEX idx_lib_ref ON lib(ref_key)")
        db.execSQL("CREATE TABLE ev(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT, kind INTEGER, ts INTEGER)")
        db.execSQL("CREATE INDEX idx_ev_ts ON ev(ts DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 3) {
            // v2 的 onCreate 漏了 source 列（新建库会踩），这里兜一次
            runCatching { db.execSQL("ALTER TABLE lib ADD COLUMN source TEXT NOT NULL DEFAULT ''") }
            db.execSQL("CREATE TABLE IF NOT EXISTS ev(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT, kind INTEGER, ts INTEGER)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_ev_ts ON ev(ts DESC)")
        }
        if (oldVersion < 4) {
            // SQLite 改不了主键 → 重建表。老行折算成 t: 身份；
            // 之后第一次写入会用 douban_id / ref_key 把它们认领过去（见 put）。
            db.execSQL(
                """CREATE TABLE lib_v4(
                     id TEXT PRIMARY KEY,
                     title TEXT NOT NULL,
                     cover TEXT NOT NULL DEFAULT '',
                     douban_id TEXT NOT NULL DEFAULT '',
                     rating REAL NOT NULL DEFAULT 0,
                     state INTEGER NOT NULL DEFAULT 0,
                     updated_at INTEGER NOT NULL DEFAULT 0,
                     source TEXT NOT NULL DEFAULT '',
                     ref_key TEXT NOT NULL DEFAULT ''
                   )""",
            )
            db.execSQL(
                """INSERT OR REPLACE INTO lib_v4(id, title, cover, douban_id, rating, state, updated_at, source, ref_key)
                   SELECT 't:'||lower(trim(title)), title, cover, douban_id, rating, state, updated_at,
                          COALESCE(source,''), ''
                   FROM lib""",
            )
            db.execSQL("DROP TABLE lib")
            db.execSQL("ALTER TABLE lib_v4 RENAME TO lib")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_lib_state ON lib(state, updated_at DESC)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_lib_douban ON lib(douban_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_lib_ref ON lib(ref_key)")
        }
    }

    private val cols =
        "id, title, cover, douban_id, rating, state, updated_at, COALESCE(source,''), COALESCE(ref_key,'')"

    private fun read(c: Cursor): LibEntry = LibEntry(
        id = c.getString(0),
        title = c.getString(1),
        cover = c.getString(2),
        doubanId = c.getString(3),
        rating = c.getDouble(4),
        state = LibState.of(c.getInt(5)),
        updatedAt = c.getLong(6),
        source = c.getString(7),
        refKey = c.getString(8),
    )

    /** 按豆瓣 id → 播放源身份 → 片名依次找同一部片（跨源归并的关键） */
    fun findId(doubanId: String, refKey: String, title: String): String? {
        val db = readableDatabase
        if (doubanId.isNotBlank()) {
            db.rawQuery("SELECT id FROM lib WHERE douban_id = ? LIMIT 1", arrayOf(doubanId.trim()))
                .use { if (it.moveToFirst()) return it.getString(0) }
        }
        if (refKey.isNotBlank()) {
            db.rawQuery("SELECT id FROM lib WHERE ref_key = ? LIMIT 1", arrayOf(refKey.trim()))
                .use { if (it.moveToFirst()) return it.getString(0) }
        }
        db.rawQuery("SELECT id FROM lib WHERE id = ? LIMIT 1", arrayOf(LibKey.legacyTitleKey(title)))
            .use { if (it.moveToFirst()) return it.getString(0) }
        // 老数据可能带空格/全角，折算不一致时再按 title 兜一次
        db.rawQuery("SELECT id FROM lib WHERE lower(trim(title)) = lower(trim(?)) LIMIT 1", arrayOf(title))
            .use { if (it.moveToFirst()) return it.getString(0) }
        return null
    }

    fun stateOf(item: MovieItem, refKey: String = ""): LibState? =
        stateOfKey(LibKey.of(item.doubanId, refKey, item.title), item.title)

    /** 只有片名的场景（老调用点 / 事件回看） */
    fun stateOf(title: String): LibState? = stateOfKey(LibKey.legacyTitleKey(title), title)

    private fun stateOfKey(fallback: String, title: String): LibState? {
        val id = findId("", "", title) ?: fallback
        return readableDatabase.rawQuery("SELECT state FROM lib WHERE id = ?", arrayOf(id))
            .use { if (it.moveToFirst()) LibState.of(it.getInt(0)) else null }
    }

    fun rowOf(id: String): LibEntry? = readableDatabase
        .rawQuery("SELECT $cols FROM lib WHERE id = ?", arrayOf(id))
        .use { c -> if (c.moveToFirst()) read(c) else null }

    /** 再按同一个态 = 取消（电视上"反悔"必须一键） */
    fun toggle(item: MovieItem, state: LibState, refKey: String = ""): LibState? {
        val cur = stateOf(item, refKey)
        return if (cur == state) {
            remove(item, refKey)
            null
        } else {
            put(item, state, refKey)
            state
        }
    }

    /**
     * 写入。身份解析：豆瓣 id → 播放源身份 → 老片名行。
     * 命中老行时**就地升级**（补 douban_id / ref_key），不留重复条目。
     */
    fun put(item: MovieItem, state: LibState, refKey: String = "") {
        val target = findId(item.doubanId, refKey, item.title)
        val old = target?.let { rowOf(it) }
        val id = target ?: LibKey.of(item.doubanId, refKey, item.title)
        val cv = ContentValues().apply {
            put("id", id)
            put("title", item.title.ifEmpty { old?.title ?: "" })
            put("cover", item.cover.ifEmpty { old?.cover ?: "" })
            put("douban_id", item.doubanId.ifEmpty { old?.doubanId ?: "" })
            put("rating", if (item.rating > 0.0) item.rating else old?.rating ?: 0.0)
            put("state", state.code)
            put("updated_at", System.currentTimeMillis())
            put("source", item.from.ifEmpty { old?.source ?: "" })
            put("ref_key", refKey.ifEmpty { old?.refKey ?: "" })
        }
        writableDatabase.insertWithOnConflict("lib", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun remove(item: MovieItem, refKey: String = "") {
        val id = findId(item.doubanId, refKey, item.title) ?: return
        writableDatabase.delete("lib", "id = ?", arrayOf(id))
    }

    fun remove(title: String) {
        val id = findId("", "", title) ?: return
        writableDatabase.delete("lib", "id = ?", arrayOf(id))
    }

    fun list(state: LibState? = null, limit: Int = 300): List<LibEntry> {
        val (where, args) = if (state == null) "" to emptyArray<String>()
        else "WHERE state = ?" to arrayOf(state.code.toString())
        return readableDatabase
            .rawQuery("SELECT $cols FROM lib $where ORDER BY updated_at DESC LIMIT $limit", args)
            .use { c -> buildList { while (c.moveToNext()) add(read(c)) } }
    }

    fun counts(): Map<LibState, Int> {
        val m = LinkedHashMap<LibState, Int>()
        LibState.entries.forEach { m[it] = 0 }
        readableDatabase.rawQuery("SELECT state, COUNT(*) FROM lib GROUP BY state", null).use { c ->
            while (c.moveToNext()) m[LibState.of(c.getInt(0))] = c.getInt(1)
        }
        return m
    }

    /** 行为事件：0=标记 1=开始播放 2=播完 */
    fun event(title: String, kind: Int) {
        writableDatabase.execSQL(
            "INSERT INTO ev(title, kind, ts) VALUES(?,?,?)",
            arrayOf(title, kind, System.currentTimeMillis()),
        )
    }

    fun playCount(title: String): Int = readableDatabase
        .rawQuery("SELECT COUNT(*) FROM ev WHERE title = ? AND kind = 1", arrayOf(title))
        .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** 推荐引擎的「信号量」：片单条目 + 播放次数，都算用户表达过兴趣 */
    fun signals(): Int = readableDatabase.rawQuery(
        "SELECT (SELECT COUNT(*) FROM lib) + (SELECT COUNT(*) FROM ev WHERE kind = 1)", null,
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        const val DB = "dpadtv_library.db"
    }
}
