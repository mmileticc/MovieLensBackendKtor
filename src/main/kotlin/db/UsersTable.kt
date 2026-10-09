package com.milinko.db

import com.milinko.db.DatabaseFactory.dbQuery
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update
import java.util.*

/**
 * App users, created on first Google/Firebase sign-in.
 *
 * `id` is a random UUID stored as text (the column type in the existing schema).
 */
object UsersTable : Table("users") {
    val id = varchar("id", 64).clientDefault { UUID.randomUUID().toString() }
    val firebaseUid = varchar("firebase_uid", 255).uniqueIndex("idx_users_firebase_uid")
    val username = varchar("username", 255).nullable()
    val email = varchar("email", 255).nullable()
    val avatarUrl = text("avatar_url").nullable()
    val bio = text("bio").nullable()
    val subscriptionTier = varchar("subscription_tier", 50).default("free")
    // jsonb DEFAULT '[]' in the database; mapped as text because nothing uses roles yet.
    val roles = text("roles").nullable().default("[]")
    val createdAt = datetime("created_at").clientDefault {
        Clock.System.now().toLocalDateTime(TimeZone.UTC)
    }
    val lastLogin = datetime("last_login").nullable()
    override val primaryKey = PrimaryKey(id)

    init {
        // These indexes already exist in the database; mapped here so Exposed does not report them as unknown.
        index("idx_users_email", false, email)
        index("idx_users_created_at", false, createdAt)
        index("idx_users_last_login", false, lastLogin)
    }
}

/** User as returned by `/api/auth/firebase`. */
@Serializable
data class UserDto(
    val id: String,
    val firebaseUid: String,
    val username: String?,
    val email: String?,
    val avatarUrl: String?
)

/** A row of [UsersTable]; `avatarUrl` is already in client form (see [publicAvatarUrl]). */
data class User(
    val id: String,
    val firebaseUid: String,
    val username: String?,
    val email: String?,
    val avatarUrl: String?,
    val bio: String? = null,
    val subscriptionTier: String = "free",
    val roles: String? = null,
    val createdAt: kotlinx.datetime.LocalDateTime,
    val lastLogin: kotlinx.datetime.LocalDateTime?
)

/** Username rule (Instagram-style): letters, digits, dot and underscore, 3-30 characters. */
private val USERNAME_REGEX = Regex("^[A-Za-z0-9_.]{3,30}$")
private const val BIO_MAX_LENGTH = 160

/**
 * Turns a raw name (e.g. the Google display name "Miloš Miletić") into a username that passes
 * [USERNAME_REGEX]: lowercase, spaces to underscores, Serbian Latin letters without diacritics
 * (č/ć/š/ž via NFD decomposition, đ by hand because NFD does not decompose it).
 *
 * The default username must be valid from the moment the account is created; otherwise every
 * later profile save would fail validation even if the user never touched the username.
 */
private fun sanitizeUsername(raw: String): String {
    var s = raw.trim()
        .replace("đ", "dj").replace("Đ", "Dj")
    s = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
    s = s.lowercase()
        .replace(Regex("\\s+"), "_")
        .replace(Regex("[^a-z0-9_.]"), "")
        .replace(Regex("_{2,}"), "_")
        .trim('_', '.')
    if (s.length > 30) s = s.take(30).trimEnd('_', '.')
    return s
}

// The app downsamples and compresses avatars to ~150 KB before base64 encoding (+33% size), so
// 400,000 characters leaves a wide safety margin.
private const val AVATAR_MAX_BASE64_LENGTH = 400_000

private fun ResultRow.toUser() = User(
    id = this[UsersTable.id],
    firebaseUid = this[UsersTable.firebaseUid],
    username = this[UsersTable.username],
    email = this[UsersTable.email],
    avatarUrl = publicAvatarUrl(this[UsersTable.avatarUrl]),
    bio = this[UsersTable.bio],
    subscriptionTier = this[UsersTable.subscriptionTier],
    roles = this[UsersTable.roles],
    createdAt = this[UsersTable.createdAt],
    lastLogin = this[UsersTable.lastLogin]
)

/** Reads and writes users: sign-in upsert, lookup, search and profile updates. */
class UserDao {

    /**
     * Creates the user on first sign-in, or updates email and last login on later sign-ins.
     *
     * Username and avatar are set only when the user is created and are never overwritten here:
     * otherwise every sign-in would replace a username or picture the user changed with the
     * Google defaults again.
     */
    suspend fun upsertByFirebaseUid(
        firebaseUid: String,
        email: String?,
        username: String?,
        avatarUrl: String?
    ): User = dbQuery {
        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)

        val existingUser = UsersTable.selectAll().where { UsersTable.firebaseUid eq firebaseUid }
            .firstOrNull()

