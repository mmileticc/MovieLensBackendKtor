package com.milinko

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.milinko.db.AvatarRepository
import com.milinko.db.CommentsRepository
import com.milinko.db.DeviceTokensRepository
import com.milinko.db.NotificationsRepository
import com.milinko.services.PushService
import com.milinko.db.DatabaseFactory
import com.milinko.db.FirebaseAdminInitializer
import com.milinko.db.FriendshipsRepository
import com.milinko.db.MovieEmbeddingsRepository
import com.milinko.db.MovieEmbeddingsTable
import com.milinko.db.MoviesRepository
import com.milinko.db.ScrapedMoviesRepository
import com.milinko.db.TasteProfileRepository
import com.milinko.db.UserActionsRepository
import com.milinko.db.UserDao
import com.milinko.scraping.sviAktivniScraperi
import com.milinko.services.CinemaSemanticIndex
import com.milinko.services.GeminiService
import com.milinko.services.GeminiTextEmbedder
import com.milinko.services.LocalTextEmbedder
import com.milinko.services.OmdbService
import com.milinko.services.TmdbService
import com.milinko.services.enrichWithTmdbIds
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.netty.*
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import com.milinko.util.CINEMA_ZONE_ID
import org.slf4j.LoggerFactory
import scraping.ScrapingEngineConcurrent
import java.time.LocalDate

private val logger = LoggerFactory.getLogger("Application")

/** Starts the Netty server; modules are listed in application.yaml (see [module]). */
fun main(args: Array<String>) {
    EngineMain.main(args)
}

/**
 * Application module: plugins (JSON, error handling, CORS, JWT auth, rate limits), the database,
 * external services, background jobs (scraping or semantic index refresh) and all routes.
 *
 * Required environment: APP_JWT_SIGNING_KEY, GEMINI_API_KEY, TMDB_API_KEY, DB_HOST, DB_PASSWORD,
 * SCRAPING_BOOLEAN (see README for the full list).
 */
fun Application.module() {
    try {
        FirebaseAdminInitializer.init()
    } catch (e: Exception) {
        logger.error("Firebase init failed: ${e.message}")
        // The server still starts: everything except sign-in and push notifications keeps working.
    }

    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                prettyPrint = false
                isLenient = true
                encodeDefaults = true
            }
        )
    }

    configureErrorHandling()


    install(CORS) {
        // ALLOWED_ORIGINS: optional comma-separated list of allowed origins. When unset, any host is
        // allowed - the Android app has no fixed origin; set it once a web frontend exists.
        val allowedOrigins = System.getenv("ALLOWED_ORIGINS")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        if (allowedOrigins.isEmpty()) {
            anyHost()
        } else {
            allowedOrigins.forEach { origin ->
                val url = Url(origin)
                val hostAndPort = if (url.port == url.protocol.defaultPort) url.host else "${url.host}:${url.port}"
                allowHost(hostAndPort, schemes = listOf(url.protocol.name))
            }
        }

        allowNonSimpleContentTypes = true
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
    }

    val jwtSigningKey = System.getenv("APP_JWT_SIGNING_KEY")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: throw IllegalStateException("APP_JWT_SIGNING_KEY nije postavljen ili je prazan")

    configureAuthentication(jwtSigningKey)

    configureRateLimiting()

    DatabaseFactory.init()

    // Repositories are created once and shared by all routes (they hold no state besides the DB connection).
    val scrapedMoviesRepository = ScrapedMoviesRepository()
    val userDao = UserDao()
    val friendshipsRepository = FriendshipsRepository()
    val userActionsRepository = UserActionsRepository()
    val tasteProfileRepository = TasteProfileRepository()
    val moviesRepository = MoviesRepository()
    val commentsRepository = CommentsRepository()
    val avatarRepository = AvatarRepository()
    val movieEmbeddingsRepository = MovieEmbeddingsRepository()
    val deviceTokensRepository = DeviceTokensRepository()
    val notificationsRepository = NotificationsRepository()
    val pushService = PushService(deviceTokensRepository, notificationsRepository)

    // API keys: the environment variable wins, application.yaml is the fallback.
    val apiKey = System.getenv("GEMINI_API_KEY")
        ?: environment.config.propertyOrNull("ktor.gemini.apiKey")?.getString()
        ?: throw IllegalStateException("GEMINI_API_KEY nije postavljen (ENV) niti u application.yaml pod 'ktor.gemini.apiKey'.")

    val tmdbKey = System.getenv("TMDB_API_KEY")
        ?: environment.config.propertyOrNull("ktor.tmdb.apiKey")?.getString()
        ?: throw IllegalStateException("TMDB_API_KEY nije postavljen (ENV) niti u application.yaml pod 'ktor.tmdb.apiKey'.")

    val omdbKey = System.getenv("OMDB_API_KEY")
        ?: environment.config.propertyOrNull("ktor.omdb.apiKey")?.getString()

    val geminiService = GeminiService(apiKey = apiKey)
    val tmdbService = TmdbService(apiKey = tmdbKey)
    val omdbService = OmdbService(apiKey = omdbKey)
    warmUpTmdbClient(tmdbService)

    val geminiCinemaSemanticIndex = CinemaSemanticIndex(scrapedMoviesRepository, tmdbService, GeminiTextEmbedder(geminiService), movieEmbeddingsRepository)
    val localCinemaSemanticIndex = CinemaSemanticIndex(scrapedMoviesRepository, tmdbService, LocalTextEmbedder(), movieEmbeddingsRepository)
    val scrapingBoolean = System.getenv("SCRAPING_BOOLEAN")
        ?: environment.config.propertyOrNull("ktor.scraping.enabled")?.getString()
        ?: throw IllegalStateException("SCRAPING_BOOLEAN nije postavljen (ENV) niti u application.yaml pod 'ktor.scraping.enabled'.")


    if (scrapingBoolean.lowercase() == "true") {
        configureScrapingLifecycle(scrapedMoviesRepository, tmdbService, geminiCinemaSemanticIndex, localCinemaSemanticIndex)
    } else {
        configureSemanticIndexRefresh(scrapedMoviesRepository, geminiCinemaSemanticIndex, localCinemaSemanticIndex)
    }
    configureRouting(geminiService, tmdbService, scrapedMoviesRepository, userDao, jwtSigningKey)
    configureMovieRouting(tmdbService, friendshipsRepository, userActionsRepository, tasteProfileRepository, scrapedMoviesRepository, geminiCinemaSemanticIndex, localCinemaSemanticIndex, omdbService)
    configureUserActionsRouting(tmdbService, userActionsRepository, moviesRepository, tasteProfileRepository, userDao, friendshipsRepository)
    configureFriendsRouting(friendshipsRepository, userDao, userActionsRepository, tasteProfileRepository, tmdbService, pushService)
    configureCommentsRouting(tmdbService, commentsRepository, moviesRepository, friendshipsRepository, userDao, pushService)
    configureNotificationsRouting(deviceTokensRepository, notificationsRepository)
    configureSharingRouting(tmdbService, moviesRepository, friendshipsRepository, userDao, pushService)
    configureAvatarRouting(avatarRepository)
    migrateInlineAvatars(avatarRepository)
}

