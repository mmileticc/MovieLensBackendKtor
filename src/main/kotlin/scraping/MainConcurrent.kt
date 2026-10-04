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
 * DIJAGNOSTIČKI test - pokreće SVE aktivne skrapere (isti spisak kao u Application.kt
 * configureScrapingLifecycle) i samo ispisuje rezultat u konzolu. NAMERNO ne dira bazu
 * (nema ScrapedMoviesRepository/insertScrapedMovies poziva ovde) - bezbedno za ponavljano
 * pokretanje bez posledica po produkcione podatke, dok se ne utvrdi zašto CineGrand ne
 * vraća ništa poslednjih par ciklusa.
 *
 * Pokretanje: desni klik na ovaj fajl u IntelliJ-u -> "Run 'MainConcurrentKt'" (ili iz
 * terminala: ./gradlew run -PrunMainClass=com.milinko.scraping.MainConcurrentKt, ako je
 * takav task podešen - najlakše je ipak direktno iz IDE-a).
 *
 * Šta gledati u izlazu:
 * - Linije "[Engine] Završio <bioskop> za X sekundi. Nađeno: N" na kraju - ako je N=0 za
 *   CineGrand, gledaj linije IZNAD sa "[Cine Grand]" prefiksom (posebno "Greška pri
 *   skrapovanju..." poruke) - tu piše tačan razlog (timeout na koji selektor, HTTP greška...).
 * - Ako CineGrand padne na waitForSelector-u, u projektnom "scraper-debug/" folderu (pored
 *   working directory-ja - u IntelliJ-u to je obično koren MovieLensBackendKtor projekta)
 *   će se pojaviti .html i .png snimak stranice u tom trenutku - pošalji i te fajlove, ne
 *   samo tekst loga, to najbrže pokazuje da li je u pitanju promenjen selektor, Cloudflare/
 *   CAPTCHA blokada, ili nešto treće.
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