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
 * JEDNOKRATNA sonda za tuck.rs (Tuckwood Cineplex, Kneza Miloša 7a, Beograd) - isti obrazac kao
 * CineGrandProbe.kt/CineStarProbe.kt. NAMERNO ne dira bazu.
 *
 * tuck.rs je (za razliku od Cine Grand/CineStar) klasičan WordPress sajt (WPBakery Page Builder,
 * potvrđeno preko istraživanja pre pisanja ove sonde - meta-generator taga u <head>-u), pa je
 * očekivano da je HTML sa karticama filmova prisutan odmah, bez čekanja na JS hidrataciju. I dalje
 * koristimo Playwright (ne prost Jsoup.connect() kao Arena/Roda) samo iz opreza - dok se ne
 * potvrdi da sajt zaista ne zahteva JS, sigurnije je proveriti sa punim browserom.
 *
 * Snima HTML+screenshot za /repertoar/ i /uskoro/ - iz markdown-konvertovanog teksta (bez CSS
 * klasa) se vidi da kartica filma ima: sliku, "Naslov / Originalni naslov" link, trajanje, kratak
 * opis, "Jezik:"/"Datum:"/"Žanr:"/"Glumac:"/"Režiser:" labele, i termine grupisane po danu i sali
 * (sale imaju imena glumaca - Humphrey Bogart, Lauren Bacall, itd.) sa "Rezerviši" linkom po
 * terminu - ali tačne CSS klase nisu poznate, otuda ova sonda.
 *
 * Pokretanje: desni klik -> Run 'TuckwoodProbeKt' u IntelliJ-u.
 */
suspend fun main() {
    System.setOut(withContext(Dispatchers.IO) { PrintStream(System.out, true, "UTF-8") })
    System.setErr(withContext(Dispatchers.IO) { PrintStream(System.err, true, "UTF-8") })

    val baseUrl = "https://www.tuck.rs"

    Playwright.create().use { playwright ->
        val browser = playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))
        val context = browser.newContext(Browser.NewContextOptions().setViewportSize(1920, 1080))
        val page = context.newPage()
        page.setExtraHTTPHeaders(mapOf("User-Agent" to MOVIE_LENS_USER_AGENT))

        var linkKaDetaljuFilma: String? = null

        // 1. /repertoar/ - tekući repertoar
        try {
            println("[PROBE] Otvaram $baseUrl/repertoar/ ...")
            page.navigate("$baseUrl/repertoar/")
            page.waitForLoadState(LoadState.NETWORKIDLE)
            page.waitForTimeout(2000.0)
            dumpScraperDebugArtifacts(page, "TuckwoodProbe", "repertoar")
            println("[PROBE] /repertoar/ OK, URL posle navigacije: ${page.url()}")

            // amy_movie je deo URL putanje ka detalju filma primećen ranije (npr.
            // "/amy_movie/blue-lock-buru-rokku/") - hvatamo prvi link tog oblika da posetimo
            // i jednu detalj-stranicu filma.
            val href = Regex("href=\"(https://www\\.tuck\\.rs/amy_movie/[a-z0-9-]+/)\"").find(page.content())?.groupValues?.get(1)
            if (href != null) {
                linkKaDetaljuFilma = href
                println("[PROBE] Nađen primer linka ka detalju filma: $href")
            }
        } catch (e: Exception) {
            println("[PROBE] /repertoar/ GREŠKA: ${e.message}")
            dumpScraperDebugArtifacts(page, "TuckwoodProbe", "repertoar-FAIL")
        }

        // 2. /uskoro/ - najavljeni filmovi
        try {
            println("[PROBE] Otvaram $baseUrl/uskoro/ ...")
            page.navigate("$baseUrl/uskoro/")
            page.waitForLoadState(LoadState.NETWORKIDLE)
            page.waitForTimeout(2000.0)
            dumpScraperDebugArtifacts(page, "TuckwoodProbe", "uskoro")
            println("[PROBE] /uskoro/ OK, URL posle navigacije: ${page.url()}")
        } catch (e: Exception) {
            println("[PROBE] /uskoro/ GREŠKA: ${e.message}")
            dumpScraperDebugArtifacts(page, "TuckwoodProbe", "uskoro-FAIL")
        }

        // 3. Jedna detalj-stranica filma, ako je nađena
        if (linkKaDetaljuFilma != null) {
            try {
                println("[PROBE] Otvaram detalj filma: $linkKaDetaljuFilma ...")
                page.navigate(linkKaDetaljuFilma)
                page.waitForLoadState(LoadState.NETWORKIDLE)
                page.waitForTimeout(2000.0)
                dumpScraperDebugArtifacts(page, "TuckwoodProbe", "detalj-filma")
                println("[PROBE] Detalj filma OK, URL posle navigacije: ${page.url()}")
            } catch (e: Exception) {
                println("[PROBE] Detalj filma GREŠKA: ${e.message}")
                dumpScraperDebugArtifacts(page, "TuckwoodProbe", "detalj-filma-FAIL")
            }
        } else {
            println("[PROBE] Nisam našao link ka detalju filma na /repertoar/ - nije kritično, HTML snimak te stranice je i dalje dovoljan da se nađe pravi selektor (kartice filma su verovatno već tu, ne treba nam detalj-stranica).")
        }

        context.close()
        browser.close()
    }

    println("\n[PROBE] Gotovo. Pogledaj scraper-debug/ folder - fajlovi počinju sa 'tuckwoodprobe_'.")
}
