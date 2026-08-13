package com.milinko

import com.milinko.db.FriendshipsRepository
import com.milinko.db.UserActionsRepository
import com.milinko.services.TmdbMovieMatch
import com.milinko.services.TmdbService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Javne rute za pretragu/detalje/trending filmova (Faza 1 iz MOVIELENS_ROADMAP.md).
 * Namerno BEZ authenticate("auth-jwt") — Android app dozvoljava pregled filmova bez prijave,
 * prijava je potrebna samo za skeniranje slike i lične akcije (watchlist/ocene, Faza 2+).
 *
 * DTO-ovi ispod su namerno oblikovani (nazivi JSON polja preko @SerialName) da tačno prate
 * ono što Android MovieModels.kt (Gson @SerializedName) već očekuje — kod na klijentu se ne dira.
 */

@Serializable
data class MovieSearchResultDto(
    val id: Int,
    val title: String,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    val overview: String? = null
)

@Serializable
data class MovieListResponseDto(val results: List<MovieSearchResultDto>)

@Serializable
data class GenreDto(val id: Int, val name: String)

@Serializable
data class CastMemberDto(
    val id: Int,
    val name: String,
    val character: String? = null,
    @SerialName("profile_path") val profilePath: String? = null
)

@Serializable
data class MovieDetailDto(
    val id: Int,
    val title: String,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val overview: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    val runtime: Int = 0,
    val genres: List<GenreDto> = emptyList(),
    val cast: List<CastMemberDto> = emptyList(),
    // Bioskopski termini se ovde namerno ne popunjavaju - videti napomenu u
    // TmdbService.getMovieDetails i MOVIELENS_ROADMAP.md. Uvek prazna lista za sada.
    val showtimes: List<String> = emptyList()
)

private fun TmdbMovieMatch.toDto() = MovieSearchResultDto(
    id = id ?: 0,
    title = title,
    releaseDate = releaseDate,
    posterPath = posterPath,
    voteAverage = voteAverage ?: 0.0,
    overview = overview
)

// "Prijatelji su gledali ovaj film" bedž na Movie Detail-u - namerno posebna, JWT-zaštićena ruta
// izvan /api/movies bloka ispod (koji ostaje javan/bez logina), jer zavisi od toga ko pita.
@Serializable
data class FriendWatchedDto(
    @SerialName("friend_id") val friendId: String,
    @SerialName("friend_name") val friendName: String,
    val rating: Int? = null
)

@Serializable
data class FriendsWatchedResponseDto(val friends: List<FriendWatchedDto>)

fun Application.configureMovieRouting(
    tmdbService: TmdbService,
    friendshipsRepository: FriendshipsRepository,
    userActionsRepository: UserActionsRepository
) {
    routing {
        route("/api/movies") {
            get("/search") {
                val query = call.request.queryParameters["query"]?.trim().orEmpty()
                if (query.isEmpty()) {
                    return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Query parametar je obavezan"))
                }
                try {
                    val results = tmdbService.searchMovies(query)
                    call.respond(MovieListResponseDto(results.map { it.toDto() }))
                } catch (e: Exception) {
                    println("[ERROR] /api/movies/search: ${e.message}")
                    call.respond(HttpStatusCode.BadGateway, ErrorResponse(e.message ?: "TMDB pretraga nije uspela"))
                }
            }

            get("/trending") {
                val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                try {
                    val results = tmdbService.getPopularMovies(page)
                    call.respond(MovieListResponseDto(results.map { it.toDto() }))
                } catch (e: Exception) {
                    println("[ERROR] /api/movies/trending: ${e.message}")
                    call.respond(HttpStatusCode.BadGateway, ErrorResponse(e.message ?: "TMDB trending nije uspeo"))
                }
            }

            get("/featured") {
                // Za sada isto što i trending, dok ne postoji personalizacija (Faza 4 - profilisanje)
                try {
                    val results = tmdbService.getPopularMovies(1)
                    call.respond(MovieListResponseDto(results.map { it.toDto() }))
                } catch (e: Exception) {
                    println("[ERROR] /api/movies/featured: ${e.message}")
                    call.respond(HttpStatusCode.BadGateway, ErrorResponse(e.message ?: "TMDB featured nije uspeo"))
                }
            }

            get("/{tmdbId}") {
                val tmdbId = call.parameters["tmdbId"]?.toIntOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Nevalidan tmdbId"))
                try {
                    val detail = tmdbService.getMovieDetails(tmdbId)
                        ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Film nije pronađen"))

                    call.respond(
                        MovieDetailDto(
                            id = detail.id,
                            title = detail.title,
                            releaseDate = detail.releaseDate,
                            posterPath = detail.posterPath,
                            backdropPath = detail.backdropPath,
                            overview = detail.overview,
                            voteAverage = detail.voteAverage ?: 0.0,
                            runtime = detail.runtime ?: 0,
                            genres = detail.genres.map { GenreDto(it.id, it.name) },
                            cast = detail.cast.map { CastMemberDto(it.id, it.name, it.character, it.profilePath) }
                        )
                    )
                } catch (e: Exception) {
                    println("[ERROR] /api/movies/$tmdbId: ${e.message}")
                    call.respond(HttpStatusCode.BadGateway, ErrorResponse(e.message ?: "TMDB detalji nisu uspeli"))
                }
            }
        }

        authenticate("auth-jwt") {
            get("/api/movies/{tmdbId}/friends-watched") {
                val callerId = call.principal<JWTPrincipal>()?.payload?.subject
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val tmdbId = call.parameters["tmdbId"]?.toLongOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Nevalidan tmdbId"))

                val friendIds = friendshipsRepository.getFriendIds(callerId)
                val watched = userActionsRepository.getFriendsWhoWatched(friendIds, tmdbId)
                call.respond(
                    FriendsWatchedResponseDto(
                        watched.map { FriendWatchedDto(it.friendId, it.friendUsername ?: "Korisnik", it.rating) }
                    )
                )
            }
        }
    }
}
