package com.milinko.db

import org.jetbrains.exposed.sql.Table

/**
 * Lajkovi na komentare filma (E2.2 dorada, 3. septembar 2026) - ko je lajkovao koji komentar.
 * Kompozitni PK (comment_id, user_id) sprečava duplo lajkovanje istog komentara od istog korisnika
 * na nivou baze (isti stil kao `friendships`/`user_actions`). Nema `created_at` - lajk nema
 * sopstvenu istoriju/prikaz, samo se broji i proverava postojanje.
 */
object CommentLikesTable : Table("comment_likes") {
    val commentId = integer("comment_id") references CommentsTable.id
    val userId = varchar("user_id", 64) references UsersTable.id

    override val primaryKey = PrimaryKey(commentId, userId, name = "comment_likes_pkey")
}
