package com.milinko

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.milinko.db.DatabaseFactory
import com.milinko.db.FirebaseAdminInitializer
import com.milinko.db.FriendshipsRepository
import com.milinko.db.MoviesRepository
import com.milinko.db.ScrapedMoviesRepository
import com.milinko.db.TasteProfileRepository
import com.milinko.db.UserActionsRepository
import com.milinko.db.UserDao
import com.milinko.scraping.scraper.ArenaCineplexScraper
import com.milinko.scraping.scraper.CineGrandScraper
import com.milinko.scraping.scraper.CineplexxScraper
import com.milinko.scraping.scraper.MtsDvoranaScraper
import com.milinko.services.GeminiService
import com.milinko.services.TmdbService
import com.milinko.services.enrichWithTmdbIds
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import com.milinko.util.CINEMA_ZONE_ID
import scraping.ScrapingEngineConcurrent
import java.time.LocalDate

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
        println("[ERROR] Firebase init failed: ${e.message}")
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

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            val status = if (cause is IllegalArgumentException) HttpStatusCode.BadRequest else HttpStatusCode.InternalServerError
            call.respond(status, ErrorResponse(error = cause.message ?: "Unexpected error"))
        }
    }

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

    install(Authentication) {
        jwt("auth-jwt") {
            realm = "MovieLens Backend"
            verifier(
                JWT.require(Algorithm.HMAC256(jwtSigningKey))
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

    DatabaseFactory.init()

    val repository = ScrapedMoviesRepository()
    val userDao = UserDao()

    // Konfiguracija Gemini API ključa: ENV var ima prioritet, fallback na application.yaml
    val apiKey = System.getenv("GEMINI_API_KEY")
        ?: environment.config.propertyOrNull("ktor.gemini.apiKey")?.getString()
        ?: throw IllegalStateException("GEMINI_API_KEY nije postavljen (ENV) niti u application.yaml pod 'ktor.gemini.apiKey'.")

    val tmdbKey = System.getenv("TMDB_API_KEY")
        ?: environment.config.propertyOrNull("ktor.tmdb.apiKey")?.getString()
        ?: throw IllegalStateException("TMDB_API_KEY nije postavljen (ENV) niti u application.yaml pod 'ktor.tmdb.apiKey'.")

    val geminiService = GeminiService(apiKey = apiKey)
    val tmdbService = TmdbService(apiKey = tmdbKey)
    warmUpTmdbClient(tmdbService)
    val scrapingBoolean = System.getenv("SCRAPING_BOOLEAN")
        ?: environment.config.propertyOrNull("ktor.scraping.enabled")?.getString()
        ?: throw IllegalStateException("SCRAPING_BOOLEAN nije postavljen (ENV) niti u application.yaml pod 'ktor.scraping.enabled'.")


    if (scrapingBoolean.lowercase() == "true") {
        configureScrapingLifecycle(tmdbService)
    }
    configureRouting(geminiService, tmdbService, repository, userDao, jwtSigningKey)
    configureMovieRouting(tmdbService, FriendshipsRepository(), UserActionsRepository(), TasteProfileRepository())
    configureUserActionsRouting(tmdbService, UserActionsRepository(), MoviesRepository(), TasteProfileRepository())
    configureFriendsRouting(FriendshipsRepository(), userDao, UserActionsRepository(), TasteProfileRepository(), tmdbService)
}

@kotlinx.serialization.Serializable
data class ErrorResponse(val error: String)

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
            println("[TMDB] Zagrevanje HTTP konekcije...")
            val movies = tmdbService.getPopularMovies(1)
            println("[TMDB] Zagrevanje uspešno (${movies.size} popularnih filmova učitano u keš).")
        } catch (e: Exception) {
            println("[WARN] TMDB zagrevanje nije uspelo, nije kritično - prvi pravi zahtev će pokušati ponovo: ${e.message}")
        }
    }
}


fun Application.configureScrapingLifecycle(tmdbService: TmdbService) {
    val repository = ScrapedMoviesRepository()

    launch {
        try {
            println("[REPREZENTACIJA] Provera stanja i svežine baze podataka...")

            // VAŽNO: eksplicitno CINEMA_ZONE_ID (Europe/Belgrade), ne LocalDate.now() (JVM lokalna
            // zona - može biti UTC na Render-u, Belgrade lokalno itd.) i ne UTC (dan bi se "prevrnuo"
            // 1-2h kasnije nego što je stvarno ponoć u Beogradu). getLatestScrapeDate() konvertuje
            // UTC-stored created_at u istu zonu pre poređenja - videti komentar tamo.
            val danasnjiDatum = LocalDate.now(CINEMA_ZONE_ID)
            val datumPoslednjegSkrapovanja = repository.getLatestScrapeDate()

            // GLAVNA PROMENA: Pokrećemo ako je baza prazna ILI ako podaci nisu od danas
            val trebaSkrapovati = datumPoslednjegSkrapovanja == null || datumPoslednjegSkrapovanja != danasnjiDatum

            if (trebaSkrapovati) {
                if (datumPoslednjegSkrapovanja == null) {
                    println("[LOG] Baza je potpuno prazna. Pokrećem skraper...")
                } else {
                    println("[LOG] Podaci u bazi su zastareli (Zadnji upis: $datumPoslednjegSkrapovanja, Danas je: $danasnjiDatum). Pokrećem osvežavanje...")
                }

                val aktivniScraperi = listOf(
                    MtsDvoranaScraper(),
                    CineplexxScraper(),
                    ArenaCineplexScraper(),
                    CineGrandScraper()
                )
                val engine = ScrapingEngineConcurrent(aktivniScraperi)
                val skrapovaniFilmovi = engine.runAllScrapers(danasnjiDatum)
                //

                //dodati zvakom filmo njegov tmdb id
                println("[LOG] Pretraga TMDB ID-ja svakom pronadjenom filmu.")
                val obogaceniFilmovi = skrapovaniFilmovi.enrichWithTmdbIds(tmdbService)
                println("[LOG] Zacrsena pretraga TMDB ID-jeva.")

                //
                if (skrapovaniFilmovi.isNotEmpty()) {


                    val saIdjem = obogaceniFilmovi.count { it.tmdbId != null }
                    println("[LOG] Enrichment gotov. Pronadjeno TMDB ID-jeva: $saIdjem od ${obogaceniFilmovi.size}")

                    repository.insertScrapedMovies(obogaceniFilmovi)
                    println("[LOG] Novo skrapovanje završeno i sveži podaci su upisani u Supabase.")

                } else {
                    println("[WARN] Skraper je pokrenut ali nije pronašao nijedan film na sajtovima.")
                }

            } else {
                println("[LOG] Podaci u bazi su sveži (Od danas su: $datumPoslednjegSkrapovanja). Skrapovanje je preskočeno!")

                val filmoviIzBaze = repository.getAllScrapedMovies()
                println("[LOG] Učitano ${filmoviIzBaze.size} filmova direktno iz Supabase-a za rad aplikacije.")
            }

        } catch (e: Exception) {
            println("[ERROR] Greška tokom inicijalizacije podataka: ${e.localizedMessage}")
            e.printStackTrace()
        }
    }
}