        if (existingUser != null) {
            // Username and avatar are deliberately not updated (see the KDoc above); only email and
            // last login follow the Google account.
            UsersTable.update({ UsersTable.firebaseUid eq firebaseUid }) { stmt ->
                stmt[UsersTable.email] = email
                stmt[UsersTable.lastLogin] = now
            }
        } else {
            // New user: the default username is the sanitized Google display name ("first_last"),
            // or a generated "user_xxxxxxxx" if Google returns no name or less than 3 characters
            // remain. It has to be valid right away (see sanitizeUsername).
            var finalUsername = username?.let { sanitizeUsername(it) }?.takeIf { it.length >= 3 }
                ?: "user_${firebaseUid.take(8).lowercase()}"

            // Two users with the same name would get the same default username - add a short
            // suffix from the Firebase UID to keep it unique.
            val taken = UsersTable.selectAll().where { UsersTable.username.lowerCase() eq finalUsername.lowercase() }
                .limit(1).any()
            if (taken) {
                finalUsername = "${finalUsername.take(24)}_${firebaseUid.take(4).lowercase()}"
            }

            UsersTable.insert {
                it[UsersTable.firebaseUid] = firebaseUid
                it[UsersTable.email] = email
                it[UsersTable.username] = finalUsername
                it[UsersTable.avatarUrl] = avatarUrl
                it[UsersTable.lastLogin] = now
            }
        }

        UsersTable.selectAll().where { UsersTable.firebaseUid eq firebaseUid }
            .map { it.toUser() }
            .firstOrNull() ?: throw Exception("User nije pronađen nakon upserta")
    }

    /** User by Firebase UID, or null. */
    suspend fun findByFirebaseUid(firebaseUid: String): User? = dbQuery {
        UsersTable.selectAll().where { UsersTable.firebaseUid eq firebaseUid }
            .map { it.toUser() }
            .firstOrNull()
    }

    /** User by internal id, or null. */
    suspend fun findById(userId: String): User? = dbQuery {
        UsersTable.selectAll().where { UsersTable.id eq userId }
            .map { it.toUser() }
            .firstOrNull()
    }

    /**
     * Case-insensitive user search for adding friends, excluding the caller.
     *
     * Usernames match partially (search as you type). Emails match only exactly, and only when the
     * query looks like an email - partial email matching would let anyone list other people's
     * addresses with a query like "@gmail".
     */
    suspend fun searchUsers(query: String, excludeUserId: String, limit: Int = 20): List<User> = dbQuery {
        val trimmed = query.trim().lowercase()
        if (trimmed.isEmpty()) return@dbQuery emptyList()

        val matchesQuery = if ('@' in trimmed) {
            UsersTable.email.lowerCase() eq trimmed
        } else {
            UsersTable.username.lowerCase() like "%$trimmed%"
        }
        UsersTable.selectAll().where { (UsersTable.id neq excludeUserId) and matchesQuery }
            .limit(limit)
            .map { it.toUser() }
    }

    /**
     * Updates the username (unique, case-insensitive, like an Instagram handle), the bio and
     * optionally the avatar.
     *
     * The avatar changes only when a new image arrives as a `data:image/...;base64,...` URI;
     * anything else, including `null`, keeps the current one.
     *
     * @throws IllegalArgumentException for every validation error (format, length, username taken,
     *   image too large or not JPEG/PNG); routes turn it into 400 with the message.
     */
    suspend fun updateProfile(userId: String, username: String, bio: String?, avatar: String?): User = dbQuery {
        val trimmedUsername = username.trim()
        if (!USERNAME_REGEX.matches(trimmedUsername)) {
            throw IllegalArgumentException(
                "Korisničko ime mora imati 3-30 karaktera i sme sadržati samo slova, brojeve, tačku i donju crtu"
            )
        }
        val trimmedBio = bio?.trim()?.take(BIO_MAX_LENGTH)?.ifBlank { null }
        if (avatar != null && avatar.length > AVATAR_MAX_BASE64_LENGTH) {
            throw IllegalArgumentException("Slika je prevelika - probaj sa manjom/kompresovanijom slikom")
        }

        val taken = UsersTable.selectAll().where {
            (UsersTable.id neq userId) and (UsersTable.username.lowerCase() eq trimmedUsername.lowercase())
        }.limit(1).any()
        if (taken) {
            throw IllegalArgumentException("Korisničko ime \"$trimmedUsername\" je već zauzeto")
        }

        UsersTable.update({ UsersTable.id eq userId }) { stmt ->
            stmt[UsersTable.username] = trimmedUsername
            stmt[UsersTable.bio] = trimmedBio
            // Only a new image (data URI) changes the avatar: the bytes go to user_avatars and only
            // the path is stored here. The app sends the current avatar URL with every save; it is
            // ignored so that no absolute URL (or arbitrary URL from a client) ends up in the database.
            if (avatar != null && avatar.startsWith("data:")) {
                stmt[UsersTable.avatarUrl] = storeAvatarFromDataUri(userId, avatar)
            }
        }

        UsersTable.selectAll().where { UsersTable.id eq userId }
            .map { it.toUser() }
            .firstOrNull() ?: throw IllegalStateException("Korisnik nije pronađen nakon ažuriranja")
    }
}





