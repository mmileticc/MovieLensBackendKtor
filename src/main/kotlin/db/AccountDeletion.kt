package com.milinko.db

import com.milinko.db.DatabaseFactory.dbQuery
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll

/**
 * Deletes an account and everything that belongs to it, in one transaction (all or nothing), in an
 * order the foreign keys allow: likes on and by the user, the user's comments, library entries
 * (watchlist, history, ratings - the taste profile is derived from them), friendships in both
 * directions, the user's notifications and the ones other users got about them (friend requests,
 * shared movies), device tokens, the avatar and finally the user row.
 *
 * Shared data (the movie cache, the cinema programme) is not personal and stays.
 */
class AccountDeletion {

    /** Returns the deleted user's Firebase uid (to remove the sign-in too), or null if there was no such user. */
    suspend fun deleteAccount(userId: String): String? = dbQuery {
        val firebaseUid = UsersTable.selectAll().where { UsersTable.id eq userId }
            .singleOrNull()?.get(UsersTable.firebaseUid)
            ?: return@dbQuery null

        val ownComments = CommentsTable.selectAll().where { CommentsTable.userId eq userId }
            .map { it[CommentsTable.id] }
        CommentLikesTable.deleteWhere {
            (CommentLikesTable.userId eq userId) or (CommentLikesTable.commentId inList ownComments)
        }
        CommentsTable.deleteWhere { CommentsTable.userId eq userId }
        UserActionsTable.deleteWhere { UserActionsTable.userId eq userId }
        FriendshipsTable.deleteWhere { (FriendshipsTable.senderId eq userId) or (FriendshipsTable.receiverId eq userId) }
        // Notifications other users got about this user name them in the payload ("actorId").
        NotificationsTable.deleteWhere {
            (NotificationsTable.userId eq userId) or (NotificationsTable.payload like "%\"actorId\":\"$userId\"%")
        }
        DeviceTokensTable.deleteWhere { DeviceTokensTable.userId eq userId }
        UserAvatarsTable.deleteWhere { UserAvatarsTable.userId eq userId }
        UsersTable.deleteWhere { UsersTable.id eq userId }
        firebaseUid
    }
}
