package dev.better.airplay.domain.model

/** Small LRU cache of artwork by track key, so revisiting a track (or a resent update) shows art instantly. */
class ArtworkCache(private val capacity: Int = 24) {
    private val map = object : LinkedHashMap<String, Artwork>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Artwork>?) = size > capacity
    }

    @Synchronized fun get(key: String): Artwork? = map[key]
    @Synchronized fun put(key: String, artwork: Artwork) { map[key] = artwork }

    companion object {
        fun keyOf(np: NowPlaying) = listOf(np.title, np.artist, np.album).joinToString("|") { it.orEmpty().trim().lowercase() }
    }
}
