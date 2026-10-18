package app.wanki.texts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChainTest {
    @Test fun buildsUpWithCues() = assertEquals(
        listOf(ChainStep(null, 0..0), ChainStep(0, 1..1), ChainStep(null, 0..1), ChainStep(1, 2..2), ChainStep(null, 0..2)),
        Chain.steps(3),
    )
    @Test fun emptyTextHasNoSteps() = assertTrue(Chain.steps(0).isEmpty())
}
