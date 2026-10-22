package app.wanki.texts

import app.wanki.srs.Rating

data class PieceRun(val words: Int, val correct: Int, val errors: Int, val hints: Int, val withSample: Boolean)

/** Rating of a typed run: under 60% correct is Again, errors/hints/sample cap at Hard, Easy only by the button after a clean run. */
object TypingScore {
    fun clean(run: PieceRun): Boolean = run.errors == 0 && run.hints == 0 && !run.withSample

    fun rating(run: PieceRun, easy: Boolean): Rating = when {
        run.correct * 10 < run.words * 6 -> Rating.AGAIN
        !clean(run) -> Rating.HARD
        easy -> Rating.EASY
        else -> Rating.GOOD
    }
}
