package app.wanki.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.wanki.ui.common.SerialSaver
import app.wanki.ui.common.rememberGuardedBack
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class GuardedBackTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var gate = CompletableDeferred<Boolean>()
    @Volatile private var left = false
    private val saver = SerialSaver(scope) { if (!gate.await()) error("disk full") }

    private fun show() {
        rule.setContent { rememberGuardedBack(saver) { left = true } }
        rule.waitForIdle()
    }

    private fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    @Test
    fun backDuringFirstFailingSaveAsks() {
        show()
        saver.request()
        back()
        assertFalse("must wait for the running save", left)
        gate.complete(false)
        rule.waitUntilAtLeastOneExists(hasText("Изменения не сохранены"), 5_000)
        assertFalse(left)
    }

    @Test
    fun backDuringSucceedingSaveLeavesAfterIt() {
        show()
        saver.request()
        back()
        assertFalse(left)
        gate.complete(true)
        rule.waitUntil(5_000) { left }
    }

    @Test
    fun questionClosesWhenALaterSaveSucceeds() {
        show()
        saver.request()
        gate.complete(false)
        rule.waitUntil(5_000) { saver.error.value != null }
        back()
        rule.waitUntilAtLeastOneExists(hasText("Изменения не сохранены"), 5_000)
        gate = CompletableDeferred(true)
        saver.request()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Изменения не сохранены")).fetchSemanticsNodes().isEmpty() }
        assertFalse(left)
        back()
        rule.waitUntil(5_000) { left }
        assertTrue(left)
    }
}
