package com.milinko.scraping

import com.milinko.db.DatabaseFactory
import com.milinko.db.DeviceTokensRepository
import com.milinko.db.FirebaseAdminInitializer
import com.milinko.db.NotificationsRepository
import com.milinko.db.UserActionsRepository
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
import com.milinko.services.PushService
import com.milinko.services.PushType
import com.milinko.services.TmdbService
import com.milinko.services.enrichWithTmdbIds
import com.milinko.util.CINEMA_ZONE_ID
import org.slf4j.LoggerFactory
import scraping.ScrapingEngineConcurrent
import java.io.File
import java.io.PrintStream
import java.time.LocalDate
import kotlin.system.exitProcess

private val logger = LoggerFactory.getLogger("ScraperJob")

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
        logger.error("[ScraperJob] FATALNA greška: ${e.message}", e)
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

    // Rezervni zakazani run (videti daily-scrape.yml) - GitHub ume da ODBACI zakazan run pri gužvi,
    // pa postoji drugi, kasniji termin koji radi samo ako glavni danas nije uspeo da upiše ništa.
    if (System.getenv("SKIP_IF_FRESH").equals("true", ignoreCase = true)) {
        val poslednjiScrape = repository.getLatestScrapeDate()
        if (poslednjiScrape == danasnjiDatum) {
            logger.info("[ScraperJob] Podaci su već od danas ($poslednjiScrape) - rezervni run preskače skrejpovanje.")
            writeGithubOutput("skipped", "true")
            return 0
        }
        logger.info("[ScraperJob] Poslednji scrape je od $poslednjiScrape - rezervni run preuzima dnevno skrejpovanje.")
    }
    val scraperi = sviAktivniScraperi()

    val skrapovaniFilmovi = ScrapingEngineConcurrent(scraperi).runAllScrapers(danasnjiDatum)

    logger.info("[ScraperJob] Pretraga TMDB ID-jeva za ${skrapovaniFilmovi.size} filmova...")
    val obogaceniFilmovi = skrapovaniFilmovi.enrichWithTmdbIds(tmdbService)
    val saIdjem = obogaceniFilmovi.count { it.tmdbId != null }
    logger.info("[ScraperJob] TMDB ID pronađen za $saIdjem od ${obogaceniFilmovi.size}")

    // insertScrapedMovies sam preskače brisanje ako je lista prazna, i menja samo bioskope koji
    // su nešto vratili - bioskop koji je pao zadržava jučerašnje redove.
    repository.insertScrapedMovies(obogaceniFilmovi)

    val poBioskopu = scraperi.associate { scraper ->
        scraper.cinemaName to obogaceniFilmovi.filter { it.cinemaName == scraper.cinemaName }
    }
    val prazni = poBioskopu.filterValues { it.isEmpty() }.keys

    logger.info("=== REZIME ($danasnjiDatum) ===")
    poBioskopu.forEach { (ime, filmovi) ->
        logger.info("- $ime: ${filmovi.size} filmova${if (filmovi.isEmpty()) "  ‼ NULA - proveri log iznad" else ""}")
    }
    writeReport(buildReport(danasnjiDatum, poBioskopu, saIdjem, obogaceniFilmovi.size))

    try {
        notifyWatchlistMoviesInCinemas(obogaceniFilmovi)
    } catch (e: Exception) {
        // Obaveštenja nisu deo samog scraping-a - greška ne sme da obori job.
        logger.warn("[ScraperJob] Obaveštenja o filmovima sa liste nisu poslata: ${e.message}")
    }

    return if (prazni.isEmpty()) {
        logger.info("[ScraperJob] Svi bioskopi OK.")
        0
    } else {
        logger.warn("[ScraperJob] Bioskopi bez rezultata: $prazni - ostali su upisani, job se označava kao neuspešan.")
        1
    }
}

// Najviše ovoliko obaveštenja "film sa liste je u bioskopu" po korisniku u jednom run-u - prvi run
// posle uvođenja bi inače poslao po jedno za svaki film sa liste koji već igra.
private const val MAX_CINEMA_NOTIFICATIONS_PER_USER = 3

/**
 * Korisnicima šalje obaveštenje kad film sa njihove liste za gledanje počne da igra (ne "uskoro").
 * Svaki film se javlja jednom po korisniku (dedupe u notifications tabeli). Preskače se ako
 * FIREBASE_SERVICE_ACCOUNT_JSON nije postavljen u CI-ju.
 */
private suspend fun notifyWatchlistMoviesInCinemas(filmovi: List<ScrapedMovie>) {
    if (System.getenv("FIREBASE_SERVICE_ACCOUNT_JSON").isNullOrBlank()) {
        logger.info("[ScraperJob] FIREBASE_SERVICE_ACCOUNT_JSON nije postavljen - obaveštenja o filmovima sa liste se preskaču.")
        return
    }
    FirebaseAdminInitializer.init()

    val playingIds = filmovi.filter { !it.isUpcoming && it.tmdbId != null }.map { it.tmdbId!!.toLong() }.toSet()
    val entries = UserActionsRepository().getWatchlistEntriesForMovies(playingIds)
    val pushService = PushService(DeviceTokensRepository(), NotificationsRepository())

    var sent = 0
    entries.groupBy({ it.first }, { it.second }).forEach { (userId, rows) ->
        var sentToUser = 0
        for (row in rows) {
            if (sentToUser >= MAX_CINEMA_NOTIFICATIONS_PER_USER) break
            val delivered = pushService.notify(
                userId = userId,
                type = PushType.WATCHLIST_IN_CINEMA,
                inboxText = "\"${row.title}\" sa tvoje liste sada igra u bioskopima",
                data = mapOf("tmdbId" to row.tmdbId.toString(), "movieTitle" to row.title),
                dedupeKey = "cinema:${row.tmdbId}"
            )
            if (delivered) {
                sentToUser++
                sent++
            }
        }
    }
    logger.info("[ScraperJob] Obaveštenja \"film sa liste je u bioskopu\": poslato $sent.")
}

/** Izlaz koraka za workflow (npr. `skipped=true` da se ne šalje mejl) - GITHUB_OUTPUT postoji samo u CI-ju. */
private fun writeGithubOutput(kljuc: String, vrednost: String) {
    val putanja = System.getenv("GITHUB_OUTPUT")?.takeIf { it.isNotBlank() } ?: return
    try {
        File(putanja).appendText("$kljuc=$vrednost\n")
    } catch (e: Exception) {
        logger.warn("[ScraperJob] Nije uspelo pisanje GITHUB_OUTPUT: ${e.message}")
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
            logger.warn("[ScraperJob] Nije uspelo pisanje izveštaja u $envIme: ${e.message}")
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
