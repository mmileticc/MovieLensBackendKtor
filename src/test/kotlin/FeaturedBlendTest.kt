package com.milinko

import com.milinko.db.GenreAffinity
import com.milinko.services.TmdbMovieMatch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeaturedBlendTest {

    private fun movie(id: Int) = TmdbMovieMatch(id = id, title = "Movie $id")

    private fun affinity(id: Int, avg: Double) = GenreAffinity(id, "Genre $id", avg, 5)

    @Test
    fun `stronger genre gets more slots and every genre gets at least two`() = runBlocking {
        val byGenre = mapOf(
            1 to (100..140).map(::movie),
            2 to (200..240).map(::movie),
            3 to (300..340).map(::movie)
        )
        val result = fetchBlendedByGenreAffinity(
            { genreId, _ -> byGenre.getValue(genreId) },
            listOf(affinity(1, 9.0), affinity(2, 6.0), affinity(3, 1.0)),
            page = 1,
            excludeTmdbIds = emptySet()
        )

        val counts = result.groupingBy { it.id!! / 100 }.eachCount()
        assertTrue(counts.getValue(1) > counts.getValue(2), "counts=$counts")
        assertTrue(counts.getValue(2) > counts.getValue(3), "counts=$counts")
        assertTrue(counts.getValue(3) >= 2, "counts=$counts")
        assertTrue(result.size <= 20, "size=${result.size}")
    }

    @Test
    fun `excluded and duplicate movies are skipped`() = runBlocking {
        val shared = movie(7)
        val result = fetchBlendedByGenreAffinity(
            { genreId, _ -> if (genreId == 1) listOf(shared, movie(1), movie(2)) else listOf(shared, movie(3)) },
            listOf(affinity(1, 8.0), affinity(2, 8.0)),
            page = 1,
            excludeTmdbIds = setOf(2L)
        )

        val ids = result.map { it.id }
        assertEquals(ids.distinct(), ids)
        assertTrue(2 !in ids)
        assertTrue(7 in ids && 1 in ids && 3 in ids, "ids=$ids")
    }

    @Test
    fun `movie left out of one genre quota is still available to the next genre`() = runBlocking {
        // Žanr 1 dobija samo 2 mesta (minimum), pa film 99 ne ulazi u njegovu kvotu - mora da
        // ostane dostupan žanru 2, gde je jedini kandidat.
        val result = fetchBlendedByGenreAffinity(
            { genreId, _ -> if (genreId == 1) listOf(movie(10), movie(11), movie(99)) else listOf(movie(99)) },
            listOf(affinity(1, 0.0), affinity(2, 0.0)),
            page = 1,
            excludeTmdbIds = emptySet()
        )

        assertEquals(listOf(10, 11, 99), result.map { it.id })
    }

    @Test
    fun `no genres gives empty result without calling TMDB`() = runBlocking {
        val result = fetchBlendedByGenreAffinity({ _, _ -> error("ne sme da se pozove") }, emptyList(), 1, emptySet())
        assertTrue(result.isEmpty())
    }
}
