package com.milinko

import com.milinko.db.ScrapedMoviesRepository
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
    repository: ScrapedMoviesRepository // PROMENA: Primamo repozitorijum
) {
    routing {
        get("/") { call.respondText("OK") }

        // Endpoint za sliku (vision) + pretragu bioskopa
        post("/chat/vision") {
            val multipart = call.receiveMultipart()
            println("\n[LOG] ${java.time.LocalTime.now()} | IMAGE ONLY")
            var message: String? = null
            var imageBytes: ByteArray? = null
            var imageMime: String = "image/jpeg"

            multipart.forEachPart { part ->
                when (part) {
                    is PartData.FormItem -> {
                        if (part.name == "message") message = part.value
                    }
                    is PartData.FileItem -> {
                        if (part.name == "image") {
                            imageBytes = part.streamProvider().readBytes()
                            part.contentType?.let { ct -> imageMime = ct.toString() }
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
            println("\n[LOG] ${java.time.LocalTime.now()} | VISION: size=${size} mime=${imageMime}")

            try {
                // NOVO: Pre nego što krenemo u TMDB petlju, asinhrono povlačimo sve filmove iz Supabase baze
                // Ovo radimo ovde kako bismo imali sveže podatke za upoređivanje
                val filmoviIzBaze = try {
                    repository.getAllScrapedMovies()
                } catch (dbEx: Exception) {
                    println("[ERROR] Neuspešno čitanje iz baze, nastavljam sa praznom listom: ${dbEx.message}")
                    emptyList<ScrapedMovie>()
                }

                // 1) Detekcija kandidata sa timeoutom preko Gemini-ja
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
                println("[LOG] Gemini done in ${tGeminiMs}ms | candidates=${detected.candidates.size}")
                println("[LOG] Detected candidates: ${detected.candidates.joinToString { it.title }}")
                // 2) TMDB pretrage u paraleli (do 3 kandidata)
                val t3 = System.currentTimeMillis()
                val resolutions = coroutineScope {
                    candidatesTop3.map { cand ->
                        async {
                            val year = cand.year
                            val matches = try {
                                tmdbService.searchMovies(cand.title)
                                    .take(5)
                                    .map { tmdbMovie ->
                                        // PROMENA: Prosleđujemo svežu listu filmova povučenu iz Supabase baze
                                        val pronadjeniBioskopi = proveriDostupnostUBioskopima(
                                            tmdbOriginalTitle = tmdbMovie.originalTitle,
                                            tmdbTitle = tmdbMovie.title,
                                            privremenaBazaFilmova = filmoviIzBaze
                                        )
                                        TmdbMatch(
                                            id = tmdbMovie.id,
                                            title = tmdbMovie.title,
                                            originalTitle = tmdbMovie.originalTitle,
                                            releaseDate = tmdbMovie.releaseDate,
                                            overview = tmdbMovie.overview,
                                            posterPath = tmdbMovie.posterPath,
                                            popularity = tmdbMovie.popularity,
                                            voteAverage = tmdbMovie.voteAverage,
                                            cinemaShowtimes = pronadjeniBioskopi
                                        )
                                    }
                            } catch (ex: Exception) {
                                println("[ERROR] TMDB search failed for '${cand.title}': ${ex.message}")
                                emptyList()
                            }
                            for (match in matches) {
                                println("[LOG] TMDB match: ${match.title} (${match.releaseDate})")
                            }
                            CandidateResolution(candidate = cand, matches = matches)
                        }
                    }.awaitAll()
                }
                val tTmdbMs = System.currentTimeMillis() - t3
                println("[LOG] TMDB done in ${tTmdbMs}ms")

                val payload = VisionSearchResponse(
                    notMovieLike = detected.notMovieLike,
                    uncertaintyReason = detected.uncertaintyReason,
                    queryLanguage = detected.queryLanguage,
                    resolutions = resolutions
                )

                println("[LOG] /chat/vision total=${System.currentTimeMillis() - t0}ms")
                call.respond(payload)

            } catch (e: IllegalStateException) {
                println("[ERROR] Upstream failure: ${e.message}")
                call.respond(HttpStatusCode.BadGateway, ErrorResponse(e.message ?: "Upstream error"))
            } catch (e: Throwable) {
                println("[ERROR] Unexpected: ${e.message}")
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Internal server error"))
            }
        }
    }
}
/**
 * Pomoćna metoda koja pretražuje lokalnu privremenu bazu skrapovanih filmova.
 * Koristi originalni naslov sa TMDB-a kao primarni izvor istine, a domaći naslov kao fallback.
 */
//private fun proveriDostupnostUBioskopima(
//    tmdbOriginalTitle: String?,
//    tmdbTitle: String,
//    privremenaBazaFilmova: List<ScrapedMovie>
//): List<ScrapedMovie> {
//    if (privremenaBazaFilmova.isEmpty()) return emptyList()
//
//    val cistiOriginalni = tmdbOriginalTitle?.lowercase()?.trim() ?: ""
//    val cistiDomaci = tmdbTitle.lowercase().trim()
//
//    return privremenaBazaFilmova.filter { film ->
//        val bioskopOriginalni = film.originalTitle?.lowercase()?.trim() ?: ""
//        val bioskopDomaci = film.title.lowercase().trim()
//
//        // 1. Prvo i najjače poklapanje: poredimo originalne naslove (izbegavamo probleme sa prevodom)
//        (cistiOriginalni.isNotEmpty() && bioskopOriginalni.isNotEmpty() && bioskopOriginalni.contains(cistiOriginalni)) ||
//                (cistiOriginalni.isNotEmpty() && bioskopDomaci.contains(cistiOriginalni)) ||
//
//                // 2. Fallback: poredimo domaće naslove ako se poklope na sajtovima
//                bioskopDomaci.contains(cistiDomaci) ||
//                (bioskopOriginalni.isNotEmpty() && bioskopOriginalni.contains(cistiDomaci))
//    }
//}


 private fun proveriDostupnostUBioskopima(
    tmdbOriginalTitle: String?,
    tmdbTitle: String,
    privremenaBazaFilmova: List<ScrapedMovie>
): List<ScrapedMovie> {
    if (privremenaBazaFilmova.isEmpty()) return emptyList()

    val cistiOriginalni = normalizeTitleForMatch(tmdbOriginalTitle ?: "")
    val cistiDomaci = normalizeTitleForMatch(tmdbTitle)

    return privremenaBazaFilmova.filter { film ->
        val bioskopOriginalni = normalizeTitleForMatch(film.originalTitle ?: "")
        val bioskopDomaci = normalizeTitleForMatch(film.title)

        // Strogo poređenje (normalizovano), bez contains - izbegava lažna poklapanja za nastavke (npr. "... 2").
        (cistiOriginalni.isNotEmpty() && bioskopOriginalni.isNotEmpty() && titlesStrictlyMatch(cistiOriginalni, bioskopOriginalni)) ||
                (cistiOriginalni.isNotEmpty() && titlesStrictlyMatch(cistiOriginalni, bioskopDomaci)) ||
                titlesStrictlyMatch(cistiDomaci, bioskopDomaci) ||
                (bioskopOriginalni.isNotEmpty() && titlesStrictlyMatch(cistiDomaci, bioskopOriginalni))
    }
}

private fun titlesStrictlyMatch(left: String, right: String): Boolean {
    if (left.isBlank() || right.isBlank()) return false
    if (left == right) return true

    // Fallback: ignoriši samo početni engleski član (The/A/An), bez "contains" logike.
    fun withoutArticle(value: String): String = value.replace(Regex("^(the|a|an)\\s+"), "")
    return withoutArticle(left) == withoutArticle(right)
}

private fun normalizeTitleForMatch(value: String): String {
    return value
        .lowercase()
        // Ukloni česte tehničke oznake formata koje često dođu iz cinema title-a.
        .replace(Regex("\\b(2d|3d|4dx|imax|atm[oa]s|sub|dub|sinhro|titlovano)\\b"), " ")
        .replace(Regex("[\\[\\](){}]"), " ")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}