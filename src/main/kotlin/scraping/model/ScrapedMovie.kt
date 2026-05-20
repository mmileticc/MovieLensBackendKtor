package com.milinko.scraping.model

import java.time.Instant

@kotlinx.serialization.Serializable
data class ScrapedMovie(
    val title: String,
    val originalTitle: String?,
    val cinemaName: String,         // Npr. "Cineplexx"
    val cinemaLocation: String,     // Npr. "CINEPLEXX 4D DELTA CITY"
    val latitude: Double?,          // Dodato za geo-filtriranje
    val longitude: Double?,         // Dodato za geo-filtriranje
    val bookingUrl: String,
    //val scrapedAt: Instant = Instant.now(),
    val isUpcoming: Boolean = false
)