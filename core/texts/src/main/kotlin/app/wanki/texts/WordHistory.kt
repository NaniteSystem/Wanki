package app.wanki.texts

enum class SampleLevel { SAMPLE, MEMORY, STRICT }

enum class WordOutcome { CORRECT, WRONG, SKIPPED }

/** Per-word history: `recent` holds the last 3 outcomes (bit 0 newest, 1 = error); `cue` is 0 none, 1 first letter, 2 whole word. */
data class WordStat(val cleanStreak: Int = 0, val recent: Int = 0, val cue: Int = 0)

enum class WordLook { SHOWN, HIDDEN, HIGHLIGHTED, NONE, CUE_FIRST, CUE_WORD, MARKED }

object WordHistory {
    /** A skipped word counts as an error. Every third clean run in a row lowers the cue one step. */
    fun after(stat: WordStat, outcome: WordOutcome): WordStat {
        val error = outcome != WordOutcome.CORRECT
        val recent = ((stat.recent shl 1) or (if (error) 1 else 0)) and 0b111
        if (error) return WordStat(0, recent, minOf(2, stat.cue + 1))
        val streak = stat.cleanStreak + 1
        val cue = if (streak % 3 == 0 && stat.cue > 0) stat.cue - 1 else stat.cue
        return WordStat(streak, recent, cue)
    }

    fun look(level: SampleLevel, stat: WordStat?, atCaret: Boolean, passed: Boolean): WordLook = when (level) {
        SampleLevel.SAMPLE -> when {
            stat == null -> WordLook.SHOWN
            Integer.bitCount(stat.recent) >= 2 -> WordLook.HIGHLIGHTED
            stat.cleanStreak >= 3 -> WordLook.HIDDEN
            else -> WordLook.SHOWN
        }
        SampleLevel.MEMORY -> when {
            stat == null || !atCaret -> WordLook.NONE
            stat.cue == 1 -> WordLook.CUE_FIRST
            stat.cue >= 2 -> WordLook.CUE_WORD
            else -> WordLook.NONE
        }
        SampleLevel.STRICT ->
            if (stat != null && stat.recent != 0 && passed) WordLook.MARKED else WordLook.NONE
    }

    fun countsAsHint(look: WordLook): Boolean = look == WordLook.CUE_FIRST || look == WordLook.CUE_WORD
}
