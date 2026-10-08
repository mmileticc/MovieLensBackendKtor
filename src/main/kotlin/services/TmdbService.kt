package com.milinko.services

import com.milinko.scraping.model.ScrapedMovie
import com.milinko.util.TtlCache
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.HttpHeaders.ContentEncoding
import io.netty.handler.codec.compression.StandardCompressionOptions.deflate
import io.netty.handler.codec.compression.StandardCompressionOptions.gzip
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("TmdbService")

class TmdbService(private val apiKey: String) {

    // Heuristika: TMDB v4 access token je JWT (počinje s "eyJ").
    // Ako je tako, koristimo Bearer auth umjesto v3 query parametara.
    private val useV4Bearer: Boolean = apiKey.trim().startsWith("eyJ")

    // In-memory keš (bez spoljne zavisnosti) da ne gađamo TMDB iznova za identične upite
    // dok korisnik kuca u search bar-u ili se vraća na iste ekrane. Videti MOVIELENS_ROADMAP.md
    // sekciju 2 za odluku "proxy uživo bez baze, sa malim in-memory kešom".
    private val searchCache = TtlCache<String, List<TmdbMovieMatch>>(ttlMillis = 10 * 60 * 1000)
    private val detailCache = TtlCache<Int, TmdbMovieDetail>(ttlMillis = 60 * 60 * 1000)
    // Dorada (2. septembar 2026, E2.3 - stranica glumca) - biografija/filmografija se menjaju
    // još ređe od detalja filma, isti dugi TTL kao detailCache je opravdan.
    private val personCache = TtlCache<Int, TmdbPersonDetail>(ttlMillis = 60 * 60 * 1000)
    private val popularCache = TtlCache<Int, List<TmdbMovieMatch>>(ttlMillis = 2 * 60 * 60 * 1000)
    // Lista TMDB žanrova se praktično nikad ne menja - jedan ključ, dug TTL.
    private val genreListCache = TtlCache<String, List<TmdbGenre>>(ttlMillis = 24 * 60 * 60 * 1000)
    // Dorada (25. avgust 2026, korisnička žalba "za neke prijatelje se predlozi ponavljaju posle
    // 3-4 ekrana") - keš sad drži CEO GenreDiscoverPage (filmovi + total_pages), ne samo listu
    // filmova, videti getMoviesByGenresWithTotalPages ispod za obrazloženje.
    private val discoverCache = TtlCache<String, GenreDiscoverPage>(ttlMillis = 60 * 60 * 1000)

