package com.milinko.db

import com.milinko.db.DatabaseFactory.dbQuery
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update

private const val STATUS_PENDING = "PENDING"
private const val STATUS_ACCEPTED = "ACCEPTED"

/** Outcome of sending a friend request; decides which notification the other user receives. */
enum class SendRequestResult { SENT, ACCEPTED_EXISTING, ALREADY_EXISTS }

/** Friendship status as seen by `userId` towards `otherUserId`. */
enum class FriendshipStatus {
    NONE,
    PENDING_SENT,     // userId sent a request and waits for otherUserId to accept
    PENDING_RECEIVED, // otherUserId sent a request; userId can accept or reject it
    ACCEPTED
}

private fun userFromRow(row: org.jetbrains.exposed.sql.ResultRow) = User(
    id = row[UsersTable.id],
    firebaseUid = row[UsersTable.firebaseUid],
    username = row[UsersTable.username],
    email = row[UsersTable.email],
    avatarUrl = publicAvatarUrl(row[UsersTable.avatarUrl]),
    bio = row[UsersTable.bio],
    subscriptionTier = row[UsersTable.subscriptionTier],
    roles = row[UsersTable.roles],
    createdAt = row[UsersTable.createdAt],
    lastLogin = row[UsersTable.lastLogin]
)

/**
 * Friend requests and friendships on top of [FriendshipsTable]: send, accept, reject/remove and
 * the lists derived from them.
 */
class FriendshipsRepository {

    /** Status of the relationship between the two users, from [userId]'s point of view. */
    suspend fun getFriendshipStatus(userId: String, otherUserId: String): FriendshipStatus = dbQuery {
        val row = FriendshipsTable.selectAll().where {
            ((FriendshipsTable.senderId eq userId) and (FriendshipsTable.receiverId eq otherUserId)) or
                ((FriendshipsTable.senderId eq otherUserId) and (FriendshipsTable.receiverId eq userId))
        }.firstOrNull() ?: return@dbQuery FriendshipStatus.NONE

        statusFromRow(row, userId)
    }

    /**
     * Statuses towards several users in one query (e.g. user search results). Users without a
     * row in `friendships` get [FriendshipStatus.NONE].
     */
    suspend fun getFriendshipStatuses(userId: String, otherUserIds: List<String>): Map<String, FriendshipStatus> {
        if (otherUserIds.isEmpty()) return emptyMap()
        val found = dbQuery {
            FriendshipsTable.selectAll().where {
                ((FriendshipsTable.senderId eq userId) and (FriendshipsTable.receiverId inList otherUserIds)) or
                    ((FriendshipsTable.receiverId eq userId) and (FriendshipsTable.senderId inList otherUserIds))
            }.associate { row ->
                val otherId = if (row[FriendshipsTable.senderId] == userId) row[FriendshipsTable.receiverId] else row[FriendshipsTable.senderId]
                otherId to statusFromRow(row, userId)
            }
        }
        return otherUserIds.associateWith { found[it] ?: FriendshipStatus.NONE }
    }

    private fun statusFromRow(row: org.jetbrains.exposed.sql.ResultRow, userId: String): FriendshipStatus =
        when (row[FriendshipsTable.status]) {
            STATUS_ACCEPTED -> FriendshipStatus.ACCEPTED
            STATUS_PENDING -> if (row[FriendshipsTable.senderId] == userId) {
                FriendshipStatus.PENDING_SENT
            } else {
                FriendshipStatus.PENDING_RECEIVED
            }
            else -> FriendshipStatus.NONE
        }

    /**
     * Sends a friend request. If [otherUserId] has already sent a request to [userId], that request
     * is accepted instead of creating a second row (mutual requests mean instant friendship).
     *
     * @throws IllegalArgumentException when a user tries to befriend themselves.
     */
    suspend fun sendRequest(userId: String, otherUserId: String): SendRequestResult = dbQuery {
        require(userId != otherUserId) { "Ne možeš poslati zahtev samom sebi" }

        val reverseRequest = FriendshipsTable.selectAll().where {
            (FriendshipsTable.senderId eq otherUserId) and
                (FriendshipsTable.receiverId eq userId) and
                (FriendshipsTable.status eq STATUS_PENDING)
        }.firstOrNull()

        if (reverseRequest != null) {
            FriendshipsTable.update({
                (FriendshipsTable.senderId eq otherUserId) and (FriendshipsTable.receiverId eq userId)
            }) { it[status] = STATUS_ACCEPTED }
            return@dbQuery SendRequestResult.ACCEPTED_EXISTING
        }

        val alreadyExists = FriendshipsTable.selectAll().where {
            ((FriendshipsTable.senderId eq userId) and (FriendshipsTable.receiverId eq otherUserId)) or
                ((FriendshipsTable.senderId eq otherUserId) and (FriendshipsTable.receiverId eq userId))
        }.empty().not()

        if (alreadyExists) return@dbQuery SendRequestResult.ALREADY_EXISTS
        FriendshipsTable.insert { row ->
            row[senderId] = userId
            row[receiverId] = otherUserId
            row[status] = STATUS_PENDING
        }
        SendRequestResult.SENT
    }

