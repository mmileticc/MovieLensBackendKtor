package com.milinko

import com.milinko.db.FriendshipsRepository
import com.milinko.db.UserDao
import com.milinko.services.PushService
import com.milinko.services.PushType
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.host
import io.ktor.server.request.userAgent
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Only codes that can exist are looked up (letters and digits, see `newInviteCode`). */
private val INVITE_CODE = Regex("^[A-Za-z0-9]{6,16}$")

@Serializable
data class MyInviteDto(val code: String, val url: String)

@Serializable
data class InviterDto(
    @SerialName("inviter_id") val inviterId: String,
    val username: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null
)

@Serializable
data class InviteAcceptedDto(
    @SerialName("friend_id") val friendId: String,
    @SerialName("already_friends") val alreadyFriends: Boolean
)

private fun ApplicationCall.signedInUserId(): String? =
    principal<JWTPrincipal>()?.payload?.subject?.takeIf { it.isNotBlank() }

/**
 * Personal invite links ("Add me on Movienee"):
 * - `GET /api/users/{userId}/invite` - the owner's code and link (created on first use);
 *   `POST /api/users/{userId}/invite/reset` - a new code, the old link stops working.
 * - `GET /api/invite/{code}` - who invites (public: name and avatar only, never the e-mail).
 * - `POST /api/invite/{code}/accept` - the signed-in user becomes the inviter's friend right away
 *   (sharing the link is the inviter's consent); the inviter is notified.
 * - `GET /i/{code}` - the shared link itself. Android opens it in the app (App Links, like `/m/`);
 *   in a browser people go to the web app's invite page, preview crawlers get a page with
 *   OpenGraph tags ("Marko invites you to Movienee").
 */
fun Application.configureInviteRouting(
    userDao: UserDao,
    friendshipsRepository: FriendshipsRepository,
    pushService: PushService,
    webAppUrl: String? = webAppUrlOrNull(System.getenv("WEB_APP_URL")),
    publicBaseUrl: String? = System.getenv("PUBLIC_BASE_URL")?.trim()?.trimEnd('/')?.ifBlank { null }
) {
    fun ApplicationCall.inviteUrl(code: String) = "${publicBaseUrl ?: "https://${request.host()}"}/i/$code"

    routing {
        rateLimit(RATE_LIMIT_PUBLIC) {
            get("/api/invite/{code}") {
                val code = call.parameters["code"]?.takeIf { INVITE_CODE.matches(it) }
                val inviter = code?.let { userDao.findByInviteCode(it) }
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Pozivni link nije važeći"))
                call.respond(InviterDto(inviter.id, inviter.username, inviter.avatarUrl))
            }

            get("/i/{code}") {
                val code = call.parameters["code"]?.takeIf { INVITE_CODE.matches(it) }
                    ?: return@get call.respondText("Not found", status = HttpStatusCode.NotFound)
                call.response.header(HttpHeaders.Vary, HttpHeaders.UserAgent)
                val isPreviewBot = LINK_PREVIEW_BOT.containsMatchIn(call.request.userAgent().orEmpty())
                // People go to the web app, which handles unknown codes itself; no lookup needed.
                if (webAppUrl != null && !isPreviewBot) {
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    return@get call.respondRedirect("$webAppUrl/invite/$code", permanent = false)
                }
                val inviter = userDao.findByInviteCode(code)
                    ?: return@get call.respondText("Not found", status = HttpStatusCode.NotFound)
                val name = (inviter.username ?: "Prijatelj").escapeHtml()
                val image = webAppUrl?.let { "<meta property=\"og:image\" content=\"$it/icons/icon-512.png\">" }.orEmpty()
                val webButton = webAppUrl?.let {
                    """<a class="btn" href="${"$it/invite/$code".escapeHtml()}">Prihvati poziv</a>"""
                }.orEmpty()
                call.response.header(HttpHeaders.CacheControl, "public, max-age=600")
                call.respondText(
                    """
                    <!doctype html>
                    <html lang="sr">
                    <head>
                    <meta charset="utf-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <title>$name te poziva na Movienee</title>
                    <meta property="og:type" content="website">
                    <meta property="og:title" content="$name te poziva na Movienee">
                    <meta property="og:description" content="Šta igra u bioskopima i sa kim da odeš - dodaj me za prijatelja.">
                    $image
                    <style>
                      body{margin:0;font-family:system-ui,sans-serif;background:#0a0a0a;color:#f5f5f5;display:flex;justify-content:center}
                      main{max-width:420px;padding:32px 16px;text-align:center}
                      .btn{display:block;margin:16px 0;padding:14px;border-radius:999px;background:#e50914;color:#fff;text-decoration:none;font-weight:600}
                    </style>
                    </head>
                    <body><main>
                    <h1>$name te poziva na Movienee</h1>
                    <p>Šta igra u bioskopima i sa kim da odeš.</p>
                    $webButton
                    </main></body>
                    </html>
                    """.trimIndent(),
                    ContentType.Text.Html
                )
            }
        }

        authenticate("auth-jwt") {
            get("/api/users/{userId}/invite") {
                val userId = call.parameters["userId"]?.takeIf { it == call.signedInUserId() }
                    ?: return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Nemaš pristup ovoj akciji"))
                val code = userDao.getOrCreateInviteCode(userId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Korisnik nije pronađen"))
                call.respond(MyInviteDto(code, call.inviteUrl(code)))
            }

            post("/api/users/{userId}/invite/reset") {
                val userId = call.parameters["userId"]?.takeIf { it == call.signedInUserId() }
                    ?: return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Nemaš pristup ovoj akciji"))
                val code = userDao.resetInviteCode(userId)
                    ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("Korisnik nije pronađen"))
                call.respond(MyInviteDto(code, call.inviteUrl(code)))
            }

            post("/api/invite/{code}/accept") {
                val userId = call.signedInUserId()
                    ?: return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Nemaš pristup ovoj akciji"))
                val code = call.parameters["code"]?.takeIf { INVITE_CODE.matches(it) }
                val inviter = code?.let { userDao.findByInviteCode(it) }
                    ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("Pozivni link nije važeći"))
                if (inviter.id == userId) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Ovo je tvoj pozivni link"))
                }
                val becameFriends = friendshipsRepository.acceptInvite(inviter.id, userId)
                if (becameFriends) {
                    val name = userDao.findById(userId)?.username ?: "Korisnik"
                    pushService.notifyAsync(
                        inviter.id, PushType.FRIEND_ACCEPTED, "$name je prihvatio tvoj poziv za Movienee",
                        mapOf("actorId" to userId, "actorName" to name)
                    )
                }
                call.respond(InviteAcceptedDto(friendId = inviter.id, alreadyFriends = !becameFriends))
            }
        }
    }
}