    private val client = HttpClient(CIO) {
        install(ContentEncoding) {
            gzip()
            deflate()
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 12_000
            socketTimeoutMillis = 12_000
        }
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true;
                    isLenient = true }
            )
        }
    }

    private fun authHeaders(builder: HttpRequestBuilder) {
        if (useV4Bearer) {
            builder.headers {
                append(HttpHeaders.Authorization, "Bearer $apiKey")
                append(HttpHeaders.Accept, ContentType.Application.Json)
            }
        }
    }

    private fun unauthorizedHint(status: HttpStatusCode): String = when (status) {
        HttpStatusCode.Unauthorized -> if (useV4Bearer)
            "(401) Unauthorized from TMDB using v4 Bearer. Provjerite da je TMDB_API_KEY v4 access token (Bearer) ispravan i aktivan."
        else
            "(401) Unauthorized from TMDB using v3 api_key. Ako ste unijeli v4 token, uklonite ga i koristite v3 API Key, ili koristite v4 Bearer način."
        else -> ""
    }

    /**
     * "Hladan start" (prvi HTTPS pozivi ka TMDB posle boot-a servera - DNS + TLS handshake)
     * ume da bude sporiji od connect/request timeout-a, pa prvih par poziva zna da pukne dok se
     * konekcija ne "zagreje" (posle toga sve radi normalno). Umesto da to vidi korisnik kao
     * grešku, tiho pokušamo još jednom pre nego što propagiramo izuzetak dalje.
     */
    private suspend fun getWithRetry(url: Url, attempts: Int = 2): HttpResponse {
        var lastError: Throwable? = null
        repeat(attempts) { attempt ->
            try {
                return client.get(url) { authHeaders(this) }
            } catch (e: Exception) {
                lastError = e
                val isTransient = e is HttpRequestTimeoutException ||
                    e is ConnectTimeoutException ||
                    e is SocketTimeoutException ||
                    e.message?.contains("timeout", ignoreCase = true) == true
                if (!isTransient || attempt == attempts - 1) throw e
                logger.warn("TMDB poziv timeout (pokušaj ${attempt + 1}/$attempts), ponavljam odmah: ${e.message}")
                delay(300)
            }
        }
        throw lastError ?: IllegalStateException("Nepoznata greška pri TMDB pozivu")
    }

    suspend fun searchMovies(title: String, year: Int? = null): List<TmdbMovieMatch> {
        val cacheKey = "${title.trim().lowercase()}|${year ?: ""}"
        searchCache.get(cacheKey)?.let { return it }

        val url = URLBuilder("https://api.themoviedb.org/3/search/movie").apply {
            parameters.append("query", title)
            year?.let { parameters.append("year", it.toString()) }
            parameters.append("include_adult", "false")
            parameters.append("language", "en-US")
            if (!useV4Bearer) {
                // v3 API key kroz query parametar
                parameters.append("api_key", apiKey)
            }
        }.build()

        val response: HttpResponse = getWithRetry(url)
        if (!response.status.isSuccess()) {
            val bodyText = try { response.bodyAsText() } catch (_: Throwable) { "" }
            throw IllegalStateException("TMDB search error: HTTP ${response.status.value}. ${unauthorizedHint(response.status)} Body=${bodyText.take(300)}")
        }
        val body: TmdbSearchResponse = response.body()
        logger.debug("Broj rezultata u JSON-u: ${body.results.size}")
        if (body.results.isNotEmpty()) {
            logger.debug("Prvi rezultat ID: ${body.results.first().id}")
        }
        val result = body.results.map { r ->
            TmdbMovieMatch(
                id = r.id,
                title = r.title ?: r.originalTitle ?: "",
                originalTitle = r.originalTitle,
                releaseDate = r.releaseDate,
                overview = r.overview,
                posterPath = r.posterPath,
                popularity = r.popularity,
                voteAverage = r.voteAverage,
                genreIds = r.genreIds
            )
        }
        searchCache.put(cacheKey, result)
        return result
    }

    /**
     * Detalji filma po TMDB ID-ju (žanrovi, cast, runtime, backdrop). Vraća null ako TMDB
     * odgovori 404 (film ne postoji). `showtimes` (bioskopski termini) namerno nisu ovde —
     * naš ScrapedMovie model nema satnicu/city/cinema_id koji Android Showtime model očekuje,
     * pa se ne izmišljaju podaci; videti napomenu u MOVIELENS_ROADMAP.md.
     */
    suspend fun getMovieDetails(tmdbId: Int): TmdbMovieDetail? {
        detailCache.get(tmdbId)?.let { return it }

        val url = URLBuilder("https://api.themoviedb.org/3/movie/$tmdbId").apply {
            // videos + images u ISTOM pozivu (trejleri i galerija na Movie Detail-u) - i dalje jedan
            // TMDB request po filmu, keširan u detailCache kao i do sada.
            parameters.append("append_to_response", "credits,videos,images")
            // Bez ovoga images vraća samo slike na `language` jeziku - backdrop-ovi su najčešće bez
            // teksta (iso_639_1 = null), pa bi galerija ostala skoro prazna.
            parameters.append("include_image_language", "en,null")
            parameters.append("language", "en-US")
            if (!useV4Bearer) parameters.append("api_key", apiKey)
        }.build()

        val response: HttpResponse = getWithRetry(url)
        if (response.status == HttpStatusCode.NotFound) return null
        if (!response.status.isSuccess()) {
            val bodyText = try { response.bodyAsText() } catch (_: Throwable) { "" }
            throw IllegalStateException("TMDB detail error: HTTP ${response.status.value}. ${unauthorizedHint(response.status)} Body=${bodyText.take(300)}")
        }

        val dto: TmdbMovieDetailDto = response.body()
        val detail = TmdbMovieDetail(
            id = dto.id,
            title = dto.title ?: "",
            releaseDate = dto.releaseDate,
            posterPath = dto.posterPath,
            backdropPath = dto.backdropPath,
            overview = dto.overview,
            voteAverage = dto.voteAverage,
            popularity = dto.popularity,
            runtime = dto.runtime,
            genres = dto.genres.map { TmdbGenre(it.id, it.name) },
            cast = dto.credits?.cast.orEmpty().map { TmdbCastMember(it.id, it.name, it.character, it.profilePath) },
            // imdb_id dolazi već u osnovnom /movie/{id} odgovoru, bez append_to_response - koristi
            // se za dopunske IMDb/Rotten Tomatoes ocene preko OmdbService (videti MovieRouting.kt).
            imdbId = dto.imdbId?.takeIf { it.isNotBlank() },
            // Samo YouTube trejleri/teaseri (to je jedino što Android plejer ume da pusti),
            // zvanični Trailer-i prvi.
            videos = dto.videos?.results.orEmpty()
                .filter { it.site == "YouTube" && !it.key.isNullOrBlank() && it.type in TRAILER_TYPES }
                .sortedWith(
                    compareByDescending<TmdbVideoDto> { it.type == "Trailer" }
                        .thenByDescending { it.official == true }
                )
                .take(MAX_VIDEOS)
                .map { TmdbVideo(key = it.key!!, name = it.name.orEmpty(), type = it.type.orEmpty()) },
            backdrops = dto.images?.backdrops.orEmpty()
                .sortedByDescending { it.voteAverage ?: 0.0 }
                .mapNotNull { it.filePath?.takeIf { p -> p.isNotBlank() } }
                .distinct()
                .take(MAX_BACKDROPS)
        )
        detailCache.put(tmdbId, detail)
        return detail
    }

    /**
     * Detalji glumca/člana ekipe po TMDB person ID-ju (biografija, foto, filmografija) - E2.3
     * (2. septembar 2026, stranica glumca). `append_to_response=movie_credits` u JEDNOM pozivu
     * vraća i osnovne podatke i filmografiju (isti trik kao `credits` kod getMovieDetails) -
     * nema potrebe za drugim pozivom ka /person/{id}/movie_credits. `language=en-US` iz istog
     * razloga kao svuda drugde u ovom fajlu (app-wide konvencija, videti napomene iznad).
     * Filmografija se sortira po popularnosti opadajuće - "najpoznatije uloge prve", isti
     * princip kao TMDB-ov sopstveni "known for".
     */
    suspend fun getPersonDetails(personId: Int): TmdbPersonDetail? {
        personCache.get(personId)?.let { return it }

        val url = URLBuilder("https://api.themoviedb.org/3/person/$personId").apply {
            parameters.append("append_to_response", "movie_credits")
            parameters.append("language", "en-US")
            if (!useV4Bearer) parameters.append("api_key", apiKey)
        }.build()

        val response: HttpResponse = getWithRetry(url)
        if (response.status == HttpStatusCode.NotFound) return null
        if (!response.status.isSuccess()) {
            val bodyText = try { response.bodyAsText() } catch (_: Throwable) { "" }
            throw IllegalStateException("TMDB person error: HTTP ${response.status.value}. ${unauthorizedHint(response.status)} Body=${bodyText.take(300)}")
        }

        val dto: TmdbPersonDetailDto = response.body()
        val detail = TmdbPersonDetail(
            id = dto.id,
            name = dto.name ?: "",
            biography = dto.biography?.takeIf { it.isNotBlank() },
            birthday = dto.birthday,
            deathday = dto.deathday,
            placeOfBirth = dto.placeOfBirth,
            profilePath = dto.profilePath,
            knownForDepartment = dto.knownForDepartment,
            movieCredits = dto.movieCredits?.cast.orEmpty()
                .distinctBy { it.id }
                .sortedByDescending { it.popularity ?: 0.0 }
                .map {
                    TmdbPersonMovieCredit(
                        tmdbId = it.id,
                        title = it.title ?: it.originalTitle ?: "",
                        character = it.character?.takeIf { c -> c.isNotBlank() },
                        posterPath = it.posterPath,
                        releaseDate = it.releaseDate,
                        voteAverage = it.voteAverage
                    )
                }
        )
        personCache.put(personId, detail)
        return detail
    }

    /** Svi TMDB filmski žanrovi (id -> naziv) - za filter po žanru na Android "Vidi sve" ekranima. */
    suspend fun getGenres(): List<TmdbGenre> {
        genreListCache.get("movie")?.let { return it }

        val url = URLBuilder("https://api.themoviedb.org/3/genre/movie/list").apply {
            parameters.append("language", "en-US")
            if (!useV4Bearer) parameters.append("api_key", apiKey)
        }.build()

        val response: HttpResponse = getWithRetry(url)
        if (!response.status.isSuccess()) {
            val bodyText = try { response.bodyAsText() } catch (_: Throwable) { "" }
            throw IllegalStateException("TMDB genre list error: HTTP ${response.status.value}. ${unauthorizedHint(response.status)} Body=${bodyText.take(300)}")
        }
        val body: TmdbGenreListDto = response.body()
        val result = body.genres.map { TmdbGenre(it.id, it.name) }
        genreListCache.put("movie", result)
        return result
    }

    /** Popularni filmovi (koristi se za /api/movies/trending i /featured). */
    suspend fun getPopularMovies(page: Int = 1): List<TmdbMovieMatch> {
        popularCache.get(page)?.let { return it }

        val url = URLBuilder("https://api.themoviedb.org/3/movie/popular").apply {
            parameters.append("language", "en-US")
            parameters.append("page", page.toString())
            if (!useV4Bearer) parameters.append("api_key", apiKey)
        }.build()

        val response: HttpResponse = getWithRetry(url)
        if (!response.status.isSuccess()) {
            val bodyText = try { response.bodyAsText() } catch (_: Throwable) { "" }
            throw IllegalStateException("TMDB popular error: HTTP ${response.status.value}. ${unauthorizedHint(response.status)} Body=${bodyText.take(300)}")
        }
        val body: TmdbSearchResponse = response.body()
        val result = body.results.map { r ->
            TmdbMovieMatch(
                id = r.id,
                title = r.title ?: r.originalTitle ?: "",
                originalTitle = r.originalTitle,
                releaseDate = r.releaseDate,
                overview = r.overview,
                posterPath = r.posterPath,
                popularity = r.popularity,
                voteAverage = r.voteAverage,
                genreIds = r.genreIds
            )
        }
        popularCache.put(page, result)
        return result
    }

    /**
     * TMDB "discover" po žanrovima - koristi se za personalizovan /api/movies/featured (Faza 4)
     * i za /movienight-suggestions (Faza 4.5). `genreIds` se šalje TMDB-u pipe-razdvojeno ("|"),
     * što TMDB tumači kao OR (bilo koji od navedenih žanrova).
     *
     * ISPRAVKA (bio bug): ranije je ovde stajalo zarezom-razdvojeno ("with_genres=28,35,80"), za
     * koje smo pogrešno mislili da je OR - TMDB dokumentacija to zapravo tumači kao AND (film mora
     * imati SVE navedene žanrove). Zato su predlozi za zajedničko gledanje sa presekom više žanrova
     * (npr. Animation+Action+Comedy) kolabirali skoro isključivo na animirane filmove - AND upit je
     * bio previše uzak i vraćao je uglavnom filmove koji spadaju u sve žanrove odjednom. Pipe "|" je
     * ispravan operator za "bilo koji od ovih žanrova".
     */
    suspend fun getMoviesByGenres(genreIds: List<Int>, page: Int = 1): List<TmdbMovieMatch> =
        getMoviesByGenresWithTotalPages(genreIds, page).movies

    /**
     * Dorada (25. avgust 2026, korisnička žalba "za neke prijatelje se predlozi ponavljaju posle
     * 3-4 ekrana, za druge nikad") - koren problema NIJE bio ovde nego na Android strani
     * (MovieNightViewModel je rotirao kroz FIKSNIH 5 stranica bez obzira na to koliko stranica
     * TMDB stvarno ima za dati par žanrova - videti napomenu tamo). Ova funkcija sad vraća i
     * `total_pages` iz TMDB odgovora (ranije se parsiralo samo `results`), da Android strana zna
     * PRAVI broj raspoloživih stranica po paru žanrova umesto izmišljenog fiksnog broja - širok
     * presek žanrova (npr. Action|Comedy|Drama) ima stotine stranica, uzak presek (npr.
     * Documentary|War) možda samo par - otud utisak da se "nasumično" ponaša različito po
     * prijatelju, iako je uzrok bio potpuno determinisan (samo pogrešno pretpostavljen).
     */
    suspend fun getMoviesByGenresWithTotalPages(genreIds: List<Int>, page: Int = 1): GenreDiscoverPage {
        if (genreIds.isEmpty()) return GenreDiscoverPage(emptyList(), 0)
        val cacheKey = "${genreIds.sorted().joinToString(",")}|$page"
        discoverCache.get(cacheKey)?.let { return it }

        val url = URLBuilder("https://api.themoviedb.org/3/discover/movie").apply {
            parameters.append("with_genres", genreIds.joinToString("|"))
            parameters.append("sort_by", "popularity.desc")
            parameters.append("language", "en-US")
            parameters.append("page", page.toString())
            parameters.append("include_adult", "false")
            if (!useV4Bearer) parameters.append("api_key", apiKey)
        }.build()

        val response: HttpResponse = getWithRetry(url)
        if (!response.status.isSuccess()) {
            val bodyText = try { response.bodyAsText() } catch (_: Throwable) { "" }
            throw IllegalStateException("TMDB discover error: HTTP ${response.status.value}. ${unauthorizedHint(response.status)} Body=${bodyText.take(300)}")
        }
        val body: TmdbSearchResponse = response.body()
        val movies = body.results.map { r ->
            TmdbMovieMatch(
                id = r.id,
                title = r.title ?: r.originalTitle ?: "",
                originalTitle = r.originalTitle,
                releaseDate = r.releaseDate,
                overview = r.overview,
                posterPath = r.posterPath,
                popularity = r.popularity,
                voteAverage = r.voteAverage,
                genreIds = r.genreIds
            )
        }
        val result = GenreDiscoverPage(movies, (body.totalPages ?: 1).coerceAtLeast(1))
        discoverCache.put(cacheKey, result)
        return result
    }
}

