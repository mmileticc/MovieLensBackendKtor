package com.milinko

import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/** Features that call Gemini on every request (each call costs quota). */
enum class GeminiFeature { VISION, SEMANTIC }

/** Outcome of [GeminiQuota.tryAcquire]. */
enum class GeminiQuotaResult { OK, CLIENT_LIMIT, DAILY_LIMIT }

/**
 * Daily Gemini budget, on top of the per-minute rate limits: a total per feature for the whole
 * service and a limit per client (user id for image recognition, IP for semantic search). The
 * totals follow the free quotas (image recognition: 2 models x 500 per day; embeddings: 1000 per
 * day, shared with indexing the cinema programme) and protect them even from a client that gets
 * around the per-client limits, e.g. with a forged IP address.
 *
 * Counters live in memory and start over every day at midnight (Belgrade time) - and after a
 * restart, which is acceptable for a single Render instance. A rejected request never adds a client
 * key, so the memory stays bounded by the daily total.
 */
class GeminiQuota(
    private val dailyTotal: Map<GeminiFeature, Int>,
    private val dailyPerClient: Map<GeminiFeature, Int>,
    private val clock: Clock = Clock.systemUTC(),
    private val zone: ZoneId = ZoneId.of("Europe/Belgrade")
) {
    private var day: LocalDate = LocalDate.now(clock.withZone(zone))
    private val totals = HashMap<GeminiFeature, Int>()
    private val perClient = HashMap<String, Int>()

    @Synchronized
    fun tryAcquire(feature: GeminiFeature, clientKey: String): GeminiQuotaResult {
        val today = LocalDate.now(clock.withZone(zone))
        if (today != day) {
            day = today
            totals.clear()
            perClient.clear()
        }
        val total = totals[feature] ?: 0
        if (total >= (dailyTotal[feature] ?: Int.MAX_VALUE)) return GeminiQuotaResult.DAILY_LIMIT
        val key = "${feature.name}:$clientKey"
        val used = perClient[key] ?: 0
        if (used >= (dailyPerClient[feature] ?: Int.MAX_VALUE)) return GeminiQuotaResult.CLIENT_LIMIT
        perClient[key] = used + 1
        totals[feature] = total + 1
        return GeminiQuotaResult.OK
    }

    companion object {
        /**
         * Limits from the environment: VISION_DAILY_LIMIT and SEMANTIC_DAILY_LIMIT (whole service),
         * VISION_DAILY_PER_USER and SEMANTIC_DAILY_PER_CLIENT.
         */
        fun fromEnv(): GeminiQuota {
            fun env(name: String, default: Int) =
                System.getenv(name)?.trim()?.toIntOrNull()?.takeIf { it >= 0 } ?: default
            return GeminiQuota(
                dailyTotal = mapOf(
                    GeminiFeature.VISION to env("VISION_DAILY_LIMIT", 900),
                    GeminiFeature.SEMANTIC to env("SEMANTIC_DAILY_LIMIT", 800)
                ),
                dailyPerClient = mapOf(
                    GeminiFeature.VISION to env("VISION_DAILY_PER_USER", 10),
                    GeminiFeature.SEMANTIC to env("SEMANTIC_DAILY_PER_CLIENT", 30)
                )
            )
        }
    }
}

/** One budget for the whole service (shared by all routes that call Gemini). */
val geminiQuota: GeminiQuota by lazy { GeminiQuota.fromEnv() }

/** User-facing message (localized to English by ErrorMessageLocalization) for a refused request. */
fun GeminiQuotaResult.message(): String = when (this) {
    GeminiQuotaResult.CLIENT_LIMIT -> "Iskoristio si današnji limit za ovu pretragu. Pokušaj ponovo sutra."
    else -> "AI pretraga je za danas iskorišćena. Ostatak aplikacije radi normalno - pokušaj ponovo sutra."
}
