package com.dpadtv.client

import android.content.ContentValues
import android.content.Context
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
    val title: String,
    val cover: String,
    val doubanId: String,
    val rating: Double,
    val state: LibState,
    val updatedAt: Long,
) {
    fun toItem(): MovieItem = MovieItem(
        title = title,
        cover = cover,
        rating = rating,
        doubanId = doubanId,
        badge = state.label,
    )
}

/**
 * 片单存储：手写 SQLite（**不引 Room**：项目零注解处理，加 KSP 的构建风险大于收益）。
 * 主键是 title —— 播放链路本来就是按片名聚合搜索，title 才是这个 App 的真正 ID。
 */
class LibraryStore(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, DB, null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE lib(
                 title TEXT PRIMARY KEY,
                 cover TEXT NOT NULL DEFAULT '',
                 douban_id TEXT NOT NULL DEFAULT '',
                 rating REAL NOT NULL DEFAULT 0,
                 state INTEGER NOT NULL DEFAULT 0,
                 updated_at INTEGER NOT NULL DEFAULT 0
               )""",
        )
        db.execSQL("CREATE INDEX idx_lib_state ON lib(state, updated_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun stateOf(title: String): LibState? = readableDatabase
        .rawQuery("SELECT state FROM lib WHERE title = ?", arrayOf(title))
        .use { if (it.moveToFirst()) LibState.of(it.getInt(0)) else null }

    /** 再按同一个态 = 取消（电视上"反悔"必须一键） */
    fun toggle(item: MovieItem, state: LibState): LibState? {
        val cur = stateOf(item.title)
        return if (cur == state) {
            remove(item.title)
            null
        } else {
            put(item, state)
            state
        }
    }

    fun put(item: MovieItem, state: LibState) {
        val cv = ContentValues().apply {
            put("title", item.title)
            put("cover", item.cover)
            put("douban_id", item.doubanId)
            put("rating", item.rating)
            put("state", state.code)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("lib", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun remove(title: String) {
        writableDatabase.delete("lib", "title = ?", arrayOf(title))
    }

    fun list(state: LibState? = null, limit: Int = 300): List<LibEntry> {
        val (where, args) = if (state == null) "" to emptyArray<String>()
        else "WHERE state = ?" to arrayOf(state.code.toString())
        return readableDatabase
            .rawQuery("SELECT title, cover, douban_id, rating, state, updated_at FROM lib $where ORDER BY updated_at DESC LIMIT $limit", args)
            .use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(
                            LibEntry(
                                title = c.getString(0),
                                cover = c.getString(1),
                                doubanId = c.getString(2),
                                rating = c.getDouble(3),
                                state = LibState.of(c.getInt(4)),
                                updatedAt = c.getLong(5),
                            ),
                        )
                    }
                }
            }
    }

    fun counts(): Map<LibState, Int> {
        val m = LinkedHashMap<LibState, Int>()
        LibState.entries.forEach { m[it] = 0 }
        readableDatabase.rawQuery("SELECT state, COUNT(*) FROM lib GROUP BY state", null).use { c ->
            while (c.moveToNext()) m[LibState.of(c.getInt(0))] = c.getInt(1)
        }
        return m
    }

    suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        const val DB = "dpadtv_library.db"
    }
}
