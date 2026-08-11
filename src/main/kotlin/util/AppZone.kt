package com.milinko.util

/**
 * MovieLens prati bioskope u Srbiji - "danas" za potrebe skrejpovanja/svežine baze treba da bude
 * beogradski kalendarski dan, ne UTC (UTC dan bi se menjao u 1-2h ujutru po lokalnom vremenu
 * umesto u ponoć, što ne odgovara stvarnom pojmu "danas" za korisnika).
 *
 * Zona je hardkodovana (namerno ne ZoneId.systemDefault()) jer backend nekad radi lokalno
 * (Beograd) a nekad na Render-u (kontejner obično radi u UTC-u) - ponašanje mora biti identično
 * na oba mesta, bez obzira gde je JVM pokrenut.
 *
 * Svi timestamp-ovi se i dalje čuvaju u bazi u UTC-u (dosledno ostatku šeme - vidi *Table.kt), ova
 * zona se koristi samo za izračunavanje "koji je datum danas" pri poređenju svežine podataka.
 */
val CINEMA_ZONE_ID: java.time.ZoneId = java.time.ZoneId.of("Europe/Belgrade")
val CINEMA_TIME_ZONE: kotlinx.datetime.TimeZone = kotlinx.datetime.TimeZone.of("Europe/Belgrade")
