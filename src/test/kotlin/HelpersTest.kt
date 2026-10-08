package com.milinko

import com.milinko.util.TtlCache
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HelpersTest {

    @Test
    fun `rating is rounded and clamped to 1-10`() {
        assertNull((null as Double?).toRatingIntOrNull())
        assertEquals(7, 7.4.toRatingIntOrNull())
        assertEquals(8, 7.5.toRatingIntOrNull())
        assertEquals(1, 0.2.toRatingIntOrNull())
        assertEquals(10, 12.0.toRatingIntOrNull())
    }

    @Test
    fun `haversine distance Belgrade to Novi Sad is about 70 km`() {
        val km = haversineKm(44.8176, 20.4569, 45.2671, 19.8335)
        assertTrue(km in 65.0..75.0, "km=$km")
        assertEquals(0.0, haversineKm(44.0, 20.0, 44.0, 20.0), 1e-9)
    }

    @Test
    fun `image type is detected from content, not from the name`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0)
        assertEquals("image/jpeg", detectImageMime(jpeg))
        assertEquals("image/png", detectImageMime(png))
        assertNull(detectImageMime("# README".toByteArray()))
        assertNull(detectImageMime(ByteArray(0)))
    }

    @Test
    fun `error messages are translated, unknown ones pass through`() {
        assertEquals("Movie not found", translateToEnglish("Film nije pronađen"))
        assertEquals("Username \"pera\" is already taken", translateToEnglish("Korisničko ime \"pera\" je već zauzeto"))
        assertEquals("Nešto novo", translateToEnglish("Nešto novo"))
    }

    @Test
    fun `cache evicts the least recently used entry when full`() {
        val cache = TtlCache<String, Int>(ttlMillis = 60_000, maxSize = 2)
        cache.put("a", 1)
        cache.put("b", 2)
        cache.get("a") // "a" je sada skorije korišćen od "b"
        cache.put("c", 3)

        assertEquals(1, cache.get("a"))
        assertNull(cache.get("b"))
        assertEquals(3, cache.get("c"))
    }

    @Test
    fun `expired cache entries are not returned`() {
        val cache = TtlCache<String, Int>(ttlMillis = -1)
        cache.put("a", 1)
        assertNull(cache.get("a"))
    }
}
