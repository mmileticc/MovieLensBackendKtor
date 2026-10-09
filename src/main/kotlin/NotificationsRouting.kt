package com.milinko

import com.milinko.db.DeviceTokensRepository
import com.milinko.db.NotificationsRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class DeviceTokenRequest(val token: String)

@Serializable
data class NotificationDto(
    val id: Int,
    val type: String?,
    val content: String,
    val data: Map<String, String> = emptyMap(),
    val isRead: Boolean,
    val createdAt: Long?
)

@Serializable
data class NotificationsResponseDto(val notifications: List<NotificationDto>)

private const val MAX_TOKEN_LENGTH = 512

private fun ApplicationCall.ownerUserIdOrNull(): String? {
    val pathUserId = parameters["userId"] ?: return null
    val tokenUserId = principal<JWTPrincipal>()?.payload?.subject
    return if (pathUserId == tokenUserId) pathUserId else null
}

private fun decodePayload(payload: String?): Map<String, String> =
    payload?.let {
        runCatching {
            Json.parseToJsonElement(it).let { el -> (el as JsonObject).mapValues { (_, v) -> v.jsonPrimitive.content } }
        }.getOrNull()
    }.orEmpty()

/**
 * Device registration for push notifications and the notification history:
 * - `PUT|DELETE /api/users/{userId}/device-token`
 * - `GET /api/users/{userId}/notifications`, `POST .../notifications/read`
 */
fun Application.configureNotificationsRouting(
    deviceTokensRepository: DeviceTokensRepository,
    notificationsRepository: NotificationsRepository
) {
    routing {
        authenticate("auth-jwt") {
            route("/api/users/{userId}") {
                // Called after sign-in and whenever FCM issues a new token (onNewToken).
                put("/device-token") {
                    val userId = call.ownerUserIdOrNull()
                        ?: return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("Nemaš pristup ovoj akciji"))
                    val token = call.receive<DeviceTokenRequest>().token.trim()
                    require(token.isNotEmpty() && token.length <= MAX_TOKEN_LENGTH) { "Neispravan zahtev" }
                    deviceTokensRepository.register(userId, token)
                    call.respond(HttpStatusCode.NoContent)
                }

                // Sign-out: the device stops receiving notifications for this account.
                delete("/device-token") {
                    val userId = call.ownerUserIdOrNull()
                        ?: return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("Nemaš pristup ovoj akciji"))
                    val token = call.request.queryParameters["token"]?.trim().orEmpty()
                    require(token.isNotEmpty()) { "Neispravan zahtev" }
                    deviceTokensRepository.unregister(userId, token)
                    call.respond(HttpStatusCode.NoContent)
                }

                get("/notifications") {
                    val userId = call.ownerUserIdOrNull()
                        ?: return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Nemaš pristup ovoj listi"))
                    val rows = notificationsRepository.latest(userId)
                    call.respond(
                        NotificationsResponseDto(
                            rows.map {
                                NotificationDto(
                                    id = it.id,
                                    type = it.type,
                                    content = it.content,
                                    data = decodePayload(it.payload),
                                    isRead = it.isRead,
                                    createdAt = it.createdAt?.toInstant(TimeZone.UTC)?.toEpochMilliseconds()
                                )
                            }
                        )
                    )
                }

                post("/notifications/read") {
                    val userId = call.ownerUserIdOrNull()
                        ?: return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Nemaš pristup ovoj akciji"))
                    notificationsRepository.markAllRead(userId)
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }
}
