package io.github.blackkcold.librepro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 片单主键的规则单测。真实事故：旧版用片名当主键，
 * 「控方证人」1957 电影 / 1982 版 / 2025 短剧 在片单里互相覆盖。
 */
class LibKeyTest {

    @Test
    fun douban_id_wins_over_everything() {
        assertEquals("d:1292052", LibKey.of("1292052", "http://a|12", "控方证人"))
        assertEquals("d:1292052", LibKey.of(" 1292052 ", "", "控方证人"))
    }

    @Test
    fun ref_key_used_when_no_douban_id() {
        assertEquals("r:http://a|12", LibKey.of("", "http://a|12", "控方证人"))
    }

    @Test
    fun title_is_last_resort_and_normalised() {
        val k = LibKey.of("", "", "控方证人 ")
        assertEquals(LibKey.legacyTitleKey("控方证人"), k)
        assertTrue(k.startsWith("t:"))
    }

    @Test
    fun same_title_different_douban_id_are_different_entries() {
        val film1957 = LibKey.of("1292052", "", "控方证人")
        val drama2025 = LibKey.of("3600000", "", "控方证人")
        assertNotEquals(film1957, drama2025)
    }

    @Test
    fun same_work_from_two_sources_collapses_by_douban_id() {
        val a = LibKey.of("1292052", "http://movie|11", "控方证人")
        val b = LibKey.of("1292052", "http://other|99", "控方证人")
        assertEquals(a, b)
    }

    @Test
    fun ref_is_stable_and_blank_safe() {
        assertEquals("http://x|42", LibKey.ref("http://x", "42"))
        assertEquals("", LibKey.ref("http://x", ""))
        assertEquals("", LibKey.ref("", "42"))
    }

    @Test
    fun normalisation_ignores_case_and_padding() {
        assertEquals(LibKey.of("", "", "The Matrix"), LibKey.of("", "", "the matrix  "))
    }
}
