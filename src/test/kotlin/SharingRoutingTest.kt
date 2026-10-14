package com.milinko

import com.milinko.db.DeviceTokensRepository
import com.milinko.db.FriendshipsRepository
import com.milinko.db.MoviesRepository
import com.milinko.db.NotificationsRepository
import com.milinko.db.UserDao
import com.milinko.services.PushService
import com.milinko.services.TmdbService
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The shared-link page `/m/{tmdbId}`: people go straight to the web app, while the decision is made
 * before any TMDB or database call (only the redirect paths are tested, they need no network).
 */
class SharingRoutingTest {

    private val chromeOnAndroid =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36"

    private fun sharingApp(webAppUrl: String?, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureAuthentication("test-signing-key")
            configureRateLimiting()
            configureSharingRouting(
                TmdbService(apiKey = "test"), MoviesRepository(), FriendshipsRepository(), UserDao(),
                PushService(DeviceTokensRepository(), NotificationsRepository()),
                webAppUrl = webAppUrl
            )
        }
        client = createClient { followRedirects = false }
        block()
    }

    @Test
    fun `a person is redirected to the movie in the web app`() = sharingApp("https://movienee.web.app") {
        val response = client.get("/m/550") { header(HttpHeaders.UserAgent, chromeOnAndroid) }
        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals("https://movienee.web.app/movie/550", response.headers[HttpHeaders.Location])
    }

    @Test
    fun `an invalid id is not found and never redirected`() = sharingApp("https://movienee.web.app") {
        for (path in listOf("/m/abc", "/m/0", "/m/-5")) {
            val response = client.get(path) { header(HttpHeaders.UserAgent, chromeOnAndroid) }
            assertEquals(HttpStatusCode.NotFound, response.status, path)
            assertNull(response.headers[HttpHeaders.Location], path)
        }
    }

    @Test
    fun `only an https web app url is accepted`() {
        assertEquals("https://movienee.web.app", webAppUrlOrNull(" https://movienee.web.app/ "))
        assertEquals("http://localhost:4200", webAppUrlOrNull("http://localhost:4200"))
        assertNull(webAppUrlOrNull("http://movienee.web.app"))
        assertNull(webAppUrlOrNull("javascript:alert(1)"))
        assertNull(webAppUrlOrNull(""))
        assertNull(webAppUrlOrNull(null))
    }
}
