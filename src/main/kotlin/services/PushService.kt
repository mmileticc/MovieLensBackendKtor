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

/** Vrste obaveštenja; ime (`name`) ide klijentu kao `type` i čuva se u notifications tabeli. */
enum class PushType {
    FRIEND_REQUEST,
    FRIEND_ACCEPTED,
    COMMENT_LIKED,
    MOVIE_SHARED,
    WATCHLIST_IN_CINEMA
}

/**
 * Slanje push obaveštenja preko FCM-a. Šalje se samo "data" poruka (tip + podaci, npr. ime
 * prijatelja i film) - tekst sastavlja aplikacija iz svojih stringova, pa je obaveštenje uvek na
 * jeziku aplikacije i poštuje korisnikova podešavanja (isključeni tipovi se ne prikazuju).
 *
 * Svako obaveštenje se upisuje i u `notifications` (istorija/inbox), čak i kad korisnik nema
 * registrovan uređaj.
 */
class PushService(
    private val deviceTokens: DeviceTokensRepository,
    private val notifications: NotificationsRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Za rute: ne čeka slanje, pa push nikad ne usporava odgovor korisniku. */
    fun notifyAsync(userId: String, type: PushType, inboxText: String, data: Map<String, String>, dedupeKey: String? = null) {
        scope.launch { notify(userId, type, inboxText, data, dedupeKey) }
    }

    /**
     * @param inboxText čitljiv opis na srpskom za `notifications.content` (kolona je obavezna).
     * @return false ako je obaveštenje sa istim [dedupeKey] već poslato ovom korisniku.
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
                // Visok prioritet: data poruka mora da probudi aplikaciju da bi prikazala obaveštenje.
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
            // Push nije kritičan - greška se samo loguje, akcija korisnika je već uspela.
            logger.warn("Slanje obaveštenja $type korisniku $userId nije uspelo: ${e.message}")
            return false
        }
    }
}