/** Jedna TMDB discover stranica po žanrovima + koliko UKUPNO stranica TMDB ima za taj upit - videti getMoviesByGenresWithTotalPages. */
data class GenreDiscoverPage(val movies: List<TmdbMovieMatch>, val totalPages: Int)

@Serializable
private data class TmdbSearchResponse(
    val page: Int? = null,
    val results: List<TmdbMovieDto> = emptyList(),
    // Dorada (25. avgust 2026) - ranije se uopšte nije parsiralo, pa je Android strana morala da
    // pogađa koliko stranica postoji (fiksnih 5) - videti getMoviesByGenresWithTotalPages.
    @SerialName("total_pages") val totalPages: Int? = null
)

@Serializable
private data class TmdbMovieDto(
    val id: Int? = null,
    val title: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    val popularity: Double? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    // TMDB šalje genre_ids u svim list odgovorima (/popular, /discover, /search) - koristi ga
    // Android za filter po žanru na "Vidi sve" ekranima.
    @SerialName("genre_ids") val genreIds: List<Int> = emptyList()
)

@Serializable
private data class TmdbMovieDetailDto(
    val id: Int,
    val title: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val overview: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    val popularity: Double? = null,
    val runtime: Int? = null,
    val genres: List<TmdbGenreDto> = emptyList(),
    val credits: TmdbCreditsDto? = null,
    @SerialName("imdb_id") val imdbId: String? = null,
    val videos: TmdbVideosDto? = null,
    val images: TmdbImagesDto? = null
)

