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
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("CinemaSemanticIndex")

// Same limit as NOW_IN_CINEMAS_TMDB_PARALLELISM in MovieRouting.kt.
private const val TMDB_PARALLELISM = 8

/**
 * In-memory semantic (vector) search over the movies currently in cinemas ("something funny for
 * tonight"). Limited to the current programme on purpose: it is small (tens to about a hundred
 * movies) and already in the database, while indexing the whole TMDB catalogue would be far out
 * of proportion.
 *
 * The whole index is replaced at once on [refresh] through a `@Volatile` reference to an immutable
 * list, so there is one writer (refresh) and many readers (searches) without locking.
 */
class CinemaSemanticIndex(
    private val scrapedMoviesRepository: ScrapedMoviesRepository,
    private val tmdbService: TmdbService,
    // Each index is bound to exactly one embedder: vectors of different embedders have different
    // dimensions and cannot be compared.
    private val embedder: TextEmbedder,
    private val movieEmbeddingsRepository: MovieEmbeddingsRepository
) {
    /** One indexed movie with its embedding. */
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
     * Rebuilds the index from the current `scraped_movies` table (the whole programme, not just the
     * 20 movies shown on Home). Movies without an overview are skipped (nothing to embed).
     * Embeddings are reused from [MovieEmbeddingsRepository] when the text has not changed.
     * A failure for one movie (TMDB or embedding error) only drops that movie.
     */
    suspend fun refresh() {
        val scraped = scrapedMoviesRepository.getAllScrapedMovies()
        val byTmdbId = scraped.filter { it.tmdbId != null }.groupBy { it.tmdbId!! }

        val cached = movieEmbeddingsRepository.getEmbeddingsForEngine(embedder.key)
        // Incremented from parallel coroutines, hence atomic.
        val reusedCount = AtomicInteger(0)
        // Without a limit, ~110 simultaneous TMDB calls on an empty cache end in timeouts and
        // some movies drop out of the index.
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
                        logger.warn("CinemaSemanticIndex: preskačem tmdbId=$tmdbId (${e.message})")
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }

        entries = newEntries
        logger.info("CinemaSemanticIndex (${embedder.label}) osvežen: ${newEntries.size}/${byTmdbId.size} filmova indeksirano (${reusedCount.get()} iz DB keša, ${newEntries.size - reusedCount.get()} novo izračunato).")
    }

    /** Hex SHA-256 of the embedded text (see MovieEmbeddingsTable.sourceHash). */
    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** False until the first [refresh] finishes (e.g. right after a deploy); callers then return an empty list. */
    fun isReady(): Boolean = entries.isNotEmpty()

    /** Embeds the query with the same embedder the index was built with and returns the closest movies. */
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
