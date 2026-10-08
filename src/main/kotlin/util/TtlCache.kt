package com.milinko.util

/**
 * Minimalan thread-safe in-memory keš sa TTL-om po unosu, bez spoljne zavisnosti.
 * Namenjen za jeftino keširanje TMDB odgovora (search/trending/detalji) da se ne gađa
 * upstream API za identične upite u kratkom vremenskom prozoru.
 *
 * Kad se dostigne [maxSize], izbacuje se najduže nekorišćen unos (LRU), a ne ceo keš - inače bi
 * svako punjenje keša pod opterećenjem poslalo sve naredne zahteve ka TMDB-u odjednom.
 */
class TtlCache<K, V>(private val ttlMillis: Long, private val maxSize: Int = 500) {
    private data class Entry<V>(val value: V, val expiresAt: Long)

    // accessOrder = true: get() pomera unos na kraj, pa je prvi u iteraciji najduže nekorišćen.
    private val map = object : LinkedHashMap<K, Entry<V>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Entry<V>>): Boolean = size > maxSize
    }

    @Synchronized
    fun get(key: K): V? {
        val entry = map[key] ?: return null
        if (System.currentTimeMillis() > entry.expiresAt) {
            map.remove(key)
            return null
        }
        return entry.value
    }

    @Synchronized
    fun put(key: K, value: V) {
        map[key] = Entry(value, System.currentTimeMillis() + ttlMillis)
    }
}
