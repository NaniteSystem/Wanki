package app.wanki.ink

import java.util.IdentityHashMap

/**
 * A per-instance cache (keys by identity) that keeps values only for keys that are live: in the owner's list now.
 * The owner reports its list on its own thread ([add], [remove], [reset]); other threads compute values from a
 * snapshot of that list ([getOrPut], [offer]). A value computed for a key that left the list meanwhile (a stroke
 * erased while a background conversion of an older snapshot ran) is handed back but never stored, so the cache
 * cannot regress to removed keys. A key may be in the list more than once; it stays live until its last copy goes.
 * Values may be null (remembered too: "cannot be made"). Thread-safe.
 */
class LiveCache<K : Any, V> {
    private val lock = Any()
    private val live = IdentityHashMap<K, Int>()
    private val values = IdentityHashMap<K, V>()

    /** One more copy of [k] is in the list. */
    fun add(k: K) {
        synchronized(lock) { live[k] = (live[k] ?: 0) + 1 }
    }

    /** One copy of [k] left the list; with the last one its value goes too. */
    fun remove(k: K) {
        synchronized(lock) {
            val n = live[k] ?: return
            if (n > 1) {
                live[k] = n - 1
            } else {
                live.remove(k)
                values.remove(k)
            }
        }
    }

    /** The list is now [keys]: values of keys not in it are dropped. */
    fun reset(keys: Collection<K>) {
        synchronized(lock) {
            live.clear()
            for (k in keys) live[k] = (live[k] ?: 0) + 1
            values.keys.retainAll(live.keys)
        }
    }

    fun isLive(k: K): Boolean = synchronized(lock) { live.containsKey(k) }

    /** Whether a value for [k] is stored. */
    fun has(k: K): Boolean = synchronized(lock) { values.containsKey(k) }

    /** The stored value for [k] (null also when none is stored: see [has]). */
    operator fun get(k: K): V? = synchronized(lock) { values[k] }

    /** The number of stored values. */
    val size: Int get() = synchronized(lock) { values.size }

    /**
     * The stored value for [k], or [make]'s (run outside the lock, so other keys are not held up), stored only if [k]
     * is live when it is done. If another thread stored one meanwhile, that one is returned.
     */
    @Suppress("UNCHECKED_CAST")
    fun getOrPut(k: K, make: () -> V): V {
        synchronized(lock) {
            if (values.containsKey(k)) return values[k] as V
        }
        val made = make()
        synchronized(lock) {
            if (values.containsKey(k)) return values[k] as V
            if (live.containsKey(k)) values[k] = made
        }
        return made
    }

    /** Stores [v] for [k] if [k] is live and has no value yet; true when stored. */
    fun offer(k: K, v: V): Boolean {
        synchronized(lock) {
            if (!live.containsKey(k) || values.containsKey(k)) return false
            values[k] = v
            return true
        }
    }
}
