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

data class CommentRow(
    val id: Int,
    val userId: String,
    val username: String?,
    val avatarUrl: String?,
    val movieId: Long,
    val body: String,
    val createdAt: LocalDateTime?,
    val likeCount: Int,
    val likedByMe: Boolean,
    val isFriend: Boolean,
    val isOwn: Boolean
)

data class CommentsPage(val rows: List<CommentRow>, val hasMore: Boolean)

data class LikeToggleResult(val liked: Boolean, val likeCount: Int)

/**
 * DAO za javne komentare na film (E2.2 dorada, 3. septembar 2026 - videti CommentsTable.kt za
 * kontekst zamene starog `review` polja). Potpuno odvojeno od `UserActionsRepository` - komentar
 * ne zavisi od watched/rating statusa.
 */
class CommentsRepository {

    /** Dodaje novi komentar, vraća njegov generisani id. */
    suspend fun add(userId: String, movieId: Long, body: String): Int = dbQuery {
        CommentsTable.insert { row ->
            row[CommentsTable.userId] = userId
            row[CommentsTable.movieId] = movieId
            row[CommentsTable.body] = body
        } get CommentsTable.id
    }

    /**
     * Briše komentar SAMO ako je `userId` njegov autor (vraća false inače - poziva se sa
     * generičkom 403 porukom na ruti, bez otkrivanja da li komentar uopšte postoji). Prvo briše
     * njegove lajkove (FK ka CommentsTable.id) pa sam komentar.
     */
    suspend fun delete(commentId: Int, userId: String): Boolean = dbQuery {
        val owns = CommentsTable.select {
            (CommentsTable.id eq commentId) and (CommentsTable.userId eq userId)
        }.empty().not()
        if (!owns) return@dbQuery false

        CommentLikesTable.deleteWhere { CommentLikesTable.commentId eq commentId }
        CommentsTable.deleteWhere { (CommentsTable.id eq commentId) and (CommentsTable.userId eq userId) }
        true
    }

    /**
     * Lista komentara za film, paginirano. Sortiranje: PRVO komentari prijatelja (`friendIds`),
     * pa svi ostali - unutar svake grupe najnoviji prvi. Namerno urađeno u Kotlin-u umesto SQL
     * CASE ORDER BY izraza (Exposed 0.41.1 API za to je nezgrapan/verzija-osetljiv) - dohvata SVE
     * komentare za taj film iz baze (bez server-side WHERE granice po broju), pa se stabilno
     * sortira i tek onda paginira u memoriji. Prihvatljivo za realan obim ovog projekta (desetine/
     * stotine komentara po filmu, ne milioni) - ako obim ikad naraste, ovo je prvo mesto za
     * optimizaciju (prava SQL ORDER BY sa CASE izrazom).
     */
    suspend fun getForMovie(
        movieId: Long,
        requestingUserId: String,
        friendIds: List<String>,
        offset: Int,
        limit: Int
    ): CommentsPage {
        val friendSet = friendIds.toSet()

        val allRows = dbQuery {
            CommentsTable
                .join(UsersTable, JoinType.INNER, additionalConstraint = { CommentsTable.userId eq UsersTable.id })
                .select { CommentsTable.movieId eq movieId }
                .orderBy(CommentsTable.createdAt, SortOrder.DESC)
                .map { row ->
                    CommentRow(
                        id = row[CommentsTable.id],
                        userId = row[CommentsTable.userId],
                        username = row[UsersTable.username],
                        avatarUrl = publicAvatarUrl(row[UsersTable.avatarUrl]),
                        movieId = row[CommentsTable.movieId],
                        body = row[CommentsTable.body],
                        createdAt = row[CommentsTable.createdAt],
                        likeCount = 0,
                        likedByMe = false,
                        isFriend = row[CommentsTable.userId] in friendSet,
                        isOwn = row[CommentsTable.userId] == requestingUserId
                    )
                }
        }

        // sortedByDescending je stabilan sort - unutar iste grupe (prijatelj/nije) ostaje
        // redosled iz ORDER BY createdAt DESC iznad.
        val sorted = allRows.sortedByDescending { it.isFriend }
        val page = sorted.drop(offset).take(limit)
        if (page.isEmpty()) return CommentsPage(emptyList(), hasMore = false)

        val commentIds = page.map { it.id }
        val likeUserIdsByComment = dbQuery {
            CommentLikesTable.select { CommentLikesTable.commentId inList commentIds }
                .map { it[CommentLikesTable.commentId] to it[CommentLikesTable.userId] }
        }.groupBy({ it.first }, { it.second })

        val enriched = page.map { row ->
            val likedBy = likeUserIdsByComment[row.id].orEmpty()
            row.copy(likeCount = likedBy.size, likedByMe = requestingUserId in likedBy)
        }
        val hasMore = sorted.size > offset + page.size
        return CommentsPage(enriched, hasMore)
    }

    /**
     * Lajkuje/skida lajk (toggle) - baca [IllegalArgumentException] ako komentar ne postoji ili
     * ako korisnik pokušava da lajkuje sopstveni komentar (korisnički zahtev - ne sme).
     */
    suspend fun toggleLike(commentId: Int, userId: String): LikeToggleResult = dbQuery {
        val ownerId = CommentsTable.select { CommentsTable.id eq commentId }
            .firstOrNull()?.get(CommentsTable.userId)
            ?: throw IllegalArgumentException("Komentar nije pronađen")
        if (ownerId == userId) {
            throw IllegalArgumentException("Ne možeš lajkovati sopstveni komentar")
        }

        val alreadyLiked = CommentLikesTable.select {
            (CommentLikesTable.commentId eq commentId) and (CommentLikesTable.userId eq userId)
        }.empty().not()

        if (alreadyLiked) {
            CommentLikesTable.deleteWhere {
                (CommentLikesTable.commentId eq commentId) and (CommentLikesTable.userId eq userId)
            }
        } else {
            CommentLikesTable.insert { row ->
                row[CommentLikesTable.commentId] = commentId
                row[CommentLikesTable.userId] = userId
            }
        }

        val likeCount = CommentLikesTable.select { CommentLikesTable.commentId eq commentId }.count().toInt()
        LikeToggleResult(liked = !alreadyLiked, likeCount = likeCount)
    }
}