@Serializable
private data class TmdbVideosDto(val results: List<TmdbVideoDto> = emptyList())

@Serializable
private data class TmdbVideoDto(
    val key: String? = null,
    val name: String? = null,
    val site: String? = null,
    val type: String? = null,
    val official: Boolean? = null
)

@Serializable
private data class TmdbImagesDto(val backdrops: List<TmdbImageDto> = emptyList())

@Serializable
private data class TmdbImageDto(
    @SerialName("file_path") val filePath: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null
)

@Serializable
private data class TmdbGenreListDto(val genres: List<TmdbGenreDto> = emptyList())

private val TRAILER_TYPES = setOf("Trailer", "Teaser")
private const val MAX_VIDEOS = 5
private const val MAX_BACKDROPS = 12

@Serializable
private data class TmdbGenreDto(val id: Int, val name: String)

@Serializable
private data class TmdbCreditsDto(val cast: List<TmdbCastDto> = emptyList())

@Serializable
private data class TmdbCastDto(
    val id: Int,
    val name: String,
    val character: String? = null,
    @SerialName("profile_path") val profilePath: String? = null
)

data class TmdbMovieDetail(
    val id: Int,
    val title: String,
    val releaseDate: String?,
    val posterPath: String?,
    val backdropPath: String?,
    val overview: String?,
    val voteAverage: Double?,
    val popularity: Double? = null,
    val runtime: Int?,
    val genres: List<TmdbGenre>,
    val cast: List<TmdbCastMember>,
    val imdbId: String? = null,
    val videos: List<TmdbVideo> = emptyList(),
    val backdrops: List<String> = emptyList()
)

