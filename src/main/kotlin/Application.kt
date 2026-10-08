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

/**
 * Inicijalizacija Ktor aplikacije: ContentNegotiation (kotlinx-json),
 * StatusPages, CORS i povezivanje Gemini servisa.
 */

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    // Inicijalizuj Firebase Admin SDK
    try {
        FirebaseAdminInitializer.init()
    } catch (e: Exception) {
        logger.error("Firebase init failed: ${e.message}")
        // Ne prekidamo aplikaciju ako Firebase nije dostupan
    }

    // Plugins
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
        // ALLOWED_ORIGINS: opciona ENV promenljiva, comma-separated lista dozvoljenih origina
        // (npr. "https://app.movielens.rs,https://admin.movielens.rs"). Ako nije postavljena,
        // ostajemo na anyHost() radi lakšeg lokalnog/mobilnog development-a (Android klijent nema
        // fiksan origin). Kad se zna produkcioni domen frontenda, postaviti ALLOWED_ORIGINS.
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

    // JWT Authentication Plugin
    val jwtSigningKey = System.getenv("APP_JWT_SIGNING_KEY")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: throw IllegalStateException("APP_JWT_SIGNING_KEY nije postavljen ili je prazan")

    configureAuthentication(jwtSigningKey)

    configureRateLimiting()

    DatabaseFactory.init()

    // Repozitorijumi se prave jednom i dele između svih ruta (nemaju stanje osim veze ka bazi).
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

    // Konfiguracija Gemini API ključa: ENV var ima prioritet, fallback na application.yaml
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
    configureAvatarRouting(avatarRepository)
    migrateInlineAvatars(avatarRepository)
}

const val JWT_ISSUER = "movienee-backend"
const val JWT_AUDIENCE = "movienee-android"

@kotlinx.serialization.Serializable
data class ErrorResponse(val error: String)

/**
 * 502 za neuspeo poziv ka spoljnom servisu (TMDB, Gemini...). Klijent dobija samo [publicMessage];
 * poruka izuzetka ide isključivo u log, jer može da sadrži URL sa API ključem ili delove
 * odgovora spoljnog servisa (videti napomenu kod StatusPages).
 */
suspend fun ApplicationCall.respondUpstreamError(logTag: String, e: Throwable, publicMessage: String) {
    logger.error("$logTag: ${e.message}")
    respond(HttpStatusCode.BadGateway, ErrorResponse(publicMessage))
}

