package com.milinko.scraping

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.LoadState
import com.milinko.scraping.agent.ScrapingConfig.MOVIE_LENS_USER_AGENT
import com.milinko.scraping.util.dumpScraperDebugArtifacts
import java.io.PrintStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * JEDNOKRATNA sonda (ne deo produkcionog koda, ne dira bazu) - cinegrand-mcf.rs je u međuvremenu
 * potpuno redizajniran (stari sajt: Angular, "event-card"/"repertoar" URL; novi: Nuxt, "u-bioskopu"/
 * "uskoro" URL, otkriveno iz prethodnog scraper-debug/ snimka koji je pao na starom "/repertoar"
 * URL-u sa 404 stranicom). Ova sonda BEZ USLOVA (uspeh ili pad) snima HTML+screenshot za tri
 * stranice da bismo mogli da napišemo tačne selektore za pravi CineGrandScraper:
 * 1. /u-bioskopu (verovatno novi "repertoar")
 * 2. /uskoro (direktan URL, ne treba više klik na navbar)
 * 3. jednu /happenings/<slug> detalj-stranicu (slug se čupa iz #1 ili #2 ako se nađe)
 *
 * Pokretanje: desni klik -> Run 'CineGrandProbeKt' u IntelliJ-u.
 */
suspend fun main() {
    System.setOut(withContext(Dispatchers.IO) { PrintStream(System.out, true, "UTF-8") })
    System.setErr(withContext(Dispatchers.IO) { PrintStream(System.err, true, "UTF-8") })

    val baseUrl = "https://cinegrand-mcf.rs"

    Playwright.create().use { playwright ->
        val browser = playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))
        val context = browser.newContext(Browser.NewContextOptions().setViewportSize(1920, 1080))
        val page = context.newPage()
        page.setExtraHTTPHeaders(mapOf("User-Agent" to MOVIE_LENS_USER_AGENT))

        var slugZaDetalje: String? = null

        // 1. /u-bioskopu
        try {
            println("[PROBE] Otvaram $baseUrl/u-bioskopu ...")
            page.navigate("$baseUrl/u-bioskopu")
            page.waitForLoadState(LoadState.NETWORKIDLE)
            page.waitForTimeout(2000.0)
            dumpScraperDebugArtifacts(page, "CineGrandProbe", "u-bioskopu")

            val href = Regex("href=\"(/happenings/[a-z0-9-]+)\"").find(page.content())?.groupValues?.get(1)
            if (href != null) {
                slugZaDetalje = href
                println("[PROBE] Nađen primer linka ka detalju filma: $href")
            }
            println("[PROBE] /u-bioskopu OK, URL posle navigacije: ${page.url()}")
        } catch (e: Exception) {
            println("[PROBE] /u-bioskopu GREŠKA: ${e.message}")
            dumpScraperDebugArtifacts(page, "CineGrandProbe", "u-bioskopu-FAIL")
        }

        // 2. /uskoro
        try {
            println("[PROBE] Otvaram $baseUrl/uskoro ...")
            page.navigate("$baseUrl/uskoro")
            page.waitForLoadState(LoadState.NETWORKIDLE)
            page.waitForTimeout(2000.0)
            dumpScraperDebugArtifacts(page, "CineGrandProbe", "uskoro")

            if (slugZaDetalje == null) {
                val href = Regex("href=\"(/happenings/[a-z0-9-]+)\"").find(page.content())?.groupValues?.get(1)
                if (href != null) {
                    slugZaDetalje = href
                    println("[PROBE] Nađen primer linka ka detalju filma (sa /uskoro): $href")
                }
            }
            println("[PROBE] /uskoro OK, URL posle navigacije: ${page.url()}")
        } catch (e: Exception) {
            println("[PROBE] /uskoro GREŠKA: ${e.message}")
            dumpScraperDebugArtifacts(page, "CineGrandProbe", "uskoro-FAIL")
        }

        // 3. Jedna /happenings/<slug> detalj-stranica, ako smo našli slug
        if (slugZaDetalje != null) {
            try {
                println("[PROBE] Otvaram detalj filma: $baseUrl$slugZaDetalje ...")
                page.navigate("$baseUrl$slugZaDetalje")
                page.waitForLoadState(LoadState.NETWORKIDLE)
                page.waitForTimeout(2000.0)
                dumpScraperDebugArtifacts(page, "CineGrandProbe", "detalj-filma")
                println("[PROBE] Detalj filma OK, URL posle navigacije: ${page.url()}")
            } catch (e: Exception) {
                println("[PROBE] Detalj filma GREŠKA: ${e.message}")
                dumpScraperDebugArtifacts(page, "CineGrandProbe", "detalj-filma-FAIL")
            }
        } else {
            println("[PROBE] Nisam našao nijedan /happenings/<slug> link ni na /u-bioskopu ni na /uskoro - proveri HTML snimke ručno.")
        }

        context.close()
        browser.close()
    }

    println("\n[PROBE] Gotovo. Pogledaj scraper-debug/ folder - fajlovi počinju sa 'cinegrandprobe_'.")
}
