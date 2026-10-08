package com.milinko

import com.milinko.db.FriendshipStatus
import com.milinko.db.FriendshipsRepository
import com.milinko.db.User
import com.milinko.db.UserActionsRepository
import com.milinko.db.UserDao
import com.milinko.db.TasteProfileRepository
import com.milinko.db.compareTasteProfiles
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
 * Prijateljstva (Faza 3, MOVIELENS_ROADMAP.md): pretraga korisnika, slanje/prihvatanje/odbijanje
 * zahteva, lista prijatelja i activity feed (samo "odgledano"/ocene - watchlist ostaje privatan,
 * dogovoreno u planu).
 */

@Serializable
data class UserSearchResultDto(
    val id: String,
    val username: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val status: String
)

@Serializable
data class UserSearchResponseDto(val users: List<UserSearchResultDto>)

@Serializable
data class FriendDto(
    @SerialName("friend_id") val friendId: String,
    val username: String,
    val avatar: String? = null,
    // Faza 4 - stvarno se računa iz compareTasteProfiles (0 ako nema preseka žanrova).
    @SerialName("match_score") val matchScore: Float = 0f,
    @SerialName("common_genres") val commonGenres: List<String> = emptyList(),
    // Android model (FriendInfo) očekuje ovaj ključ da postoji u JSON-u - Gson kod Kotlin data
    // klasa ne primenjuje default vrednosti za izostavljene ključeve (bypass-uje konstruktor).
    @SerialName("mutual_watchlist") val mutualWatchlist: List<String> = emptyList()
)

@Serializable
data class FriendsResponseDto(val friends: List<FriendDto>)

@Serializable
data class PendingRequestDto(val id: String, val username: String? = null, @SerialName("avatar_url") val avatarUrl: String? = null)

@Serializable
data class PendingRequestsResponseDto(val requests: List<PendingRequestDto>)

@Serializable
data class FriendActionRequest(val action: String)

@Serializable
data class ActivityMetadataDto(
    val rating: Double? = null,
    val review: String? = null,
    @SerialName("watched_at") val watchedAt: Long? = null
)

@Serializable
data class FriendActivityDto(
    @SerialName("activity_id") val activityId: String,
    @SerialName("friend_id") val friendId: String,
    @SerialName("friend_name") val friendName: String,
    val action: String,
    @SerialName("tmdb_id") val tmdbId: Long,
    @SerialName("movie_title") val movieTitle: String,
    // Faza 7 dorada - Profile ekran prikazuje poster umesto plain ikonice/rečenice.
    @SerialName("poster_path") val posterPath: String? = null,
    val metadata: ActivityMetadataDto = ActivityMetadataDto(),
    val timestamp: Long
)

@Serializable
data class FriendActivityResponseDto(val activities: List<FriendActivityDto>)

// Profil prijatelja - osnovni info + njegova WATCHED lista (nikad watchlist, taj ostaje privatan).
@Serializable
data class FriendProfileMovieDto(
    @SerialName("tmdb_id") val tmdbId: Long,
    val title: String,
    @SerialName("poster_path") val posterPath: String? = null,
    val rating: Int? = null,
    val review: String? = null,
    @SerialName("watched_at") val watchedAt: Long
)

// Bugfix (E1.5, 2. septembar 2026, feedback testera - "nemogućnost pregleda profila pre slanja
// zahteva") - RANIJE je ova ruta bezuslovno vraćala 403 dok status nije ACCEPTED. Sad se uvek
// vraća profil, ali OGRANIČEN dok prijateljstvo nije prihvaćeno: `email`/`commonGenres` popunjeni,
// `watchedMovies` PRAZNO (nikad se ne otkriva šta je neko gledao pre nego što postane prijatelj -
// isto pravilo kao i za watchlist, koji se ne otkriva NIKAD, čak ni prijateljima).
// `friendshipStatus` govori Android klijentu koji prikaz da renderuje (pun profil vs. preview).
@Serializable
data class FriendProfileResponseDto(
    val id: String,
    val username: String,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    // Faza 7 redizajna - prijateljev "mini status" ispod imena, isto polje kao sopstveni profil.
    val bio: String? = null,
    @SerialName("watched_movies") val watchedMovies: List<FriendProfileMovieDto>,
    // Sledeća tri polja - E1.5 dorada.
    val email: String? = null,
    @SerialName("friendship_status") val friendshipStatus: String = "ACCEPTED",
    @SerialName("common_genres") val commonGenres: List<String> = emptyList()
)