/** YouTube trejler/teaser filma - `key` je YouTube video ID. */
data class TmdbVideo(val key: String, val name: String, val type: String)

// @Serializable (ne samo data class) - genres se čuvaju kao JSON string u movies.genres koloni
// (Faza 4, MOVIELENS_ROADMAP.md - taste profile po žanru), videti MoviesRepository.ensureCached.
@Serializable
data class TmdbGenre(val id: Int, val name: String)

data class TmdbCastMember(
    val id: Int,
    val name: String,
    val character: String?,
    val profilePath: String?
)

@Serializable
private data class TmdbPersonDetailDto(
    val id: Int,
    val name: String? = null,
    val biography: String? = null,
    val birthday: String? = null,
    val deathday: String? = null,
    @SerialName("place_of_birth") val placeOfBirth: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
    @SerialName("known_for_department") val knownForDepartment: String? = null,
    @SerialName("movie_credits") val movieCredits: TmdbPersonMovieCreditsDto? = null
)

@Serializable
private data class TmdbPersonMovieCreditsDto(val cast: List<TmdbPersonCastCreditDto> = emptyList())

@Serializable
private data class TmdbPersonCastCreditDto(
    val id: Int,
    val title: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    val character: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    val popularity: Double? = null
)

data class TmdbPersonDetail(
    val id: Int,
    val name: String,
    val biography: String?,
    val birthday: String?,
    val deathday: String?,
    val placeOfBirth: String?,
    val profilePath: String?,
    val knownForDepartment: String?,
    val movieCredits: List<TmdbPersonMovieCredit>
)

