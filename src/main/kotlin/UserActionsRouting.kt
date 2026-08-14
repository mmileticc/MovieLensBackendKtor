package com.milinko

import com.milinko.db.MoviesRepository
import com.milinko.db.TasteProfileRepository
import com.milinko.db.UserActionMovieRow
import com.milinko.db.UserActionsRepository
import com.milinko.services.TmdbService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Watchlist i "odgledano"/ocena (Faza 2, MOVIELENS_ROADMAP.md). Sve rute su iza JWT auth-a i
 * eksplicitno proveravaju da path {userId} odgovara JWT subject-u - Android ugovor šalje userId
 * kao path parametar, pa bez ove provere bi teoretski neko mogao da menja tuđu listu.
 */

@Serializable
data class WatchlistItemDto(
    @SerialName("tmdb_id") val tmdbId: Long,
    val title: String,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("added_at") val addedAt: Long,
    // Nema priority koncepta u stvarnoj šemi - uvek 0 (Android ga i dalje samo uslovno prikazuje).
    val priority: Int = 0
)

@Serializable
data class WatchlistResponseDto(val items: List<WatchlistItemDto>)

@Serializable
data class AddToWatchlistRequest(val tmdbId: Int)

@Serializable
data class HistoryItemDto(
    @SerialName("tmdb_id") val tmdbId: Long,
    val title: String,
    @SerialName("poster_path") val posterPath: String? = null,
    // 0.0 = odgledano bez ocene (rating je null u bazi). Android prikazuje "X/10" bez posebne
    // provere za "nema ocene" - poznato pojednostavljenje za MVP, videti MOVIELENS_ROADMAP.md.
    @SerialName("user_rating") val userRating: Double = 0.0,
    @SerialName("watched_at") val watchedAt: Long,
    // Nema duration kolone u stvarnoj šemi - uvek 0 (Android ekran ga ni ne prikazuje).
    val duration: Int = 0
)

@Serializable
data class HistoryResponseDto(val items: List<HistoryItemDto>)

@Serializable
data class AddToHistoryRequest(val tmdbId: Int, val rating: Double? = null, val duration: Int? = null)

@Serializable
data class UpdateHistoryRequest(val rating: Double)

// Taste profile (Faza 4) - prosečna ocena po žanru, izvedeno direktno iz ocena bez "crne kutije".
@Serializable
data class GenreAffinityDto(
    @SerialName("genre_id") val genreId: Int,
    val genre: String,
    @SerialName("average_rating") val averageRating: Double,
    @SerialName("movie_count") val movieCount: Int
)

@Serializable
data class TasteProfileResponseDto(val genres: List<GenreAffinityDto>)

private fun LocalDateTime?.toEpochMillisOrNow(): Long {
    val localDateTime = this ?: Clock.System.now().toLocalDateTime(TimeZone.UTC)
    return localDateTime.toInstant(TimeZone.UTC).toEpochMilliseconds()
}

private fun UserActionMovieRow.toWatchlistDto() = WatchlistItemDto(
    tmdbId = tmdbId,
    title = title,
    posterPath = posterPath,
    addedAt = createdAt.toEpochMillisOrNow()
)

private fun UserActionMovieRow.toHistoryDto() = HistoryItemDto(
    tmdbId = tmdbId,
    title = title,
    posterPath = posterPath,
    userRating = rating?.toDouble() ?: 0.0,
    watchedAt = createdAt.toEpochMillisOrNow()
)

/** Zaokružuje na najbliži ceo broj i ograničava na [1,10] (DB CHECK opseg). Null ostaje null. */
private fun Double?.toRatingIntOrNull(): Int? {
    if (this == null) return null
    return Math.round(this).toInt().coerceIn(1, 10)
}

