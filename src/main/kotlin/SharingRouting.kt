package com.milinko

import com.milinko.db.FriendshipStatus
import com.milinko.db.FriendshipsRepository
import com.milinko.db.MoviesRepository
import com.milinko.db.UserDao
import com.milinko.services.PushService
import com.milinko.services.PushType
import com.milinko.services.TmdbService
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("SharingRouting")

private const val ANDROID_PACKAGE = "dev.milinko.movielens"
private const val SHARE_NOTE_MAX_LENGTH = 200

@Serializable
data class ShareMovieRequest(val friendId: String, val tmdbId: Int, val note: String? = null)

private fun ApplicationCall.ownerUserIdOrNull(): String? {
    val pathUserId = parameters["userId"] ?: return null
    val tokenUserId = principal<JWTPrincipal>()?.payload?.subject
    return if (pathUserId == tokenUserId) pathUserId else null
}

private fun String.escapeHtml(): String =
    replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

/**
 * Deljenje filma:
 * - `GET /m/{tmdbId}` - javna stranica za link koji korisnik pošalje bilo kome. Messengeri iz
 *   OpenGraph tagova prave pregled (poster + naslov); ako je aplikacija instalirana, Android isti
 *   link otvara direktno u njoj (App Links, videti assetlinks.json ispod).
 * - `GET /.well-known/assetlinks.json` - dokaz da aplikacija sme da otvara ove linkove. SHA-256
 *   otisci ključeva kojima je APK potpisan dolaze iz ANDROID_CERT_SHA256 (više njih odvojeno zarezom).
 * - `POST /api/users/{userId}/share` - slanje filma prijatelju unutar aplikacije (push obaveštenje).
 */
fun Application.configureSharingRouting(
    tmdbService: TmdbService,
    moviesRepository: MoviesRepository,
    friendshipsRepository: FriendshipsRepository,
    userDao: UserDao,
    pushService: PushService
) {
    val certFingerprints = System.getenv("ANDROID_CERT_SHA256")
        ?.split(",")
        ?.map { it.trim().uppercase() }
        ?.filter { it.isNotEmpty() }
        .orEmpty()
    // Opciono: link za preuzimanje aplikacije (Play prodavnica ili APK) na stranici filma.
    val appDownloadUrl = System.getenv("APP_DOWNLOAD_URL")?.trim()?.ifBlank { null }

    routing {
        get("/.well-known/assetlinks.json") {
            val body = if (certFingerprints.isEmpty()) "[]" else {
                val prints = certFingerprints.joinToString(",") { "\"$it\"" }
                """[{"relation":["delegate_permission/common.handle_all_urls"],""" +
                    """"target":{"namespace":"android_app","package_name":"$ANDROID_PACKAGE","sha256_cert_fingerprints":[$prints]}}]"""
            }
            call.respondText(body, ContentType.Application.Json)
        }

        rateLimit(RATE_LIMIT_PUBLIC) {
            get("/m/{tmdbId}") {
                val tmdbId = call.parameters["tmdbId"]?.toIntOrNull()
                    ?: return@get call.respondText("Not found", status = HttpStatusCode.NotFound)
                val detail = try {
                    tmdbService.getMovieDetails(tmdbId)
                } catch (e: Exception) {
                    logger.warn("Stranica za deljenje: TMDB detalji za $tmdbId nisu uspeli: ${e.message}")
                    null
                } ?: return@get call.respondText("Not found", status = HttpStatusCode.NotFound)

                val title = detail.title.escapeHtml()
                val year = detail.releaseDate?.take(4)?.takeIf { it.length == 4 }?.let { " ($it)" }.orEmpty()
                val overview = detail.overview.orEmpty().escapeHtml()
                val shortOverview = if (overview.length > 200) overview.take(197) + "..." else overview
                val poster = detail.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
                val downloadButton = appDownloadUrl?.let {
                    """<a class="btn secondary" href="${it.escapeHtml()}">Preuzmi Movienee</a>"""
                }.orEmpty()

                call.response.header(HttpHeaders.CacheControl, "public, max-age=3600")
                call.respondText(
                    """
                    <!doctype html>
                    <html lang="sr">
                    <head>
                    <meta charset="utf-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <title>$title$year · Movienee</title>
                    <meta property="og:type" content="video.movie">
                    <meta property="og:title" content="$title$year">
                    <meta property="og:description" content="$shortOverview">
                    ${poster?.let { "<meta property=\"og:image\" content=\"$it\">" }.orEmpty()}
                    <meta name="twitter:card" content="summary_large_image">
                    <style>
                      body{margin:0;font-family:system-ui,sans-serif;background:#0f1115;color:#eceef2;display:flex;justify-content:center}
                      main{max-width:420px;padding:24px 16px;text-align:center}
                      img{width:100%;max-width:300px;border-radius:12px}
                      h1{font-size:1.4rem;margin:16px 0 8px}
                      p{color:#b4b9c4;line-height:1.5}
                      .btn{display:block;margin:12px 0;padding:14px;border-radius:999px;background:#e5b84b;color:#111;text-decoration:none;font-weight:600}
                      .btn.secondary{background:transparent;border:1px solid #e5b84b;color:#e5b84b}
                    </style>
                    </head>
                    <body><main>
                    ${poster?.let { "<img src=\"$it\" alt=\"$title\">" }.orEmpty()}
                    <h1>$title$year</h1>
                    <p>$overview</p>
                    <a class="btn" href="https://www.themoviedb.org/movie/$tmdbId">Detalji na TMDB-u</a>
                    $downloadButton
                    </main></body>
                    </html>
                    """.trimIndent(),
                    ContentType.Text.Html
                )
            }
        }

        authenticate("auth-jwt") {
            post("/api/users/{userId}/share") {
                val userId = call.ownerUserIdOrNull()
                    ?: return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Nemaš pristup ovoj akciji"))
                val req = call.receive<ShareMovieRequest>()
                if (friendshipsRepository.getFriendshipStatus(userId, req.friendId) != FriendshipStatus.ACCEPTED) {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Film možeš poslati samo prijatelju"))
                }
                // Film se kešira da bi naslov bio poznat (i za istoriju obaveštenja).
                moviesRepository.ensureCached(req.tmdbId, tmdbService)
                val title = moviesRepository.getTitle(req.tmdbId.toLong()).orEmpty()
                val name = userDao.findById(userId)?.username ?: "Korisnik"
                val note = req.note?.trim()?.take(SHARE_NOTE_MAX_LENGTH)?.ifBlank { null }

                pushService.notifyAsync(
                    req.friendId, PushType.MOVIE_SHARED, "$name ti preporučuje \"$title\"",
                    buildMap {
                        put("actorId", userId)
                        put("actorName", name)
                        put("tmdbId", req.tmdbId.toString())
                        put("movieTitle", title)
                        note?.let { put("note", it) }
                    }
                )
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
