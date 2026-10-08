package com.milinko.db

import com.milinko.db.DatabaseFactory.dbQuery
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.upsert

/**
 * FCM registracioni tokeni uređaja. Jedan korisnik može imati više uređaja; token je jedinstven
 * (isti uređaj posle prijave drugim nalogom prelazi na novog korisnika).
 */
object DeviceTokensTable : Table("device_tokens") {
    val token = varchar("token", 512)
    val userId = varchar("user_id", 64).references(UsersTable.id)
    val platform = varchar("platform", 16).default("android")
    val updatedAt = datetime("updated_at").clientDefault { Clock.System.now().toLocalDateTime(TimeZone.UTC) }
    override val primaryKey = PrimaryKey(token)

    init {
        index("idx_device_tokens_user", false, userId)
    }
}

class DeviceTokensRepository {

    suspend fun register(userId: String, token: String): Unit = dbQuery {
        DeviceTokensTable.upsert {
            it[DeviceTokensTable.token] = token
            it[DeviceTokensTable.userId] = userId
            it[DeviceTokensTable.updatedAt] = Clock.System.now().toLocalDateTime(TimeZone.UTC)
        }
    }

    /** Odjava: briše token samo ako pripada ovom korisniku. */
    suspend fun unregister(userId: String, token: String): Unit = dbQuery {
        DeviceTokensTable.deleteWhere { (DeviceTokensTable.token eq token) and (DeviceTokensTable.userId eq userId) }
    }

    suspend fun tokensFor(userId: String): List<String> = dbQuery {
        DeviceTokensTable.selectAll().where { DeviceTokensTable.userId eq userId }.map { it[DeviceTokensTable.token] }
    }

    /** Tokeni koje je FCM odbio kao nevažeće (aplikacija obrisana, token istekao). */
    suspend fun deleteTokens(tokens: List<String>): Unit = dbQuery {
        if (tokens.isNotEmpty()) DeviceTokensTable.deleteWhere { DeviceTokensTable.token inList tokens }
    }
}
