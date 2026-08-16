package com.milinko.db

import com.milinko.db.DatabaseFactory.dbQuery
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update

private const val ACTION_WATCHLIST = "WATCHLIST"
private const val ACTION_WATCHED = "WATCHED"

data class UserActionMovieRow(
    val tmdbId: Long,
    val title: String,
    val posterPath: String?,
    val rating: Int?,
    val review: String?,
    val createdAt: LocalDateTime?
)

data class FriendActivityRow(
    val friendId: String,
    val friendUsername: String?,
    val tmdbId: Long,
    val title: String,
    val posterPath: String?,
    val rating: Int?,
    val review: String?,
    val createdAt: LocalDateTime?
)

data class FriendWatchedRow(
    val friendId: String,
    val friendUsername: String?,
    val rating: Int?,
    val review: String?
)

/**
 * DAO za watchlist + "odgledano"/ocena (Faza 2, MOVIELENS_ROADMAP.md). Obe funkcionalnosti dele
 * istu `user_actions` tabelu, razdvojene preko `action_type`.
 */
class UserActionsRepository {

    // Join sa MoviesTable da svaki red odmah nosi title/poster - Android WatchlistItem/HistoryItem
    // modeli ih očekuju direktno, bez dodatnog poziva.
    private val joined = UserActionsTable.join(
        MoviesTable,
        JoinType.INNER,
        additionalConstraint = { UserActionsTable.movieId eq MoviesTable.tmdbId }
    )

    private fun rowMapper(row: org.jetbrains.exposed.sql.ResultRow) = UserActionMovieRow(
        tmdbId = row[MoviesTable.tmdbId],
        title = row[MoviesTable.title],
        posterPath = row[MoviesTable.posterUrl],
        rating = row[UserActionsTable.rating],
        review = row[UserActionsTable.review],
        createdAt = row[UserActionsTable.createdAt]
    )

    suspend fun getWatchlist(userId: String): List<UserActionMovieRow> = dbQuery {
        joined.select {
            (UserActionsTable.userId eq userId) and (UserActionsTable.actionType eq ACTION_WATCHLIST)
        }.map(::rowMapper)
    }

    suspend fun getHistory(userId: String): List<UserActionMovieRow> = dbQuery {
        joined.select {
            (UserActionsTable.userId eq userId) and (UserActionsTable.actionType eq ACTION_WATCHED)
        }.map(::rowMapper)
    }

    suspend fun getWatchlistItem(userId: String, tmdbId: Long): UserActionMovieRow? = dbQuery {
        joined.select {
            (UserActionsTable.userId eq userId) and
                (UserActionsTable.movieId eq tmdbId) and
                (UserActionsTable.actionType eq ACTION_WATCHLIST)
        }.map(::rowMapper).firstOrNull()
    }

    suspend fun getHistoryItem(userId: String, tmdbId: Long): UserActionMovieRow? = dbQuery {
        joined.select {
            (UserActionsTable.userId eq userId) and
                (UserActionsTable.movieId eq tmdbId) and
                (UserActionsTable.actionType eq ACTION_WATCHED)
        }.map(::rowMapper).firstOrNull()
    }

    suspend fun addToWatchlist(userId: String, tmdbId: Long): Unit = dbQuery {
        val exists = UserActionsTable.select {
            (UserActionsTable.userId eq userId) and
                (UserActionsTable.movieId eq tmdbId) and
                (UserActionsTable.actionType eq ACTION_WATCHLIST)
        }.empty().not()

        if (!exists) {
            UserActionsTable.insert { row ->
                row[UserActionsTable.userId] = userId
                row[UserActionsTable.movieId] = tmdbId
                row[UserActionsTable.actionType] = ACTION_WATCHLIST
            }
        }
    }

    suspend fun removeFromWatchlist(userId: String, tmdbId: Long): Unit = dbQuery {
        UserActionsTable.deleteWhere {
            (UserActionsTable.userId eq userId) and
                (UserActionsTable.movieId eq tmdbId) and
                (UserActionsTable.actionType eq ACTION_WATCHLIST)
        }
    }

