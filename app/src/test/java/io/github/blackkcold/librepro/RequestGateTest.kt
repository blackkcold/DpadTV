package io.github.blackkcold.librepro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 闸门是「不被封 IP」的全部依据，规则一旦被改坏必须立刻红。
 * 全部用**虚拟时钟**，不 sleep，毫秒级跑完。
 */
class RequestGateTest {

    private fun gate(
        minGap: Long = 3_000,
        jitter: Long = 900,
        window: Long = 600_000,
        budget: Int = 20,
        base: Long = 600_000,
        max: Long = 7_200_000,
    ) = RequestGate(minGap, jitter, window, budget, base, max, Random(1))

    @Test
    fun first_request_is_immediate() {
        val g = gate()
        val p = g.acquire(1_000)
        assertEquals(RequestGate.Verdict.ALLOW, p.verdict)
        assertEquals(0, p.waitMs)
    }

    @Test
    fun second_request_waits_at_least_min_gap() {
        val g = gate()
        g.markSent(1_000)
        val p = g.acquire(1_200)
        assertEquals(RequestGate.Verdict.ALLOW, p.verdict)
        assertTrue("wait=${p.waitMs}", p.waitMs in 2_800..3_900)
    }

    @Test
    fun request_after_gap_is_immediate_again() {
        val g = gate()
        g.markSent(1_000)
        val p = g.acquire(1_000 + 5_000)
        assertEquals(0, p.waitMs)
    }

    @Test
    fun budget_caps_requests_inside_window() {
        val g = gate(minGap = 0, jitter = 0, window = 60_000, budget = 3)
        var now = 0L
        repeat(3) {
            assertEquals(RequestGate.Verdict.ALLOW, g.acquire(now).verdict)
            g.markSent(now)
            now += 1_000
        }
        assertEquals(RequestGate.Verdict.BUDGET, g.acquire(now).verdict)
    }

    @Test
    fun budget_window_slides() {
        val g = gate(minGap = 0, jitter = 0, window = 60_000, budget = 2)
        g.markSent(0); g.markSent(10_000)
        assertEquals(RequestGate.Verdict.BUDGET, g.acquire(20_000).verdict)
        // 70s 之后第一笔已经滑出窗口
        assertEquals(RequestGate.Verdict.ALLOW, g.acquire(61_000).verdict)
    }

    @Test
    fun rate_limit_enters_exponential_backoff() {
        val g = gate()
        val first = g.onRateLimited(0)
        assertEquals(600_000, first)
        assertEquals(RequestGate.Verdict.COOLDOWN, g.acquire(1).verdict)
        assertEquals(0L, g.cooldownLeft(600_000))
        // 冷却结束后再被风控：翻倍
        assertEquals(1_200_000, g.onRateLimited(600_000))
        assertEquals(2_400_000, g.onRateLimited(2_000_000))
    }

    @Test
    fun backoff_is_capped() {
        val g = gate(base = 600_000, max = 2_400_000)
        var now = 0L
        var last = 0L
        repeat(6) {
            last = g.onRateLimited(now)
            now += last
        }
        assertEquals(2_400_000, last)
    }

    @Test
    fun retry_after_wins_over_our_own_backoff() {
        val g = gate()
        assertEquals(30_000, g.onRateLimited(0, retryAfterSeconds = 30))
    }

    @Test
    fun success_resets_strikes() {
        val g = gate()
        g.onRateLimited(0)
        assertEquals(1, g.strikes())
        g.onSuccess()
        assertEquals(0, g.strikes())
        // 归零后再次风控回到首次的 10 分钟，而不是继续翻倍
        assertEquals(600_000, g.onRateLimited(10_000_000))
    }

    @Test
    fun server_error_cools_down_one_minute() {
        val g = gate()
        g.onServerError(0)
        assertEquals(60_000, g.cooldownLeft(0))
        assertEquals(RequestGate.Verdict.ALLOW, g.acquire(60_001).verdict)
    }
}
