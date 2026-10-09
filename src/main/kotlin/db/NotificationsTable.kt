package com.milinko.db

import com.milinko.db.DatabaseFactory.dbQuery
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

/**
 * Notification history: everything sent to a user as a push notification, also shown in the
 * app's Notifications screen.
 *
 * - `type` is a [com.milinko.services.PushType] name.
 * - `payload` is the JSON data sent with the push (actor, movie, note...), so the app can rebuild
 *   the text in its own language.
 * - `dedupeKey` prevents sending the same notification twice (e.g. "movie from your watchlist is
 *   now in cinemas").
 * - `content` is a readable Serbian fallback text (the column is NOT NULL in the existing schema).
 */
object NotificationsTable : Table("notifications") {
    val id = integer("id").autoIncrement()
    val userId = (varchar("user_id", 64) references UsersTable.id).nullable()
    val content = text("content")
    val isRead = bool("is_read").default(false)
    val createdAt = datetime("created_at").nullable().clientDefault {
        Clock.System.now().toLocalDateTime(TimeZone.UTC)
    }
    val type = varchar("type", 40).nullable()
    val payload = text("payload").nullable()
    val dedupeKey = varchar("dedupe_key", 120).nullable()
    override val primaryKey = PrimaryKey(id, name = "notifications_pkey")
}

/** One notification as stored in [NotificationsTable]. */
data class NotificationRow(
    val id: Int,
    val type: String?,
    val content: String,
    val payload: String?,
    val isRead: Boolean,
    val createdAt: LocalDateTime?
)

/** Reads and writes [NotificationsTable]. */
class NotificationsRepository {

    /** Stores a new (unread) notification. */
    suspend fun add(userId: String, type: String, content: String, payload: String?, dedupeKey: String? = null): Unit = dbQuery {
        NotificationsTable.insert {
            it[NotificationsTable.userId] = userId
            it[NotificationsTable.type] = type
            it[NotificationsTable.content] = content
            it[NotificationsTable.payload] = payload
            it[NotificationsTable.dedupeKey] = dedupeKey
        }
    }

    /** True if a notification with this [dedupeKey] was already stored for the user. */
    suspend fun exists(userId: String, dedupeKey: String): Boolean = dbQuery {
        NotificationsTable.selectAll()
            .where { (NotificationsTable.userId eq userId) and (NotificationsTable.dedupeKey eq dedupeKey) }
            .limit(1)
            .any()
    }

    /** The user's most recent notifications, newest first. */
    suspend fun latest(userId: String, limit: Int = 50): List<NotificationRow> = dbQuery {
        NotificationsTable.selectAll()
            .where { NotificationsTable.userId eq userId }
            .orderBy(NotificationsTable.createdAt, SortOrder.DESC)
            .limit(limit)
            .map {
                NotificationRow(
                    id = it[NotificationsTable.id],
                    type = it[NotificationsTable.type],
                    content = it[NotificationsTable.content],
                    payload = it[NotificationsTable.payload],
                    isRead = it[NotificationsTable.isRead],
                    createdAt = it[NotificationsTable.createdAt]
                )
            }
    }

    /** Marks all of the user's notifications as read. */
    suspend fun markAllRead(userId: String): Unit = dbQuery {
        NotificationsTable.update({ (NotificationsTable.userId eq userId) and (NotificationsTable.isRead eq false) }) {
            it[isRead] = true
        }
    }
}
