package com.jarvis.assistant

import android.Manifest
import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import com.jarvis.assistant.accessibility.ScreenReader
import com.jarvis.assistant.agent.SensitiveActions
import com.jarvis.assistant.ai.AiMode
import com.jarvis.assistant.ai.AiRouter
import com.jarvis.assistant.ai.LocalServerManager
import com.jarvis.assistant.ai.TaskKind
import com.jarvis.assistant.memory.MemoryStore
import com.jarvis.assistant.runtime.EmbeddedRuntime
import com.jarvis.assistant.runtime.RuntimeSocketFactory
import com.jarvis.assistant.service.JarvisService
import com.jarvis.assistant.tasks.TaskControl
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.InetSocketAddress
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PrivacyBoundaryTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val settings = Settings(app)
    private val memory = MemoryStore.get(app)

    @Before fun reset() {
        app.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().clear().commit()
        memory.clearAll()
        JarvisService.resetForTest()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        settings.embeddedRuntimeEnabled = false
    }

    private fun request(service: JarvisService, task: String) = service.onStartCommand(
        Intent().setAction(JarvisService.ACTION_RUN).putExtra(JarvisService.EXTRA_TASK, task), 0, 1)

    @Test fun forgettingUsesTheLocalDeletionPath() {
        val controller = Robolectric.buildService(JarvisService::class.java).create()
        try {
            memory.addFact("my wifi name is test", false)
            request(controller.get(), "forget my wifi name")
            val end = System.nanoTime() + Duration.ofSeconds(3).toNanos()
            while (memory.search("wifi").isNotEmpty() && System.nanoTime() < end) Thread.sleep(10)
            assertTrue(memory.search("wifi").isEmpty())
            assertTrue(memory.history().isEmpty())
            assertEquals("forget my wifi name", JarvisService.lastTask)
            assertEquals(TaskControl.Cancel(null), TaskControl.parse("forget it"))
        } finally { controller.destroy() }
    }

    @Test fun cancelledLockedRequestNeverDeletesTheFactAfterUnlock() {
        val lock = shadowOf(app.getSystemService(KeyguardManager::class.java))
        lock.setKeyguardLocked(true)
        val controller = Robolectric.buildService(JarvisService::class.java).create()
        try {
            memory.addFact("my wifi name is test", false)
            request(controller.get(), "forget my wifi name")
            controller.get().onStartCommand(Intent().setAction(JarvisService.ACTION_STOP_TASK), 0, 2)
            lock.setKeyguardLocked(false)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertTrue(memory.search("wifi").isNotEmpty())
        } finally { controller.destroy() }
    }

    @Test fun offlineExcludesAllSavedServersEvenOnLoopback() {
        for (url in listOf("https://localhost/v1", "https://192.168.1.6/v1", "https://model.example/v1")) {
            settings.saveProfile(ModelProfile(name = url, providerId = "custom", baseUrl = url, apiKey = "", model = "test"))
        }
        settings.aiMode = AiMode.OFFLINE
        val router = AiRouter(app, settings, LocalServerManager(app, settings), { memory })
        for (kind in TaskKind.entries) assertTrue(router.plan("hello", kind).isEmpty())
    }

    @Test fun savedCredentialsRequireMatchingProviderAndOrigin() {
        val profile = ModelProfile(name = "Mine", providerId = "custom", baseUrl = "https://one.example/v1", apiKey = "dummy", model = "m")
        assertEquals("dummy", ModelProfile.fromJson(profile.toJson()).toConfig().apiKey)
        assertEquals("", ModelProfile.fromJson(profile.toJson().put("base", "https://two.example/v1")).toConfig().apiKey)
        assertEquals("", ModelProfile.fromJson(profile.toJson().put("provider", "openai")).toConfig().apiKey)
        assertEquals("", ModelProfile.fromJson(profile.toJson().also { it.remove("keyOrigin") }).toConfig().apiKey)
    }

    @Test fun screenInspectionWithholdsSensitiveTreesAndImagesDefaultOff() {
        assertFalse(settings.allowScreenshots)
        val root = AccessibilityNodeInfo.obtain()
        root.isPassword = true
        assertTrue(ScreenReader.containsSensitiveContent(root))
        root.isPassword = false
        root.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        assertTrue(ScreenReader.containsSensitiveContent(root))
    }

    @Test fun sensitiveAndUnidentifiedControlsRequireThePersonAtTheScreen() {
        assertTrue(SensitiveActions.canNavigate("Search", false))
        for (label in listOf("Send", "Pay now", "भेजें", "")) assertFalse(SensitiveActions.canNavigate(label, false))
        assertFalse(SensitiveActions.isYes("okay but change the number first"))
    }

    @Test fun runtimeWithoutOwnedChildCannotConnect() {
        assertEquals(com.jarvis.assistant.ai.ServerHealth.State.DOWN, EmbeddedRuntime.get(app).health())
        val factory = RuntimeSocketFactory { null }
        assertThrows(IOException::class.java) {
            factory.createSocket().connect(InetSocketAddress.createUnresolved("localhost", 80), 500)
        }
    }
}
