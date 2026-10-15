package com.milinko.scraping.scraper

import com.microsoft.playwright.Page
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val logger = LoggerFactory.getLogger("DebugSnapshots")

/** Folder (relative to the working directory) where failed pages are saved. */
const val DEBUG_SNAPSHOT_DIR = "scraper-debug"

/**
 * Saves what the browser saw when a page failed: `<cinema>-<label>-<time>.html` and `.png` in
 * [DEBUG_SNAPSHOT_DIR]. Usually shows at a glance whether a selector changed or the site blocked the
 * request. Never throws - a failing snapshot must not hide the original error.
 */
fun saveDebugSnapshot(page: Page, cinemaName: String, label: String) {
    try {
        val dir = File(DEBUG_SNAPSHOT_DIR).apply { mkdirs() }
        val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val baseName = "${cinemaName.lowercase().replace(Regex("[^a-z0-9]+"), "-")}-$label-$time"

        File(dir, "$baseName.html").writeText(page.content())
        page.screenshot(Page.ScreenshotOptions().setPath(Paths.get(dir.path, "$baseName.png")).setFullPage(true))

        logger.info("[$cinemaName] Saved a debug snapshot: ${dir.path}/$baseName.html/.png")
    } catch (e: Exception) {
        logger.warn("[$cinemaName] Could not save a debug snapshot: ${e.message}")
    }
}
