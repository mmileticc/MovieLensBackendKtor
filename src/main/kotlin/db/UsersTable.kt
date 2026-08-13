package com.milinko.db

import com.milinko.db.DatabaseFactory.dbQuery
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
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
 * Users table za Firebase Auth integraciju.
 *
 * NAPOMENA: stvarna Supabase tabela ima `id` kolonu tipa `text`, ne `uuid` (verovatno da bi
 * ostalo prenosivo/jednostavno). Ranija verzija ovog fajla je pogrešno deklarisala `id` kao
 * `uuid()`, što se "slučajno" nije lomilo jer su generisane vrednosti uvek bile validan UUID
 * string, ali je bio type mismatch između koda i baze. Ovde generišemo isti format (random UUID)
 * samo kao String, na varchar koloni, da se poklapa sa stvarnom šemom.
 */
object UsersTable : Table("users") {
    val id = varchar("id", 64).clientDefault { UUID.randomUUID().toString() }
    val firebaseUid = varchar("firebase_uid", 255).uniqueIndex()
    val username = varchar("username", 255).nullable()
    val email = varchar("email", 255).nullable()
    val avatarUrl = text("avatar_url").nullable()
    val bio = text("bio").nullable()
    val subscriptionTier = varchar("subscription_tier", 50).default("free")
    // Stvarna kolona je jsonb DEFAULT '[]' u Supabase-u; modelovano kao text dok se ne uvede
    // jsonb podrška (exposed-json) i dok ne postoji stvarna funkcionalnost koja je koristi.
    val roles = text("roles").nullable().default("[]")
    val createdAt = datetime("created_at").clientDefault {
        Clock.System.now().toLocalDateTime(TimeZone.UTC)
    }
    val lastLogin = datetime("last_login").nullable()
    override val primaryKey = PrimaryKey(id)
}

/**
 * User DTO za API responses
 */
@Serializable
data class UserDto(
    val id: String,
    val firebaseUid: String,
    val username: String?,
    val email: String?,
    val avatarUrl: String?
)

/**
 * User entity klasa
 */
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

/**
 * UserDao - Data Access Object za User tabelu
 */
class UserDao {

    /**
     * Upsert user po Firebase UID
     * - Ako korisnik postoji, ažurira email, username, avatar_url i last_login
     * - Ako ne postoji, kreira novog korisnika
     */
    suspend fun upsertByFirebaseUid(
        firebaseUid: String,
        email: String?,
        username: String?,
        avatarUrl: String?
    ): User = dbQuery {
        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)

        // Prvo proverim da li korisnik postoji
        val existingUser = UsersTable.select { UsersTable.firebaseUid eq firebaseUid }
            .firstOrNull()

        val finalUsername = username ?: "User_${firebaseUid.take(8)}"

        if (existingUser != null) {
            // Update postojećeg korisnika
            UsersTable.update({ UsersTable.firebaseUid eq firebaseUid }) { stmt ->
                stmt[UsersTable.email] = email
                stmt[UsersTable.username] = finalUsername
                stmt[UsersTable.avatarUrl] = avatarUrl
                stmt[UsersTable.lastLogin] = now
            }
        } else {
            // Insert novog korisnika
            UsersTable.insert {
                it[UsersTable.firebaseUid] = firebaseUid
                it[UsersTable.email] = email
                it[UsersTable.username] = finalUsername
                it[UsersTable.avatarUrl] = avatarUrl
                it[UsersTable.lastLogin] = now
            }
        }

        // Čitaj upsertovani red
        UsersTable.select { UsersTable.firebaseUid eq firebaseUid }
            .map { row ->
                User(
                    id = row[UsersTable.id],
                    firebaseUid = row[UsersTable.firebaseUid],
                    username = row[UsersTable.username],
                    email = row[UsersTable.email],
                    avatarUrl = row[UsersTable.avatarUrl],
                    bio = row[UsersTable.bio],
                    subscriptionTier = row[UsersTable.subscriptionTier],
                    roles = row[UsersTable.roles],
                    createdAt = row[UsersTable.createdAt],
                    lastLogin = row[UsersTable.lastLogin]
                )
            }
            .firstOrNull() ?: throw Exception("User nije pronađen nakon upserta")
    }

    /**
     * Pronađi korisnika po Firebase UID
     */
    suspend fun findByFirebaseUid(firebaseUid: String): User? = dbQuery {
        UsersTable.select { UsersTable.firebaseUid eq firebaseUid }
            .map { row ->
                User(
                    id = row[UsersTable.id],
                    firebaseUid = row[UsersTable.firebaseUid],
                    username = row[UsersTable.username],
                    email = row[UsersTable.email],
                    avatarUrl = row[UsersTable.avatarUrl],
                    bio = row[UsersTable.bio],
                    subscriptionTier = row[UsersTable.subscriptionTier],
                    roles = row[UsersTable.roles],
                    createdAt = row[UsersTable.createdAt],
                    lastLogin = row[UsersTable.lastLogin]
                )
            }
            .firstOrNull()
    }

    /**
     * Pronađi korisnika po ID-u (text, ne uuid — vidi napomenu uz UsersTable)
     */
    suspend fun findById(userId: String): User? = dbQuery {
        UsersTable.select { UsersTable.id eq userId }
            .map { row ->
                User(
                    id = row[UsersTable.id],
                    firebaseUid = row[UsersTable.firebaseUid],
                    username = row[UsersTable.username],
                    email = row[UsersTable.email],
                    avatarUrl = row[UsersTable.avatarUrl],
                    bio = row[UsersTable.bio],
                    subscriptionTier = row[UsersTable.subscriptionTier],
                    roles = row[UsersTable.roles],
                    createdAt = row[UsersTable.createdAt],
                    lastLogin = row[UsersTable.lastLogin]
                )
            }
            .firstOrNull()
    }

    /**
     * Pretraga korisnika za dodavanje prijatelja - delimično podudaranje (case-insensitive) po
     * username-u ILI email-u, isključuje samog sebe. Koristi se za "search-as-you-type" na
     * Android strani (isti obrazac kao pretraga filmova).
     */
    suspend fun searchUsers(query: String, excludeUserId: String, limit: Int = 20): List<User> = dbQuery {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@dbQuery emptyList()

        val pattern = "%${trimmed.lowercase()}%"
        UsersTable.select {
            (UsersTable.id neq excludeUserId) and
                (
                    (UsersTable.username.lowerCase() like pattern) or
                        (UsersTable.email.lowerCase() like pattern)
                )
        }.limit(limit).map { row ->
            User(
                id = row[UsersTable.id],
                firebaseUid = row[UsersTable.firebaseUid],
                username = row[UsersTable.username],
                email = row[UsersTable.email],
                avatarUrl = row[UsersTable.avatarUrl],
                bio = row[UsersTable.bio],
                subscriptionTier = row[UsersTable.subscriptionTier],
                roles = row[UsersTable.roles],
                createdAt = row[UsersTable.createdAt],
                lastLogin = row[UsersTable.lastLogin]
            )
        }
    }
}





