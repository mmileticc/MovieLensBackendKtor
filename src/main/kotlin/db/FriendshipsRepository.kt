package com.milinko.db

import com.milinko.db.DatabaseFactory.dbQuery
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

/** Status prijateljstva iz perspektive `userId` u odnosu na `otherUserId`. */
enum class FriendshipStatus {
    NONE,
    PENDING_SENT,     // userId je poslao zahtev, čeka se otherUserId da prihvati
    PENDING_RECEIVED, // otherUserId je poslao zahtev, userId može da prihvati/odbije
    ACCEPTED
}

private fun userFromRow(row: org.jetbrains.exposed.sql.ResultRow) = User(
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

/**
 * DAO za prijateljstva (Faza 3, MOVIELENS_ROADMAP.md). Pravi request/accept/reject flow (ne
 * instant-add) nad postojećom `friendships` tabelom (sender_id/receiver_id/status).
 */
class FriendshipsRepository {

    suspend fun getFriendshipStatus(userId: String, otherUserId: String): FriendshipStatus = dbQuery {
        val row = FriendshipsTable.select {
            ((FriendshipsTable.senderId eq userId) and (FriendshipsTable.receiverId eq otherUserId)) or
                ((FriendshipsTable.senderId eq otherUserId) and (FriendshipsTable.receiverId eq userId))
        }.firstOrNull() ?: return@dbQuery FriendshipStatus.NONE

        statusFromRow(row, userId)
    }

    /**
     * Status prema više korisnika jednim upitom (npr. rezultati pretrage korisnika). Korisnik bez
     * reda u `friendships` dobija NONE.
     */
    suspend fun getFriendshipStatuses(userId: String, otherUserIds: List<String>): Map<String, FriendshipStatus> {
        if (otherUserIds.isEmpty()) return emptyMap()
        val found = dbQuery {
            FriendshipsTable.select {
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
     * Šalje zahtev za prijateljstvo. Ako otherUserId već ima poslat zahtev ka userId (obrnut
     * smer), umesto duplog reda odmah prihvata postojeći - uobičajeno ponašanje ("obostrani
     * zahtev" = odmah prijateljstvo).
     */
    suspend fun sendRequest(userId: String, otherUserId: String): Unit = dbQuery {
        require(userId != otherUserId) { "Ne možeš poslati zahtev samom sebi" }

        val reverseRequest = FriendshipsTable.select {
            (FriendshipsTable.senderId eq otherUserId) and
                (FriendshipsTable.receiverId eq userId) and
                (FriendshipsTable.status eq STATUS_PENDING)
        }.firstOrNull()

        if (reverseRequest != null) {
            FriendshipsTable.update({
                (FriendshipsTable.senderId eq otherUserId) and (FriendshipsTable.receiverId eq userId)
            }) { it[status] = STATUS_ACCEPTED }
            return@dbQuery
        }

        val alreadyExists = FriendshipsTable.select {
            ((FriendshipsTable.senderId eq userId) and (FriendshipsTable.receiverId eq otherUserId)) or
                ((FriendshipsTable.senderId eq otherUserId) and (FriendshipsTable.receiverId eq userId))
        }.empty().not()

        if (!alreadyExists) {
            FriendshipsTable.insert { row ->
                row[senderId] = userId
                row[receiverId] = otherUserId
                row[status] = STATUS_PENDING
            }
        }
    }

    /** Prihvata dolazni zahtev - samo primalac (userId) sme da prihvati. */
    suspend fun acceptRequest(userId: String, otherUserId: String): Boolean = dbQuery {
        val updated = FriendshipsTable.update({
            (FriendshipsTable.senderId eq otherUserId) and
                (FriendshipsTable.receiverId eq userId) and
                (FriendshipsTable.status eq STATUS_PENDING)
        }) { it[status] = STATUS_ACCEPTED }
        updated > 0
    }

    /**
     * Odbija dolazni zahtev, otkazuje sopstveni poslati zahtev, ili raskida postojeće
     * prijateljstvo - sve je isto na nivou baze (brisanje reda), pa se koristi i za reject i za
     * unfriend i za cancel.
     */
    suspend fun removeFriendship(userId: String, otherUserId: String): Unit = dbQuery {
        FriendshipsTable.deleteWhere {
            ((FriendshipsTable.senderId eq userId) and (FriendshipsTable.receiverId eq otherUserId)) or
                ((FriendshipsTable.senderId eq otherUserId) and (FriendshipsTable.receiverId eq userId))
        }
    }

    /** Lista prihvaćenih prijatelja (userId može biti sender ili receiver u redu). */
    suspend fun getFriends(userId: String): List<User> = dbQuery {
        val asSender = FriendshipsTable
            .join(UsersTable, JoinType.INNER, additionalConstraint = { FriendshipsTable.receiverId eq UsersTable.id })
            .select { (FriendshipsTable.senderId eq userId) and (FriendshipsTable.status eq STATUS_ACCEPTED) }
            .map(::userFromRow)

        val asReceiver = FriendshipsTable
            .join(UsersTable, JoinType.INNER, additionalConstraint = { FriendshipsTable.senderId eq UsersTable.id })
            .select { (FriendshipsTable.receiverId eq userId) and (FriendshipsTable.status eq STATUS_ACCEPTED) }
            .map(::userFromRow)

        asSender + asReceiver
    }

    /** Dolazni (nerešeni) zahtevi za userId - da ih prihvati/odbije. */
    suspend fun getPendingIncoming(userId: String): List<User> = dbQuery {
        FriendshipsTable
            .join(UsersTable, JoinType.INNER, additionalConstraint = { FriendshipsTable.senderId eq UsersTable.id })
            .select { (FriendshipsTable.receiverId eq userId) and (FriendshipsTable.status eq STATUS_PENDING) }
            .map(::userFromRow)
    }

    /**
     * Odlazni (nerešeni) zahtevi koje je userId poslao - korisnički zahtev (2. septembar 2026,
     * "da mi budu vidljivi izlistani poslati zahtevi, a ne samo da se otkrije kad ponovo
     * pretražim osobu"). Simetrična ruta sa getPendingIncoming - isti join, samo obrnut smer
     * (senderId eq userId umesto receiverId eq userId).
     */
    suspend fun getPendingOutgoing(userId: String): List<User> = dbQuery {
        FriendshipsTable
            .join(UsersTable, JoinType.INNER, additionalConstraint = { FriendshipsTable.receiverId eq UsersTable.id })
            .select { (FriendshipsTable.senderId eq userId) and (FriendshipsTable.status eq STATUS_PENDING) }
            .map(::userFromRow)
    }

    /** ID-jevi svih prihvaćenih prijatelja - koristi se za friend activity feed. */
    suspend fun getFriendIds(userId: String): List<String> = dbQuery {
        val asSender = FriendshipsTable.select {
            (FriendshipsTable.senderId eq userId) and (FriendshipsTable.status eq STATUS_ACCEPTED)
        }.map { it[FriendshipsTable.receiverId] }

        val asReceiver = FriendshipsTable.select {
            (FriendshipsTable.receiverId eq userId) and (FriendshipsTable.status eq STATUS_ACCEPTED)
        }.map { it[FriendshipsTable.senderId] }

        asSender + asReceiver
    }
}
