package com.milinko.scraping
import com.milinko.scraping.scraper.ArenaCineplexScraper
import com.milinko.scraping.scraper.CineGrandScraper
import com.milinko.scraping.scraper.CineStarScraper
import com.milinko.scraping.scraper.CineplexxScraper
import com.milinko.scraping.scraper.MtsDvoranaScraper
import com.milinko.scraping.scraper.RodaCineplexScraper
import com.milinko.scraping.scraper.TuckwoodCineplexScraper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import scraping.ScrapingEngineConcurrent
import java.io.PrintStream
import java.time.LocalDate

/**
 * Diagnostic run of all active scrapers (the same list as the daily job) that only prints the
 * results. It never touches the database, so it is safe to run repeatedly.
 *
 * Run it from the IDE ("Run 'MainConcurrentKt'").
 *
 * In the output, check the "[Engine] Završio <cinema> ... Nađeno: N" lines at the end. If a
 * cinema found 0 movies, the lines above with that cinema's prefix give the reason (timeout on a
 * selector, HTTP error...). When a Playwright scraper fails on waitForSelector, an .html and .png
 * snapshot of the page is saved to `scraper-debug/` - it shows whether a selector changed or the
 * site blocked the request.
 */
suspend fun main() {
    System.setOut(withContext(Dispatchers.IO) {
        PrintStream(System.out, true, "UTF-8")
    })
    System.setErr(withContext(Dispatchers.IO) {
        PrintStream(System.err, true, "UTF-8")
    })
    println("=== DIJAGNOSTIČKI TEST SKRAPOVANJA (bez upisa u bazu) ===")

    val aktivniScraperi = listOf(
        MtsDvoranaScraper(),
        CineplexxScraper(),
        ArenaCineplexScraper(),
        CineGrandScraper(),
        CineStarScraper(),
        RodaCineplexScraper(),
        TuckwoodCineplexScraper()
    )

    val engine = ScrapingEngineConcurrent(aktivniScraperi)

    val danasnjiDatum = LocalDate.now()
    val listOfAllMovies = engine.runAllScrapers(danasnjiDatum)

    listOfAllMovies.forEach { film ->
        println("[FILM] [${if (film.isUpcoming) "UPCOMING" else "ACTIVE"}] ${film.title} | (${film.originalTitle ?: "Nema originalnog"}) Lokacija ${film.cinemaLocation} -> ${film.bookingUrl}")
    }

    println("\n=== REZIME PO BIOSKOPU (bez upisa u bazu - samo za dijagnostiku) ===")
    aktivniScraperi.forEach { scraper ->
        val broj = listOfAllMovies.count { it.cinemaName == scraper.cinemaName }
        val oznaka = if (broj == 0) "‼ NULA FILMOVA - proveri log iznad i scraper-debug/ folder" else "OK"
        println("- ${scraper.cinemaName}: $broj filmova [$oznaka]")
    }

    println("\n=== PROGRAM JE USPEŠNO ZAVRŠIO RAD (baza NIJE dirana) ===")
}