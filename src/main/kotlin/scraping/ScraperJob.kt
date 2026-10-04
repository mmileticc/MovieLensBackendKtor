package com.milinko.scraping

import com.milinko.db.DatabaseFactory
import com.milinko.db.ScrapedMoviesRepository
import com.milinko.scraping.model.ScrapedMovie
import com.milinko.scraping.scraper.ArenaCineplexScraper
import com.milinko.scraping.scraper.CineGrandScraper
import com.milinko.scraping.scraper.CineStarScraper
import com.milinko.scraping.scraper.CinemaScraper
import com.milinko.scraping.scraper.CineplexxScraper
import com.milinko.scraping.scraper.MtsDvoranaScraper
import com.milinko.scraping.scraper.RodaCineplexScraper
import com.milinko.scraping.scraper.TuckwoodCineplexScraper
import com.milinko.services.TmdbService
import com.milinko.services.enrichWithTmdbIds
import com.milinko.util.CINEMA_ZONE_ID
import scraping.ScrapingEngineConcurrent
import java.io.File
import java.io.PrintStream
import java.time.LocalDate
import kotlin.system.exitProcess

/**
 * Jedan zajednički spisak aktivnih skrepera - koristi ga i Application.kt
 * (configureScrapingLifecycle, kad je SCRAPING_BOOLEAN=true) i ovaj samostalni job, da se novi
 * bioskop dodaje na jednom mestu.
 */
fun sviAktivniScraperi(): List<CinemaScraper> = listOf(
    MtsDvoranaScraper(),
    CineplexxScraper(),
    ArenaCineplexScraper(),
    CineGrandScraper(),
    CineStarScraper(),
    RodaCineplexScraper(),
    TuckwoodCineplexScraper()
)

/**
 * Samostalni dnevni scraping job (bez Ktor servera) - pokreće ga GitHub Actions jednom dnevno
 * (.github/workflows/daily-scrape.yml), jer Render free plan ne može da izdrži Playwright.
 *
 * Tok: svi skreperi paralelno -> TMDB ID enrichment -> per-bioskop zamena u `scraped_movies`
 * (ScrapedMoviesRepository.insertScrapedMovies) -> izlaz. Uvek skrejpuje (nema provere "da li je
 * već danas skrejpovano" kao u configureScrapingLifecycle) - job se pokreće namerno.
 *
 * Render backend sam primećuje nove podatke (configureSemanticIndexRefresh periodično proverava
 * najnoviji created_at i osvežava semantički indeks), pa ovaj job ne mora da ga "budi".
 *
 * Izlazni kod je 1 ako je BILO KOJI bioskop vratio 0 filmova (posle upisa ostalih u bazu) - tako
 * GitHub Actions run postane crven i pošalje mejl čim neki sajt promeni strukturu, umesto da se
 * to primeti tek kad u aplikaciji nestane repertoar.
 *
 * Potrebne ENV promenljive: DB_HOST, DB_PASSWORD (+ opciono DB_PORT/DB_NAME/DB_USER), TMDB_API_KEY.
 * Pokretanje lokalno: ./gradlew runScraperJob
 */
suspend fun main() {
    System.setOut(PrintStream(System.out, true, "UTF-8"))
    System.setErr(PrintStream(System.err, true, "UTF-8"))

    val exitCode = try {
        runScraperJob()
    } catch (e: Throwable) {
        println("[ScraperJob] FATALNA greška: ${e.message}")
        e.printStackTrace()
        writeReport("## ❌ Scraping nije ni završen\n\nFatalna greška: `${e.message}`\n\nDetalji su u logu run-a.\n")
        2
    }
    // exitProcess umesto prirodnog kraja main-a - Playwright/Ktor client niti umeju da drže JVM živim.
    exitProcess(exitCode)
}

private suspend fun runScraperJob(): Int {
    val tmdbKey = System.getenv("TMDB_API_KEY")?.takeIf { it.isNotBlank() }
        ?: throw IllegalStateException("TMDB_API_KEY nije postavljen.")

    DatabaseFactory.init()
    val repository = ScrapedMoviesRepository()
    val tmdbService = TmdbService(apiKey = tmdbKey)

    val danasnjiDatum = LocalDate.now(CINEMA_ZONE_ID)
    val scraperi = sviAktivniScraperi()

    val skrapovaniFilmovi = ScrapingEngineConcurrent(scraperi).runAllScrapers(danasnjiDatum)

    println("[ScraperJob] Pretraga TMDB ID-jeva za ${skrapovaniFilmovi.size} filmova...")
    val obogaceniFilmovi = skrapovaniFilmovi.enrichWithTmdbIds(tmdbService)
    val saIdjem = obogaceniFilmovi.count { it.tmdbId != null }
    println("[ScraperJob] TMDB ID pronađen za $saIdjem od ${obogaceniFilmovi.size}")

    // insertScrapedMovies sam preskače brisanje ako je lista prazna, i menja samo bioskope koji
    // su nešto vratili - bioskop koji je pao zadržava jučerašnje redove.
    repository.insertScrapedMovies(obogaceniFilmovi)

    val poBioskopu = scraperi.associate { scraper ->
        scraper.cinemaName to obogaceniFilmovi.filter { it.cinemaName == scraper.cinemaName }
    }
    val prazni = poBioskopu.filterValues { it.isEmpty() }.keys

    println("\n=== REZIME ($danasnjiDatum) ===")
    poBioskopu.forEach { (ime, filmovi) ->
        println("- $ime: ${filmovi.size} filmova${if (filmovi.isEmpty()) "  ‼ NULA - proveri log iznad" else ""}")
    }
    writeReport(buildReport(danasnjiDatum, poBioskopu, saIdjem, obogaceniFilmovi.size))

    return if (prazni.isEmpty()) {
        println("[ScraperJob] Svi bioskopi OK.")
        0
    } else {
        println("[ScraperJob] Bioskopi bez rezultata: $prazni - ostali su upisani, job se označava kao neuspešan.")
        1
    }
}

/**
 * Markdown izveštaj ide na dva mesta (oba postoje samo kad su ENV promenljive postavljene, tj. u CI-ju):
 * - GITHUB_STEP_SUMMARY - "Summary" tab run-a na GitHub-u
 * - SCRAPE_REPORT_FILE - fajl koji workflow šalje kao telo mejla posle svakog run-a
 */
private fun writeReport(tekst: String) {
    listOf("GITHUB_STEP_SUMMARY", "SCRAPE_REPORT_FILE").forEach { envIme ->
        val putanja = System.getenv(envIme)?.takeIf { it.isNotBlank() } ?: return@forEach
        try {
            File(putanja).appendText(tekst)
        } catch (e: Exception) {
            println("[ScraperJob] Nije uspelo pisanje izveštaja u $envIme: ${e.message}")
        }
    }
}

private fun buildReport(
    datum: LocalDate,
    poBioskopu: Map<String, List<ScrapedMovie>>,
    saIdjem: Int,
    ukupno: Int
): String {
    return buildString {
        appendLine("## Scraping $datum")
        appendLine()
        appendLine("| Bioskop | Ukupno | Repertoar | Uskoro | Status |")
        appendLine("|---|---|---|---|---|")
        poBioskopu.forEach { (ime, filmovi) ->
            val uskoro = filmovi.count { it.isUpcoming }
            val status = if (filmovi.isEmpty()) "❌" else "✅"
            appendLine("| $ime | ${filmovi.size} | ${filmovi.size - uskoro} | $uskoro | $status |")
        }
        appendLine()
        appendLine("TMDB ID pronađen za **$saIdjem / $ukupno** zapisa.")
    }
}
