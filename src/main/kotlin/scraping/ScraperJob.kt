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
 * The single list of active scrapers, used by this job and by Application.configureScrapingLifecycle
 * (SCRAPING_BOOLEAN=true), so a new cinema is added in one place.
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
 * Standalone daily scraping job (no Ktor server), run once a day by GitHub Actions
 * (.github/workflows/daily-scrape.yml) because Render's free plan cannot run Playwright.
 *
 * Flow: all scrapers in parallel -> TMDB id matching -> per-cinema replacement in `scraped_movies`
 * -> "watchlist movie now in cinemas" notifications -> report. The Render backend notices new data
 * by itself (configureSemanticIndexRefresh), so the job does not have to wake it.
 *
 * Exit code 1 if ANY cinema returned 0 movies (after the others were stored), so the run turns red
 * and an e-mail arrives as soon as a site changes its layout.
 *
 * Environment: DB_HOST, DB_PASSWORD (+ optional DB_PORT/DB_NAME/DB_USER), TMDB_API_KEY, optional
 * FIREBASE_SERVICE_ACCOUNT_JSON and SKIP_IF_FRESH. Local run: ./gradlew runScraperJob
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
    // exitProcess instead of returning from main: Playwright/Ktor client threads can keep the JVM alive.
    exitProcess(exitCode)
}

private suspend fun runScraperJob(): Int {
    val tmdbKey = System.getenv("TMDB_API_KEY")?.takeIf { it.isNotBlank() }
        ?: throw IllegalStateException("TMDB_API_KEY nije postavljen.")

    DatabaseFactory.init()
    val repository = ScrapedMoviesRepository()
    val tmdbService = TmdbService(apiKey = tmdbKey)

    val danasnjiDatum = LocalDate.now(CINEMA_ZONE_ID)

    // Backup scheduled runs (see daily-scrape.yml): GitHub sometimes drops scheduled runs when busy,
    // so later triggers only scrape if nothing was stored today yet.
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

    // insertScrapedMovies skips empty input and replaces only cinemas that returned movies - a
    // cinema whose site failed keeps yesterday's rows.
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
        // Notifications are not part of scraping itself - a failure must not fail the job.
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

// At most this many "movie from your watchlist is now in cinemas" notifications per user and run;
// otherwise the first run would send one for every watchlist movie that is already playing.
private const val MAX_CINEMA_NOTIFICATIONS_PER_USER = 3

/**
 * Notifies users when a movie on their watchlist starts playing (not "coming soon"). Each movie is
 * announced once per user (dedupe in the notifications table). Skipped when
 * FIREBASE_SERVICE_ACCOUNT_JSON is not set in CI.
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

/** Step output for the workflow (e.g. `skipped=true` so no e-mail is sent); GITHUB_OUTPUT exists only in CI. */
private fun writeGithubOutput(kljuc: String, vrednost: String) {
    val putanja = System.getenv("GITHUB_OUTPUT")?.takeIf { it.isNotBlank() } ?: return
    try {
        File(putanja).appendText("$kljuc=$vrednost\n")
    } catch (e: Exception) {
        logger.warn("[ScraperJob] Nije uspelo pisanje GITHUB_OUTPUT: ${e.message}")
    }
}

/**
 * The Markdown report goes to two places (each only when its environment variable is set, i.e. in CI):
 * - GITHUB_STEP_SUMMARY - the run's "Summary" tab on GitHub
 * - SCRAPE_REPORT_FILE - the file the workflow sends as the e-mail body after every run
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
