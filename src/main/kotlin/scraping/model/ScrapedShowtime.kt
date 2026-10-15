package com.milinko.scraping.model

import kotlinx.serialization.Serializable

/** One screening of a movie at one cinema location. */
@Serializable
data class ScrapedShowtime(
    /** Local start time in Belgrade, ISO-8601 without a zone, e.g. "2026-10-15T19:00". */
    val startsAt: String,
    /** Hall / screen as the cinema names it, e.g. "2" or "Sala 4". */
    val hall: String? = null,
    /** Projection format if the site says it, e.g. "3D", "4DX", "titlovano". */
    val format: String? = null,
    /** Ticket page of exactly this screening, when the site has one. */
    val bookingUrl: String? = null,
    /** Ticket price in dinars, when the site shows it. */
    val priceRsd: Int? = null,
)
