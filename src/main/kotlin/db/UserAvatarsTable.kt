package com.milinko.db

import com.milinko.db.DatabaseFactory.dbQuery
import com.milinko.detectImageMime
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update
import java.security.MessageDigest
import java.util.Base64
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("UserAvatarsTable")

/**
 * Profile pictures uploaded by users. `users.avatar_url` stores only the path to [AVATAR_ROUTE];
 * the bytes are served separately so that every response containing an avatar (comments, friends,
 * search) stays small and the app can cache the image.
 */
object UserAvatarsTable : Table("user_avatars") {
    val userId = varchar("user_id", 64).references(UsersTable.id)
    val bytes = binary("bytes")
    val mime = varchar("mime", 32)
    val hash = varchar("hash", 64)
    override val primaryKey = PrimaryKey(userId)
}

/** Avatar bytes with their content type and a short content hash (used as `?v=` and ETag). */
data class StoredAvatar(val bytes: ByteArray, val mime: String, val hash: String)

/** Route that serves an avatar; `{userId}` is replaced with the user's id. */
const val AVATAR_ROUTE = "/api/users/{userId}/avatar"

private fun avatarPath(userId: String, hash: String) = "/api/users/$userId/avatar?v=$hash"

/**
 * Public base URL of the server (e.g. https://movielensbackendktor.onrender.com). When set, stored
 * avatar paths are returned as absolute URLs, because older app versions can only load absolute
 * URLs. The database always keeps the relative path, since the local and the production server
 * share one database.
 */
private val publicBaseUrl: String? = System.getenv("PUBLIC_BASE_URL")?.trim()?.trimEnd('/')?.ifBlank { null }

/** `users.avatar_url` as returned to clients. Use it wherever that column is read. */
fun publicAvatarUrl(stored: String?): String? =
    if (stored != null && stored.startsWith("/") && publicBaseUrl != null) publicBaseUrl + stored else stored

/**
 * Decodes a `data:image/...;base64,...` URI, checks that the content really is JPEG or PNG, stores
 * the image and returns the path to put into `users.avatar_url`. Must be called inside an open
 * transaction.
 *
 * @throws IllegalArgumentException if the data is not a valid JPEG/PNG image.
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

    val exists = UserAvatarsTable.selectAll().where { UserAvatarsTable.userId eq userId }.limit(1).any()
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

/** Reads [UserAvatarsTable] and migrates old inline avatars. */
class AvatarRepository {

    /** The stored avatar of the user, or null if they never uploaded one. */
    suspend fun getAvatar(userId: String): StoredAvatar? = dbQuery {
        UserAvatarsTable.selectAll().where { UserAvatarsTable.userId eq userId }
            .map { StoredAvatar(it[UserAvatarsTable.bytes], it[UserAvatarsTable.mime], it[UserAvatarsTable.hash]) }
            .firstOrNull()
    }

    /**
     * One-time migration: avatars still stored as base64 in `users.avatar_url` are moved to
     * [UserAvatarsTable]. Idempotent - once done, no `data:` values remain.
     *
     * @return the number of migrated avatars.
     */
    suspend fun migrateInlineAvatars(): Int = dbQuery {
        val inline = UsersTable.selectAll().where { UsersTable.avatarUrl like "data:%" }
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
