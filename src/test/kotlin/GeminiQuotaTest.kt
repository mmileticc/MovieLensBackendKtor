package com.milinko

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class GeminiQuotaTest {

    /** A clock the test can move forward. */
    private class MutableClock(var now: Instant, private val zone: ZoneId = ZoneOffset.UTC) : Clock() {
        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId): Clock = ZonedView(this, zone)
        override fun instant(): Instant = now
    }

    /** The same moving clock seen in another time zone. */
    private class ZonedView(private val base: MutableClock, private val zone: ZoneId) : Clock() {
        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId): Clock = ZonedView(base, zone)
        override fun instant(): Instant = base.instant()
    }

    private fun quota(clock: Clock = Clock.systemUTC(), total: Int = 100, perClient: Int = 2) = GeminiQuota(
        dailyTotal = mapOf(GeminiFeature.VISION to total, GeminiFeature.SEMANTIC to total),
        dailyPerClient = mapOf(GeminiFeature.VISION to perClient, GeminiFeature.SEMANTIC to perClient),
        clock = clock
    )

    @Test
    fun `a client stops at its daily limit, others go on`() {
        val q = quota(perClient = 2)
        assertEquals(GeminiQuotaResult.OK, q.tryAcquire(GeminiFeature.VISION, "a"))
        assertEquals(GeminiQuotaResult.OK, q.tryAcquire(GeminiFeature.VISION, "a"))
        assertEquals(GeminiQuotaResult.CLIENT_LIMIT, q.tryAcquire(GeminiFeature.VISION, "a"))
        assertEquals(GeminiQuotaResult.OK, q.tryAcquire(GeminiFeature.VISION, "b"))
        // The features have separate per-client limits.
        assertEquals(GeminiQuotaResult.OK, q.tryAcquire(GeminiFeature.SEMANTIC, "a"))
    }

    @Test
    fun `the daily total stops everyone, even many different clients`() {
        val q = quota(total = 3, perClient = 100)
        repeat(3) { assertEquals(GeminiQuotaResult.OK, q.tryAcquire(GeminiFeature.SEMANTIC, "ip$it")) }
        assertEquals(GeminiQuotaResult.DAILY_LIMIT, q.tryAcquire(GeminiFeature.SEMANTIC, "fresh-ip"))
        // Each feature has its own total (separate Gemini quotas).
        assertEquals(GeminiQuotaResult.OK, q.tryAcquire(GeminiFeature.VISION, "user"))
    }

    @Test
    fun `everything starts over the next day in Belgrade`() {
        // 21:30 UTC = 23:30 on 14 Oct in Belgrade (UTC+2 in October); 22:30 UTC = 00:30 on 15 Oct.
        val clock = MutableClock(Instant.parse("2026-10-14T21:30:00Z"))
        val q = quota(clock = clock, total = 1)
        assertEquals(GeminiQuotaResult.OK, q.tryAcquire(GeminiFeature.VISION, "a"))
        assertEquals(GeminiQuotaResult.DAILY_LIMIT, q.tryAcquire(GeminiFeature.VISION, "a"))
        clock.now = Instant.parse("2026-10-14T22:30:00Z")
        assertEquals(GeminiQuotaResult.OK, q.tryAcquire(GeminiFeature.VISION, "a"))
    }
}
