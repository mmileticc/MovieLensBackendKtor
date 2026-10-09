package com.milinko.db

import com.milinko.db.DatabaseFactory.dbQuery
import com.milinko.services.TmdbGenre
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select

/**
 * How much a user likes one genre: the average of their ratings for watched movies of that genre.
 *
 * There is deliberately no minimum number of movies; [movieCount] is returned with the average so
 * the app can show "based on N movies" instead of hiding genres behind an arbitrary threshold.
 */
data class GenreAffinity(
    val genreId: Int,
    val genreName: String,
    val averageRating: Double,
    val movieCount: Int
)

/** Result of comparing two taste profiles, see [compareTasteProfiles]. */
data class FriendMatch(
    val matchScore: Float, // 0-100
    val commonGenres: List<String>, // up to 3 genres both users really like, for display
    val sharedGenreIds: List<Int> // all shared genres, best first - used for Movie Night suggestions
)

// Uncertainty penalty (in the spirit of a lower confidence bound): a genre's score is its average
// rating minus Z / sqrt(movieCount). A genre backed by one 10/10 movie scores 8.5, while one backed
// by four movies averaging 9.5 scores 8.75 - so a single lucky rating cannot outrank a genre the
// user consistently rates highly. Plain "shrink towards the user's average" does not achieve this:
// it can never swap two genres that sit on opposite sides of that average.
private const val CONFIDENCE_PENALTY_Z = 1.5

private fun GenreAffinity.confidenceScore(): Double =
    averageRating - CONFIDENCE_PENALTY_Z / kotlin.math.sqrt(movieCount.toDouble())

/**
 * Compares two taste profiles (the user and a friend).
 *
 * For every shared genre, the "enthusiasm" is the lower of the two penalized scores, so both users
 * must like the genre - and reliably so - for it to rank high. `matchScore` is the average
 * enthusiasm over all shared genres, scaled from the 1-10 rating scale to 0-100. With no shared
 * genres the score is 0 and the lists are empty.
 *
 * `commonGenres` deliberately uses the raw averages (both >= 6.0): it answers "do both of them
 * like this genre", while the penalized score only decides the order and the match score.
 */
fun compareTasteProfiles(self: List<GenreAffinity>, friend: List<GenreAffinity>): FriendMatch {
    val selfByGenreId = self.associateBy { it.genreId }
    val friendByGenreId = friend.associateBy { it.genreId }
    val sharedIds = selfByGenreId.keys.intersect(friendByGenreId.keys)
    if (sharedIds.isEmpty()) return FriendMatch(matchScore = 0f, commonGenres = emptyList(), sharedGenreIds = emptyList())

    val enthusiasmByGenreId = sharedIds.associateWith { genreId ->
        minOf(selfByGenreId.getValue(genreId).confidenceScore(), friendByGenreId.getValue(genreId).confidenceScore())
    }
    val matchScore = (enthusiasmByGenreId.values.average() * 10.0).toFloat().coerceIn(0f, 100f)
    val sortedIds = enthusiasmByGenreId.entries.sortedByDescending { it.value }.map { it.key }
    // Shown as "common genres" only when both really like the genre (raw average >= 6/10);
    // otherwise two badly rated movies of the same genre would count as a shared taste.
    val commonGenres = sortedIds
        .filter { genreId ->
            minOf(selfByGenreId.getValue(genreId).averageRating, friendByGenreId.getValue(genreId).averageRating) >= 6.0
        }
        .take(3)
        .map { selfByGenreId.getValue(it).genreName }

    return FriendMatch(matchScore = matchScore, commonGenres = commonGenres, sharedGenreIds = sortedIds)
}

/** Builds taste profiles from the users' rated WATCHED movies and their genres. */
class TasteProfileRepository {

    /** The user's taste profile, best genre first (see [CONFIDENCE_PENALTY_Z] for the ranking). */
    suspend fun getTasteProfile(userId: String): List<GenreAffinity> =
        getTasteProfiles(listOf(userId))[userId].orEmpty()

    /**
     * Taste profiles of several users in one query (e.g. the user and all of their friends).
     * Users without ratings get an empty list.
     */
    suspend fun getTasteProfiles(userIds: List<String>): Map<String, List<GenreAffinity>> {
        if (userIds.isEmpty()) return emptyMap()
        // "WATCHED" is the same literal as ACTION_WATCHED in UserActionsRepository (private there).
        val ratedRowsByUser = dbQuery {
            UserActionsTable
                .join(MoviesTable, JoinType.INNER, additionalConstraint = { UserActionsTable.movieId eq MoviesTable.tmdbId })
                .selectAll().where {
                    (UserActionsTable.userId inList userIds) and
                        (UserActionsTable.actionType eq "WATCHED") and
                        (UserActionsTable.rating.isNotNull())
                }
                .map { row ->
                    row[UserActionsTable.userId] to (row[UserActionsTable.rating]!! to decodeGenres(row[MoviesTable.genres]))
                }
                .groupBy({ it.first }, { it.second })
        }
        return userIds.associateWith { buildTasteProfile(ratedRowsByUser[it].orEmpty()) }
    }
}

private fun buildTasteProfile(ratedRows: List<Pair<Int, List<TmdbGenre>>>): List<GenreAffinity> {
    // Group by genre id rather than name (stable, immune to localized names); the name is only
    // kept for display.
    val ratingSumByGenreId = mutableMapOf<Int, Double>()
    val countByGenreId = mutableMapOf<Int, Int>()
    val nameByGenreId = mutableMapOf<Int, String>()
    for ((rating, genres) in ratedRows) {
        for (genre in genres) {
            ratingSumByGenreId[genre.id] = (ratingSumByGenreId[genre.id] ?: 0.0) + rating
            countByGenreId[genre.id] = (countByGenreId[genre.id] ?: 0) + 1
            nameByGenreId[genre.id] = genre.name
        }
    }

    // Ranked by confidenceScore() so that genres with few ratings are penalized (see
    // CONFIDENCE_PENALTY_Z). The returned averageRating stays the raw average for display.
    return ratingSumByGenreId.keys
        .map { genreId ->
            val count = countByGenreId.getValue(genreId)
            val average = ratingSumByGenreId.getValue(genreId) / count
            GenreAffinity(
                genreId = genreId,
                genreName = nameByGenreId.getValue(genreId),
                averageRating = average,
                movieCount = count
            )
        }
        .sortedByDescending { affinity -> affinity.confidenceScore() }
}
