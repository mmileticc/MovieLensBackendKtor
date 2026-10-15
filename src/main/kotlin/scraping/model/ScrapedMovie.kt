package com.milinko.scraping.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One movie at one cinema location, as found by a scraper (before and after TMDB matching). */
@Serializable
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
    /** Screenings in the next [com.milinko.scraping.agent.ScrapingConfig.SHOWTIME_DAYS] days, sorted. */
    val showtimes: List<ScrapedShowtime> = emptyList(),
) {
    companion object {
        /**
         * [cinemaLocation] of a coming-soon movie that no cinema has scheduled yet. The apps show it
         * as is, hence Serbian here (the standalone scrapers repository uses "Coming soon").
         */
        const val NO_LOCATION = "Uskoro u bioskopima"
    }
}
