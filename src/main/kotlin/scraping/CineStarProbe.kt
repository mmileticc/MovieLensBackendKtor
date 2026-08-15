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
 * JEDNOKRATNA sonda za cinestarcinemas.rs (isti obrazac koji je otkrio pravu strukturu
 * cinegrand-mcf.rs - videti CineGrandProbe.kt). NAMERNO ne dira bazu, ne pretpostavlja ništa o
 * strukturi sajta unapred - samo snima HTML+screenshot za nekoliko ključnih stranica da bismo
 * mogli da napišemo tačan CineStarScraper.
 *
 * Za razliku od CineGrand-a (jedan agregatni "/u-bioskopu" spisak sa lokacijama PO FILMU), CineStar
 * ima zaseban URL PO BIOSKOPU (npr. "/novi-sad-big", "/zrenjanin-big" - nađeno pretragom), pa ne
 * znamo unapred da li repertoar treba čitati sa agregatne strane (grupisano po filmu) ili sa
 * pojedinačnih stranica bioskopa (grupisano po lokaciji) - zato snimamo OBA tipa stranice.
 *
 * Pokretanje: desni klik -> Run 'CineStarProbeKt' u IntelliJ-u.
 */
suspend fun main() {
    System.setOut(withContext(Dispatchers.IO) { PrintStream(System.out, true, "UTF-8") })
    System.setErr(withContext(Dispatchers.IO) { PrintStream(System.err, true, "UTF-8") })

    val baseUrl = "https://cinestarcinemas.rs"

    // Kandidati za "agregatni" spisak svih bioskopa/filmova - ne znamo tačan URL unapred, probamo
    // nekoliko razumnih pogodaka (nađenih pretragom ili uobičajenih za ovakve sajtove).
    val agregatniKandidati = listOf(
        "/program-svi-bioskopi",
        "/repertoar"
    )
    // Poznata pojedinačna stranica bioskopa (nađena pretragom) - da vidimo da li se repertoar
    // umesto agregatno organizuje PO LOKACIJI.
    val poznataLokacija = "/novi-sad-big"

    // Poznat link ka detalju filma (ista URL koju CineStarScraper.kt već čupa iz "a.poster-wrapper"
    // na stranici lokacije) - korisnik je primetio da postoji polje "Izvorni naslov" koje prva
    // verzija skrapera nije pokupila jer se ono NE nalazi na kartici filma na stranici lokacije
    // (samo na ovoj, zasebnoj detalj-stranici). Dodato da nađemo tačan selektor.
    val poznatDetaljFilma = "/novi-sad-big/hari-poter-i-kamen-mudrosti/6579"

    Playwright.create().use { playwright ->
        val browser = playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))
        val context = browser.newContext(Browser.NewContextOptions().setViewportSize(1920, 1080))
        val page = context.newPage()
        page.setExtraHTTPHeaders(mapOf("User-Agent" to MOVIE_LENS_USER_AGENT))

        var linkKaDetaljuFilma: String? = null

        // 1. Početna strana - da vidimo nav meni (linkove ka repertoaru/bioskopima) i opštu
        // strukturu (Nuxt/React/Angular/WordPress - šta god da je).
        try {
            println("[PROBE] Otvaram $baseUrl/ ...")
            page.navigate(baseUrl)
            page.waitForLoadState(LoadState.NETWORKIDLE)
            page.waitForTimeout(2500.0)
            dumpScraperDebugArtifacts(page, "CineStarProbe", "pocetna")
            println("[PROBE] Početna OK, URL posle navigacije: ${page.url()}")
        } catch (e: Exception) {
            println("[PROBE] Početna GREŠKA: ${e.message}")
            dumpScraperDebugArtifacts(page, "CineStarProbe", "pocetna-FAIL")
        }

        // 2. Agregatni kandidati
        for (putanja in agregatniKandidati) {
            try {
                println("[PROBE] Otvaram $baseUrl$putanja ...")
                page.navigate("$baseUrl$putanja")
                page.waitForLoadState(LoadState.NETWORKIDLE)
                page.waitForTimeout(2500.0)
                val stigliNa = page.url()
                val stigaoNaIstuPutanju = stigliNa.trimEnd('/').endsWith(putanja.trimEnd('/'))
                dumpScraperDebugArtifacts(page, "CineStarProbe", "agregat${putanja.replace("/", "-")}")
                println("[PROBE] $putanja -> stvarni URL posle navigacije: $stigliNa (poklapa se sa traženim: $stigaoNaIstuPutanju)")

                if (linkKaDetaljuFilma == null) {
                    linkKaDetaljuFilma = nadjiVerovatnLinkFilma(page.content(), baseUrl)
                    if (linkKaDetaljuFilma != null) {
                        println("[PROBE] Nađen mogući link ka detalju filma na $putanja: $linkKaDetaljuFilma")
                    }
                }
            } catch (e: Exception) {
                println("[PROBE] $putanja GREŠKA: ${e.message}")
                dumpScraperDebugArtifacts(page, "CineStarProbe", "agregat${putanja.replace("/", "-")}-FAIL")
            }
        }

        // 3. Poznata pojedinačna lokacija (da vidimo da li se repertoar organizuje po bioskopu)
        try {
            println("[PROBE] Otvaram $baseUrl$poznataLokacija ...")
            page.navigate("$baseUrl$poznataLokacija")
            page.waitForLoadState(LoadState.NETWORKIDLE)
            page.waitForTimeout(2500.0)
            dumpScraperDebugArtifacts(page, "CineStarProbe", "lokacija-novi-sad-big")
            println("[PROBE] $poznataLokacija OK, URL posle navigacije: ${page.url()}")

            if (linkKaDetaljuFilma == null) {
                linkKaDetaljuFilma = nadjiVerovatnLinkFilma(page.content(), baseUrl)
                if (linkKaDetaljuFilma != null) {
                    println("[PROBE] Nađen mogući link ka detalju filma na $poznataLokacija: $linkKaDetaljuFilma")
                }
            }
        } catch (e: Exception) {
            println("[PROBE] $poznataLokacija GREŠKA: ${e.message}")
            dumpScraperDebugArtifacts(page, "CineStarProbe", "lokacija-novi-sad-big-FAIL")
        }

        // 4. Detalj filma - ako generička heuristika ništa nije našla (očekivano, videli smo da
        // pravi obrazac "/<lokacija>/<slug>/<id>" nije u njenoj listi kandidata), koristimo poznat,
        // ručno potvrđen URL kao fallback.
        if (linkKaDetaljuFilma == null) {
            linkKaDetaljuFilma = baseUrl + poznatDetaljFilma
            println("[PROBE] Heuristika ništa nije našla - koristim poznat URL kao fallback: $linkKaDetaljuFilma")
        }

        run {
            try {
                println("[PROBE] Otvaram detalj filma: $linkKaDetaljuFilma ...")
                page.navigate(linkKaDetaljuFilma)
                page.waitForLoadState(LoadState.NETWORKIDLE)
                page.waitForTimeout(2500.0)
                dumpScraperDebugArtifacts(page, "CineStarProbe", "detalj-filma")
                println("[PROBE] Detalj filma OK, URL posle navigacije: ${page.url()}")
            } catch (e: Exception) {
                println("[PROBE] Detalj filma GREŠKA: ${e.message}")
                dumpScraperDebugArtifacts(page, "CineStarProbe", "detalj-filma-FAIL")
            }
        }

        context.close()
        browser.close()
    }

    println("\n[PROBE] Gotovo. Pogledaj scraper-debug/ folder - fajlovi počinju sa 'cinestarprobe_'.")
}

