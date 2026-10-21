package app.wanki.input

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StylusTrackerTest {
    @AfterTest
    fun tearDown() = StylusTracker.onScreenChanged()

    @Test
    fun stylusStopsFingerDrawingUntilScreenChanges() {
        StylusTracker.onScreenChanged()
        assertTrue(StylusTracker.fingerDraws())
        StylusTracker.onStylusEvent(1_000L)
        assertFalse(StylusTracker.fingerDraws())
        // no time window: still pan-only long after the pen was last seen
        assertFalse(StylusTracker.fingerDraws())
        StylusTracker.onScreenChanged()
        assertTrue(StylusTracker.fingerDraws())
    }

    @Test
    fun reviewWindowKept() {
        StylusTracker.onStylusEvent(1_000L)
        assertTrue(StylusTracker.isStylusActive(3_000L))
        assertFalse(StylusTracker.isStylusActive(7_000L))
    }

    @Test
    fun palmByTouchMajor() {
        assertTrue(PalmFilter.isPalm(touchMajorPx = 300f, density = 2.625f)) // ~114 dp
        assertFalse(PalmFilter.isPalm(touchMajorPx = 60f, density = 2.625f)) // ~23 dp, a fingertip
    }
}
