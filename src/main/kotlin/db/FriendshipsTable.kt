package com.milinko.db

import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.datetime

/**
 * Zahtevi/prijateljstva između korisnika. Postoji već u Supabase-u; usklađivanje koda sa
 * stvarnom šemom. Odgovara planu iz Faze 3 (MOVIELENS_ROADMAP.md) — sender/receiver + status
 * (pending/accepted/rejected), pravi friend-request flow umesto instant-add.
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
