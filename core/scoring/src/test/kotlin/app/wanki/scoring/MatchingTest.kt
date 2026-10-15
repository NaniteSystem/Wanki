package app.wanki.scoring

import app.wanki.scoring.match.hungarian
import app.wanki.scoring.match.longestIncreasingSubsequence
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class MatchingTest {
    @Test
    fun `hungarian finds the optimum on random matrices`() {
        val r = Random(7)
        repeat(50) {
            val n = r.nextInt(1, 6)
            val m = n + r.nextInt(0, 3)
            val c = Array(n) { DoubleArray(m) { r.nextDouble() } }
            val a = hungarian(c)
            assertEquals(n, a.toSet().size)
            val got = a.indices.sumOf { c[it][a[it]] }
            assertEquals(bruteForce(c), got, 1e-9)
        }
    }

    private fun bruteForce(c: Array<DoubleArray>): Double {
        val m = c[0].size
        fun go(row: Int, used: Set<Int>): Double =
            if (row == c.size) 0.0 else (0 until m).filter { it !in used }.minOf { c[row][it] + go(row + 1, used + it) }
        return go(0, emptySet())
    }

    @Test
    fun `lis marks the out-of-order stroke`() {
        // strokes written as 0 1 3 2 4 → one of {2,3} is out of order
        assertEquals(4, longestIncreasingSubsequence(listOf(0, 1, 3, 2, 4)).size)
        // stroke 4 written first → only it is out of order
        assertEquals(setOf(1, 2, 3, 4), longestIncreasingSubsequence(listOf(4, 0, 1, 2, 3)))
    }
}
