package com.milinko.scraping.model

import kotlinx.serialization.SerialName

@kotlinx.serialization.Serializable
data class ScrapedMovie(
    val title: String,
    val originalTitle: String?,
    val cinemaName: String,         // Npr. "Cineplexx"
    val cinemaLocation: String,     // Npr. "CINEPLEXX 4D DELTA CITY"
    val latitude: Double?,          // Dodato za geo-filtriranje
    val longitude: Double?,         // Dodato za geo-filtriranje
    val bookingUrl: String,
    val isUpcoming: Boolean = false,
    @SerialName("tmdb_id")
    val tmdbId: Int? = null,
)