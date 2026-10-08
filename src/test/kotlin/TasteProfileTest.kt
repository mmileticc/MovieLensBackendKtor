package com.milinko

import com.milinko.db.GenreAffinity
import com.milinko.db.compareTasteProfiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TasteProfileTest {

    private fun genre(id: Int, name: String, avg: Double, count: Int) = GenreAffinity(id, name, avg, count)

    @Test
    fun `no shared genres gives zero match`() {
        val result = compareTasteProfiles(
            listOf(genre(28, "Action", 9.0, 5)),
            listOf(genre(35, "Comedy", 8.0, 5))
        )
        assertEquals(0f, result.matchScore)
        assertTrue(result.commonGenres.isEmpty())
        assertTrue(result.sharedGenreIds.isEmpty())
    }

    @Test
    fun `genre backed by many ratings outranks a single perfect rating`() {
        // Fantasy: 10.0 - 1.5/sqrt(1) = 8.5; Action: 9.5 - 1.5/sqrt(4) = 8.75
        val self = listOf(genre(14, "Fantasy", 10.0, 1), genre(28, "Action", 9.5, 4))
        val friend = listOf(genre(14, "Fantasy", 10.0, 1), genre(28, "Action", 9.5, 4))

        val result = compareTasteProfiles(self, friend)

        assertEquals(listOf(28, 14), result.sharedGenreIds)
    }

    @Test
    fun `common genres only include genres both users actually like`() {
        val self = listOf(genre(28, "Action", 8.0, 4), genre(27, "Horror", 4.0, 4))
        val friend = listOf(genre(28, "Action", 7.0, 4), genre(27, "Horror", 9.0, 4))

        val result = compareTasteProfiles(self, friend)

        assertEquals(listOf("Action"), result.commonGenres)
        assertEquals(setOf(28, 27), result.sharedGenreIds.toSet())
    }

    @Test
    fun `match score stays within 0-100`() {
        val strong = listOf(genre(28, "Action", 10.0, 50))
        val score = compareTasteProfiles(strong, strong).matchScore
        assertTrue(score in 0f..100f, "score=$score")
    }
}
