package com.dpadtv.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * 主题解析的回归网。
 *
 * 这套测试是主题系统里**唯一**能自动回归的部分：调色板对不对靠人眼，
 * 但「几点该浅色」「到点还剩多久」这两个判断错了，用户半夜会被闪醒。
 */
class ThemeResolverTest {

    private val day = ThemeResolver.Window(7, 19)

    private fun at(hour: Int, minute: Int = 0, zone: String = "Asia/Shanghai"): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone(zone))
        cal.set(2026, Calendar.OCTOBER, 25, hour, minute, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    // ---------- 模式 ----------

    @Test
    fun dark_mode_ignores_hour() {
        assertFalse(ThemeResolver.isLight(ThemeMode.DARK, 12, day))
        assertFalse(ThemeResolver.isLight(ThemeMode.DARK, 3, day))
    }

    @Test
    fun light_mode_ignores_hour() {
        assertTrue(ThemeResolver.isLight(ThemeMode.LIGHT, 3, day))
        assertTrue(ThemeResolver.isLight(ThemeMode.LIGHT, 23, day))
    }

    // ---------- 时段边界（含开始、不含结束） ----------

    @Test
    fun time_mode_boundaries() {
        assertFalse("06:59 应为深色", ThemeResolver.isLight(ThemeMode.TIME, 6, day))
        assertTrue("07:00 应为浅色", ThemeResolver.isLight(ThemeMode.TIME, 7, day))
        assertTrue("18:59 应为浅色", ThemeResolver.isLight(ThemeMode.TIME, 18, day))
        assertFalse("19:00 应为深色", ThemeResolver.isLight(ThemeMode.TIME, 19, day))
    }

    @Test
    fun time_mode_cross_midnight_window() {
        val nightLight = ThemeResolver.Window(20, 6)   // 20:00–06:00 算浅色
        assertTrue(ThemeResolver.isLightHour(21, nightLight))
        assertTrue(ThemeResolver.isLightHour(2, nightLight))
        assertFalse(ThemeResolver.isLightHour(12, nightLight))
        assertFalse(ThemeResolver.isLightHour(6, nightLight))
    }

    @Test
    fun invalid_window_falls_back_to_dark() {
        val broken = ThemeResolver.Window(9, 9)
        assertFalse(ThemeResolver.isLight(ThemeMode.TIME, 12, broken))
        assertNull(ThemeResolver.millisUntilNextFlip(at(12), broken))
    }

    @Test
    fun hours_are_clamped_not_crashing() {
        val wild = ThemeResolver.Window(-5, 40)
        assertEquals(0, wild.clamped().startHour)
        assertEquals(23, wild.clamped().endHour)
        assertFalse(ThemeResolver.isLightHour(99, day))
    }

    // ---------- 到点排程 ----------

    @Test
    fun next_flip_from_0659_is_within_a_minute() {
        val delay = ThemeResolver.millisUntilNextFlip(at(6, 59), day)
        assertNotNull(delay)
        assertTrue("距 07:00 不应超过 2 分钟，实际 $delay", delay!! <= 2 * 60_000L)
        assertTrue(delay > 0)
    }

    @Test
    fun next_flip_from_0701_is_about_twelve_hours() {
        val delay = ThemeResolver.millisUntilNextFlip(at(7, 1), day)!!
        val hours = delay / 3_600_000.0
        assertTrue("距 19:00 应≈11.98h，实际 $hours", hours > 11.9 && hours < 12.1)
    }

    @Test
    fun next_flip_crosses_midnight() {
        val nightLight = ThemeResolver.Window(20, 6)
        val delay = ThemeResolver.millisUntilNextFlip(at(23), nightLight)!!
        // 23:00 → 次日 06:00 是 7 小时；绝不能算成「已过点」
        val hours = delay / 3_600_000.0
        assertTrue("跨午夜应≈7h，实际 $hours", hours > 6.9 && hours < 7.1)
    }

    @Test
    fun next_flip_respects_timezone() {
        val utc = ThemeResolver.millisUntilNextFlip(at(6, 58, "UTC"), day, TimeZone.getTimeZone("UTC"))!!
        assertTrue(utc <= 2 * 60_000L)
    }

    // ---------- 持久化值 ----------

    @Test
    fun persisted_values_map_safely() {
        assertEquals(ThemeMode.LIGHT, ThemeMode.of("light"))
        assertEquals(ThemeMode.TIME, ThemeMode.of("TIME"))
        assertEquals(ThemeMode.DARK, ThemeMode.of("system"))     // 旧值/脏值都退回深色
        assertEquals(ThemeMode.DARK, ThemeMode.of(null))
    }
}
