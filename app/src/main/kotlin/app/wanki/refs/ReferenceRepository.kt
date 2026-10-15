package app.wanki.refs

import android.content.res.AssetManager
import app.wanki.scoring.CharReference
import app.wanki.scoring.kanjivg.KanjiVg
import java.util.concurrent.ConcurrentHashMap

/** Reference characters bundled as KanjiVG SVGs under assets/kanjivg (file name = code point in hex). */
class ReferenceRepository(private val assets: AssetManager) {
    private val cache = ConcurrentHashMap<String, CharReference>()

    val chars: List<String> by lazy {
        assets.list(DIR).orEmpty()
            .filter { it.endsWith(".svg") }
            .mapNotNull { it.removeSuffix(".svg").toIntOrNull(16) }
            .sorted()
            .map { String(Character.toChars(it)) }
    }

    fun get(char: String): CharReference? {
        if (char.codePointCount(0, char.length) != 1) return null
        cache[char]?.let { return it }
        val file = "$DIR/%05x.svg".format(char.codePointAt(0))
        val ref = runCatching { assets.open(file).use { KanjiVg.parse(it.reader().readText()) } }.getOrNull() ?: return null
        cache[char] = ref
        return ref
    }

    /**
     * Look-alikes to check the attempt against: recognizer candidates ranked above the target
     * plus the static [Confusables] table, limited to characters we have references for.
     */
    fun competitorsFor(target: String, candidates: List<String>?): List<CharReference> {
        val aboveTarget = candidates?.takeWhile { it != target }.orEmpty()
        return (aboveTarget + Confusables.of(target))
            .distinct()
            .filter { it != target }
            .mapNotNull(::get)
    }

    private companion object {
        const val DIR = "kanjivg"
    }
}

/** Characters that differ by stroke proportions only, which geometry must separate. */
object Confusables {
    private val groups = listOf(
        "土士", "末未", "日曰", "人入八", "己已巳", "千干于", "大犬太", "王玉主", "木本禾",
        "白百自", "田由甲申", "力刀", "右石", "左在", "天夫", "午牛", "矢失", "刀刃",
        "ぬめ", "ねれわ", "るろ", "はほ", "さち", "いり", "こに", "あお", "シツ", "ソン",
    )
    private val index: Map<String, List<String>> = buildMap {
        for (g in groups) {
            val cs = g.codePoints().toArray().map { String(Character.toChars(it)) }
            for (c in cs) put(c, (get(c).orEmpty() + cs.filter { it != c }).distinct())
        }
    }

    fun of(char: String): List<String> = index[char].orEmpty()
}
