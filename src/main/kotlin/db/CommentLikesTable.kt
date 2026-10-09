package com.milinko.db

import org.jetbrains.exposed.sql.Table

/**
 * Likes on movie comments: which user liked which comment.
 *
 * The composite primary key (comment_id, user_id) lets the database itself reject a second like
 * from the same user. There is no `created_at` because likes are only counted and checked for
 * existence, never listed.
 */
object CommentLikesTable : Table("comment_likes") {
    val commentId = integer("comment_id") references CommentsTable.id
    val userId = varchar("user_id", 64) references UsersTable.id

    override val primaryKey = PrimaryKey(commentId, userId, name = "comment_likes_pkey")
}
