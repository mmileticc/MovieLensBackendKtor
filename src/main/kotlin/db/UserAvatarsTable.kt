package com.milinko.db

import com.milinko.db.DatabaseFactory.dbQuery
import com.milinko.detectImageMime
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update
import java.security.MessageDigest
import java.util.Base64
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("UserAvatarsTable")

/**
 * Profilne slike koje je korisnik sam postavio. Ranije su stajale kao base64 data-URI direktno u
 * `users.avatar_url` (do ~400KB), pa je svaki odgovor sa avatarom (komentari, prijatelji,
 * pretraga) nosio celu sliku. Sada `avatar_url` sadrži samo putanju ka [AVATAR_ROUTE], a bajtovi
 * se služe posebno i keširaju na klijentu.
 */
object UserAvatarsTable : Table("user_avatars") {
    val userId = varchar("user_id", 64).references(UsersTable.id)
    val bytes = binary("bytes")
    val mime = varchar("mime", 32)
    val hash = varchar("hash", 64)
    override val primaryKey = PrimaryKey(userId)
}

data class StoredAvatar(val bytes: ByteArray, val mime: String, val hash: String)

/** Putanja rute koja služi avatar; `{userId}` se zamenjuje ID-jem korisnika. */
const val AVATAR_ROUTE = "/api/users/{userId}/avatar"

private fun avatarPath(userId: String, hash: String) = "/api/users/$userId/avatar?v=$hash"

/**
 * Javna osnova servera (npr. https://movienee.onrender.com). Ako je postavljena, relativne putanje
 * avatara se u odgovorima pretvaraju u pune URL-ove - starije verzije Android aplikacije umeju da
 * prikažu samo pun URL. U bazi uvek ostaje relativna putanja, jer lokalni i produkcioni server
 * dele istu bazu.
 */
private val publicBaseUrl: String? = System.getenv("PUBLIC_BASE_URL")?.trim()?.trimEnd('/')?.ifBlank { null }

/** `users.avatar_url` u obliku za klijenta. Koristi se na svakom mestu gde se kolona čita. */
fun publicAvatarUrl(stored: String?): String? =
    if (stored != null && stored.startsWith("/") && publicBaseUrl != null) publicBaseUrl + stored else stored

/**
 * Dekodira `data:image/...;base64,...` URI, proverava da je sadržaj zaista JPEG/PNG, upisuje sliku
 * i vraća putanju koja ide u `users.avatar_url`. Poziva se UNUTAR postojeće transakcije.
 */
internal fun storeAvatarFromDataUri(userId: String, dataUri: String): String {
    val base64 = dataUri.substringAfter("base64,", missingDelimiterValue = "")
    require(dataUri.startsWith("data:image") && base64.isNotEmpty()) { "Nepodržan format slike - koristi JPEG ili PNG" }
    val bytes = try {
        Base64.getMimeDecoder().decode(base64)
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("Nepodržan format slike - koristi JPEG ili PNG")
    }
    val mime = detectImageMime(bytes) ?: throw IllegalArgumentException("Nepodržan format slike - koristi JPEG ili PNG")
    val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
        .take(16)

    val exists = UserAvatarsTable.select { UserAvatarsTable.userId eq userId }.limit(1).any()
    if (exists) {
        UserAvatarsTable.update({ UserAvatarsTable.userId eq userId }) {
            it[UserAvatarsTable.bytes] = bytes
            it[UserAvatarsTable.mime] = mime
            it[UserAvatarsTable.hash] = hash
        }
    } else {
        UserAvatarsTable.insert {
            it[UserAvatarsTable.userId] = userId
            it[UserAvatarsTable.bytes] = bytes
            it[UserAvatarsTable.mime] = mime
            it[UserAvatarsTable.hash] = hash
        }
    }
    return avatarPath(userId, hash)
}

class AvatarRepository {

    suspend fun getAvatar(userId: String): StoredAvatar? = dbQuery {
        UserAvatarsTable.select { UserAvatarsTable.userId eq userId }
            .map { StoredAvatar(it[UserAvatarsTable.bytes], it[UserAvatarsTable.mime], it[UserAvatarsTable.hash]) }
            .firstOrNull()
    }

    /**
     * Jednokratna migracija: avatari koji su još base64 u `users.avatar_url` prebacuju se u
     * [UserAvatarsTable]. Idempotentna - posle prvog prolaska nema više `data:` vrednosti.
     */
    suspend fun migrateInlineAvatars(): Int = dbQuery {
        val inline = UsersTable.select { UsersTable.avatarUrl like "data:%" }
            .map { it[UsersTable.id] to it[UsersTable.avatarUrl]!! }
        var migrated = 0
        for ((userId, dataUri) in inline) {
            try {
                val path = storeAvatarFromDataUri(userId, dataUri)
                UsersTable.update({ UsersTable.id eq userId }) { it[UsersTable.avatarUrl] = path }
                migrated++
            } catch (e: IllegalArgumentException) {
                logger.warn("Avatar korisnika $userId nije validna slika, preskačem migraciju: ${e.message}")
            }
        }
        migrated
    }
}
