package com.milinko.db

import com.milinko.db.DatabaseFactory.dbQuery
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update

/**
 * Faza D dorada (23. avgust 2026, korisničko pitanje "zašto se ne bi odmah i embedovan rezultat
 * čuvao... jer trenutno svaki restart backenda potroši ~70 Gemini upita") - trajni keš embeding
 * vektora po (tmdbId, engine), da [com.milinko.services.CinemaSemanticIndex.refresh] ne mora da
 * ponovo embeduje SVAKI film na SVAKOM startu servera, samo one koji su stvarno novi/promenjeni.
 *
 * `engine` je isti string koji se koristi i za `?engine=` query parametar rute i za
 * [com.milinko.services.TextEmbedder.key] ("gemini"/"local") - jedan indeks (Gemini ili lokalni
 * MiniLM) ima svoj potpuno odvojen red po filmu, jer vektori različitih embeddera nisu uporedivi.
 *
 * `sourceHash` (SHA-256 heks nad "${title}. ${overview}") postoji da se INVALIDIRA keš ako TMDB
 * naslov/opis filma naknadno promeni tekst (redak slučaj, ali jeftino da se proveri) - ako se
 * hash ne poklapa sa poslednjim upisanim, tretira se kao da embedding uopšte ne postoji i ponovo
 * se računa. `embedding` je čuvan kao obična `text` kolona (zarezom razdvojeni float-ovi) - bez
 * dodatnog Postgres array/vector modula, isti pristup kao `genres` na MoviesTable (JSON u text
 * koloni) jer trenutna verzija Exposed-a (0.41.1) nema prirodnu podršku za array kolone.
 */
object MovieEmbeddingsTable : Table("movie_embeddings") {
    val tmdbId = integer("tmdb_id")
    val engine = varchar("engine", 32)
    val sourceHash = varchar("source_hash", 64)
    val embedding = text("embedding")
    val updatedAt = datetime("updated_at").clientDefault { Clock.System.now().toLocalDateTime(TimeZone.UTC) }
    override val primaryKey = PrimaryKey(tmdbId, engine, name = "movie_embeddings_pkey")
}

/** Jedan keširan red - vektor plus hash teksta od kog je izračunat (za detekciju promene). */
class CachedEmbedding(val sourceHash: String, val embedding: FloatArray)

private fun serializeEmbedding(embedding: FloatArray): String = embedding.joinToString(",")

private fun deserializeEmbedding(raw: String): FloatArray =
    if (raw.isBlank()) FloatArray(0) else raw.split(",").map { it.toFloat() }.toFloatArray()

class MovieEmbeddingsRepository {

    /**
     * Učitava SVE keširane embeding-e za dati engine JEDNIM upitom (isti obrazac kao
     * `ScrapedMoviesRepository.getAllScrapedMovies()`) - `CinemaSemanticIndex.refresh()` ovo zove
     * jednom na početku i posle samo poredi hash po filmu, umesto da radi upit po filmu.
     */
    suspend fun getEmbeddingsForEngine(engine: String): Map<Int, CachedEmbedding> = dbQuery {
        MovieEmbeddingsTable.select { MovieEmbeddingsTable.engine eq engine }
            .associate { row ->
                row[MovieEmbeddingsTable.tmdbId] to CachedEmbedding(
                    sourceHash = row[MovieEmbeddingsTable.sourceHash],
                    embedding = deserializeEmbedding(row[MovieEmbeddingsTable.embedding])
                )
            }
    }

    /** Upsert (update ako red već postoji za taj (tmdbId, engine) par, inače insert). */
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
