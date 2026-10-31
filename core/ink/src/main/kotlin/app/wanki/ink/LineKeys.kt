package app.wanki.ink

/** Identity of a recognised line: the multiset of its strokes' content keys, so a line is recognised again only when its strokes change. */
object LineKeys {
    private const val OFFSET = -3750763034362895579L // FNV-1a 64-bit offset basis
    private const val PRIME = 1099511628211L

    /**
     * Order-independent: [strokeKeys] are sorted (duplicates stay, identical strokes share a key) and hashed with
     * FNV-1a over the bytes of each long.
     */
    fun key(strokeKeys: Collection<Long>): Long {
        var h = OFFSET
        for (k in strokeKeys.sorted()) {
            for (shift in 0 until 64 step 8) {
                h = (h xor ((k ushr shift) and 0xff)) * PRIME
            }
        }
        return h
    }
}
