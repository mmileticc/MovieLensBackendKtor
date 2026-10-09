package com.milinko.services

import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.MessagingErrorCode
import com.google.firebase.messaging.MulticastMessage
import com.milinko.db.DeviceTokensRepository
import com.milinko.db.NotificationsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("PushService")

/** Notification types. The name is sent to the app as `type` and stored in the notifications table. */
enum class PushType {
    FRIEND_REQUEST,
    FRIEND_ACCEPTED,
    COMMENT_LIKED,
    MOVIE_SHARED,
    WATCHLIST_IN_CINEMA
}

/**
 * Sends push notifications through FCM.
 *
 * Only "data" messages are sent (type plus details such as the friend's name and the movie); the
 * app builds the text from its own strings, so it is always in the app's language and respects
 * the user's per-type settings.
 *
 * Every notification is also stored in `notifications` (history shown in the app), even when the
 * user has no registered device.
 */
class PushService(
    private val deviceTokens: DeviceTokensRepository,
    private val notifications: NotificationsRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Fire-and-forget variant for routes: the response to the user never waits for FCM. */
    fun notifyAsync(userId: String, type: PushType, inboxText: String, data: Map<String, String>, dedupeKey: String? = null) {
        scope.launch { notify(userId, type, inboxText, data, dedupeKey) }
    }

    /**
     * Stores the notification and sends it to all of the user's devices.
     *
     * @param inboxText readable Serbian text for `notifications.content` (the column is required).
     * @param dedupeKey if set and already stored for this user, nothing is sent.
     * @return false if it was a duplicate or sending failed.
     */
    suspend fun notify(userId: String, type: PushType, inboxText: String, data: Map<String, String>, dedupeKey: String? = null): Boolean {
        try {
            if (dedupeKey != null && notifications.exists(userId, dedupeKey)) return false
            val payload = JsonObject(data.mapValues { JsonPrimitive(it.value) }).let { Json.encodeToString(JsonObject.serializer(), it) }
            notifications.add(userId, type.name, inboxText, payload, dedupeKey)

            if (FirebaseApp.getApps().isEmpty()) {
                logger.warn("$type za $userId: Firebase nije inicijalizovan - upisano samo u istoriju.")
                return true
            }
            val tokens = deviceTokens.tokensFor(userId)
            if (tokens.isEmpty()) {
                logger.info("$type za $userId: korisnik nema registrovan uređaj - upisano samo u istoriju.")
                return true
            }

            val message = MulticastMessage.builder()
                .addAllTokens(tokens)
                .putAllData(data + ("type" to type.name))
                // High priority: a data message has to wake the app so it can show the notification.
                .setAndroidConfig(AndroidConfig.builder().setPriority(AndroidConfig.Priority.HIGH).build())
                .build()
            val response = FirebaseMessaging.getInstance().sendEachForMulticast(message)
            logger.info("$type za $userId: poslato na ${response.successCount}/${tokens.size} uređaja.")
            response.responses.filter { !it.isSuccessful }.forEach {
                logger.warn("$type za $userId: FCM greška ${it.exception?.messagingErrorCode}: ${it.exception?.message}")
            }

            val invalid = response.responses.mapIndexedNotNull { index, r ->
                val code = r.exception?.messagingErrorCode
                if (!r.isSuccessful && (code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.INVALID_ARGUMENT)) tokens[index] else null
            }
            if (invalid.isNotEmpty()) {
                deviceTokens.deleteTokens(invalid)
                logger.info("Obrisano ${invalid.size} nevažećih FCM tokena za korisnika $userId")
            }
            return true
        } catch (e: Exception) {
            // Push is not critical: only log, the user's action has already succeeded.
            logger.warn("Slanje obaveštenja $type korisniku $userId nije uspelo: ${e.message}")
            return false
        }
    }
}