// Movie Night predlozi (Faza 4, korak 2) - Android ugovor za ovo je postojao od ranije
// (UserModels.kt: MovieSuggestion/SuggestionScores/MovieNightResponse), samo nikad nije bio
// implementiran na backendu ni pozvan sa UI-ja.
@Serializable
data class SuggestionScoresDto(
    @SerialName("your_match") val yourMatch: Float = 0f,
    @SerialName("their_match") val theirMatch: Float = 0f,
    @SerialName("combined_score") val combinedScore: Float = 0f
)

@Serializable
data class MovieSuggestionDto(
    @SerialName("tmdb_id") val tmdbId: Int,
    val title: String,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("match_reason") val matchReason: String,
    val scores: SuggestionScoresDto = SuggestionScoresDto(),
    // UX dorada (oktobar 2026) - osnovni podaci da se o filmu može odlučiti bez ulaska u detalje.
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("genre_ids") val genreIds: List<Int> = emptyList()
)

@Serializable
data class MovieNightResponseDto(
    val suggestions: List<MovieSuggestionDto>,
    // Dorada (25. avgust 2026, korisnička žalba "za neke prijatelje se predlozi ponavljaju posle
    // 3-4 ekrana") - da li TMDB ima još stranica za ovaj par žanrova NAKON trenutne. Android strana
    // (MovieNightViewModel) ranije nije imala ovu informaciju pa je rotirala kroz izmišljenih fiksnih
    // 5 stranica - sad umesto toga sledi ovaj flag i vraća se na stranicu 1 tek kad je stvarno nema
    // više šta novo da se ponudi.
    @SerialName("has_more") val hasMore: Boolean = false,
    // UX dorada (oktobar 2026) - zaglavlje Movie Night ekrana: ime prijatelja, zajednički žanrovi
    // (Android ih nudi i kao filter) i ukupno poklapanje ukusa. Isti za sve predloge - zato je ovde,
    // a ne ponovljen u svakom MovieSuggestionDto.
    @SerialName("friend_username") val friendUsername: String? = null,
    @SerialName("shared_genres") val sharedGenres: List<GenreDto> = emptyList(),
    @SerialName("match_score") val matchScore: Float = 0f
)

private fun LocalDateTime?.toEpochMillisOrNow(): Long {
    val localDateTime = this ?: Clock.System.now().toLocalDateTime(TimeZone.UTC)
    return localDateTime.toInstant(TimeZone.UTC).toEpochMilliseconds()
}

private fun User.toFriendDto(matchScore: Float = 0f, commonGenres: List<String> = emptyList()) = FriendDto(
    friendId = id,
    username = username ?: "Korisnik",
    avatar = avatarUrl,
    matchScore = matchScore,
    commonGenres = commonGenres
)

private fun User.toPendingRequestDto() = PendingRequestDto(id = id, username = username, avatarUrl = avatarUrl)

