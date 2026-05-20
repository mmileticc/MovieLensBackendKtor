package com.milinko

import com.milinko.scraping.model.ScrapedMovie
import com.milinko.services.GeminiService
import com.milinko.services.MovieCandidate
import com.milinko.services.TmdbService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import io.ktor.http.content.*
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout

@Serializable
data class ChatRequest(val message: String)

@Serializable
data class TmdbMatch(
    val id: Int,
    val title: String,
    val originalTitle: String? = null,
    val releaseDate: String? = null,
    val overview: String? = null,
    val posterPath: String? = null,
    val popularity: Double? = null,
    val voteAverage: Double? = null,
    // MODIFIKACIJA: Dodata lista bioskopa koja se vraća frontu
    val cinemaShowtimes: List<ScrapedMovie> = emptyList()
)

@Serializable
data class CandidateResolution(
    val candidate: MovieCandidate,
    val matches: List<TmdbMatch>
)

@Serializable
data class VisionSearchResponse(
    val notMovieLike: Boolean,
    val uncertaintyReason: String? = null,
    val queryLanguage: String? = null,
    val resolutions: List<CandidateResolution>
)

fun Application.configureRouting(
    geminiService: GeminiService,
    tmdbService: TmdbService,
    privremenaBazaFilmova: List<ScrapedMovie>) {
    routing {
        get("/") { call.respondText("OK") }

        post("/chat") {
            val req = call.receive<ChatRequest>()

            // Logovanje pitanja u konzolu
            println("\n[LOG] ${java.time.LocalTime.now()} | Zahtev: ${req.message}")

            call.response.status(HttpStatusCode.OK)
            call.respondTextWriter(contentType = ContentType.Text.Plain.withCharset(Charsets.UTF_8)) {
                try {
                    geminiService.streamChat(req.message).collect { chunk ->
                        write(chunk)
                        flush() // Gura podatke ka mreži momentalno
                    }
                    println("[LOG] Odgovor uspešno završen.")
                } catch (e: Throwable) {
                    println("[ERROR] Greška: ${e.message}")
                    write("\n[Greška na serveru]: ${e.message}")
                }
            }
        }

        post("/chat/text") {
            val message = call.receiveText().trim()
            if (message.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest)

            println("\n[LOG] ${java.time.LocalTime.now()} | TEXT-ONLY: $message")

            call.respondTextWriter(contentType = ContentType.Text.Plain.withCharset(Charsets.UTF_8)) {
                try {
                    geminiService.streamChat(message).collect { chunk ->
                        write(chunk)
                        flush()
                    }
                } catch (e: Throwable) {
                    write("\n[Error]: ${e.message}")
                }
            }
        }

        // Multipart endpoint for text + image (vision)
        post("/chat/vision") {
            val multipart = call.receiveMultipart()
            println("\n[LOG] ${java.time.LocalTime.now()} | IMAGE ONLY")
            var message: String? = null
            var imageBytes: ByteArray? = null
            var imageMime: String = "image/jpeg"

            multipart.forEachPart { part ->
                when (part) {
                    is PartData.FormItem -> {
                        if (part.name == "message") {
                            message = part.value
                        }
                    }
                    is PartData.FileItem -> {
                        if (part.name == "image") {
                            val bytes = part.streamProvider().readBytes()
                            imageBytes = bytes
                            // Try to capture content type from upload
                            part.contentType?.let { ct ->
                                imageMime = ct.toString()
                            }
                        }
                    }
                    else -> {}
                }
                part.dispose()
            }

            if (imageBytes == null) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing file part: image"))
            }

            val size = imageBytes!!.size
            val allowedMimes = setOf("image/jpeg", "image/jpg", "image/png")
            if (size > 8 * 1024 * 1024) {
                return@post call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("Image too large (>8MB)"))
            }
            if (imageMime.lowercase() !in allowedMimes) {
                return@post call.respond(HttpStatusCode.UnsupportedMediaType, ErrorResponse("Unsupported image mime: $imageMime"))
            }

            val t0 = System.currentTimeMillis()
            println("\n[LOG] ${java.time.LocalTime.now()} | VISION: size=${size} mime=${imageMime} (message ignored)")

            try {
                // 1) Detekcija kandidata sa timeoutom
                val (detected, tGeminiMs) = try {
                    val start = System.currentTimeMillis()
                    val detected = withTimeout(25_000) {
                        geminiService.detectMoviesFromImage(imageBytes!!, imageMime)
                    }
                    detected to (System.currentTimeMillis() - start)
                } catch (e: TimeoutCancellationException) {
                    return@post call.respond(HttpStatusCode.GatewayTimeout, ErrorResponse("Gemini detection timed out"))
                }

                val candidatesTop3 = detected.candidates.take(3)
                println("[LOG] Gemini done in ${tGeminiMs}ms | candidates=${detected.candidates.size} | top=${candidatesTop3.joinToString { it.title + (it.year?.let { y -> " ($y)" } ?: "") }}")

                // 2) TMDB pretrage u paraleli (do 3)
                val t3 = System.currentTimeMillis()
                val resolutions = coroutineScope {
                    candidatesTop3.map { cand ->
                        async {
                            val year = cand.year
                            val matches = try {
                                tmdbService.searchMovies(cand.title, year)
                                    .take(5)
                                    .map {
                                        // MODIFIKACIJA: Pokretanje tvoje pomoćne metode nad privremenom bazom
                                        val pronadjeniBioskopi = proveriDostupnostUBioskopima(
                                            tmdbOriginalTitle = it.originalTitle,
                                            tmdbTitle = it.title,
                                            privremenaBazaFilmova = privremenaBazaFilmova
                                        )
                                        TmdbMatch(
                                            id = it.id,
                                            title = it.title,
                                            originalTitle = it.originalTitle,
                                            releaseDate = it.releaseDate,
                                            overview = it.overview,
                                            posterPath = it.posterPath,
                                            popularity = it.popularity,
                                            voteAverage = it.voteAverage,
                                            cinemaShowtimes = pronadjeniBioskopi
                                        )
                                    }
                            } catch (ex: Exception) {
                                // Logiraj i vrati prazan set za ovog kandidata (globalni status će i dalje biti 200)
                                println("[ERROR] TMDB search failed for '${cand.title}' (${year ?: "?"}): ${ex.message}")
                                emptyList()
                            }
                            CandidateResolution(candidate = cand, matches = matches)
                        }
                    }.awaitAll()
                }
                val tTmdbMs = System.currentTimeMillis() - t3
                println("[LOG] TMDB done in ${tTmdbMs}ms | per-candidate matches: ${resolutions.joinToString { it.matches.size.toString() }}")

                val payload = VisionSearchResponse(
                    notMovieLike = detected.notMovieLike,
                    uncertaintyReason = detected.uncertaintyReason,
                    queryLanguage = detected.queryLanguage,
                    resolutions = resolutions
                )

                val total = System.currentTimeMillis() - t0
                println("[LOG] /chat/vision total=${total}ms")
                call.respond(payload)
            } catch (e: IllegalStateException) {
                // Pretpostavimo da je upstream problem (Gemini parsing/SDK ili TMDB HTTP)
                println("[ERROR] Upstream failure: ${e.message}\n" + (e.stackTraceToString().lineSequence().take(10).joinToString("\n")))
                call.respond(HttpStatusCode.BadGateway, ErrorResponse(e.message ?: "Upstream error"))
            } catch (e: Throwable) {
                println("[ERROR] Unexpected: ${e.message}\n" + (e.stackTraceToString().lineSequence().take(10).joinToString("\n")))
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Internal server error"))
            }
        }
    }
}
/**
 * Pomoćna metoda koja pretražuje lokalnu privremenu bazu skrapovanih filmova.
 * Koristi originalni naslov sa TMDB-a kao primarni izvor istine, a domaći naslov kao fallback.
 */
