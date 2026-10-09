package com.milinko.util

/**
 * Small thread-safe in-memory cache with a per-entry TTL, used to avoid repeating identical TMDB
 * and OMDb requests within a short time.
 *
 * When [maxSize] is reached, the least recently used entry is evicted (LRU) rather than the whole
 * cache, so a full cache under load does not send every following request to TMDB at once.
 */
class TtlCache<K, V>(private val ttlMillis: Long, private val maxSize: Int = 500) {
    private data class Entry<V>(val value: V, val expiresAt: Long)

    // accessOrder = true: get() moves an entry to the end, so the first entry is the least recently used.
    private val map = object : LinkedHashMap<K, Entry<V>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Entry<V>>): Boolean = size > maxSize
    }

    /** The cached value, or null if missing or expired. */
    @Synchronized
    fun get(key: K): V? {
        val entry = map[key] ?: return null
        if (System.currentTimeMillis() > entry.expiresAt) {
            map.remove(key)
            return null
        }
        return entry.value
    }

    /** Stores [value] for [key] for the configured TTL. */
    @Synchronized
    fun put(key: K, value: V) {
        map[key] = Entry(value, System.currentTimeMillis() + ttlMillis)
    }
}
