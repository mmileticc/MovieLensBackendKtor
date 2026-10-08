package com.milinko

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.milinko.db.FriendshipsRepository
import com.milinko.db.MoviesRepository
import com.milinko.db.ScrapedMoviesRepository
import com.milinko.db.TasteProfileRepository
import com.milinko.db.UserActionsRepository
import com.milinko.db.UserDao
import com.milinko.services.GeminiService
import com.milinko.services.TmdbService
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Instant
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Provere pristupa i validacije koje se dešavaju PRE bilo kakvog poziva baze ili spoljnog servisa,
 * pa se testiraju bez Supabase-a, TMDB-a i Gemini-ja (repozitorijumi/servisi se prave, ali se ne
 * pozivaju).
 */
class RoutesSecurityTest {

    private val signingKey = "test-signing-key"

    private fun token(
        userId: String,
        issuer: String? = JWT_ISSUER,
        key: String = signingKey
    ): String {
        val builder = JWT.create()
            .withSubject(userId)
            .withAudience(JWT_AUDIENCE)
            .withExpiresAt(Date.from(Instant.now().plusSeconds(3600)))
        if (issuer != null) builder.withIssuer(issuer)
        return builder.sign(Algorithm.HMAC256(key))
    }

    private fun Application.baseModule() {
        install(ContentNegotiation) { json() }
        configureErrorHandling()
        configureAuthentication(signingKey)
        configureRateLimiting()
    }

    private fun secureApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        // Prazna konfiguracija - bez nje bi test učitao application.yaml i pravi module().
        environment { config = MapApplicationConfig() }
        application {
            baseModule()
            val tmdb = TmdbService(apiKey = "test")
            configureUserActionsRouting(
                tmdb, UserActionsRepository(), MoviesRepository(), TasteProfileRepository(), UserDao(), FriendshipsRepository()
            )
            configureRouting(GeminiService(apiKey = "test"), tmdb, ScrapedMoviesRepository(), UserDao(), signingKey)
        }
        block()
    }

    @Test
    fun `personal data requires a token`() = secureApp {
        val response = client.get("/api/users/u1/watchlist")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `token signed with another key is rejected`() = secureApp {
        val response = client.get("/api/users/u1/watchlist") {
            header(HttpHeaders.Authorization, "Bearer ${token("u1", key = "wrong-key")}")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `token with a foreign issuer is rejected`() = secureApp {
        val response = client.get("/api/users/u1/watchlist") {
            header(HttpHeaders.Authorization, "Bearer ${token("u1", issuer = "someone-else")}")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `user cannot read another user's list`() = secureApp {
        val response = client.get("/api/users/u2/watchlist") {
            header(HttpHeaders.Authorization, "Bearer ${token("u1")}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `old token without issuer is still accepted during the transition`() = secureApp {
        // Tuđi userId -> 403 znači da je token PRIHVAĆEN (inače bi bilo 401), a da do baze nije došlo.
        val response = client.get("/api/users/u2/watchlist") {
            header(HttpHeaders.Authorization, "Bearer ${token("u1", issuer = null)}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `vision rejects files that are not images and is rate limited`() = secureApp {
        val auth = "Bearer ${token("vision-user")}"
        val statuses = (1..12).map {
            client.submitFormWithBinaryData(
                url = "/chat/vision",
                formData = formData {
                    append("image", "# not an image".toByteArray(), Headers.build {
                        append(HttpHeaders.ContentType, "image/jpeg")
                        append(HttpHeaders.ContentDisposition, "filename=\"fake.jpg\"")
                    })
                }
            ) { header(HttpHeaders.Authorization, auth) }.status
        }

        assertEquals(List(10) { HttpStatusCode.UnsupportedMediaType }, statuses.take(10))
        assertEquals(List(2) { HttpStatusCode.TooManyRequests }, statuses.drop(10))
    }

    @Test
    fun `rate limit response carries a localized message`() = secureApp {
        val auth = "Bearer ${token("vision-user-2")}"
        repeat(10) {
            client.submitFormWithBinaryData("/chat/vision", formData {
                append("image", byteArrayOf(1), Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"x.jpg\"")
                })
            }) { header(HttpHeaders.Authorization, auth) }
        }
        val response = client.submitFormWithBinaryData("/chat/vision", formData {
            append("image", byteArrayOf(1), Headers.build {
                append(HttpHeaders.ContentDisposition, "filename=\"x.jpg\"")
            })
        }) {
            header(HttpHeaders.Authorization, auth)
            header(HttpHeaders.AcceptLanguage, "en")
        }

        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        assertTrue(response.bodyAsText().contains("Too many requests"), response.bodyAsText())
    }
}
