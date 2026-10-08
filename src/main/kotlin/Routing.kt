package com.milinko

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.milinko.auth.AuthFirebaseRequest
import com.milinko.auth.AuthResponse
import com.milinko.db.ScrapedMoviesRepository
import com.milinko.db.UserDao
import com.milinko.db.UserDto
import com.milinko.scraping.model.ScrapedMovie
import com.milinko.services.GeminiOverloadedException
import com.milinko.services.GeminiService
import com.milinko.services.MovieCandidate
import com.milinko.services.TmdbService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ratelimit.rateLimit
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
import java.time.Instant
import java.util.Date
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("Routing")

@Serializable
data class ChatRequest(val message: String)

@Serializable
data class TmdbMatch(
    val id: Int?,
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
    repository: ScrapedMoviesRepository, // PROMENA: Primamo repozitorijum
    userDao: UserDao,
    jwtSigningKey: String
) {
    routing {
        get("/") { call.respondText("OK") }

        // =================== FIREBASE AUTHENTICATION ===================
        post("/api/auth/firebase") {
            try {
                val req = call.receive<AuthFirebaseRequest>()

                // Verify Firebase ID token
                val decoded = FirebaseAuth.getInstance().verifyIdToken(req.idToken)
                val uid = decoded.uid
                val email = decoded.claims["email"]?.toString()
                val name = decoded.claims["name"]?.toString()
                val picture = decoded.claims["picture"]?.toString()

                logger.info("[AUTH] Verified Firebase token for UID: $uid")

                // Upsert user u DB
                val user = userDao.upsertByFirebaseUid(uid, email, name, picture)

                // Issue backend JWT token (24h expiry)
                val token = JWT.create()
                    .withIssuer(JWT_ISSUER)
                    .withAudience(JWT_AUDIENCE)
                    .withSubject(user.id)
                    .withClaim("firebaseUid", uid)
                    .withExpiresAt(Date.from(Instant.now().plusSeconds(86400)))
                    .sign(Algorithm.HMAC256(jwtSigningKey))

                logger.info("[AUTH] Issued JWT token for user: ${user.id}")

                call.respond(AuthResponse(
                    appToken = token,
                    user = UserDto(
                        id = user.id,
                        firebaseUid = uid,
                        username = user.username,
                        email = user.email,
                        avatarUrl = user.avatarUrl
                    )
                ))
            } catch (e: FirebaseAuthException) {
                logger.warn("[AUTH] Firebase verification failed: ${e.message}")
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
            } catch (e: IllegalArgumentException) {
                // Prazan ili neispravno formiran idToken.
                logger.info("[AUTH] Invalid token format: ${e.message}")
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
            } catch (e: BadRequestException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Neispravan zahtev"))
            } catch (e: Exception) {
                // Pad baze, Firebase nije inicijalizovan i sl. - nije problem sa tokenom, pa ne
                // vraćamo 401 (klijent bi pogrešno zaključio da korisnik mora ponovo da se prijavi).
                logger.error("[AUTH] Unexpected error: ${e.message}", e)
                call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Prijava trenutno nije moguća, pokušaj ponovo"))
            }
        }

        // =================== PROTECTED ENDPOINTS ===================
        authenticate("auth-jwt") {
            rateLimit(RATE_LIMIT_VISION) {
                post("/chat/vision") {
                    val multipart = call.receiveMultipart()
                    logger.info("IMAGE ONLY")
                    var message: String? = null
                    var imageBytes: ByteArray? = null
                    var tooLarge = false

                    multipart.forEachPart { part ->
                        when (part) {
                            is PartData.FormItem -> {
                                if (part.name == "message") message = part.value
                            }
                            is PartData.FileItem -> {
                                if (part.name == "image") {
                                    // Čita najviše limit+1 bajt - veći fajl se odbija bez učitavanja celog u memoriju.
                                    val bytes = part.streamProvider().use { it.readNBytes(MAX_IMAGE_BYTES + 1) }
                                    if (bytes.size > MAX_IMAGE_BYTES) tooLarge = true else imageBytes = bytes
                                }
                            }
                            else -> {}
                        }
                        part.dispose()
                    }

                    if (tooLarge) {
                        return@post call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("Slika je prevelika - probaj sa manjom/kompresovanijom slikom"))
                    }
                    if (imageBytes == null) {
                        return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Nedostaje slika"))
                    }

                    val size = imageBytes.size
                    // Tip se određuje iz sadržaja fajla, ne iz Content-Type zaglavlja koje klijent bira sam.
                    val imageMime = detectImageMime(imageBytes)
                        ?: return@post call.respond(HttpStatusCode.UnsupportedMediaType, ErrorResponse("Nepodržan format slike - koristi JPEG ili PNG"))

                    val t0 = System.currentTimeMillis()
                    logger.info("VISION: size=${size} mime=${imageMime}")

                    try {

                        val filmoviIzBaze = try {
                            repository.getAllScrapedMovies()
                        } catch (dbEx: Exception) {
                            logger.error("Neuspešno čitanje iz baze, nastavljam sa praznom listom: ${dbEx.message}")
                            emptyList<ScrapedMovie>()
                        }

                        // 1) Detekcija kandidata sa timeoutom preko Gemini-ja
                        val (detected, tGeminiMs) = try {
                            val start = System.currentTimeMillis()
                            val detected = withTimeout(25_000) {
                                geminiService.detectMoviesFromImage(imageBytes, imageMime)
                            }
                            detected to (System.currentTimeMillis() - start)
                        } catch (e: TimeoutCancellationException) {
                            return@post call.respond(HttpStatusCode.GatewayTimeout, ErrorResponse("Gemini detection timed out"))
                        }

                        val candidatesTop3 = detected.candidates.take(3)
                        logger.info("Gemini done in ${tGeminiMs}ms | candidates=${detected.candidates.size}")
                        logger.info("Detected candidates: ${detected.candidates.joinToString { it.title }}")
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
                                                val pronadjeniBioskopi = proveriDostupnostUBioskopimaPoPrioritetu(
                                                    tmdbId = tmdbMovie.id,
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
                                        logger.error("TMDB search failed for '${cand.title}': ${ex.message}")
                                        emptyList()
                                    }
                                    for (match in matches) {
                                        logger.info("TMDB match: ${match.title} (${match.releaseDate})")
                                    }
                                    CandidateResolution(candidate = cand, matches = matches)
                                }
                            }.awaitAll()
                        }
                        val tTmdbMs = System.currentTimeMillis() - t3
                        logger.info("TMDB done in ${tTmdbMs}ms")

                        val payload = VisionSearchResponse(
                            notMovieLike = detected.notMovieLike,
                            uncertaintyReason = detected.uncertaintyReason,
                            queryLanguage = detected.queryLanguage,
                            resolutions = resolutions
                        )

                        logger.info("/chat/vision total=${System.currentTimeMillis() - t0}ms")
                        call.respond(payload)

                    } catch (e: GeminiOverloadedException) {
                    
                        logger.error("Gemini overloaded: ${e.message}")
                        call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Gemini je trenutno preopterećen. Pokušaj ponovo za par trenutaka."))
                    } catch (e: IllegalStateException) {
                        call.respondUpstreamError("Upstream failure", e, "Analiza slike trenutno nije uspela")
                    } catch (e: Throwable) {
                        logger.error("Unexpected: ${e.message}")
                        call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Došlo je do greške na serveru"))
                    }
                }
            }
        }
    }
}

private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024

/** JPEG ili PNG po potpisu (magic bytes) na početku fajla; null za sve ostalo. */
internal fun detectImageMime(bytes: ByteArray): String? {
    fun startsWith(vararg sig: Int) = bytes.size >= sig.size && sig.indices.all { bytes[it] == sig[it].toByte() }
    return when {
        startsWith(0xFF, 0xD8, 0xFF) -> "image/jpeg"
        startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "image/png"
        else -> null
    }
}

private fun proveriDostupnostUBioskopimaPoPrioritetu(
    tmdbId: Int?,
    privremenaBazaFilmova: List<ScrapedMovie>
): List<ScrapedMovie> {
    if (privremenaBazaFilmova.isEmpty()) return emptyList()

    if (tmdbId != null) {
        val poId = privremenaBazaFilmova.filter { it.tmdbId == tmdbId }
        if (poId.isNotEmpty()) return poId
    }
    return emptyList()

}
