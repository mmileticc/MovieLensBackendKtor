package com.milinko.scraping.scraper

/**
 * Format hints cinemas put in the movie title, e.g. "OSTRVO SEĆANJA 3D (sinhronizovano)" or
 * "Ostrvo Sećanja (sinhro.)".
 */
private val FORMATS = listOf(
    Regex("""\b3D""", RegexOption.IGNORE_CASE) to "3D",
    Regex("""\b4DX""", RegexOption.IGNORE_CASE) to "4DX",
    Regex("""sinhro""", RegexOption.IGNORE_CASE) to "sinhronizovano",
    Regex("""titl""", RegexOption.IGNORE_CASE) to "titlovano",
)

/** "3D, sinhronizovano" from the title, or null when it says nothing about the format. */
internal fun formatFromTitle(title: String): String? =
    FORMATS.filter { (pattern, _) -> pattern.containsMatchIn(title) }.joinToString(", ") { it.second }.ifEmpty { null }
