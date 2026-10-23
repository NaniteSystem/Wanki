package app.wanki.data

import java.text.Collator
import java.util.Locale

/** How a list of notebooks, boards or notes is ordered. */
enum class ListSort { EDITED, TITLE, CREATED }

object ListOrder {
    private val collator: Collator = Collator.getInstance(Locale.forLanguageTag("ru")).apply {
        // secondary strength: case is ignored, «ё» still differs from «е»
        strength = Collator.SECONDARY
    }

    /** Pinned items first; then by title (case-insensitive) or newest first. Equal items keep their order. */
    fun <T> sort(
        items: List<T>,
        sort: ListSort,
        pinned: (T) -> Boolean,
        title: (T) -> String,
        edited: (T) -> Long,
        created: (T) -> Long,
    ): List<T> {
        // a Collator is not safe to share between threads
        val collator = collator.clone() as Collator
        val byPin = compareByDescending<T> { pinned(it) }
        val order: Comparator<T> = when (sort) {
            ListSort.EDITED -> byPin.thenByDescending { edited(it) }
            ListSort.CREATED -> byPin.thenByDescending { created(it) }
            ListSort.TITLE -> byPin.then { a, b -> collator.compare(title(a), title(b)) }
        }
        return items.sortedWith(order)
    }
}
