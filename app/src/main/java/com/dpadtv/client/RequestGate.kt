package com.dpadtv.client

import kotlin.random.Random

/**
 * 对外请求的闸门：**节奏 + 配额 + 退避**，纯逻辑、不碰 Android，所以能直接跑 JVM 单测。
 *
 * 为什么单独抽出来：这三条规则是「别被豆瓣封 IP」的全部依据，
 * 埋在 suspend 函数里既测不了，也容易在改动中悄悄失效（比如旧版的 ttlMs 就是这么丢的）。
 *
 * 规则：
 *  1. 相邻两次请求至少间隔 [minGapMs]（再叠加 0..[jitterMs] 的随机抖动，固定节拍最像脚本）；
 *  2. [windowMs] 内最多 [budget] 次请求；
 *  3. 被判定为风控时进入冷却，第一次 [baseBackoffMs]，之后每次翻倍，上限 [maxBackoffMs]；
 *     服务端给了 Retry-After 就听服务端的。
 */
class RequestGate(
    private val minGapMs: Long = 3_000,
    private val jitterMs: Long = 900,
    private val windowMs: Long = 10 * 60_000,
    private val budget: Int = 20,
    private val baseBackoffMs: Long = 10 * 60_000,
    private val maxBackoffMs: Long = 2 * 60 * 60_000,
    private val random: Random = Random.Default,
) {

    enum class Verdict { ALLOW, COOLDOWN, BUDGET }

    /** acquire 的结果：要不要等、等多久、能不能发 */
    data class Permit(val verdict: Verdict, val waitMs: Long = 0, val cooldownMs: Long = 0)

    private var lastSentAt = 0L
    private var cooldownUntil = 0L
    private var strikes = 0
    private val window = ArrayDeque<Long>()

    /** 请求前先要一张票；拿到 ALLOW 才发，按 waitMs 先等。 */
    fun acquire(now: Long): Permit {
        if (now < cooldownUntil) {
            return Permit(Verdict.COOLDOWN, cooldownMs = cooldownUntil - now)
        }
        while (window.isNotEmpty() && now - window.first() >= windowMs) window.removeFirst()
        if (window.size >= budget) return Permit(Verdict.BUDGET)
        val gap = if (lastSentAt == 0L) 0L else (minGapMs + random.nextLong(0, jitterMs + 1)) - (now - lastSentAt)
        return Permit(Verdict.ALLOW, waitMs = if (gap > 0) gap else 0)
    }

    /** 票用掉了：记下实际发出时间，并计入窗口配额。 */
    fun markSent(now: Long) {
        lastSentAt = now
        window.addLast(now)
    }

    fun onSuccess() {
        strikes = 0
    }

    /** 风控：进入冷却并返回本次冷却时长。 */
    fun onRateLimited(now: Long, retryAfterSeconds: Long = 0): Long {
        strikes = (strikes + 1).coerceAtMost(8)
        val backoff = if (retryAfterSeconds > 0) {
            retryAfterSeconds * 1000
        } else {
            var v = baseBackoffMs
            repeat(strikes - 1) { v *= 2 }
            v
        }.coerceAtMost(maxBackoffMs)
        cooldownUntil = now + backoff
        return backoff
    }

    fun onServerError(now: Long) {
        cooldownUntil = now + 60_000
    }

    fun cooldownLeft(now: Long): Long = (cooldownUntil - now).coerceAtLeast(0)

    fun strikes(): Int = strikes

    fun sentInWindow(now: Long): Int {
        while (window.isNotEmpty() && now - window.first() >= windowMs) window.removeFirst()
        return window.size
    }
}
