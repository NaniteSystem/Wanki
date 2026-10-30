package app.wanki.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import app.wanki.input.InkSection
import app.wanki.input.ToolSelection
import app.wanki.input.decode
import app.wanki.input.encode
import app.wanki.scoring.ScoringConfig
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** User settings (SharedPreferences), observable from Compose. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var passThreshold by pref("passThreshold", 60f)
    var orderStrictness by pref("orderStrictness", 1f)
    var desiredRetention by pref("desiredRetention", 0.9f)
    /** Pause after the last stroke before a written answer is checked automatically. */
    var autoCheckDelayMs by pref("autoCheckDelayMs", 1500f)

    /** Languages tried when a notebook's language is "auto" (comma-separated codes). */
    var autoLanguages by pref("autoLanguages", "ru,en,ja")
    /** Translation target. */
    var translateTo by pref("translateTo", "ru")

    /** Read the answer aloud when it is shown. */
    var autoSpeak by pref("autoSpeak", false)
    /** "system", "light" or "dark". */
    var theme by pref("theme", "system")
    var reminderEnabled by pref("reminderEnabled", false)
    /** Daily reminder time, minutes after midnight. */
    var reminderMinutes by pref("reminderMinutes", 19 * 60)
    /** Last automatic or manual backup, epoch ms. */
    var lastBackupAt by pref("lastBackupAt", 0L)

    /** The one-time deletion of empty ink rows has run. */
    var emptyInkCleaned by pref("emptyInkCleaned", false)
    /** Epoch day the expired trash was last purged. */
    var trashPurgedDay by pref("trashPurgedDay", -1L)
    /** Why the last daily cleanup (trash purge, media GC) failed; "" = it did not. */
    var lastCleanupError by pref("lastCleanupError", "")

    /** Hifz: how many study days a newly learned piece stays in sabqi. */
    var sabqiDays by pref("sabqiDays", 7)
    /** Hifz: days one full manzil cycle over all learned pieces takes. */
    var manzilCycleDays by pref("manzilCycleDays", 14)
    /** Id of the piece the next manzil chunk starts from; "" = from the beginning. */
    var manzilPointer by pref("manzilPointer", "")
    /** Epoch day the last manzil chunk was finished. */
    var manzilDoneDay by pref("manzilDoneDay", -1L)

    private var typingFontRaw by pref("typingFontSp", 22)
    /** Text size in the «Печать» study mode, sp. */
    var typingFontSp: Int
        get() = typingFontRaw.coerceIn(TYPING_FONT_MIN, TYPING_FONT_MAX)
        set(v) { typingFontRaw = v.coerceIn(TYPING_FONT_MIN, TYPING_FONT_MAX) }

    /** The pen/marker/eraser choice last used in an ink section. */
    fun tool(section: InkSection): ToolSelection = ToolSelection.decode(prefs.getString("tool.${section.name}", null))
    fun setTool(section: InkSection, t: ToolSelection) { prefs.edit().putString("tool.${section.name}", t.encode()).apply() }

    private val listSorts = mutableStateMapOf<String, ListSort>()

    /** Sort order of a list: [SECTION_NOTEBOOKS], [SECTION_BOARDS] or [SECTION_NOTES]. */
    fun listSort(section: String): ListSort = listSorts[section]
        ?: runCatching { ListSort.valueOf(prefs.getString("listSort.$section", null) ?: "") }.getOrDefault(ListSort.EDITED)

    fun setListSort(section: String, sort: ListSort) {
        listSorts[section] = sort
        prefs.edit().putString("listSort.$section", sort.name).apply()
    }

    val autoLanguageList: List<String> get() = autoLanguages.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    fun scoringConfig() = ScoringConfig(
        passThreshold = passThreshold.toDouble(),
        orderStrictness = orderStrictness.toDouble(),
    )

    /** Every stored value, for backups. */
    fun export(): Map<String, *> = prefs.all

    @Suppress("UNCHECKED_CAST")
    private fun <T> pref(key: String, default: T): ReadWriteProperty<Any?, T> = object : ReadWriteProperty<Any?, T> {
        private val state = mutableStateOf(read(prefs, key, default))
        override fun getValue(thisRef: Any?, property: KProperty<*>): T = state.value
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            state.value = value
            prefs.edit().also { write(it, key, value) }.apply()
        }
    }

    companion object {
        const val NAME = "settings"
        const val SECTION_NOTEBOOKS = "notebooks"
        const val SECTION_BOARDS = "boards"
        const val SECTION_NOTES = "notes"
        const val TYPING_FONT_MIN = 16
        const val TYPING_FONT_MAX = 32

        @Suppress("UNCHECKED_CAST")
        private fun <T> read(prefs: SharedPreferences, key: String, default: T): T = runCatching {
            when (default) {
                is Float -> prefs.getFloat(key, default) as T
                is Int -> prefs.getInt(key, default) as T
                is Long -> prefs.getLong(key, default) as T
                is Boolean -> prefs.getBoolean(key, default) as T
                is String -> (prefs.getString(key, default) ?: default) as T
                else -> default
            }
        }.getOrDefault(default)

        fun write(editor: SharedPreferences.Editor, key: String, value: Any?) {
            when (value) {
                is Float -> editor.putFloat(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is String -> editor.putString(key, value)
                null -> editor.remove(key)
            }
        }
    }
}
