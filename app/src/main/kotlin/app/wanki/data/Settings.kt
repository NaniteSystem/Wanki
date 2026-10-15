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

    fun scoringConfig() = ScoringConfig(
        passThreshold = passThreshold.toDouble(),
        orderStrictness = orderStrictness.toDouble(),
    )

    private fun pref(key: String, default: Float) = object : kotlin.properties.ReadWriteProperty<Any?, Float> {
        private val state = mutableStateOf(prefs.getFloat(key, default))
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): Float = state.value
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Float) {
            state.value = value
            prefs.edit().putFloat(key, value).apply()
        }
    }
}
