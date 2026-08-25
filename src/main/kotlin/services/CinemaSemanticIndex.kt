package com.milinko.services

import com.milinko.db.MovieEmbeddingsRepository
import com.milinko.db.ScrapedMoviesRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.security.MessageDigest
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
class CinemaSemanticIndex(
    private val scrapedMoviesRepository: ScrapedMoviesRepository,
    private val tmdbService: TmdbService,
    // Faza D dorada - bilo koji TextEmbedder (Gemini ili lokalni MiniLM), videti TextEmbedder.kt.
    // Svaka instanca ovog indeksa je vezana za TAČNO JEDAN embedder - vektori različitih embedder-a
    // imaju različitu dimenziju/prostor, ne mogu se mešati u istom indeksu.
    private val embedder: TextEmbedder,
    // Faza D dorada #2 (23. avgust 2026, korisničko pitanje "zašto se ne bi odmah i embedovan
    // rezultat čuvao... jer trenutno svaki restart potroši ~70 Gemini upita") - trajni keš u bazi,
    // videti MovieEmbeddingsTable.kt. Deljen između obe instance (Gemini/lokalna) na poziv-nivou -
    // razdvajanje po `embedder.key` se dešava UNUTAR repository poziva, ne treba dve instance.
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

        // Faza D dorada #2 - jedan upit učita SVE već izračunate vektore za OVAJ embedder (isti
        // obrazac kao getAllScrapedMovies() iznad - bulk fetch pa filtriranje u memoriji, umesto
        // upita po filmu). Za svaki film se hash trenutnog "${title}. $overview" poredi sa hash-om
        // sačuvanim uz keširan vektor - ako se poklapaju, embedding se NE računa ponovo (ušteda
        // Gemini poziva/lokalne inferencije na svaki restart). Ako se ne poklapaju (TMDB opis se
        // promenio) ili film uopšte nije keširan, računa se i odmah upisuje nazad u bazu.
        val cached = movieEmbeddingsRepository.getEmbeddingsForEngine(embedder.key)
        var reusedCount = 0

        val newEntries = coroutineScope {
            byTmdbId.entries.map { (tmdbId, rows) ->
                async {
                    try {
                        val detail = tmdbService.getMovieDetails(tmdbId) ?: return@async null
                        val overview = detail.overview?.trim().orEmpty()
                        if (overview.isEmpty()) return@async null

                        val sourceText = "${detail.title}. $overview"
                        val sourceHash = sha256(sourceText)
                        val cachedEntry = cached[tmdbId]

                        val embedding = if (cachedEntry != null && cachedEntry.sourceHash == sourceHash && cachedEntry.embedding.isNotEmpty()) {
                            reusedCount++
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
        println("[LOG] CinemaSemanticIndex (${embedder.label}) osvežen: ${newEntries.size}/${byTmdbId.size} filmova indeksirano (${reusedCount} iz DB keša, ${newEntries.size - reusedCount} novo izračunato).")
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
