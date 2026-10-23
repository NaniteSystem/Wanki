package app.wanki.ui.common

/**
 * A small LRU of rendered thumbnails keyed by page id and its `updatedAt`: an edited page gets a
 * new stamp, so its old entry is never returned (it ages out).
 */
class ThumbnailCache<T : Any>(private val maxEntries: Int = 64) {
    private val map = object : LinkedHashMap<Pair<String, Long>, T>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, Long>, T>) = size > maxEntries
    }

    @Synchronized
    fun get(pageId: String, updatedAt: Long): T? = map[pageId to updatedAt]

    @Synchronized
    fun put(pageId: String, updatedAt: Long, value: T) { map[pageId to updatedAt] = value }
}
