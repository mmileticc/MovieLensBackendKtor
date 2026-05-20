package com.milinko

import com.milinko.scraping.model.ScrapedMovie
import com.milinko.scraping.scraper.ArenaCineplexScraper
import com.milinko.scraping.scraper.CineplexxScraper
import com.milinko.scraping.scraper.MtsDvoranaScraper
import com.milinko.services.GeminiService
import com.milinko.services.TmdbService
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.http.*
import io.ktor.server.netty.EngineMain
import io.ktor.server.response.*
import io.ktor.server.plugins.cors.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import scraping.ScrapingEngineConcurrent
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

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


    val privremenaBazaFilmova = CopyOnWriteArrayList<ScrapedMovie>()

    // Pokrećemo skrapovanje asinhrono u pozadini odmah po startovanju Ktor-a
    // da ne bismo blokirali podizanje samog servera
    launch(Dispatchers.Default) {
        println("\n=== [KTOR POZADINA] INICIJALIZACIJA SKRAPERA ===")
        val aktivniScraperi = listOf(
            MtsDvoranaScraper(),
            CineplexxScraper(),
            ArenaCineplexScraper()
        )
        val engine = ScrapingEngineConcurrent(aktivniScraperi)

        try {
            val skrapovaniFilmovi = engine.runAllScrapers(LocalDate.now())
            privremenaBazaFilmova.addAll(skrapovaniFilmovi)
            println("=== [KTOR POZADINA] Skrapovanje uspešno završeno! Učitano ${privremenaBazaFilmova.size} zapisa u memoriju. ===\n")
        } catch (e: Exception) {
            println("=== [KTOR POZADINA] Greška tokom inicijalnog skrapovanja: ${e.message} ===")
        }
    }



    // Konfiguracija Gemini API ključa: ENV var ima prioritet, fallback na application.yaml
    val apiKey = System.getenv("GEMINI_API_KEY")
        ?: environment.config.propertyOrNull("ktor.gemini.apiKey")?.getString()
        ?: throw IllegalStateException("GEMINI_API_KEY nije postavljen (ENV) niti u application.yaml pod 'ktor.gemini.apiKey'.")

    val tmdbKey = System.getenv("TMDB_API_KEY")
        ?: environment.config.propertyOrNull("ktor.tmdb.apiKey")?.getString()
        ?: throw IllegalStateException("TMDB_API_KEY nije postavljen (ENV) niti u application.yaml pod 'ktor.tmdb.apiKey'.")

    val geminiService = GeminiService(apiKey = apiKey)
    val tmdbService = TmdbService(apiKey = tmdbKey)

    configureRouting(geminiService, tmdbService, privremenaBazaFilmova)
}

@kotlinx.serialization.Serializable
data class ErrorResponse(val error: String)
