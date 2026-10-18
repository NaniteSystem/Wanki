package app.wanki.texts

/** Sabaq (new portion), sabqi (recent window) and manzil (older material in a cycle) for hifz texts. */
object HifzPlan {
    /** Pieces still in the sabqi window that are not yet counted today. */
    fun sabqi(text: PlanText, today: Long): List<String> =
        text.pieces
            .filter { it.learnedDay != null && it.sabqiLeft > 0 && it.learnedDay != today && it.hifzDay != today }
            .sortedBy { it.index }
            .map { it.id }

    /** Learned pieces of hifz texts in rotation order: texts by order, then piece index. */
    private fun learnedPieces(texts: List<PlanText>): List<PlanPiece> =
        texts.filter { it.schedule == TextSchedule.HIFZ }
            .sortedBy { it.order }
            .flatMap { t -> t.pieces.filter { it.learnedDay != null }.sortedBy { it.index } }

    /** Today's share of the manzil: ceil(n / cycleDays) pieces starting at [pointer], circular. */
    fun manzilChunk(texts: List<PlanText>, pointer: String?, cycleDays: Int, today: Long): List<String> {
        val all = learnedPieces(texts).filter { it.sabqiLeft == 0 && it.hifzDay != today }.map { it.id }
        if (all.isEmpty()) return emptyList()
        val days = cycleDays.coerceAtLeast(1)
        val size = ((all.size + days - 1) / days).coerceIn(1, all.size)
        val start = all.indexOf(pointer).coerceAtLeast(0)
        return List(size) { all[(start + it) % all.size] }
    }

    /**
     * The manzil piece after the chunk's last surviving piece; where tomorrow's manzil starts.
     * Works before or after ratings: a chunk piece sent back to sabqi still marks the position.
     */
    fun nextPointer(texts: List<PlanText>, chunk: List<String>): String? {
        val all = learnedPieces(texts)
        val manzil = all.filter { it.sabqiLeft == 0 }
        if (manzil.isEmpty()) return null
        val start = chunk.indices.reversed().map { i -> all.indexOfFirst { it.id == chunk[i] } }.firstOrNull { it >= 0 } ?: -1
        if (start < 0) return manzil.first().id
        val order = List(all.size) { all[(start + 1 + it) % all.size] }.filter { it.sabqiLeft == 0 }
        return (order.firstOrNull { it.id !in chunk } ?: order.first()).id
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