    /**
     * Upsert "odgledano" reda - ako već postoji, samo ažurira ocenu (može biti null = ostaje
     * odgledano bez ocene); ako ne postoji, kreira novi red. Koristi se i za POST (prvo
     * označavanje) i za PUT (izmena ocene) - vidi napomenu u MOVIELENS_ROADMAP.md o WATCHED
     * dizajnu.
     *
     * `review` (16. avgust 2026) - kratak tekstualni komentar uz ocenu, nezavisan parametar od
     * rating-a (može se menjati i sam, bez dodira na ocenu). `review = null` znači "ne diraj
     * postojeći komentar" (isti obrazac kao avatar u UserDao.updateProfile) - VAŽNO za PUT (izmena
     * samo ocene): ako pozivalac ne prosledi review, postojeći komentar mora ostati netaknut, ne
     * obrisan. Prazan string ("") se i dalje tretira kao "obriši komentar" (korisnik je namerno
     * ispraznio polje) - razlika je između null (nedostaje iz zahteva) i "" (eksplicitno prazno).
     *
     * ISTI dogovor sad važi i za `rating` (izmena 16. avgust 2026, ranije se UVEK bezuslovno
     * upisivao) - `rating = null` u UPDATE grani znači "ne diraj postojeću ocenu" umesto "obriši
     * je". Ovo je neophodno da bi "napiši/izmeni komentar bez dodira na ocenu" (npr. film je
     * odgledan bez zvezdica, korisnik samo ostavlja utisak u tekstu) radilo bez slučajnog brisanja
     * već postojeće ocene. Nema trenutno UI puta koji namerno želi "obriši samo ocenu, ostani
     * odgledano" - da zatreba, treba poseban parametar (npr. `clearRating: Boolean`), ne
     * preopterećivati `null` sa dva značenja.
     */
    suspend fun markWatched(userId: String, tmdbId: Long, rating: Int?, review: String? = null): Unit = dbQuery {
        val exists = UserActionsTable.select {
            (UserActionsTable.userId eq userId) and
                (UserActionsTable.movieId eq tmdbId) and
                (UserActionsTable.actionType eq ACTION_WATCHED)
        }.empty().not()

        if (exists) {
            UserActionsTable.update({
                (UserActionsTable.userId eq userId) and
                    (UserActionsTable.movieId eq tmdbId) and
                    (UserActionsTable.actionType eq ACTION_WATCHED)
            }) { row ->
                if (rating != null) {
                    row[UserActionsTable.rating] = rating
                }
                if (review != null) {
                    row[UserActionsTable.review] = review.ifBlank { null }
                }
            }
        } else {
            UserActionsTable.insert { row ->
                row[UserActionsTable.userId] = userId
                row[UserActionsTable.movieId] = tmdbId
                row[UserActionsTable.actionType] = ACTION_WATCHED
                row[UserActionsTable.rating] = rating
                row[UserActionsTable.review] = review?.ifBlank { null }
            }
        }
    }

    /**
     * Uklanja "odgledano" (i ocenu, pošto je u istom redu) - podrška za "undo" ako je korisnik
     * greškom kliknuo "Odgledano" (lapsus klik).
     */
    suspend fun removeFromHistory(userId: String, tmdbId: Long): Unit = dbQuery {
        UserActionsTable.deleteWhere {
            (UserActionsTable.userId eq userId) and
                (UserActionsTable.movieId eq tmdbId) and
                (UserActionsTable.actionType eq ACTION_WATCHED)
        }
    }

    /**
     * Friend activity feed - SAMO "odgledano"/ocene (ACTION_WATCHED), namerno bez watchlist-e.
     * Dogovoreno u MOVIELENS_ROADMAP.md: watchlist ("planiram da gledam") ostaje privatan, deli se
     * samo ono što je korisnik stvarno odgledao/ocenio - kao Letterboxd. Ako je friendIds prazna
     * lista, vraća praznu listu (nema poziva ka bazi bez WHERE IN uslova).
     */
    suspend fun getFriendsActivity(friendIds: List<String>, limit: Int = 50): List<FriendActivityRow> {
        if (friendIds.isEmpty()) return emptyList()
        return dbQuery {
            UserActionsTable
                .join(MoviesTable, JoinType.INNER, additionalConstraint = { UserActionsTable.movieId eq MoviesTable.tmdbId })
                .join(UsersTable, JoinType.INNER, additionalConstraint = { UserActionsTable.userId eq UsersTable.id })
                .select {
                    (UserActionsTable.userId inList friendIds) and (UserActionsTable.actionType eq ACTION_WATCHED)
                }
                .orderBy(UserActionsTable.createdAt, SortOrder.DESC)
                .limit(limit)
                .map { row ->
                    FriendActivityRow(
                        friendId = row[UserActionsTable.userId],
                        friendUsername = row[UsersTable.username],
                        tmdbId = row[MoviesTable.tmdbId],
                        title = row[MoviesTable.title],
                        posterPath = row[MoviesTable.posterUrl],
                        rating = row[UserActionsTable.rating],
                        review = row[UserActionsTable.review],
                        createdAt = row[UserActionsTable.createdAt]
                    )
                }
        }
    }

    /**
     * Za Movie Detail "prijatelji su gledali" bedž - od datog spiska friendIds, koji su WATCHED-
     * ovali baš ovaj tmdbId (i sa kojom ocenom, ako postoji). Nema JOIN na MoviesTable jer nam
     * ovde ne treba title/poster (već su na klijentu, gleda se taj isti film).
     */
    suspend fun getFriendsWhoWatched(friendIds: List<String>, tmdbId: Long): List<FriendWatchedRow> {
        if (friendIds.isEmpty()) return emptyList()
        return dbQuery {
            UserActionsTable
                .join(UsersTable, JoinType.INNER, additionalConstraint = { UserActionsTable.userId eq UsersTable.id })
                .select {
                    (UserActionsTable.userId inList friendIds) and
                        (UserActionsTable.movieId eq tmdbId) and
                        (UserActionsTable.actionType eq ACTION_WATCHED)
                }
                .map { row ->
                    FriendWatchedRow(
                        friendId = row[UserActionsTable.userId],
                        friendUsername = row[UsersTable.username],
                        rating = row[UserActionsTable.rating],
                        review = row[UserActionsTable.review]
                    )
                }
        }
    }
}
