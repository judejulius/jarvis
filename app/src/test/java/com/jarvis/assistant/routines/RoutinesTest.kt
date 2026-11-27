package com.jarvis.assistant.routines

import android.Manifest
import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.jarvis.assistant.R
import com.jarvis.assistant.Settings
import com.jarvis.assistant.ai.AiMode
import com.jarvis.assistant.service.JarvisService
import com.jarvis.assistant.ui.RoutinesActivity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoutinesTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val store = RoutineStore(app)

    @Before fun reset() {
        app.getSharedPreferences("personal_routines", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun presetAndCustomRoutinesPersistIncludingOrderAndDeletion() {
        assertEquals(listOf(Routine.study), store.all())
        val steps = listOf(RoutineStep.App("Notes", "test.notes"), RoutineStep.Timer(8), RoutineStep.Volume(40))
        val custom = Routine.create().copy(name = "On my break", steps = steps)
        assertTrue(store.save(custom))
        assertFalse(store.save(Routine.create().copy(name = "ON MY BREAK!", steps = steps)))
        assertThrows(IllegalArgumentException::class.java) { store.save(custom.copy(steps = List(6) { steps.first() })) }
        assertThrows(IllegalArgumentException::class.java) { store.save(custom.copy(steps = emptyList())) }
        assertTrue(store.save(custom.copy(steps = steps.reversed())))
        store.delete(Routine.study.id)
        assertEquals(listOf(custom.copy(steps = steps.reversed())), RoutineStore(app).all())
        store.delete(custom.id)
        assertTrue(RoutineStore(app).all().isEmpty())
    }

    @Test fun choosingToContinueNeverRepeatsEarlierSteps() {
        val steps = listOf(RoutineStep.Timer(5), RoutineStep.App("Notes", "missing.app"), RoutineStep.Volume(10))
        val seen = mutableListOf<RoutineStep>()
        val run = RoutineRun(Routine.study.copy(steps = steps))
        val paused = run.execute(app, { true }) { step, _ ->
            seen += step
            if (step is RoutineStep.App) "Unavailable" else null
        }
        assertEquals(steps.take(2), seen)
        assertTrue(paused.message.contains("Step 2 of 3"))
        val rest = requireNotNull(paused.remaining)
        rest.execute(app, { false }) { _, _ -> error("Cancelled routine performed a step") }
        val done = rest.execute(app, { true }) { step, _ -> seen += step; null }
        assertNull(done.remaining)
        assertFalse(done.success)
        assertEquals(steps, seen)
    }

    @Test fun editorPreservesDraftAcrossRotationAndSavesOnlyAfterPreview() {
        ActivityScenario.launch(RoutinesActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val root = activity.findViewById<ViewGroup>(R.id.content)
                button(root, "Study time").performClick()
                views(root).filterIsInstance<EditText>().single().setText("Reading")
                button(root, "2. Start a timer for 25 min").performClick()
                select("Move up")
                assertTrue(button(root, "1. Start a timer for 25 min").isShown)
                button(root, "Preview and save").performClick()
                pressDialogButton(AlertDialog.BUTTON_NEGATIVE)
                assertEquals(Routine.study, store.all().single())
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                val root = activity.findViewById<ViewGroup>(R.id.content)
                assertEquals("Reading", views(root).filterIsInstance<EditText>().single().text.toString())
                button(root, "Preview and save").performClick()
                pressDialogButton(AlertDialog.BUTTON_POSITIVE)
                assertEquals(listOf(RoutineStep.Timer(25), RoutineStep.Volume(20)), store.all().single().steps)
                button(root, "Create your own").performClick()
                views(root).filterIsInstance<EditText>().single().setText("Tea break")
                button(root, "Add action").performClick()
                select("Timer (minutes)")
                pressDialogButton(AlertDialog.BUTTON_POSITIVE)
                button(root, "Preview and save").performClick()
                pressDialogButton(AlertDialog.BUTTON_POSITIVE)
                assertEquals(listOf(RoutineStep.Timer(25)), store.all().last().steps)
            }
        }
    }

    @Test fun serviceRunsNamedRoutinesOfflineAndAcceptsTypedContinueOnlyOnce() {
        val steps = listOf(RoutineStep.App("Gone", "example.uninstalled"), RoutineStep.Volume(100))
        store.save(Routine.create().copy(name = "Focus time", steps = steps))
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        Settings(app).apply { aiMode = AiMode.OFFLINE; embeddedRuntimeEnabled = false }
        val audio = app.getSystemService(AudioManager::class.java)
        val stream = AudioManager.STREAM_MUSIC
        audio.setStreamVolume(stream, 0, 0)
        JarvisService.resetForTest()
        val controller = Robolectric.buildService(JarvisService::class.java).create()
        val service = controller.get()
        try {
            fun request(task: String) {
                service.onStartCommand(Intent().setAction(JarvisService.ACTION_RUN).putExtra(JarvisService.EXTRA_TASK, task), 0, 1)
            }
            request("FOCUS time!")
            until { JarvisService.lastResult?.contains("continue routine") == true }
            assertEquals(0, audio.getStreamVolume(stream))
            request("continue routine")
            until { JarvisService.lastResult?.contains("Skipped 1 step") == true }
            assertEquals(audio.getStreamMaxVolume(stream), audio.getStreamVolume(stream))
            request("run routine focus time")
            until { JarvisService.lastResult?.contains("continue routine") == true }
            audio.setStreamVolume(stream, 0, 0)
            service.onStartCommand(Intent().setAction(JarvisService.ACTION_STOP_TASK), 0, 2)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            request("continue routine")
            until { JarvisService.lastResult?.contains("no paused routine") == true }
            assertEquals(0, audio.getStreamVolume(stream))
            request("run routine focus time")
            until { JarvisService.lastResult?.contains("continue routine") == true }
            val keyguard = shadowOf(app.getSystemService(KeyguardManager::class.java))
            keyguard.setKeyguardLocked(true)
            app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            keyguard.setKeyguardLocked(false)
            request("continue routine")
            until { JarvisService.lastResult?.contains("no paused routine") == true }
            assertEquals(0, audio.getStreamVolume(stream))
        } finally { controller.destroy() }
    }

    @Test fun builtInCommandsKeepTheirMeaningWhenARoutineSharesTheirName() {
        val stream = AudioManager.STREAM_MUSIC
        val audio = app.getSystemService(AudioManager::class.java)
        val settings = Settings(app).apply { aiMode = AiMode.AUTO; embeddedRuntimeEnabled = false }
        store.save(Routine.create().copy(name = "Offline mode", steps = listOf(RoutineStep.Volume(100))))
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        audio.setStreamVolume(stream, 0, 0)
        val controller = Robolectric.buildService(JarvisService::class.java).create()
        try {
            fun request(task: String) = controller.get().onStartCommand(
                Intent().setAction(JarvisService.ACTION_RUN).putExtra(JarvisService.EXTRA_TASK, task), 0, 1)
            request("offline mode")
            until { settings.aiMode == AiMode.OFFLINE && JarvisService.lastTask == null }
            assertEquals(0, audio.getStreamVolume(stream))
            request("run routine offline mode")
            until { audio.getStreamVolume(stream) == audio.getStreamMaxVolume(stream) }
        } finally { controller.destroy() }
    }

    private fun until(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            Thread.sleep(10)
        }
        assertTrue("Last result: ${JarvisService.lastResult}", condition())
    }

    private fun views(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) yieldAll(views(root.getChildAt(i)))
    }

    private fun button(root: View, label: String) = views(root).filterIsInstance<TextView>().first { it.text.toString() == label }
    private fun dialog() = ShadowDialog.getLatestDialog() as AlertDialog
    private fun pressDialogButton(which: Int) {
        dialog().getButton(which).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun select(label: String) {
        val list = dialog().listView
        val index = (0 until list.adapter.count).first { list.adapter.getItem(it).toString() == label }
        list.performItemClick(null, index, list.adapter.getItemId(index))
        shadowOf(Looper.getMainLooper()).idle()
    }
}