const val JWT_ISSUER = "movienee-backend"
const val JWT_AUDIENCE = "movienee-android"

/** JSON error body returned to clients: `{"error": "..."}`. */
@kotlinx.serialization.Serializable
data class ErrorResponse(val error: String)

/**
 * Responds 502 for a failed call to an external service (TMDB, Gemini...). The client only gets
 * [publicMessage]; the exception message goes to the log only, because it can contain the request
 * URL with an API key or parts of the upstream response.
 */
suspend fun ApplicationCall.respondUpstreamError(logTag: String, e: Throwable, publicMessage: String) {
    logger.error("$logTag: ${e.message}")
    respond(HttpStatusCode.BadGateway, ErrorResponse(publicMessage))
}

/** One-time move of old base64 avatars from `users.avatar_url` to `user_avatars`, in the background. */
fun Application.migrateInlineAvatars(avatarRepository: AvatarRepository) {
    launch {
        try {
            val migrated = avatarRepository.migrateInlineAvatars()
            if (migrated > 0) logger.info("Prebačeno $migrated avatara iz users.avatar_url u user_avatars.")
        } catch (e: Exception) {
            logger.error("Migracija avatara nije uspela (pokušaće ponovo pri sledećem startu): ${e.message}")
        }
    }
}

/**
 * The first HTTPS calls to TMDB after a server start (DNS + TLS handshake) can be slower than the
 * timeouts, so the first real user requests would sometimes fail. This "cold start" is done here in
 * the background instead. As a bonus, it fills the popular-movies cache for /trending and /featured.
 */
fun Application.warmUpTmdbClient(tmdbService: TmdbService) {
    launch {
        try {
            logger.info("[TMDB] Zagrevanje HTTP konekcije...")
            val movies = tmdbService.getPopularMovies(1)
            logger.info("[TMDB] Zagrevanje uspešno (${movies.size} popularnih filmova učitano u keš).")
        } catch (e: Exception) {
            logger.warn("TMDB zagrevanje nije uspelo, nije kritično - prvi pravi zahtev će pokušati ponovo: ${e.message}")
        }
    }
}