fun Application.configureUserActionsRouting(
    tmdbService: TmdbService,
    userActionsRepository: UserActionsRepository,
    moviesRepository: MoviesRepository,
    tasteProfileRepository: TasteProfileRepository
) {
    routing {
        authenticate("auth-jwt") {
            route("/api/users/{userId}") {

                fun ApplicationCall.ownerUserIdOrNull(): String? {
                    val pathUserId = parameters["userId"] ?: return null
                    val tokenUserId = principal<JWTPrincipal>()?.payload?.subject
                    return if (pathUserId == tokenUserId) pathUserId else null
                }

                route("/watchlist") {
                    get {
                        val userId = call.ownerUserIdOrNull()
                            ?: return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Nemaš pristup ovoj listi"))
                        val items = userActionsRepository.getWatchlist(userId).map { it.toWatchlistDto() }
                        call.respond(WatchlistResponseDto(items))
                    }

                    post {
                        val userId = call.ownerUserIdOrNull()
                            ?: return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Nemaš pristup ovoj listi"))
                        val req = call.receive<AddToWatchlistRequest>()

                        try {
                            moviesRepository.ensureCached(req.tmdbId, tmdbService)
                        } catch (e: IllegalArgumentException) {
                            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Nevalidan film"))
                        }

                        userActionsRepository.addToWatchlist(userId, req.tmdbId.toLong())
                        val item = userActionsRepository.getWatchlistItem(userId, req.tmdbId.toLong())
                            ?: return@post call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Neočekivana greška pri čuvanju"))
                        call.respond(item.toWatchlistDto())
                    }

                    delete("/{tmdbId}") {
                        val userId = call.ownerUserIdOrNull()
                            ?: return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("Nemaš pristup ovoj listi"))
                        val tmdbId = call.parameters["tmdbId"]?.toLongOrNull()
                            ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Nevalidan tmdbId"))
                        userActionsRepository.removeFromWatchlist(userId, tmdbId)
                        call.respond(HttpStatusCode.NoContent)
                    }
                }

                route("/history") {
                    get {
                        val userId = call.ownerUserIdOrNull()
                            ?: return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Nemaš pristup ovoj listi"))
                        val items = userActionsRepository.getHistory(userId).map { it.toHistoryDto() }
                        call.respond(HistoryResponseDto(items))
                    }

                    post {
                        val userId = call.ownerUserIdOrNull()
                            ?: return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Nemaš pristup ovoj listi"))
                        val req = call.receive<AddToHistoryRequest>()

                        try {
                            moviesRepository.ensureCached(req.tmdbId, tmdbService)
                        } catch (e: IllegalArgumentException) {
                            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Nevalidan film"))
                        }

                        userActionsRepository.markWatched(userId, req.tmdbId.toLong(), req.rating.toRatingIntOrNull())
                        val item = userActionsRepository.getHistoryItem(userId, req.tmdbId.toLong())
                            ?: return@post call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Neočekivana greška pri čuvanju"))
                        call.respond(item.toHistoryDto())
                    }

                    put("/{tmdbId}") {
                        val userId = call.ownerUserIdOrNull()
                            ?: return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("Nemaš pristup ovoj listi"))
                        val tmdbId = call.parameters["tmdbId"]?.toLongOrNull()
                            ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Nevalidan tmdbId"))
                        val req = call.receive<UpdateHistoryRequest>()

                        // PUT ažurira samo postojeći "odgledano" red - ako ne postoji, korisnik prvo
                        // treba da odradi POST /history (markWatched ga ionako kreira ako fali,
                        // ali vraćamo 404 da bi ponašanje ostalo predvidljivo za klijenta).
                        val existing = userActionsRepository.getHistoryItem(userId, tmdbId)
                            ?: return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("Film nije u istoriji"))

                        userActionsRepository.markWatched(userId, tmdbId, req.rating.toRatingIntOrNull())
                        val updated = userActionsRepository.getHistoryItem(userId, tmdbId) ?: existing
                        call.respond(updated.toHistoryDto())
                    }

                    // "Undo" za slučajan (lapsus) klik na "Označi kao odgledano" - briše i ocenu
                    // pošto je u istom redu.
                    delete("/{tmdbId}") {
                        val userId = call.ownerUserIdOrNull()
                            ?: return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("Nemaš pristup ovoj listi"))
                        val tmdbId = call.parameters["tmdbId"]?.toLongOrNull()
                            ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Nevalidan tmdbId"))
                        userActionsRepository.removeFromHistory(userId, tmdbId)
                        call.respond(HttpStatusCode.NoContent)
                    }
                }

                // Faza 4 - prosečna ocena po žanru, izvedeno iz WATCHED+rated filmova. Samo za
                // sebe za sada (ownerUserIdOrNull) - poređenje sa prijateljem dolazi kasnije.
                get("/taste-profile") {
                    val userId = call.ownerUserIdOrNull()
                        ?: return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Nemaš pristup ovom profilu"))
                    val profile = tasteProfileRepository.getTasteProfile(userId)
                    call.respond(
                        TasteProfileResponseDto(
                            profile.map { GenreAffinityDto(it.genreId, it.genreName, it.averageRating, it.movieCount) }
                        )
                    )
                }
            }
        }
    }
}
