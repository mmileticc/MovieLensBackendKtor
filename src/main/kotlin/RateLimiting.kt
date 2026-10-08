package com.milinko

import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import kotlin.time.Duration.Companion.minutes

/**
 * Limiti zahteva po klijentu. Najvažniji su oni ispred Gemini poziva (prepoznavanje slike i
 * semantička pretraga): svaki takav zahtev troši plaćenu kvotu, a semantička pretraga je javna.
 * Prekoračenje vraća 429 (telo postavlja StatusPages u Application.kt).
 */
val RATE_LIMIT_VISION = RateLimitName("vision")
val RATE_LIMIT_SEMANTIC = RateLimitName("semantic")
val RATE_LIMIT_PUBLIC = RateLimitName("public")

fun Application.configureRateLimiting() {
    install(RateLimit) {
        register(RATE_LIMIT_VISION) {
            rateLimiter(limit = 10, refillPeriod = 1.minutes)
            requestKey { call -> call.userIdOrNull() ?: call.clientIp() }
        }
        register(RATE_LIMIT_SEMANTIC) {
            rateLimiter(limit = 20, refillPeriod = 1.minutes)
            requestKey { call -> call.clientIp() }
        }
        register(RATE_LIMIT_PUBLIC) {
            rateLimiter(limit = 120, refillPeriod = 1.minutes)
            requestKey { call -> call.clientIp() }
        }
    }
}

private fun ApplicationCall.userIdOrNull(): String? =
    principal<JWTPrincipal>()?.payload?.subject?.takeIf { it.isNotBlank() }

/**
 * Render je iza reverse proxy-ja, pa je `remoteHost` adresa proxy-ja, a ne klijenta. Prava adresa
 * je prva u X-Forwarded-For listi.
 */
private fun ApplicationCall.clientIp(): String =
    request.headers[HttpHeaders.XForwardedFor]
        ?.split(',')
        ?.firstOrNull()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: request.origin.remoteHost
