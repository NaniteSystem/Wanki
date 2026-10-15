package app.wanki.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.wanki.scoring.ScoringConfig

/** User settings (SharedPreferences), observable from Compose. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var passThreshold by pref("passThreshold", 60f)
    var orderStrictness by pref("orderStrictness", 1f)
    var desiredRetention by pref("desiredRetention", 0.9f)
    /** Pause after the last stroke before a written answer is checked automatically. */
    var autoCheckDelayMs by pref("autoCheckDelayMs", 1500f)

    /** Languages tried when a notebook's language is "auto" (comma-separated codes). */
    var autoLanguages by stringPref("autoLanguages", "ru,en,ja")
    /** Translation target. */
    var translateTo by stringPref("translateTo", "ru")

    val autoLanguageList: List<String> get() = autoLanguages.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    fun scoringConfig() = ScoringConfig(
        passThreshold = passThreshold.toDouble(),
        orderStrictness = orderStrictness.toDouble(),
    )

    private fun stringPref(key: String, default: String) = object : kotlin.properties.ReadWriteProperty<Any?, String> {
        private val state = mutableStateOf(prefs.getString(key, default) ?: default)
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): String = state.value
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: String) {
            state.value = value
            prefs.edit().putString(key, value).apply()
        }
    }

    private fun pref(key: String, default: Float) = object : kotlin.properties.ReadWriteProperty<Any?, Float> {
        private val state = mutableStateOf(prefs.getFloat(key, default))
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): Float = state.value
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Float) {
            state.value = value
            prefs.edit().putFloat(key, value).apply()
        }
    }
}
