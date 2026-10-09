package com.milinko.db

import com.milinko.db.DatabaseFactory.dbQuery
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update

/**
 * Persistent cache of embedding vectors per (movie, engine), so the semantic index does not have
 * to re-embed every movie on every server start (each Gemini embedding call costs quota).
 *
 * - `engine` is the [com.milinko.services.TextEmbedder.key] ("gemini" or "local"). Vectors from
 *   different engines live in separate rows because they are not comparable.
 * - `sourceHash` is the SHA-256 of the embedded text ("title. overview"). If TMDB changes the text,
 *   the hash no longer matches and the vector is recomputed.
 * - `embedding` is stored as comma-separated floats in a text column (no array/vector extension).
 */
object MovieEmbeddingsTable : Table("movie_embeddings") {
    val tmdbId = integer("tmdb_id")
    val engine = varchar("engine", 32)
    val sourceHash = varchar("source_hash", 64)
    val embedding = text("embedding")
    val updatedAt = datetime("updated_at").clientDefault { Clock.System.now().toLocalDateTime(TimeZone.UTC) }
    override val primaryKey = PrimaryKey(tmdbId, engine, name = "movie_embeddings_pkey")
}

/** A cached vector plus the hash of the text it was computed from (used to detect changes). */
class CachedEmbedding(val sourceHash: String, val embedding: FloatArray)

private fun serializeEmbedding(embedding: FloatArray): String = embedding.joinToString(",")

private fun deserializeEmbedding(raw: String): FloatArray =
    if (raw.isBlank()) FloatArray(0) else raw.split(",").map { it.toFloat() }.toFloatArray()

/** Reads and writes [MovieEmbeddingsTable]. */
class MovieEmbeddingsRepository {

    /**
     * Loads every cached embedding for [engine] in one query, keyed by TMDB id. The semantic index
     * calls this once per refresh and then only compares hashes per movie.
     */
    suspend fun getEmbeddingsForEngine(engine: String): Map<Int, CachedEmbedding> = dbQuery {
        MovieEmbeddingsTable.selectAll().where { MovieEmbeddingsTable.engine eq engine }
            .associate { row ->
                row[MovieEmbeddingsTable.tmdbId] to CachedEmbedding(
                    sourceHash = row[MovieEmbeddingsTable.sourceHash],
                    embedding = deserializeEmbedding(row[MovieEmbeddingsTable.embedding])
                )
            }
    }

    /** Inserts or updates the vector for (tmdbId, engine). */
    suspend fun upsertEmbedding(tmdbId: Int, engine: String, sourceHash: String, embedding: FloatArray) = dbQuery {
        val serialized = serializeEmbedding(embedding)
        val updatedRows = MovieEmbeddingsTable.update({
            (MovieEmbeddingsTable.tmdbId eq tmdbId) and (MovieEmbeddingsTable.engine eq engine)
        }) {
            it[MovieEmbeddingsTable.sourceHash] = sourceHash
            it[MovieEmbeddingsTable.embedding] = serialized
            it[MovieEmbeddingsTable.updatedAt] = Clock.System.now().toLocalDateTime(TimeZone.UTC)
        }
        if (updatedRows == 0) {
            MovieEmbeddingsTable.insert { row ->
                row[MovieEmbeddingsTable.tmdbId] = tmdbId
                row[MovieEmbeddingsTable.engine] = engine
                row[MovieEmbeddingsTable.sourceHash] = sourceHash
                row[MovieEmbeddingsTable.embedding] = serialized
            }
        }
    }
}
