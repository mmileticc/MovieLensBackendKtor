package com.milinko.db

import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.datetime

/**
 * Friend requests and friendships. One row per pair: the sender of the original request, the
 * receiver, and the status (PENDING until the receiver accepts, then ACCEPTED). Rejecting or
 * removing a friend deletes the row.
 */
object FriendshipsTable : Table("friendships") {
    val senderId = varchar("sender_id", 64) references UsersTable.id
    val receiverId = varchar("receiver_id", 64) references UsersTable.id
    val status = text("status").default("PENDING")
    val createdAt = datetime("created_at").nullable().clientDefault {
        Clock.System.now().toLocalDateTime(TimeZone.UTC)
    }

    override val primaryKey = PrimaryKey(senderId, receiverId, name = "friendships_pkey")

    init {
        check("friendships_status_check") { status inList listOf("PENDING", "ACCEPTED", "REJECTED") }
    }
}