fun Application.configureFriendsRouting(
    friendshipsRepository: FriendshipsRepository,
    userDao: UserDao,
    userActionsRepository: UserActionsRepository,
    tasteProfileRepository: TasteProfileRepository,
    tmdbService: TmdbService
) {
    routing {
        authenticate("auth-jwt") {

            // Pretraga korisnika za dodavanje prijatelja - flat ruta (nema {userId} path parametra,
            // "ko sam ja" se čita direktno iz JWT-a).
            get("/api/users/search") {
                val callerId = call.principal<JWTPrincipal>()?.payload?.subject
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val query = call.request.queryParameters["q"]?.trim().orEmpty()
                if (query.length < 2) {
                    return@get call.respond(UserSearchResponseDto(emptyList()))
                }

                val results = userDao.searchUsers(query, excludeUserId = callerId).map { user ->
                    val status = friendshipsRepository.getFriendshipStatus(callerId, user.id)
                    UserSearchResultDto(
                        id = user.id,
                        username = user.username,
                        avatarUrl = user.avatarUrl,
                        status = status.name
                    )
                }
                call.respond(UserSearchResponseDto(results))
            }

            route("/api/users/{userId}") {

                fun ApplicationCall.ownerUserIdOrNull(): String? {
                    val pathUserId = parameters["userId"] ?: return null
                    val tokenUserId = principal<JWTPrincipal>()?.payload?.subject
                    return if (pathUserId == tokenUserId) pathUserId else null
                }

                route("/friends") {
                    get {
                        val userId = call.ownerUserIdOrNull()
                            ?: return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Nemaš pristup ovoj listi"))
                        // Faza 4, korak 2: pravi match_score/common_genres umesto placeholder 0f/empty.
                        // Self profil se računa jednom, pa se poredi sa svakim prijateljem posebno -
                        // sasvim u redu za male liste prijatelja (diplomski obim, ne treba optimizacija).
                        val selfProfile = tasteProfileRepository.getTasteProfile(userId)
                        val friends = friendshipsRepository.getFriends(userId).map { friend ->
                            val friendProfile = tasteProfileRepository.getTasteProfile(friend.id)
                            val match = compareTasteProfiles(selfProfile, friendProfile)
                            friend.toFriendDto(match.matchScore, match.commonGenres)
                        }
                        call.respond(FriendsResponseDto(friends))
                    }

                    get("/pending") {
                        val userId = call.ownerUserIdOrNull()
                            ?: return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Nemaš pristup ovoj listi"))
                        val pending = friendshipsRepository.getPendingIncoming(userId).map { it.toPendingRequestDto() }
                        call.respond(PendingRequestsResponseDto(pending))
                    }

                    // Korisnički zahtev (2. septembar 2026) - poslati (odlazni) zahtevi, da budu
                    // vidljivi izlistani kao i prijatelji, ne samo otkriveni ponovnom pretragom te
                    // osobe. Simetrična ruta sa /pending.
                    get("/pending/sent") {
                        val userId = call.ownerUserIdOrNull()
                            ?: return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Nemaš pristup ovoj listi"))
                        val sent = friendshipsRepository.getPendingOutgoing(userId).map { it.toPendingRequestDto() }
                        call.respond(PendingRequestsResponseDto(sent))
                    }

                    get("/activity") {
                        val userId = call.ownerUserIdOrNull()
                            ?: return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Nemaš pristup ovoj listi"))
                        val friendIds = friendshipsRepository.getFriendIds(userId)
                        val activity = userActionsRepository.getFriendsActivity(friendIds).map { row ->
                            FriendActivityDto(
                                activityId = "${row.friendId}-${row.tmdbId}",
                                friendId = row.friendId,
                                friendName = row.friendUsername ?: "Korisnik",
                                action = if (row.rating != null) "rated" else "watched",
                                tmdbId = row.tmdbId,
                                movieTitle = row.title,
                                posterPath = row.posterPath,
                                metadata = ActivityMetadataDto(
                                    rating = row.rating?.toDouble(),
                                    review = row.review,
                                    watchedAt = row.createdAt.toEpochMillisOrNow()
                                ),
                                timestamp = row.createdAt.toEpochMillisOrNow()
                            )
                        }
                        call.respond(FriendActivityResponseDto(activity))
                    }

                    // Profil prijatelja - pun profil (sa WATCHED listom, nikad watchlist-om) za sebe
                    // ili ACCEPTED prijatelja. E1.5 dorada (2. septembar 2026) - za bilo koji drugi
                    // status (NONE/PENDING_SENT/PENDING_RECEIVED) više NE vraća 403, nego OGRANIČEN
                    // preview (slika/ime/email/opis/presek žanrova, BEZ liste odgledanih filmova) -
                    // omogućava pregled pre slanja/prihvatanja zahteva za prijateljstvo.
                    get("/{friendId}/profile") {
                        val userId = call.ownerUserIdOrNull()
                            ?: return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Nemaš pristup ovoj akciji"))
                        val friendId = call.parameters["friendId"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Nedostaje friendId"))

                        val friendUser = userDao.findById(friendId)
                            ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Korisnik nije pronađen"))

                        val status = if (friendId == userId) FriendshipStatus.ACCEPTED else friendshipsRepository.getFriendshipStatus(userId, friendId)

                        if (status != FriendshipStatus.ACCEPTED) {
                            // Ograničen preview - presek žanrovskih interesovanja (isti
                            // compareTasteProfiles helper kao /friends lista), bez otkrivanja
                            // ičega o gledanim filmovima.
                            val selfProfile = tasteProfileRepository.getTasteProfile(userId)
                            val friendProfile = tasteProfileRepository.getTasteProfile(friendId)
                            val match = compareTasteProfiles(selfProfile, friendProfile)
                            return@get call.respond(
                                FriendProfileResponseDto(
                                    id = friendUser.id,
                                    username = friendUser.username ?: "Korisnik",
                                    avatarUrl = friendUser.avatarUrl,
                                    bio = friendUser.bio,
                                    watchedMovies = emptyList(),
                                    // Email vidi samo prihvaćeni prijatelj - inače bi svako ko zna
                                    // nečiji ID (npr. iz komentara) mogao da dobije njegovu adresu.
                                    email = null,
                                    friendshipStatus = status.name,
                                    commonGenres = match.commonGenres
                                )
                            )
                        }

                        val watchedMovies = userActionsRepository.getHistory(friendId).map { row ->
                            FriendProfileMovieDto(
                                tmdbId = row.tmdbId,
                                title = row.title,
                                posterPath = row.posterPath,
                                rating = row.rating,
                                review = row.review,
                                watchedAt = row.createdAt.toEpochMillisOrNow()
                            )
                        }

                        call.respond(
                            FriendProfileResponseDto(
                                id = friendUser.id,
                                username = friendUser.username ?: "Korisnik",
                                avatarUrl = friendUser.avatarUrl,
                                bio = friendUser.bio,
                                watchedMovies = watchedMovies,
                                email = friendUser.email,
                                friendshipStatus = status.name
                            )
                        )
                    }

                    // Movie Night predlozi - samo za ACCEPTED prijatelje. Discover po zajedničkim
                    // omiljenim žanrovima (compareTasteProfiles), isključuje filmove koje je BILO
                    // KO od njih dvoje već odgledao (userActionsRepository.getHistory = WATCHED).
                    get("/{friendId}/movienight-suggestions") {
                        val userId = call.ownerUserIdOrNull()
                            ?: return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Nemaš pristup ovoj akciji"))
                        val friendId = call.parameters["friendId"]
                            ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Nedostaje friendId"))
                        // ?page= - koristi Android "Osveži predloge" dugme da dobije drugu TMDB
                        // discover stranicu za isti par žanrova umesto uvek istih prvih 10 rezultata.
                        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1

                        val status = friendshipsRepository.getFriendshipStatus(userId, friendId)
                        if (status != FriendshipStatus.ACCEPTED) {
                            return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Predlozi su dostupni samo za prijatelje"))
                        }

                        val selfProfile = tasteProfileRepository.getTasteProfile(userId)
                        val friendProfile = tasteProfileRepository.getTasteProfile(friendId)
                        val match = compareTasteProfiles(selfProfile, friendProfile)
                        val friendUsername = userDao.findById(friendId)?.username

                        if (match.sharedGenreIds.isEmpty()) {
                            // Nema preseka žanrova (nedovoljno zajedničkih ocena) - nema šta pametno
                            // da se predloži umesto da izmišljamo nasumične filmove.
                            return@get call.respond(MovieNightResponseDto(emptyList(), friendUsername = friendUsername))
                        }

                        val topGenreIds = match.sharedGenreIds.take(3)
                        val selfByGenreId = selfProfile.associateBy { it.genreId }
                        val friendByGenreId = friendProfile.associateBy { it.genreId }
                        val sharedGenres = topGenreIds.map { GenreDto(it, selfByGenreId.getValue(it).genreName) }
                        val primaryGenreId = topGenreIds.first()
                        val yourMatch = (selfByGenreId.getValue(primaryGenreId).averageRating * 10.0).toFloat().coerceIn(0f, 100f)
                        val theirMatch = (friendByGenreId.getValue(primaryGenreId).averageRating * 10.0).toFloat().coerceIn(0f, 100f)

                        val alreadySeen = (userActionsRepository.getHistory(userId) + userActionsRepository.getHistory(friendId))
                            .map { it.tmdbId }
                            .toSet()

                        // Dorada (25. avgust 2026) - koristi novu metodu koja i vraća total_pages
                        // (videti TmdbService.getMoviesByGenresWithTotalPages), umesto stare
                        // getMoviesByGenres koja je taj podatak tiho odbacivala.
                        val discoverPage = tmdbService.getMoviesByGenresWithTotalPages(topGenreIds, page)
                        val suggestions = discoverPage.movies
                            .filter { it.id != null && it.id.toLong() !in alreadySeen }
                            .take(10)
                            .map { movie ->
                                // Razlog po filmu: koje od ZAJEDNIČKIH omiljenih žanrova ovaj film ima
                                // (ranije ista rečenica sa glavnim žanrom za svaki predlog).
                                val movieSharedGenres = sharedGenres.filter { it.id in movie.genreIds }
                                    .ifEmpty { sharedGenres.take(1) }
                                MovieSuggestionDto(
                                    tmdbId = movie.id!!,
                                    title = movie.title,
                                    posterPath = movie.posterPath,
                                    matchReason = if (call.prefersEnglish()) {
                                        "You both like ${movieSharedGenres.joinToString(" and ") { it.name }}"
                                    } else {
                                        "Oboje volite ${movieSharedGenres.joinToString(" i ") { it.name }}"
                                    },
                                    scores = SuggestionScoresDto(
                                        yourMatch = yourMatch,
                                        theirMatch = theirMatch,
                                        combinedScore = match.matchScore
                                    ),
                                    releaseDate = movie.releaseDate,
                                    voteAverage = movie.voteAverage,
                                    genreIds = movie.genreIds
                                )
                            }

                        call.respond(
                            MovieNightResponseDto(
                                suggestions = suggestions,
                                hasMore = page < discoverPage.totalPages,
                                friendUsername = friendUsername,
                                sharedGenres = sharedGenres,
                                matchScore = match.matchScore
                            )
                        )
                    }

                    post("/{friendId}") {
                        val userId = call.ownerUserIdOrNull()
                            ?: return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Nemaš pristup ovoj akciji"))
                        val friendId = call.parameters["friendId"]
                            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Nedostaje friendId"))
                        val req = call.receive<FriendActionRequest>()

                        when (req.action.lowercase()) {
                            "add" -> {
                                try {
                                    friendshipsRepository.sendRequest(userId, friendId)
                                } catch (e: IllegalArgumentException) {
                                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Nevalidan zahtev"))
                                }
                            }
                            "accept" -> {
                                val accepted = friendshipsRepository.acceptRequest(userId, friendId)
                                if (!accepted) {
                                    return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("Nema zahteva za prihvatanje"))
                                }
                            }
                            "reject" -> friendshipsRepository.removeFriendship(userId, friendId)
                            else -> return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Nepoznata akcija: ${req.action}"))
                        }
                        call.respond(HttpStatusCode.NoContent)
                    }

                    delete("/{friendId}") {
                        val userId = call.ownerUserIdOrNull()
                            ?: return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("Nemaš pristup ovoj akciji"))
                        val friendId = call.parameters["friendId"]
                            ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Nedostaje friendId"))
                        friendshipsRepository.removeFriendship(userId, friendId)
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
            }
        }
    }
}
