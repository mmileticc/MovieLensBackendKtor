package com.milinko.scraping.scraper

import com.milinko.scraping.model.ScrapedShowtime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.nodes.Document
import java.text.Normalizer
import java.time.LocalDateTime

/**
 * MTS Dvorana and Cine Grand run the same Nuxt website platform ("happenings"). A movie page is
 * server-rendered and carries its data in `<script id="__NUXT_DATA__">`: the movie
 * (`happening-detail-*`: name, original name) and all its screenings (`happening-projections-*`:
 * location, hall, start time, format, free seats). Reading that JSON is much more reliable than the
 * rendered HTML, and needs no browser.
 */

/** One screening as the platform describes it. */
internal data class HappeningProjection(
    val id: Long,
    val locationName: String,
    val hallName: String?,
    val startTime: LocalDateTime,
    val format: String?,
    val availableSeats: Int?,
)

/** What a movie page says about the movie and its screenings. */
internal data class HappeningPage(
    val name: String?,
    val originalName: String?,
    val projections: List<HappeningProjection>,
)

/** Parses a movie page; null when it has no Nuxt data (e.g. the site changed). */
internal fun parseHappeningPage(doc: Document): HappeningPage? {
    val raw = doc.selectFirst("script#__NUXT_DATA__")?.data() ?: return null
    val payload = runCatching { Json.parseToJsonElement(raw).jsonArray }.getOrNull() ?: return null
    val root = NuxtPayload(payload).resolve(0) as? JsonObject ?: return null
    val data = root["data"] as? JsonObject ?: return null

    val detail = data.entries.firstOrNull { it.key.startsWith("happening-detail") }?.value.dataField() as? JsonObject
    val projections = (data.entries.firstOrNull { it.key.startsWith("happening-projections") }?.value.dataField() as? JsonArray)
        ?.mapNotNull { (it as? JsonObject)?.toProjection() }
        .orEmpty()
    return HappeningPage(detail.string("name"), detail.string("originalName"), projections)
}

/**
 * The page where exactly this screening is bought, built like the site does it:
 * `/u-bioskopu/{movie}/{location}/{hall}/{yyyy-MM-dd}-{HHmm}/{id}`.
 */
internal fun projectionUrl(baseUrl: String, movieName: String, projection: HappeningProjection): String {
    val start = projection.startTime
    val stamp = "%s-%02d%02d".format(start.toLocalDate(), start.hour, start.minute)
    val parts = listOf(slug(movieName), slug(projection.locationName), slug(projection.hallName), stamp, projection.id.toString())
        .filter { it.isNotEmpty() }
    return "$baseUrl/u-bioskopu/${parts.joinToString("/")}"
}

internal fun HappeningProjection.toShowtime(baseUrl: String, movieName: String) = ScrapedShowtime(
    startsAt = startTime.toString(),
    hall = hallName,
    format = format,
    bookingUrl = projectionUrl(baseUrl, movieName, this),
)

/** The site's own slug rule: no diacritics, lower case, everything else becomes "-". */
internal fun slug(text: String?): String = Normalizer.normalize(text.orEmpty(), Normalizer.Form.NFD)
    .replace(Regex("[\\u0300-\\u036f]"), "")
    .lowercase()
    .replace(Regex("[^a-z0-9]+"), "-")
    .trim('-')

private fun JsonObject.toProjection(): HappeningProjection? {
    val id = (this["id"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: return null
    val location = string("locationName") ?: return null
    val start = string("startTime")?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() } ?: return null
    val mediaType = this["mediaType"] as? JsonObject
    return HappeningProjection(
        id = id,
        locationName = location,
        hallName = string("hallName"),
        startTime = start.withSecond(0).withNano(0),
        format = mediaType.string("name"),
        availableSeats = (this["availableSeats"] as? JsonPrimitive)?.intOrNull,
    )
}

private fun JsonElement?.dataField(): JsonElement? = (this as? JsonObject)?.get("data")

private fun JsonObject?.string(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.ifEmpty { null }

/**
 * Nuxt serializes its state with "devalue": a flat array where objects and arrays refer to other
 * entries by index, and wrappers like `["Reactive", 5]` mark Vue types. This rebuilds the tree.
 */
private class NuxtPayload(private val items: JsonArray) {
    private val wrappers = setOf("Reactive", "ShallowReactive", "Ref", "ShallowRef", "EmptyRef", "NuxtError")

    fun resolve(index: Int, depth: Int = 0): JsonElement {
        // The data we read is a few levels deep; the limit also guards against reference cycles.
        if (depth > 40 || index !in items.indices) return JsonNull
        return when (val value = items[index]) {
            is JsonObject -> JsonObject(value.mapValues { (_, ref) -> ref(ref, depth) })
            is JsonArray -> {
                val tag = (value.firstOrNull() as? JsonPrimitive)?.takeIf { it.isString }?.content
                when {
                    tag in wrappers && value.size == 2 -> ref(value[1], depth)
                    tag == "Date" -> value.getOrNull(1) ?: JsonNull
                    tag == "Set" || tag == "Map" -> JsonNull
                    else -> JsonArray(value.map { ref(it, depth) })
                }
            }
            else -> value
        }
    }

    private fun ref(element: JsonElement, depth: Int): JsonElement =
        (element as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.let { resolve(it, depth + 1) } ?: element
}

/** Screenings from [now] up to the end of the [days]-day window starting at [from]. */
internal fun List<HappeningProjection>.inWindow(from: java.time.LocalDate, days: Int, now: LocalDateTime) =
    filter { !it.startTime.isBefore(now) && it.startTime.toLocalDate().isBefore(from.plusDays(days.toLong())) }
        .sortedBy { it.startTime }