/** Jednokratno prebacivanje starih base64 avatara iz `users.avatar_url` u `user_avatars`. */
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
 * Prvi HTTPS pozivi ka TMDB posle boot-a servera (DNS + TLS handshake) znaju da budu sporiji
 * od connect/request timeout-a, pa prvi pravi korisnički zahtevi ponekad puknu dok se konekcija
 * ne "zagreje" (videti i getWithRetry u TmdbService). Ovde tiho odradimo taj "hladan start" u
 * pozadini odmah pri startu servera, umesto da to iskusi korisnik. Kao bonus, ovim se odmah puni
 * popularCache za /api/movies/trending i /featured.
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

            // GLAVNA PROMENA: Pokrećemo ako je baza prazna ILI ako podaci nisu od danas
            val trebaSkrapovati = datumPoslednjegSkrapovanja == null || datumPoslednjegSkrapovanja != danasnjiDatum

            if (trebaSkrapovati) {
                if (datumPoslednjegSkrapovanja == null) {
                    logger.info("Baza je potpuno prazna. Pokrećem skraper...")
                } else {
                    logger.info("Podaci u bazi su zastareli (Zadnji upis: $datumPoslednjegSkrapovanja, Danas je: $danasnjiDatum). Pokrećem osvežavanje...")
                }

                val engine = ScrapingEngineConcurrent(sviAktivniScraperi())
                val skrapovaniFilmovi = engine.runAllScrapers(danasnjiDatum)
                //

                //dodati zvakom filmo njegov tmdb id
                logger.info("Pretraga TMDB ID-ja svakom pronadjenom filmu.")
                val obogaceniFilmovi = skrapovaniFilmovi.enrichWithTmdbIds(tmdbService)
                logger.info("Zacrsena pretraga TMDB ID-jeva.")

                //
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
 * Bugfix (25. avgust 2026, treći deo) - puni semantičke indekse NEZAVISNO od skrejpovanja, za
 * slučaj SCRAPING_BOOLEAN=false (npr. Render free plan, gde korisnik namerno drži skrepere
 * isključene jer ih taj host ne izdrži). Čita TRENUTNO stanje `scraped_movies` tabele (deljena
 * Supabase baza - skrejpovanje se radi na drugom hostu, npr. GitHub Actions ScraperJob) i gradi
 * oba indeksa od toga, bez pokretanja ijednog skrepera ovde.
 *
 * Posle starta periodično (SEMANTIC_INDEX_POLL_MINUTES, podrazumevano 60) proverava najnoviji
 * `created_at` u `scraped_movies` i osvežava indekse samo kad se promenio - Render instanca se
 * drži budnom cron ping-ovima pa se praktično nikad ne restartuje, a bez ovoga bi semantička
 * pretraga zauvek videla repertoar od trenutka poslednjeg deploy-a. Provera je jedan jeftin
 * MAX() upit; Gemini embedding pozivi se dešavaju samo kad stvarno stignu novi podaci (i tad samo
 * za filmove čiji se tekst promenio - videti MovieEmbeddingsTable keš).
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

/**
 * Zajednička refresh logika za oba mesta odakle se poziva (configureScrapingLifecycle posle
 * skrejpovanja, i configureSemanticIndexRefresh kad je skrejpovanje isključeno) - videti
 * obrazloženje na oba poziv-mesta zašto su razdvojena u prvom redu.
 */
suspend fun refreshSemanticIndices(
    geminiCinemaSemanticIndex: CinemaSemanticIndex,
    localCinemaSemanticIndex: CinemaSemanticIndex
) {
    // dorada (poređenje Gemini vs. lokalni MiniLM) - osvežavamo OBA indeksa, svaki u
    // svom try/catch bloku, da pad jednog (npr. Gemini API greška) ne spreči osvežavanje
    // drugog (lokalni MiniLM nema mrežnu zavisnost pa je manje verovatno da padne).
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
            // `Throwable` namerno (ne `Exception`) - baš OutOfMemoryError je razlog zašto
            // ovaj blok uopšte postoji, videti napomenu iznad.
            logger.error("CinemaSemanticIndex (Lokalno/MiniLM).refresh() nije uspeo: ${e.localizedMessage}", e)
        }
    } else {
        logger.info("ENABLE_LOCAL_EMBEDDING nije postavljen na 'true' - lokalni MiniLM indeks se ne puni (ostaje prazan, engine=local vraća praznu listu).")
    }
}
/**
 * Poruke grešaka (lokalizacija + StatusPages). Izdvojeno iz [module] da testovi ruta dobiju isto
 * ponašanje bez baze i spoljnih servisa.
 */
fun Application.configureErrorHandling() {
    // Poruke grešaka na engleskom za klijente koji šalju Accept-Language: en (videti Localization.kt).
    install(ErrorMessageLocalization)

    // Klijentu NIKAD ne vraćamo poruku internog izuzetka - poruke Ktor HTTP klijenta sadrže pun URL
    // upstream poziva, a TMDB v3/OMDb ključevi idu baš u URL (?api_key=...). Pun detalj ide samo u
    // log. Izuzetak: IllegalArgumentException su naše validacione poruke namenjene korisniku.
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            when (cause) {
                is IllegalArgumentException ->
                    call.respond(HttpStatusCode.BadRequest, call.localizedError(cause.message ?: "Neispravan zahtev"))
                is BadRequestException -> {
                    // Neispravno telo zahteva (npr. JSON koji ne odgovara DTO-u u call.receive).
                    logger.warn("Neispravan zahtev ${call.request.local.uri}: ${cause.message}")
                    call.respond(HttpStatusCode.BadRequest, call.localizedError("Neispravan zahtev"))
                }
                else -> {
                    logger.error("Neobrađena greška na ${call.request.local.uri}: ${cause.message}", cause)
                    call.respond(HttpStatusCode.InternalServerError, call.localizedError("Došlo je do greške na serveru"))
                }
            }
        }
        // RateLimit plugin vraća 429 bez tela - klijent dobija poruku kao i za ostale greške.
        status(HttpStatusCode.TooManyRequests) { call, status ->
            call.respond(status, call.localizedError("Previše zahteva. Pokušaj ponovo za minut."))
        }
    }
}

/** JWT autentikacija ("auth-jwt"); izdvojeno iz [module] iz istog razloga kao [configureErrorHandling]. */
fun Application.configureAuthentication(jwtSigningKey: String) {
    install(Authentication) {
        jwt("auth-jwt") {
            realm = "MovieLens Backend"
            // Token bez issuer/audience (izdat pre oktobra 2026) se odbija sa 401; Android ga tada
            // sam zameni novim (TokenAuthenticator), bez ponovne prijave korisnika.
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
