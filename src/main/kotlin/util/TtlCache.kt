package com.milinko.util

import java.util.concurrent.ConcurrentHashMap

/**
 * Minimalan thread-safe in-memory keš sa TTL-om po unosu, bez spoljne zavisnosti.
 * Namenjen za jeftino keširanje TMDB odgovora (search/trending/detalji) da se ne gađa
 * upstream API za identične upite u kratkom vremenskom prozoru.
 *
 * Namerno jednostavan: kad se dostigne [maxSize], ceo keš se isprazni umesto prave LRU
 * evikcije — dovoljno za ovu skalu saobraćaja, bez dodatne biblioteke.
 */
class TtlCache<K, V>(private val ttlMillis: Long, private val maxSize: Int = 500) {
    private data class Entry<V>(val value: V, val expiresAt: Long)

    private val map = ConcurrentHashMap<K, Entry<V>>()

    fun get(key: K): V? {
        val entry = map[key] ?: return null
        if (System.currentTimeMillis() > entry.expiresAt) {
            map.remove(key)
            return null
        }
        return entry.value
    }

    fun put(key: K, value: V) {
        if (map.size >= maxSize) {
            map.clear()
        }
        map[key] = Entry(value, System.currentTimeMillis() + ttlMillis)
    }
}
