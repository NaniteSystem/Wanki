package app.wanki.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
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

    /** Hifz: how many study days a newly learned piece stays in sabqi. */
    var sabqiDays by pref("sabqiDays", 7)
    /** Hifz: days one full manzil cycle over all learned pieces takes. */
    var manzilCycleDays by pref("manzilCycleDays", 14)
    /** Id of the piece the next manzil chunk starts from; "" = from the beginning. */
    var manzilPointer by pref("manzilPointer", "")
    /** Epoch day the last manzil chunk was finished. */
    var manzilDoneDay by pref("manzilDoneDay", -1L)

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
