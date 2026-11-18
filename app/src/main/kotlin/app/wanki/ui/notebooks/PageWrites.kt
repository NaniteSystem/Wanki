package app.wanki.ui.notebooks

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay

/**
 * Writes of a page's stored ink and objects (an editor's saves, a move to another page, the undo of a move on a page
 * whose editor is closed). An editor that reads its page while one of them runs reads it again once it is done
 * ([mark] before the read, [unchangedSince] after it), so it never edits a copy from before the write and saves that
 * copy over it. Any thread.
 */
object PageWrites {
    private val running = ConcurrentHashMap<String, Int>()
    private val revs = ConcurrentHashMap<String, Long>()

    /** Runs [block], a write of the pages [ids], marking them as being written until it ends (also when it fails). */
    suspend fun <T> around(vararg ids: String, block: suspend () -> T): T {
        for (id in ids) running.merge(id, 1, Int::plus)
        try {
            return block()
        } finally {
            for (id in ids) {
                // the count goes after the revision, so a reader that sees no write running also sees the new revision
                revs.merge(id, 1L, Long::plus)
                running.computeIfPresent(id) { _, n -> if (n <= 1) null else n - 1 }
            }
        }
    }

    /** Taken before reading page [id]: the writes ended so far, or -1 when one is running (the read cannot be trusted). */
    fun mark(id: String): Long = (revs[id] ?: 0L).let { if (running.containsKey(id)) -1L else it }

    /** No write of page [id] ran or ended since [mark] returned [rev]: what was read then is still the stored page. */
    fun unchangedSince(id: String, rev: Long): Boolean = rev >= 0 && !running.containsKey(id) && (revs[id] ?: 0L) == rev

    /** Waits until no write of page [id] runs. */
    suspend fun awaitIdle(id: String) {
        while (running.containsKey(id)) delay(10)
    }
}