/**
 * Heuristika bez pretpostavki o tačnoj strukturi sajta - traži href atribute koji izgledaju kao
 * link ka pojedinačnom filmu/eventu (sadrže reč "film", "movie", "event" ili "happening" u putanji,
 * a NISU statične nav stranice kao "/kontakt", "/o-nama", "/program-svi-bioskopi" itd.). Vraća prvi
 * pogodak kao apsolutan URL, ili null ako ništa ne liči na to - u tom slučaju HTML snimak i dalje
 * ostaje dovoljan da se ručno pronađe pravi obrazac.
 */
private fun nadjiVerovatnLinkFilma(html: String, baseUrl: String): String? {
    val kandidatiRegex = listOf(
        Regex("href=\"(/film/[a-z0-9-]+)\""),
        Regex("href=\"(/movie/[a-z0-9-]+)\""),
        Regex("href=\"(/filmovi/[a-z0-9-]+)\""),
        Regex("href=\"(/event/[a-z0-9-]+)\""),
        Regex("href=\"(/events/[a-z0-9-]+)\""),
        Regex("href=\"(/happening[s]?/[a-z0-9-]+)\"")
    )
    for (regex in kandidatiRegex) {
        val match = regex.find(html)
        if (match != null) {
            return baseUrl + match.groupValues[1]
        }
    }
    return null
}
