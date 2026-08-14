package com.milinko.scraping.util

import com.microsoft.playwright.Page
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Snima screenshot i HTML sadržaj stranice u lokalni "scraper-debug/" folder (pored radnog
 * direktorijuma procesa) kad neki od Playwright scrapera padne pri navigaciji/waitForSelector-u.
 *
 * ZAŠTO OVO POSTOJI: bez ovoga, jedini trag greške u produkcionim logovima je jedna linija poput
 * "Timeout 15000ms exceeded" - što NIJE dovoljno da se utvrdi da li je sajt promenio HTML strukturu
 * (selektor više ne postoji), uveo bot-zaštitu (Cloudflare/CAPTCHA stranica umesto repertoara),
 * preusmerio na drugi domen, ili je bio stvarno privremeno nedostupan. Sledeći put kad neki scraper
 * (npr. CineGrand) padne, ovaj snimak pokazuje TAČNO šta je browser video u tom trenutku.
 *
 * Tiho ne radi ništa ako i sam snimak padne (npr. stranica/kontekst se već zatvorio) - ne sme da
 * sakrije originalnu grešku iza nove.
 */
fun dumpScraperDebugArtifacts(page: Page, cinemaName: String, stage: String) {
    try {
        val dir = File("scraper-debug")
        if (!dir.exists()) dir.mkdirs()

        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
        val safeName = cinemaName.replace(" ", "_").lowercase()
        val base = "${safeName}_${stage}_$timestamp"

        File(dir, "$base.html").writeText(page.content())
        page.screenshot(Page.ScreenshotOptions().setPath(File(dir, "$base.png").toPath()))

        println("[$cinemaName] [DEBUG] Sačuvan dijagnostički snimak: scraper-debug/$base.{html,png} (URL u tom trenutku: ${page.url()})")
    } catch (e: Exception) {
        println("[$cinemaName] [DEBUG] Nisam uspeo da sačuvam dijagnostički snimak: ${e.message}")
    }
}