private fun proveriDostupnostUBioskopima(
    tmdbOriginalTitle: String?,
    tmdbTitle: String,
    privremenaBazaFilmova: List<ScrapedMovie>
): List<ScrapedMovie> {
    if (privremenaBazaFilmova.isEmpty()) return emptyList()

    val cistiOriginalni = tmdbOriginalTitle?.lowercase()?.trim() ?: ""
    val cistiDomaci = tmdbTitle.lowercase().trim()

    return privremenaBazaFilmova.filter { film ->
        val bioskopOriginalni = film.originalTitle?.lowercase()?.trim() ?: ""
        val bioskopDomaci = film.title.lowercase().trim()

        // 1. Prvo i najjače poklapanje: poredimo originalne naslove (izbegavamo probleme sa prevodom)
        (cistiOriginalni.isNotEmpty() && bioskopOriginalni.isNotEmpty() && bioskopOriginalni.contains(cistiOriginalni)) ||
                (cistiOriginalni.isNotEmpty() && bioskopDomaci.contains(cistiOriginalni)) ||

                // 2. Fallback: poredimo domaće naslove ako se poklope na sajtovima
                bioskopDomaci.contains(cistiDomaci) ||
                (bioskopOriginalni.isNotEmpty() && bioskopOriginalni.contains(cistiDomaci))
    }
}