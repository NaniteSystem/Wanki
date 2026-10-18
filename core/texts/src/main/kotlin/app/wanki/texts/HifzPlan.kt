package app.wanki.texts

/** Sabaq (new portion), sabqi (recent window) and manzil (older material in a cycle) for hifz texts. */
object HifzPlan {
    /** Pieces still in the sabqi window that are not yet counted today. */
    fun sabqi(text: PlanText, today: Long): List<String> =
        text.pieces
            .filter { it.learnedDay != null && it.sabqiLeft > 0 && it.learnedDay != today && it.hifzDay != today }
            .sortedBy { it.index }
            .map { it.id }

    /** Manzil pieces in rotation order: hifz texts by order, then piece index. */
    private fun manzilPieces(texts: List<PlanText>): List<String> =
        texts.filter { it.schedule == TextSchedule.HIFZ }
            .sortedBy { it.order }
            .flatMap { t -> t.pieces.filter { it.learnedDay != null && it.sabqiLeft == 0 }.sortedBy { it.index } }
            .map { it.id }

    /** Today's share of the manzil: ceil(n / cycleDays) pieces starting at [pointer], circular. */
    fun manzilChunk(texts: List<PlanText>, pointer: String?, cycleDays: Int): List<String> {
        val all = manzilPieces(texts)
        if (all.isEmpty()) return emptyList()
        val size = ((all.size + cycleDays - 1) / cycleDays).coerceIn(1, all.size)
        val start = all.indexOf(pointer).coerceAtLeast(0)
        return List(size) { all[(start + it) % all.size] }
    }

    /** The piece after the chunk's last one; where tomorrow's manzil starts. */
    fun nextPointer(texts: List<PlanText>, chunk: List<String>): String? {
        val all = manzilPieces(texts)
        if (all.isEmpty()) return null
        val last = chunk.lastOrNull()?.let { all.indexOf(it) } ?: -1
        return all[(last + 1) % all.size]
    }

    /** A piece just learned: it enters the sabqi window, first counted tomorrow. */
    fun learned(p: PlanPiece, today: Long, window: Int): PlanPiece =
        p.copy(learnedDay = today, sabqiLeft = window, hifzDay = null)

    fun afterSabqi(p: PlanPiece, error: Boolean, today: Long, window: Int): PlanPiece {
        if (p.hifzDay == today) return p
        val left = if (error) window else (p.sabqiLeft - 1).coerceAtLeast(0)
        return p.copy(sabqiLeft = left, hifzDay = today)
    }

    fun afterManzil(p: PlanPiece, error: Boolean, today: Long, window: Int): PlanPiece =
        p.copy(sabqiLeft = if (error) window else p.sabqiLeft, hifzDay = today)
}
