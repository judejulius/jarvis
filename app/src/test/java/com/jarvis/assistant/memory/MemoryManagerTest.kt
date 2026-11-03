package com.jarvis.assistant.memory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoryManagerTest {

    private lateinit var memory: MemoryManager

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        memory = MemoryManager.get(context)
        memory.clearAll()
    }

    @Test
    fun savesAndResolvesAliases() {
        memory.saveAlias("yt", "YouTube")
        memory.saveAlias("insta", "Instagram")

        assertEquals("YouTube", memory.resolveAppAlias("yt"))
        assertEquals("Instagram", memory.resolveAppAlias("insta"))
        assertEquals("Chrome", memory.resolveAppAlias("Chrome"))
    }

    @Test
    fun savesAndRetrievesPreferences() {
        memory.savePreference("brevity", "prefers concise bullet answers")
        memory.saveFact("car", "Tesla Model 3")

        val all = memory.getAllMemories()
        assertEquals(2, all.size)

        val promptContext = memory.getPromptContext()
        assertTrue(promptContext.contains("USER PREFERENCES"))
        assertTrue(promptContext.contains("brevity"))
        assertTrue(promptContext.contains("Tesla Model 3"))
    }

    @Test
    fun savesAndRetrievesCorrections() {
        memory.saveCorrection("launch yt", "open YouTube")
        val action = memory.findCorrection("launch yt")
        assertEquals("open YouTube", action)
    }
}