/**
 * SCRAPING_BOOLEAN=true: on startup, scrapes all cinemas if the database has no data from today,
 * then builds the semantic indexes. Production runs with false (the daily scrape runs in GitHub
 * Actions, see ScraperJob) and uses [configureSemanticIndexRefresh] instead.
 */
fun Application.configureScrapingLifecycle(
    repository: ScrapedMoviesRepository,
    tmdbService: TmdbService,
    geminiCinemaSemanticIndex: CinemaSemanticIndex,
    localCinemaSemanticIndex: CinemaSemanticIndex
) {

    launch {
        try {
            logger.info("[REPREZENTACIJA] Provera stanja i svežine baze podataka...")

            val danasnjiDatum = LocalDate.now(CINEMA_ZONE_ID)
            val datumPoslednjegSkrapovanja = repository.getLatestScrapeDate()

            // Scrape when the database is empty or the data is not from today (Belgrade time).
            val trebaSkrapovati = datumPoslednjegSkrapovanja == null || datumPoslednjegSkrapovanja != danasnjiDatum

            if (trebaSkrapovati) {
                if (datumPoslednjegSkrapovanja == null) {
                    logger.info("Baza je potpuno prazna. Pokrećem skraper...")
                } else {
                    logger.info("Podaci u bazi su zastareli (Zadnji upis: $datumPoslednjegSkrapovanja, Danas je: $danasnjiDatum). Pokrećem osvežavanje...")
                }

                val engine = ScrapingEngineConcurrent(sviAktivniScraperi())
                val skrapovaniFilmovi = engine.runAllScrapers(danasnjiDatum)

                logger.info("Pretraga TMDB ID-ja svakom pronadjenom filmu.")
                val obogaceniFilmovi = skrapovaniFilmovi.enrichWithTmdbIds(tmdbService)
                logger.info("Zacrsena pretraga TMDB ID-jeva.")

                if (skrapovaniFilmovi.isNotEmpty()) {


                    val saIdjem = obogaceniFilmovi.count { it.tmdbId != null }
                    logger.info("Enrichment gotov. Pronadjeno TMDB ID-jeva: $saIdjem od ${obogaceniFilmovi.size}")

                    repository.insertScrapedMovies(obogaceniFilmovi)
                    logger.info("Novo skrapovanje završeno i sveži podaci su upisani u Supabase.")

                } else {
                    logger.warn("Skraper je pokrenut ali nije pronašao nijedan film na sajtovima.")
                }

            } else {
                logger.info("Podaci u bazi su sveži (Od danas su: $datumPoslednjegSkrapovanja). Skrapovanje je preskočeno!")

                val filmoviIzBaze = repository.getAllScrapedMovies()
                logger.info("Učitano ${filmoviIzBaze.size} filmova direktno iz Supabase-a za rad aplikacije.")
            }

            refreshSemanticIndices(geminiCinemaSemanticIndex, localCinemaSemanticIndex)

        } catch (e: Exception) {
            logger.error("Greška tokom inicijalizacije podataka: ${e.localizedMessage}", e)
        }
    }
}

/**
 * Fills the semantic indexes from the current `scraped_movies` table without running any scraper
 * (SCRAPING_BOOLEAN=false, e.g. on Render, where scraping happens in GitHub Actions and the
 * database is shared).
 *
 * After startup it checks every SEMANTIC_INDEX_POLL_MINUTES (default 60) whether the newest
 * `created_at` changed and rebuilds the indexes only then. The Render instance is kept awake and
 * practically never restarts, so without this the semantic search would keep showing the
 * programme from the last deploy. The check is one cheap MAX() query; Gemini embedding calls only
 * happen for movies whose text changed (see MovieEmbeddingsTable).
 */
fun Application.configureSemanticIndexRefresh(
    repository: ScrapedMoviesRepository,
    geminiCinemaSemanticIndex: CinemaSemanticIndex,
    localCinemaSemanticIndex: CinemaSemanticIndex
) {
    val intervalMinuta = System.getenv("SEMANTIC_INDEX_POLL_MINUTES")?.toLongOrNull()?.takeIf { it > 0 } ?: 60L

    launch {
        logger.info("SCRAPING_BOOLEAN nije 'true' - skrejpovanje se preskače na ovom hostu, ali semantički indeksi se i dalje pune iz postojećih podataka u bazi (provera novih podataka svakih $intervalMinuta min).")

        var poslednjiViden = try {
            repository.getLatestScrapeTimestamp()
        } catch (e: Exception) {
            logger.warn("Čitanje poslednjeg vremena skrejpovanja nije uspelo: ${e.localizedMessage}")
            null
        }
        refreshSemanticIndices(geminiCinemaSemanticIndex, localCinemaSemanticIndex)

        while (true) {
            delay(intervalMinuta * 60_000)
            try {
                val najnoviji = repository.getLatestScrapeTimestamp()
                if (najnoviji != null && najnoviji != poslednjiViden) {
                    logger.info("Novi skrejpovani podaci u bazi ($poslednjiViden -> $najnoviji UTC) - osvežavam semantičke indekse.")
                    refreshSemanticIndices(geminiCinemaSemanticIndex, localCinemaSemanticIndex)
                    poslednjiViden = najnoviji
                }
            } catch (e: Exception) {
                logger.warn("Periodična provera novih skrejpovanih podataka nije uspela, pokušavam ponovo za $intervalMinuta min: ${e.localizedMessage}")
            }
        }
    }
}

