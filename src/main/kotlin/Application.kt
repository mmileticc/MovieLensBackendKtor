package com.milinko

import com.milinko.db.DatabaseFactory
import com.milinko.db.ScrapedMoviesRepository
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
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
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
        anyHost() // Po potrebi suziti na dozvoljene hostove/origine
        allowNonSimpleContentTypes = true
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Get)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
    }

    DatabaseFactory.init()



    val repository = ScrapedMoviesRepository()


    // Konfiguracija Gemini API ključa: ENV var ima prioritet, fallback na application.yaml
    val apiKey = System.getenv("GEMINI_API_KEY")
        ?: environment.config.propertyOrNull("ktor.gemini.apiKey")?.getString()
        ?: throw IllegalStateException("GEMINI_API_KEY nije postavljen (ENV) niti u application.yaml pod 'ktor.gemini.apiKey'.")

    val tmdbKey = System.getenv("TMDB_API_KEY")
        ?: environment.config.propertyOrNull("ktor.tmdb.apiKey")?.getString()
        ?: throw IllegalStateException("TMDB_API_KEY nije postavljen (ENV) niti u application.yaml pod 'ktor.tmdb.apiKey'.")

    val geminiService = GeminiService(apiKey = apiKey)
    val tmdbService = TmdbService(apiKey = tmdbKey)

    configureScrapingLifecycle(tmdbService)
    configureRouting(geminiService, tmdbService, repository)
}

@kotlinx.serialization.Serializable
data class ErrorResponse(val error: String)


fun Application.configureScrapingLifecycle(tmdbService: TmdbService) {
    val repository = ScrapedMoviesRepository()

    launch {
        try {
            println("[REPREZENTACIJA] Provera stanja i svežine baze podataka...")

            val danasnjiDatum = LocalDate.now()
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