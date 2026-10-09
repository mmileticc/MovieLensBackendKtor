package com.milinko

import com.milinko.db.AVATAR_ROUTE
import com.milinko.db.AvatarRepository
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/**
 * Public route for profile pictures (no sign-in: avatars also appear in comments that guests can
 * read). The URL carries `?v=<hash>`, so the content behind a URL never changes and clients may
 * cache it forever; a new picture gets a new URL.
 */
fun Application.configureAvatarRouting(avatarRepository: AvatarRepository) {
    routing {
        get(AVATAR_ROUTE) {
            val userId = call.parameters["userId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Nevalidan id"))
            val avatar = avatarRepository.getAvatar(userId)
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Korisnik nije pronađen"))

            val etag = "\"${avatar.hash}\""
            call.response.header(HttpHeaders.ETag, etag)
            call.response.header(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
            if (call.request.headers[HttpHeaders.IfNoneMatch] == etag) {
                return@get call.respond(HttpStatusCode.NotModified)
            }
            call.respondBytes(avatar.bytes, ContentType.parse(avatar.mime))
        }
    }
}
