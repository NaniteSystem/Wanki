package app.wanki.texts

/** One chaining step: show line [cue] (if any), then recite the lines in [targets]. */
data class ChainStep(val cue: Int?, val targets: IntRange)

object Chain {
    /** Builds the text up line by line: each new line is cued by the previous one, then everything so far is recited. */
    fun steps(count: Int): List<ChainStep> = buildList {
        for (i in 0 until count) {
            add(ChainStep(if (i == 0) null else i - 1, i..i))
            if (i > 0) add(ChainStep(null, 0..i))
        }
    }
}
