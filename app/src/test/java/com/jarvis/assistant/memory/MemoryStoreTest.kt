package com.jarvis.assistant.memory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MemoryStoreTest {
    private lateinit var store: MemoryStore

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        store = MemoryStore.get(ctx)
        store.clearAll()
    }

    @Test
    fun taughtAppNameIsFoundAgain() {
        assertNull(store.appAlias("yutub"))
        store.addAlias("YouTube", "com.google.android.youtube", "YouTube")
        assertEquals("com.google.android.youtube", store.appAlias("youtube"))
        // teaching the same phrase again replaces the old meaning
        store.addAlias("youtube", "com.other", "Other")
        assertEquals("com.other", store.appAlias("youtube"))
    }

    @Test
    fun notesAreSavedOnceAndFoundBySearch() {
        val id = store.addFact("My favourite colour is green", false)
        assertTrue(id > 0)
        assertEquals(id, store.addFact("my favourite colour is GREEN", false)) // same note, not duplicated
        assertEquals(1, store.all().count { it.kind == MemoryStore.Kind.FACT })
        assertEquals(listOf("My favourite colour is green"), store.search("what is my favourite colour").map { it.text })
        assertTrue(store.search("weather forecast").isEmpty())
    }

    @Test
    fun styleNotesAlwaysGoToThePrompt_otherNotesOnlyToLocalModels() {
        store.addFact("I prefer short answers", true)
        store.addFact("my wifi name is HomeNet", false)
        val local = store.promptContext("what is my wifi name", toLocal = true, shareWithCloud = false)
        assertTrue(local.contains("short answers"))
        assertTrue(local.contains("HomeNet"))
        val cloud = store.promptContext("what is my wifi name", toLocal = false, shareWithCloud = false)
        assertTrue(cloud.contains("short answers"))
        assertFalse(cloud.contains("HomeNet"))
        val sharedCloud = store.promptContext("what is my wifi name", toLocal = false, shareWithCloud = true)
        assertTrue(sharedCloud.contains("HomeNet"))
    }

    @Test
    fun editingAndForgetting() {
        val id = store.addFact("my locker code is on the fridge", false)
        store.updateFact(id, "my locker code is in the drawer")
        assertEquals(listOf("my locker code is in the drawer"), store.search("locker code").map { it.text })
        assertEquals(1, store.forget("locker"))
        assertTrue(store.all().isEmpty())
        assertEquals(0, store.forget("locker"))
    }

    @Test
    fun forgettingAnAppNameRemovesTheAlias() {
        store.addAlias("yt", "com.google.android.youtube", "YouTube")
        assertEquals(1, store.forget("yt"))
        assertNull(store.appAlias("yt"))
    }

    @Test
    fun everythingCanBeDeleted() {
        store.addAlias("yt", "com.google.android.youtube", "YouTube")
        store.addFact("remember me", false)
        assertEquals(2, store.all().size)
        store.clearAll()
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun oldestPlainNotesAreDroppedBeyondTheLimit() {
        store.addFact("I prefer short answers", true)
        repeat(MemoryStore.MAX_FACTS + 5) { store.addFact("note number $it about topic", false) }
        val facts = store.all().filter { it.kind == MemoryStore.Kind.FACT }
        assertEquals(MemoryStore.MAX_FACTS, facts.size)
        assertTrue(facts.any { it.title == "I prefer short answers" })
    }
}
