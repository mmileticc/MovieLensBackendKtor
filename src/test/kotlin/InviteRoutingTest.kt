package com.milinko

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.milinko.db.DeviceTokensRepository
import com.milinko.db.FriendshipsRepository
import com.milinko.db.NotificationsRepository
import com.milinko.db.UserDao
import com.milinko.services.PushService
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Instant
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals

/** Invite link checks that happen before any database call (no database in tests). */
class InviteRoutingTest {

    private val signingKey = "test-signing-key"

    private fun token(userId: String) = JWT.create()
        .withSubject(userId)
        .withIssuer(JWT_ISSUER)
        .withAudience(JWT_AUDIENCE)
        .withExpiresAt(Date.from(Instant.now().plusSeconds(3600)))
        .sign(Algorithm.HMAC256(signingKey))

    private fun inviteApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            install(ContentNegotiation) { json() }
            configureAuthentication(signingKey)
            configureRateLimiting()
            configureInviteRouting(
                UserDao(), FriendshipsRepository(), PushService(DeviceTokensRepository(), NotificationsRepository()),
                webAppUrl = "https://movienee.web.app"
            )
        }
        client = createClient { followRedirects = false }
        block()
    }

    @Test
    fun `a person opening the link goes to the web app's invite page`() = inviteApp {
        val response = client.get("/i/Ab3dEf7hJk") {
            header(HttpHeaders.UserAgent, "Mozilla/5.0 (Linux; Android 14) Chrome/140.0 Mobile Safari/537.36")
        }
        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals("https://movienee.web.app/invite/Ab3dEf7hJk", response.headers[HttpHeaders.Location])
    }

    @Test
    fun `codes that cannot exist are rejected without a lookup`() = inviteApp {
        assertEquals(HttpStatusCode.NotFound, client.get("/i/ab").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/i/abc-def-ghi").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/invite/x%27or1").status)
    }

    @Test
    fun `only the owner can see or reset their invite link`() = inviteApp {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/users/u1/invite").status)
        val other = token("u2")
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/users/u1/invite") { header(HttpHeaders.Authorization, "Bearer $other") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/users/u1/invite/reset") { header(HttpHeaders.Authorization, "Bearer $other") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/invite/Ab3dEf7hJk/accept").status)
    }
}
