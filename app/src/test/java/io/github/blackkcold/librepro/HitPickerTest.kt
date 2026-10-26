package io.github.blackkcold.librepro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 夹具取自真实接口（2026-10-25 `/api/search?wd=控方证人` 的 22 条命中）：
 * 服务端把 2025 短剧排在第一条，而用户点的是 1957 年的电影。
 * 这个用例就是防止「取 first()」的回归。
 */
class HitPickerTest {

    private fun hit(
        name: String,
        year: String,
        type: String = "剧情片",
        remarks: String = "正片",
        source: String = "电影天堂",
    ) = SearchItem(source, "id-$name-$year-$source", name, "", type, year, remarks, "http://x")

    private val real = listOf(
        hit("控方证人", "2025", "爽文短剧", "全75集（合全集）", "360资源"),
        hit("控方证人", "1957", "悬疑片", "正片", "360资源"),
        hit("控方证人", "1957", "剧情片", "HD中字", "电影天堂"),
        hit("控方证人", "1957", "剧情片", "HD中字", "非凡资源"),
        hit("控方证人", "1982", "剧情片", "HD", "如意资源"),
        hit("控方证人", "2016", "欧美剧", "全2集", "iKun资源"),
        hit("控方证人[电影解说]", "1957", "电影解说", "已完结", "量子资源"),
        hit("控方证人短剧", "2025", "短剧", "已完结", "如意资源"),
        hit("控方证人1957", "1957", "剧情片", "正片", "MD资源"),
        hit("短剧控方证人", "", "短剧", "已完结", "量子资源"),
    )

    @Test
    fun wanted_year_beats_server_order() {
        val best = HitPicker.best(real, "控方证人", "1957")!!
        assertEquals("1957", best.year.take(4))
        assertTrue("不该选到短剧/解说：$best", !best.typeName.contains("短剧"))
        assertTrue(best.name.contains("控方证人"))
    }

    @Test
    fun short_drama_is_penalised_even_without_year() {
        val best = HitPicker.best(real, "控方证人", "")!!
        // 没有年份线索时，正片也应该压过「爽文短剧」
        assertTrue("选中了 ${best.name}/${best.typeName}", !best.typeName.contains("短剧"))
        assertTrue(HitPicker.score(real[0], "控方证人", "") < HitPicker.score(real[1], "控方证人", ""))
    }

    @Test
    fun year_mismatch_is_worse_than_unknown_year() {
        val want = "控方证人"
        val wrongYear = hit(want, "2025")
        val unknownYear = hit(want, "")
        assertTrue(HitPicker.score(wrongYear, want, "1957") < HitPicker.score(unknownYear, want, "1957"))
    }

    @Test
    fun off_branch_markers_cost_points() {
        val plain = hit("控方证人", "1957", "剧情片", "正片")
        val commentary = hit("控方证人[电影解说]", "1957", "电影解说", "已完结")
        assertTrue(HitPicker.score(commentary, "控方证人", "1957") < HitPicker.score(plain, "控方证人", "1957"))
    }

    @Test
    fun rank_is_stable_for_equal_scores() {
        val a = hit("控方证人", "1957", source = "A")
        val b = hit("控方证人", "1957", source = "B")
        assertEquals(listOf(a, b), HitPicker.rank(listOf(a, b), "控方证人", "1957"))
        assertEquals(listOf(b, a), HitPicker.rank(listOf(b, a), "控方证人", "1957"))
    }

    @Test
    fun empty_hits_yield_null() {
        assertEquals(null, HitPicker.best(emptyList(), "控方证人", "1957"))
    }
}
