package com.milinko.scraping.scraper

import java.time.DateTimeException
import java.time.LocalDate

/** Month names (Latin script) by their first three letters: "okt" -> 10. */
private val MONTHS = listOf("jan", "feb", "mar", "apr", "maj", "jun", "jul", "avg", "sep", "okt", "nov", "dec")

private val DAY_MONTH_YEAR = Regex("""(\d{1,2})\.\s*([a-zA-ZčćšžđČĆŠŽĐ]+)\s*(\d{4})?""")
private val NUMERIC_DAY_MONTH = Regex("""(\d{1,2})\.(\d{1,2})\.(\d{4})?""")

/**
 * A date written the Serbian way, e.g. "15. oktobar 2026." or "15. okt". Null when the text is not
 * a date. A date without a year is placed by [dateNear].
 */
internal fun parseSerbianDate(text: String, reference: LocalDate): LocalDate? {
    val match = DAY_MONTH_YEAR.find(text) ?: return null
    val (day, monthName, year) = match.destructured
    val month = MONTHS.indexOf(monthName.lowercase().take(3)) + 1
    if (month == 0) return null
    return dateNear(day.toInt(), month, year.toIntOrNull(), reference)
}

/** A numeric date like "sreda 21.10." or "21.10.2026."; a date without a year is placed by [dateNear]. */
internal fun parseNumericDate(text: String, reference: LocalDate): LocalDate? {
    val match = NUMERIC_DAY_MONTH.find(text) ?: return null
    val (day, month, year) = match.destructured
    return dateNear(day.toInt(), month.toInt(), year.toIntOrNull(), reference)
}

/**
 * The date, or null if it does not exist. Without a year it is taken in the year of [reference], or
 * the next one if it would be more than 6 months back (a programme read around New Year).
 */
private fun dateNear(day: Int, month: Int, year: Int?, reference: LocalDate): LocalDate? = try {
    if (year != null) {
        LocalDate.of(year, month, day)
    } else {
        val sameYear = LocalDate.of(reference.year, month, day)
        if (sameYear.isBefore(reference.minusMonths(6))) sameYear.plusYears(1) else sameYear
    }
} catch (e: DateTimeException) {
    null
}
