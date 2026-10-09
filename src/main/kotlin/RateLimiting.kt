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

// Per-client request limits. The most important ones guard Gemini calls (image recognition and
// semantic search): every such request costs paid quota, and semantic search is public.
// Exceeding a limit returns 429 (the body is set by StatusPages, see configureErrorHandling).

/** Image recognition: 10 per minute per user. */
val RATE_LIMIT_VISION = RateLimitName("vision")
/** Semantic search: 20 per minute per IP. */
val RATE_LIMIT_SEMANTIC = RateLimitName("semantic")
/** Other public movie routes: 120 per minute per IP. */
val RATE_LIMIT_PUBLIC = RateLimitName("public")

/** Installs the RateLimit plugin with the limits above. */
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
 * Render sits behind a reverse proxy, so `remoteHost` is the proxy's address; the client's real
 * address is the first entry of X-Forwarded-For.
 */
private fun ApplicationCall.clientIp(): String =
    request.headers[HttpHeaders.XForwardedFor]
        ?.split(',')
        ?.firstOrNull()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: request.origin.remoteHost
