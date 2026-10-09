package com.milinko.util

/**
 * Time zone of the cinemas (Serbia). "Today" for scraping and data freshness must be the Belgrade
 * calendar day; the UTC day would change at 1-2 AM local time instead of at midnight.
 *
 * Hard-coded on purpose (not ZoneId.systemDefault()): the backend runs both locally and on Render,
 * where containers usually run in UTC, and must behave the same in both places.
 *
 * Timestamps are still stored in UTC; this zone is only used to work out the current date.
 */
val CINEMA_ZONE_ID: java.time.ZoneId = java.time.ZoneId.of("Europe/Belgrade")
/** Same zone as [CINEMA_ZONE_ID], for kotlinx-datetime APIs. */
val CINEMA_TIME_ZONE: kotlinx.datetime.TimeZone = kotlinx.datetime.TimeZone.of("Europe/Belgrade")
