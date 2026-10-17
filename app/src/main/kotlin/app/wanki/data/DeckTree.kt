package app.wanki.data

/** Per-deck numbers straight from the database: the deck's own cards, subdecks not included. */
data class DeckCardCounts(
    val deckId: String,
    /** New cards that are neither suspended nor buried. */
    val newCount: Int,
    /** Learning/relearning cards due now (with learn-ahead). */
    val learnCount: Int,
    /** Review cards due now. */
    val reviewCount: Int,
    /** All cards, suspended ones included. */
    val total: Int,
)

/** What has been studied today per deck, for the daily limits. */
data class DeckTodayCounts(val deckId: String, val newDone: Int, val reviewDone: Int)

/** A line of the deck list: counts include subdecks and respect the daily limits. */
data class DeckRow(
    val deck: DeckEntity,
    val depth: Int,
    val hasChildren: Boolean,
    val newCount: Int,
    val learnCount: Int,
    val reviewCount: Int,
    val total: Int,
) {
    val dueCount: Int get() = learnCount + reviewCount
}

data class DeckPath(val deck: DeckEntity, val path: String, val depth: Int)

/**
 * Decks as a tree (via [DeckEntity.parentId]) with the daily limits applied as in Anki: a deck's
 * limit caps everything studied from it, subdecks included, and each subdeck's own limit still
 * applies inside. Learning cards are never limited.
 */
class DeckTree(
    decks: List<DeckEntity>,
    counts: List<DeckCardCounts> = emptyList(),
    today: List<DeckTodayCounts> = emptyList(),
) {
    val byId: Map<String, DeckEntity> = decks.associateBy { it.id }
    private val counts = counts.associateBy { it.deckId }
    private val today = today.associateBy { it.deckId }

    /** Children sorted by name; decks whose parent is missing become roots. */
    private val children: Map<String?, List<DeckEntity>> = decks
        .groupBy { d -> d.parentId?.takeIf { it in byId && it != d.id } }
        .mapValues { (_, list) -> list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }) }

    val roots: List<DeckEntity> get() = children[null].orEmpty()

    fun children(id: String): List<DeckEntity> = children[id].orEmpty()

    /** The deck and all its descendants, depth first. */
    fun subtree(id: String): List<String> {
        if (id !in byId) return emptyList()
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        fun walk(d: String) {
            if (!seen.add(d)) return
            out += d
            children(d).forEach { walk(it.id) }
        }
        walk(id)
        return out
    }

    /** For the UI: "Parent › Child". */
    fun title(id: String): String = path(id).split("::").joinToString(" › ")

    /** "Parent::Child" names, as Anki shows them. */
    fun path(id: String): String {
        val names = ArrayList<String>()
        var cur = byId[id]
        val seen = HashSet<String>()
        while (cur != null && seen.add(cur.id)) {
            names += cur.name
            cur = cur.parentId?.let { byId[it] }
        }
        return names.asReversed().joinToString("::")
    }

    /** Whether [candidate] may become the parent of [deckId] (no cycles). */
    fun canBeParent(deckId: String, candidate: String?): Boolean =
        candidate == null || (candidate in byId && candidate !in subtree(deckId))

    /** How many of its own new cards each deck contributes when studying [rootId]. */
    fun newQuota(rootId: String): Map<String, Int> =
        quota(rootId, { counts[it]?.newCount ?: 0 }, { it.newPerDay }, { today[it]?.newDone ?: 0 })

    /** How many of its own due review cards each deck contributes when studying [rootId]. */
    fun reviewQuota(rootId: String): Map<String, Int> =
        quota(rootId, { counts[it]?.reviewCount ?: 0 }, { it.reviewsPerDay }, { today[it]?.reviewDone ?: 0 })

    private fun quota(rootId: String, own: (String) -> Int, limit: (DeckEntity) -> Int, done: (String) -> Int): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        val seen = HashSet<String>()
        fun take(id: String, cap: Int): Int {
            val d = byId[id] ?: return 0
            if (!seen.add(id)) return 0
            val doneHere = subtree(id).sumOf(done)
            var left = minOf(cap, (limit(d) - doneHere).coerceAtLeast(0))
            val start = left
            val mine = minOf(own(id), left)
            if (mine > 0) out[id] = mine
            left -= mine
            for (c in children(id)) {
                if (left <= 0) break
                left -= take(c.id, left)
            }
            return start - left
        }
        take(rootId, Int.MAX_VALUE)
        return out
    }

    fun row(id: String, depth: Int = 0): DeckRow? {
        val d = byId[id] ?: return null
        val ids = subtree(id)
        return DeckRow(
            deck = d,
            depth = depth,
            hasChildren = children(id).isNotEmpty(),
            newCount = newQuota(id).values.sum(),
            learnCount = ids.sumOf { counts[it]?.learnCount ?: 0 },
            reviewCount = reviewQuota(id).values.sum(),
            total = ids.sumOf { counts[it]?.total ?: 0 },
        )
    }

    /** The visible deck list: depth first, children of collapsed decks hidden. */
    fun rows(): List<DeckRow> {
        val out = ArrayList<DeckRow>()
        val seen = HashSet<String>()
        fun walk(d: DeckEntity, depth: Int) {
            if (!seen.add(d.id)) return
            row(d.id, depth)?.let { out += it }
            if (!d.collapsed) children(d.id).forEach { walk(it, depth + 1) }
        }
        roots.forEach { walk(it, 0) }
        return out
    }

    /** Every deck depth first with its full path, for pickers. */
    fun flat(): List<DeckPath> {
        val out = ArrayList<DeckPath>()
        val seen = HashSet<String>()
        fun walk(d: DeckEntity, depth: Int) {
            if (!seen.add(d.id)) return
            out += DeckPath(d, path(d.id), depth)
            children(d.id).forEach { walk(it, depth + 1) }
        }
        roots.forEach { walk(it, 0) }
        return out
    }
}
