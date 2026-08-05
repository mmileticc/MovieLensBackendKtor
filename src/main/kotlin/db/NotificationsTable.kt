package com.milinko.db

import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.datetime

/**
 * Obaveštenja za korisnika (npr. aktivnost prijatelja). Postoji već u Supabase-u; usklađivanje
 * koda sa stvarnom šemom. Nije bilo u originalnom planu (MOVIELENS_ROADMAP.md) ali je koristan
 * dodatak — funkcionalnost koja ga puni/čita gradi se kad se radi Faza 3 (prijatelji).
 */
object NotificationsTable : Table("notifications") {
    val id = integer("id").autoIncrement()
    val userId = (varchar("user_id", 64) references UsersTable.id).nullable()
    val content = text("content")
    val isRead = bool("is_read").default(false)
    val createdAt = datetime("created_at").nullable().clientDefault {
        Clock.System.now().toLocalDateTime(TimeZone.UTC)
    }
    override val primaryKey = PrimaryKey(id, name = "notifications_pkey")
}