data class TmdbPersonMovieCredit(
    val tmdbId: Int,
    val title: String,
    val character: String?,
    val posterPath: String?,
    val releaseDate: String?,
    val voteAverage: Double?
)

data class TmdbMovieMatch(
    val id: Int?,
    val title: String,
    val originalTitle: String? = null,
    val releaseDate: String? = null,
    val overview: String? = null,
    val posterPath: String? = null,
    val popularity: Double? = null,
    val voteAverage: Double? = null,
    val genreIds: List<Int> = emptyList()
)


/**
 * Proširenje koje obogaćuje listu ScrapedMovie objekata sa TMDB ID-jevima.
 * Vraća Mapu gde je ključ originalni ScrapedMovie, a vrednost pronađeni TMDB ID.
 */
suspend fun List<ScrapedMovie>.enrichWithTmdbIds(
    tmdbService: TmdbService
): List<ScrapedMovie> { // Vrati listu, ne mapu
    val titleCache = mutableMapOf<String, Int?>()

    return this.map { movie ->
        val searchKey = (movie.originalTitle ?: movie.title).trim().lowercase()

        // Ako imamo u kešu, odmah iskoristi
        if (titleCache.containsKey(searchKey)) {
            return@map movie.copy(tmdbId = titleCache[searchKey])
        }

        // Ako nema u kešu, pozovi TMDB
        val searchResults = try {
            tmdbService.searchMovies(movie.originalTitle ?: movie.title)
        } catch (e: Exception) {
            logger.error("Enrichment failed for ${movie.title}: ${e.message}")
            emptyList<TmdbMovieMatch>()
        }

        val foundId = searchResults.firstOrNull()?.id
        titleCache[searchKey] = foundId

        // PAUZA: Obavezno delay
        delay(300)

        // Vrati novi objekat sa popunjenim ID-jem
        movie.copy(tmdbId = foundId)
    }
}