/** Rebuilds the Gemini index and, if ENABLE_LOCAL_EMBEDDING=true, the local MiniLM index. */
suspend fun refreshSemanticIndices(
    geminiCinemaSemanticIndex: CinemaSemanticIndex,
    localCinemaSemanticIndex: CinemaSemanticIndex
) {
    // Each index in its own try/catch, so a failure of one (e.g. a Gemini API error) does not stop
    // the other.
    try {
        geminiCinemaSemanticIndex.refresh()
    } catch (e: Exception) {
        logger.error("CinemaSemanticIndex (Gemini).refresh() nije uspeo: ${e.localizedMessage}", e)
    }

    val localEmbeddingEnabled = System.getenv("ENABLE_LOCAL_EMBEDDING")?.equals("true", ignoreCase = true) == true
    if (localEmbeddingEnabled) {
        try {
            localCinemaSemanticIndex.refresh()
        } catch (e: Throwable) {
            // Throwable on purpose, not Exception: the local model can run out of memory
            // (OutOfMemoryError) on small hosts, which must not crash the server.
            logger.error("CinemaSemanticIndex (Lokalno/MiniLM).refresh() nije uspeo: ${e.localizedMessage}", e)
        }
    } else {
        logger.info("ENABLE_LOCAL_EMBEDDING nije postavljen na 'true' - lokalni MiniLM indeks se ne puni (ostaje prazan, engine=local vraća praznu listu).")
    }
}
/**
 * Error messages: localization plus StatusPages. Separate from [module] so route tests get the
 * same behaviour without a database or external services.
 */
fun Application.configureErrorHandling() {
    // English error messages for clients sending Accept-Language: en (see Localization.kt).
    install(ErrorMessageLocalization)

    // Never return an internal exception message to the client: Ktor HTTP client messages contain
    // the full upstream URL, and the TMDB v3/OMDb keys are part of that URL (?api_key=...). The
    // details go to the log only. Exception: IllegalArgumentException carries our own validation
    // messages meant for the user.
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            when (cause) {
                is IllegalArgumentException ->
                    call.respond(HttpStatusCode.BadRequest, call.localizedError(cause.message ?: "Neispravan zahtev"))
                is BadRequestException -> {
                    // Invalid request body (e.g. JSON that does not match the DTO in call.receive).
                    logger.warn("Neispravan zahtev ${call.request.local.uri}: ${cause.message}")
                    call.respond(HttpStatusCode.BadRequest, call.localizedError("Neispravan zahtev"))
                }
                else -> {
                    logger.error("Neobrađena greška na ${call.request.local.uri}: ${cause.message}", cause)
                    call.respond(HttpStatusCode.InternalServerError, call.localizedError("Došlo je do greške na serveru"))
                }
            }
        }
        // The RateLimit plugin answers 429 without a body - the client gets a message like for other errors.
        status(HttpStatusCode.TooManyRequests) { call, status ->
            call.respond(status, call.localizedError("Previše zahteva. Pokušaj ponovo za minut."))
        }
    }
}

/** JWT authentication ("auth-jwt"); separate from [module] for the same reason as [configureErrorHandling]. */
fun Application.configureAuthentication(jwtSigningKey: String) {
    install(Authentication) {
        jwt("auth-jwt") {
            realm = "MovieLens Backend"
            // Tokens without issuer/audience (issued before October 2026) get 401; the Android app
            // then fetches a new one by itself (TokenAuthenticator), without signing the user out.
            verifier(
                JWT.require(Algorithm.HMAC256(jwtSigningKey))
                    .withIssuer(JWT_ISSUER)
                    .withAudience(JWT_AUDIENCE)
                    .build()
            )
            validate { credential ->
                try {
                    val userId = credential.payload.subject
                    if (userId != null && userId.isNotEmpty()) {
                        JWTPrincipal(credential.payload)
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    null
                }
            }
        }
    }
}
