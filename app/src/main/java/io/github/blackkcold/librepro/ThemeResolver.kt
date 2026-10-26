package io.github.blackkcold.librepro

import java.util.Calendar
import java.util.TimeZone

/**
 * 主题模式。
 *
 * 只有三种：深色 / 浅色 / 按时间。
 * **刻意没有「跟随系统」**：实测这台小米电视 `cmd uimode night` 返回 no、
 * 且 `dumpsys uimode` 里 `mNightModeLocked=true` —— 系统恒为浅色且用户改不了，
 * 跟随系统等于「永远浅色」，既不可预期也没法验证。要自动就按时段自动。
 */
enum class ThemeMode {
    DARK, LIGHT, TIME;

    /** 持久化值 → 枚举。未知值退回深色（与默认值一致）。 */
    companion object {
        fun of(raw: String?): ThemeMode = when (raw?.trim()?.lowercase()) {
            "light" -> LIGHT
            "time" -> TIME
            else -> DARK
        }
    }
}

/**
 * 纯函数解析器：**不碰 Context**，所以能在 JVM 单测里直接跑（项目其他测试也是这个路子）。
 * 时间用 [java.util.Calendar] 而不是 java.time —— minSdk 23 且没有开 core library desugaring，
 * java.time 在 API 26 以下会崩。
 */
object ThemeResolver {

    /** 浅色时段（含开始、不含结束）。默认 07:00–19:00。 */
    data class Window(val startHour: Int = 7, val endHour: Int = 19) {
        fun clamped(): Window = Window(startHour.coerceIn(0, 23), endHour.coerceIn(0, 23))
        val valid: Boolean get() = clamped().let { it.startHour != it.endHour }
    }

    fun isLight(mode: ThemeMode, hour: Int, win: Window): Boolean = when (mode) {
        ThemeMode.LIGHT -> true
        ThemeMode.DARK -> false
        ThemeMode.TIME -> isLightHour(hour, win)
    }

    /**
     * 07–19 是正常区间；20–06 是跨午夜区间。
     * 开始 == 结束 视为非法窗口 → 恒深色（不做「全天亮」这种半吊子语义）。
     */
    fun isLightHour(hour: Int, win: Window): Boolean {
        val w = win.clamped()
        if (w.startHour == w.endHour) return false
        val h = hour.coerceIn(0, 23)
        return if (w.startHour < w.endHour) {
            h >= w.startHour && h < w.endHour
        } else {
            h >= w.startHour || h < w.endHour
        }
    }

    fun currentHour(now: Long = System.currentTimeMillis(), tz: TimeZone = TimeZone.getDefault()): Int {
        val cal = Calendar.getInstance(tz)
        cal.timeInMillis = now
        return cal.get(Calendar.HOUR_OF_DAY)
    }

    /**
     * 距下一次翻转的毫秒数，用于 Handler.postDelayed。
     * 窗口非法返回 null（不必排程）。跨午夜、跨夏令时都由 Calendar 自己算，不手推 24 小时。
     */
    fun millisUntilNextFlip(now: Long, win: Window, tz: TimeZone = TimeZone.getDefault()): Long? {
        val w = win.clamped()
        if (w.startHour == w.endHour) return null
        var best = Long.MAX_VALUE
        for (dayOffset in 0..2) {
            for (hour in intArrayOf(w.startHour, w.endHour)) {
                val cal = Calendar.getInstance(tz)
                cal.timeInMillis = now
                cal.add(Calendar.DAY_OF_MONTH, dayOffset)
                cal.set(Calendar.HOUR_OF_DAY, hour)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                val t = cal.timeInMillis
                if (t > now && t < best) best = t
            }
        }
        return if (best == Long.MAX_VALUE) null else best - now
    }
}
