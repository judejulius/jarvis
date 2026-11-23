package com.jarvis.assistant.ui

import android.content.Context
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.jarvis.assistant.Settings
import com.jarvis.assistant.memory.MemoryStore
import com.jarvis.assistant.memory.TaskReplayDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TaskReplayTest {
    @Test fun stepsCanBeRevisitedAndRotationKeepsThePosition() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        Settings(ctx).onboarded = true
        val store = MemoryStore.get(ctx)
        store.clearAll()
        val id = store.startTask("open maps", true)!!
        store.appendStep(id, "Opened Maps.")
        store.finishTask(id, "Completed")
        val item = store.history().single()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val replay = TaskReplayDialog.forTask(item)
                replay.showNow(activity.supportFragmentManager, "replay")
                val dialog = replay.requireDialog() as AlertDialog
                awaitSteps(dialog)
                val next = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                val prev = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                assertFalse(prev.isEnabled)
                next.performClick()
                assertEquals("Step 2 of 3\n\nOpened Maps.", message(dialog))
                next.performClick()
                assertFalse(next.isEnabled)
                prev.performClick()
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                val replay = activity.supportFragmentManager.findFragmentByTag("replay") as TaskReplayDialog
                val dialog = replay.requireDialog() as AlertDialog
                awaitSteps(dialog)
                assertEquals("Step 2 of 3\n\nOpened Maps.", message(dialog))
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
                assertFalse(dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled)
                assertEquals(1, store.history().size)
            }
        }
    }

    private fun message(dialog: AlertDialog) = dialog.findViewById<TextView>(android.R.id.message)!!.text.toString()

    private fun awaitSteps(dialog: AlertDialog) {
        val end = System.nanoTime() + 5_000_000_000L
        while (message(dialog) == "Loading steps…" && System.nanoTime() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue(message(dialog), message(dialog).startsWith("Step "))
    }
}
