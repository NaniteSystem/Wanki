package app.wanki.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TapGesturesTest {
    private val slop = 10f
    private fun taps() = TapGestures(slopPx = slop)

    @Test
    fun twoFingerTap() {
        val g = taps()
        g.onDown(0, 100f, 100f, 1000)
        g.onDown(1, 200f, 100f, 1030)
        assertNull(g.onUp(0, 1120))
        assertEquals(Tap.TWO_FINGER, g.onUp(1, 1120))
    }

    @Test
    fun movingFingerIsNoTap() {
        val g = taps()
        g.onDown(0, 100f, 100f, 1000)
        g.onDown(1, 200f, 100f, 1030)
        g.onMove(1, 200f + 3 * slop, 100f)
        assertNull(g.onUp(0, 1120))
        assertNull(g.onUp(1, 1120))
    }

    @Test
    fun longContactIsNoTap() {
        val g = taps()
        g.onDown(0, 100f, 100f, 1000)
        g.onDown(1, 200f, 100f, 1030)
        assertNull(g.onUp(0, 1400))
        assertNull(g.onUp(1, 1400))
    }

    @Test
    fun threeFingerTap() {
        val g = taps()
        g.onDown(0, 100f, 100f, 1000)
        g.onDown(1, 200f, 100f, 1010)
        g.onDown(2, 300f, 100f, 1030)
        assertNull(g.onUp(0, 1120))
        assertNull(g.onUp(1, 1120))
        assertEquals(Tap.THREE_FINGER, g.onUp(2, 1120))
    }

    @Test
    fun oneFingerIsNoTap() {
        val g = taps()
        g.onDown(0, 100f, 100f, 1000)
        assertNull(g.onUp(0, 1100))
    }

    @Test
    fun nextGestureStartsClean() {
        val g = taps()
        g.onDown(0, 100f, 100f, 1000)
        g.onMove(0, 500f, 100f)
        assertNull(g.onUp(0, 1100))
        g.onDown(0, 100f, 100f, 2000)
        g.onDown(1, 200f, 100f, 2010)
        g.onUp(0, 2100)
        assertEquals(Tap.TWO_FINGER, g.onUp(1, 2100))
    }

    @Test
    fun resetDropsTheGesture() {
        val g = taps()
        g.onDown(0, 100f, 100f, 1000)
        g.onDown(1, 200f, 100f, 1010)
        g.reset()
        assertNull(g.onUp(0, 1100))
        assertNull(g.onUp(1, 1100))
    }
}
