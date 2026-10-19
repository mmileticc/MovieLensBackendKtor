package com.milinko

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.milinko.db.DeviceTokensRepository
import com.milinko.db.FriendshipsRepository
import com.milinko.db.NotificationsRepository
import com.milinko.db.TasteProfileRepository
import com.milinko.db.UserActionsRepository
import com.milinko.db.UserDao
import com.milinko.services.PushService
import com.milinko.services.TmdbService
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import java.time.Instant
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals

class FeedRoutingTest {

    private val signingKey = "test-signing-key"

    private fun token(userId: String) = JWT.create()
        .withSubject(userId)
        .withIssuer(JWT_ISSUER)
        .withAudience(JWT_AUDIENCE)
        .withExpiresAt(Date.from(Instant.now().plusSeconds(3600)))
        .sign(Algorithm.HMAC256(signingKey))

    @Test
    fun `only the owner can read their feed`() = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            install(ContentNegotiation) { json() }
            configureAuthentication(signingKey)
            configureFriendsRouting(
                FriendshipsRepository(), UserDao(), UserActionsRepository(), TasteProfileRepository(),
                TmdbService(apiKey = "test"), PushService(DeviceTokensRepository(), NotificationsRepository())
            )
        }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/users/u1/feed").status)
        val response = client.get("/api/users/u1/feed") { header(HttpHeaders.Authorization, "Bearer ${token("u2")}") }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }
}
