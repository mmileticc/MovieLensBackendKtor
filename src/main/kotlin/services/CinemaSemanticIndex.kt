package com.milinko.services

import com.milinko.db.MovieEmbeddingsRepository
import com.milinko.db.ScrapedMoviesRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/**
 * Faza D (17. avgust 2026, predlog mentora Marka) - semantička/vektorska pretraga filmova,
 * namerno SVEDENA na filmove trenutno na repertoaru bioskopa (ne ceo TMDB katalog - nema se
 * lokalna lista svih filmova/opisa, pun ingest bi bio nesrazmeran obim). Ta lista je već mala
 * (do par desetina jedinstvenih filmova) i već se drži u Postgres bazi preko
 * [ScrapedMoviesRepository] - ovaj indeks je čist U MEMORIJI, isti princip kao [com.milinko.util.TtlCache],
 * BEZ nove tabele/migracije. `entries` se čita/piše kroz `@Volatile` referencu na NEPROMENLJIVU
 * listu (ceo indeks se zameni odjednom pri [refresh] - nema potrebe za `synchronized`/zaključavanjem
 * pojedinačnih unosa, jedan pisac - refresh - i mnogo čitalaca - pretrage).
 */
// Isti limit kao NOW_IN_CINEMAS_TMDB_PARALLELISM u MovieRouting.kt.
private const val TMDB_PARALLELISM = 8

class CinemaSemanticIndex(
    private val scrapedMoviesRepository: ScrapedMoviesRepository,
    private val tmdbService: TmdbService,
    // Faza D dorada - bilo koji TextEmbedder (Gemini ili lokalni MiniLM), videti TextEmbedder.kt.
    // Svaka instanca ovog indeksa je vezana za TAČNO JEDAN embedder - vektori različitih embedder-a
    // imaju različitu dimenziju/prostor, ne mogu se mešati u istom indeksu.
    private val embedder: TextEmbedder,
    private val movieEmbeddingsRepository: MovieEmbeddingsRepository
) {
    data class Entry(
        val tmdbId: Int,
        val title: String,
        val posterPath: String?,
        val voteAverage: Double,
        val cinemas: List<String>,
        val embedding: FloatArray
    )

    @Volatile
    private var entries: List<Entry> = emptyList()

    /**
     * Ponovo gradi ceo indeks od trenutnog stanja `scraped_movies` tabele. Namerno BEZ `.take(20)`
     * limita koji `/api/movies/now-in-cinemas` ruta ima za Home traku - taj limit je tamo namerna
     * odluka za kratku preview traku, ali pretraga mora da pokrije ceo trenutni repertoar da ima
     * smisla. Filmovi bez `overview`-a (redak slučaj) se preskaču - nema teksta za embedovanje.
     * Ne baca izuzetak na grešku pojedinačnog filma (TMDB detalj padne, embedding padne) - taj film
     * se samo izostavi iz indeksa, ne ruši ceo refresh.
     */
    suspend fun refresh() {
        val scraped = scrapedMoviesRepository.getAllScrapedMovies()
        val byTmdbId = scraped.filter { it.tmdbId != null }.groupBy { it.tmdbId!! }

        val cached = movieEmbeddingsRepository.getEmbeddingsForEngine(embedder.key)
        // Brojač se uvećava iz paralelnih korutina - zato atomičan.
        val reusedCount = AtomicInteger(0)
        // Bez ograničenja ~110 istovremenih TMDB poziva pri praznom kešu završava u timeout-ima,
        // pa deo filmova ispadne iz indeksa.
        val tmdbLimiter = Semaphore(TMDB_PARALLELISM)

        val newEntries = coroutineScope {
            byTmdbId.entries.map { (tmdbId, rows) ->
                async {
                    try {
                        val detail = tmdbLimiter.withPermit { tmdbService.getMovieDetails(tmdbId) } ?: return@async null
                        val overview = detail.overview?.trim().orEmpty()
                        if (overview.isEmpty()) return@async null

                        val sourceText = "${detail.title}. $overview"
                        val sourceHash = sha256(sourceText)
                        val cachedEntry = cached[tmdbId]

                        val embedding = if (cachedEntry != null && cachedEntry.sourceHash == sourceHash && cachedEntry.embedding.isNotEmpty()) {
                            reusedCount.incrementAndGet()
                            cachedEntry.embedding
                        } else {
                            val computed = embedder.embedDocument(sourceText)
                            if (computed.isNotEmpty()) {
                                movieEmbeddingsRepository.upsertEmbedding(tmdbId, embedder.key, sourceHash, computed)
                            }
                            computed
                        }
                        if (embedding.isEmpty()) return@async null

                        Entry(
                            tmdbId = detail.id,
                            title = detail.title,
                            posterPath = detail.posterPath,
                            voteAverage = detail.voteAverage ?: 0.0,
                            cinemas = rows.map { it.cinemaName }.distinct().sorted(),
                            embedding = embedding
                        )
                    } catch (e: Exception) {
                        println("[WARN] CinemaSemanticIndex: preskačem tmdbId=$tmdbId (${e.message})")
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }

        entries = newEntries
        println("[LOG] CinemaSemanticIndex (${embedder.label}) osvežen: ${newEntries.size}/${byTmdbId.size} filmova indeksirano (${reusedCount.get()} iz DB keša, ${newEntries.size - reusedCount.get()} novo izračunato).")
    }

    /** SHA-256 heks digest teksta od kog je embedding izračunat - videti napomenu na MovieEmbeddingsTable.sourceHash. */
    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Prazno dok se prvi [refresh] ne završi (npr. odmah posle deploy-a) - pozivalac vraća praznu listu, ne grešku. */
    fun isReady(): Boolean = entries.isNotEmpty()

    /** Embeduje upit ISTIM embedder-om kojim je indeks izgrađen (obavezno - videti napomenu na konstruktoru) i vraća top rezultate. */
    suspend fun searchByQuery(query: String, topN: Int = 10): List<Entry> {
        val queryEmbedding = embedder.embedQuery(query)
        return search(queryEmbedding, topN)
    }

    private fun search(queryEmbedding: FloatArray, topN: Int = 10): List<Entry> {
        if (queryEmbedding.isEmpty() || entries.isEmpty()) return emptyList()
        return entries
            .map { it to cosineSimilarity(it.embedding, queryEmbedding) }
            .sortedByDescending { it.second }
            .take(topN)
            .map { it.first }
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size || a.isEmpty()) return -1.0
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        if (normA == 0.0 || normB == 0.0) return -1.0
        return dot / (sqrt(normA) * sqrt(normB))
    }
}
