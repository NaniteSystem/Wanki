package app.wanki.ui.notebooks

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Writes of a page's stored ink and objects (an editor's saves, a move to another page, the undo of a move on a page
 * whose editor is closed). An editor that reads its page while one of them runs reads it again once it is done
 * ([read]), so it never edits a copy from before the write and saves that copy over it. Any thread.
 */
object PageWrites {
    private val running = ConcurrentHashMap<String, Int>()
    private val revs = ConcurrentHashMap<String, Long>()

    /** Pages marked as being written from [begin] until [end]. */
    class Hold internal constructor(private val ids: List<String>) {
        private val ended = AtomicBoolean()

        /** The write is over (done, failed or never started). Once: later calls do nothing. */
        fun end() {
            if (!ended.compareAndSet(false, true)) return
            for (id in ids) {
                // the count goes after the revision, so a reader that sees no write running also sees the new revision
                revs.merge(id, 1L, Long::plus)
                running.computeIfPresent(id) { _, n -> if (n <= 1) null else n - 1 }
            }
        }
    }

    /** Marks the pages [ids] as being written from now on, until the returned hold [ends][Hold.end]. */
    fun begin(vararg ids: String): Hold {
        val list = ids.distinct()
        for (id in list) running.merge(id, 1, Int::plus)
        return Hold(list)
    }

    /** Runs [block], a write of the pages [ids], marking them as being written until it ends (also when it fails). */
    suspend fun <T> around(vararg ids: String, block: suspend () -> T): T {
        val hold = begin(*ids)
        try {
            return block()
        } finally {
            hold.end()
        }
    }

    /**
     * Launches [block], a write of the pages [ids], in [scope]: they count as being written from this call, not from
     * when the coroutine starts, until it completes (also when it fails or is cancelled before it starts). An editor
     * opening one of them in between would otherwise read it from before the write and save that copy over it.
     */
    fun launchWrite(scope: CoroutineScope, vararg ids: String, block: suspend CoroutineScope.() -> Unit): Job {
        val hold = begin(*ids)
        return try {
            scope.launch(block = block).also { it.invokeOnCompletion { hold.end() } }
        } catch (e: Throwable) {
            hold.end()
            throw e
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

    /**
     * Where a change of page [id] goes (e.g. the undo of a move): the open editor [editor] gives (it waits for one still
     * loading and is null when none is open or can edit), or null once there is none and no write of the page runs,
     * e.g. the last save of an editor that has just closed, which would otherwise store its copy over the change. An
     * editor opened while that write ran is asked again (loading or loaded, the change goes through it). Null holds
     * until the caller next suspends: it writes the stored page inside [around] before that, so an editor opening later
     * reads the page after the write. Main thread.
     */
    suspend fun <E : Any> editorOrIdle(id: String, editor: suspend () -> E?): E? {
        while (true) {
            editor()?.let { return it }
            if (!running.containsKey(id)) return null
            awaitIdle(id)
        }
    }

    /** Reads page [id] with [block] while no write of it runs, again until no write ran or ended during the read. */
    suspend fun <T> read(id: String, block: suspend () -> T): T {
        while (true) {
            awaitIdle(id)
            val mark = mark(id)
            val result = block()
            if (unchangedSince(id, mark)) return result
        }
    }
}