    /** Accepts an incoming request; only the receiver ([userId]) can accept. Returns false if there was none. */
    suspend fun acceptRequest(userId: String, otherUserId: String): Boolean = dbQuery {
        val updated = FriendshipsTable.update({
            (FriendshipsTable.senderId eq otherUserId) and
                (FriendshipsTable.receiverId eq userId) and
                (FriendshipsTable.status eq STATUS_PENDING)
        }) { it[status] = STATUS_ACCEPTED }
        updated > 0
    }

    /**
     * Rejects an incoming request, cancels an outgoing one, or ends a friendship. In the database
     * all three are the same operation: deleting the row.
     */
    suspend fun removeFriendship(userId: String, otherUserId: String): Unit = dbQuery {
        FriendshipsTable.deleteWhere {
            ((FriendshipsTable.senderId eq userId) and (FriendshipsTable.receiverId eq otherUserId)) or
                ((FriendshipsTable.senderId eq otherUserId) and (FriendshipsTable.receiverId eq userId))
        }
    }

    /** Accepted friends ([userId] can be either the sender or the receiver of the row). */
    suspend fun getFriends(userId: String): List<User> = dbQuery {
        val asSender = FriendshipsTable
            .join(UsersTable, JoinType.INNER, additionalConstraint = { FriendshipsTable.receiverId eq UsersTable.id })
            .selectAll().where { (FriendshipsTable.senderId eq userId) and (FriendshipsTable.status eq STATUS_ACCEPTED) }
            .map(::userFromRow)

        val asReceiver = FriendshipsTable
            .join(UsersTable, JoinType.INNER, additionalConstraint = { FriendshipsTable.senderId eq UsersTable.id })
            .selectAll().where { (FriendshipsTable.receiverId eq userId) and (FriendshipsTable.status eq STATUS_ACCEPTED) }
            .map(::userFromRow)

        asSender + asReceiver
    }

    /** Incoming pending requests that [userId] can accept or reject. */
    suspend fun getPendingIncoming(userId: String): List<User> = dbQuery {
        FriendshipsTable
            .join(UsersTable, JoinType.INNER, additionalConstraint = { FriendshipsTable.senderId eq UsersTable.id })
            .selectAll().where { (FriendshipsTable.receiverId eq userId) and (FriendshipsTable.status eq STATUS_PENDING) }
            .map(::userFromRow)
    }

    /** Outgoing pending requests sent by [userId] (the mirror of [getPendingIncoming]). */
    suspend fun getPendingOutgoing(userId: String): List<User> = dbQuery {
        FriendshipsTable
            .join(UsersTable, JoinType.INNER, additionalConstraint = { FriendshipsTable.receiverId eq UsersTable.id })
            .selectAll().where { (FriendshipsTable.senderId eq userId) and (FriendshipsTable.status eq STATUS_PENDING) }
            .map(::userFromRow)
    }

    /** Ids of all accepted friends (for the activity feed, comments and "friends watched"). */
    suspend fun getFriendIds(userId: String): List<String> = dbQuery {
        val asSender = FriendshipsTable.selectAll().where {
            (FriendshipsTable.senderId eq userId) and (FriendshipsTable.status eq STATUS_ACCEPTED)
        }.map { it[FriendshipsTable.receiverId] }

        val asReceiver = FriendshipsTable.selectAll().where {
            (FriendshipsTable.receiverId eq userId) and (FriendshipsTable.status eq STATUS_ACCEPTED)
        }.map { it[FriendshipsTable.senderId] }

        asSender + asReceiver
    }
}
