package com.milinko.scraping.model

import kotlinx.serialization.SerialName

/** One movie at one cinema location, as found by a scraper (before and after TMDB matching). */
@kotlinx.serialization.Serializable
data class ScrapedMovie(
    val title: String,
    val originalTitle: String?,
    val cinemaName: String,         // e.g. "Cineplexx"
    val cinemaLocation: String,     // e.g. "CINEPLEXX 4D DELTA CITY"
    val latitude: Double?,          // for sorting cinemas by distance
    val longitude: Double?,         // for sorting cinemas by distance
    val bookingUrl: String,
    val isUpcoming: Boolean = false,
    @SerialName("tmdb_id")
    val tmdbId: Int? = null,
)