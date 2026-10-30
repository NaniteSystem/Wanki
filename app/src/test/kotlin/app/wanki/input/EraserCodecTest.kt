package app.wanki.input

import kotlin.test.Test
import kotlin.test.assertEquals

class EraserCodecTest {
    @Test fun roundTripsEveryCombination() {
        for (m in EraserMode.entries) for (s in EraserSize.entries) {
            val e = EraserSettings(m, s)
            assertEquals(e, EraserSettings.decode(e.encode()))
        }
    }

    @Test fun defaultIsPartialMedium() {
        assertEquals("PARTIAL:M", EraserSettings().encode())
    }

    @Test fun unknownTextGivesTheDefault() {
        val d = EraserSettings()
        for (bad in listOf(null, "", "WHOLE", "BOGUS:M", "WHOLE:XL", "WHOLE:M:1", "partial:m")) assertEquals(d, EraserSettings.decode(bad), "for $bad")
    }

    @Test fun sizesAreEightSixteenThirtyTwoDp() {
        assertEquals(listOf(8f, 16f, 32f), EraserSize.entries.map { it.radiusDp })
    }
}
