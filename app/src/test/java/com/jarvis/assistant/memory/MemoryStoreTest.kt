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

    @Test
    fun phraseCorrectionsAreStoredAndNeverMistakenForApps() {
        store.addPhraseCorrection("you too", "youtube")
        assertEquals(mapOf("you too" to "youtube"), store.phraseCorrections())
        assertNull(store.appAlias("you too")) // it is a text correction, not a package name
        store.addAlias("yt", "com.google.android.youtube", "YouTube")
        assertEquals(mapOf("you too" to "youtube"), store.phraseCorrections()) // app names are not corrections
        assertEquals(1, store.forget("you too"))
        assertTrue(store.phraseCorrections().isEmpty())
    }

    @Test
    fun replayPreservesOrderAndDoesNotAddStepsToModelContext() {
        val task = store.startTask("open maps", forContext = true)!!
        val second = store.startTask("increase volume", forContext = false)!!
        store.appendStep(task, "Opened Maps.")
        store.appendStep(second, "Changed volume.")
        store.appendStep(task, "Tapped the search button.")
        store.appendStep(task, "My password is sensitive.")
        store.finishTask(task, "Completed")
        store.finishTask(second, "Cancelled")
        store.appendStep(second, "Late callback")
        assertEquals(listOf("Received the command.", "Opened Maps.", "Tapped the search button.", "Completed"), store.steps(task))
        assertEquals(listOf("Received the command.", "Changed volume.", "Cancelled"), store.steps(second))
        assertTrue(store.history().first().detail.startsWith("Cancelled"))
        assertEquals(listOf("open maps"), store.relatedTasks("maps"))
        assertTrue(store.relatedTasks("volume").isEmpty())
        store.delete(MemoryStore.Kind.TASK, task)
        store.appendStep(task, "Late write")
        assertTrue(store.steps(task).isEmpty())
        store.clearHistory()
        assertTrue(store.steps(second).isEmpty())
    }

    @Test
    fun reopeningPreservesPartialStepsAndMarksAnUnfinishedTaskInterrupted() {
        val id = store.startTask("open settings", forContext = true)!!
        store.appendStep(id, "Opened Settings.")
        store.close()
        assertTrue(store.history().single().detail.startsWith("Interrupted"))
        assertEquals("Interrupted", store.steps(id).last())
        assertTrue(store.relatedTasks("settings").isEmpty())
    }

    @Test
    fun pruningAnOldTaskAlsoPrunesItsSteps() {
        val first = store.startTask("oldest request", false)!!
        store.appendStep(first, "First step.")
        repeat(MemoryStore.MAX_TASKS) { store.addTask("next request $it", true) }
        assertEquals(MemoryStore.MAX_TASKS, store.history().size)
        assertTrue(store.steps(first).isEmpty())
    }

    @Test
    fun disablingHistoryDiscardsNewCommandsAndTheirSteps() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val settings = com.jarvis.assistant.Settings(ctx)
        val history = com.jarvis.assistant.tasks.TaskHistory(ctx)
        settings.rememberTasks = false
        history.start(1, "private request", false)
        history.step(1, "Private step.")
        history.finish(1, "Completed")
        settings.rememberTasks = true
        history.start(2, "next request", false)
        history.finish(2, "Completed")
        val end = System.nanoTime() + 5_000_000_000L
        while (store.history().none { it.detail.startsWith("Completed") } && System.nanoTime() < end) Thread.sleep(10)
        assertEquals(listOf("next request"), store.history().map { it.title })
        assertEquals(listOf("Received the command.", "Completed"), store.steps(store.history().single().id))
    }

    @Test
    fun migratesTaskRowsThatPredateReplay() {
        android.database.sqlite.SQLiteDatabase.create(null).use { db ->
            db.execSQL("CREATE TABLE task(id INTEGER PRIMARY KEY, text TEXT NOT NULL, ok INTEGER NOT NULL, updated INTEGER NOT NULL)")
            db.execSQL("INSERT INTO task VALUES (1, 'open maps', 1, 23)")
            store.onUpgrade(db, 2, 3)
            db.rawQuery("SELECT text, ok, for_context FROM task", null).use {
                assertTrue(it.moveToFirst())
                assertEquals("open maps", it.getString(0))
                assertEquals(1, it.getInt(1))
                assertEquals(1, it.getInt(2))
            }
            db.rawQuery("SELECT * FROM task_step", null).use { assertEquals(0, it.count) }
        }
    }

    @Test
    fun earlierTasksAreFoundAndCanBeDeleted() {
        store.addTask("search for pizza places near me", true)
        store.addTask("open chrome", false)
        store.addTask("my otp is 123456 please remember", true) // never stored
        assertEquals(listOf("search for pizza places near me"), store.relatedTasks("pizza recommendations"))
        assertEquals(listOf("open chrome (did not work)"), store.relatedTasks("chrome"))
        assertTrue(store.relatedTasks("otp").isEmpty())
        val tasks = store.all().filter { it.kind == MemoryStore.Kind.TASK }
        assertEquals(2, tasks.size)
        store.delete(MemoryStore.Kind.TASK, tasks.first().id)
        assertEquals(1, store.all().count { it.kind == MemoryStore.Kind.TASK })
        store.clearAll()
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun taskHistoryIsOnlyShownToLocalModelsUnlessCloudSharingIsOn() {
        store.addTask("order biryani from the usual place", true)
        assertTrue(store.promptContext("biryani", toLocal = true, shareWithCloud = false).contains("biryani"))
        assertTrue(store.promptContext("biryani", toLocal = false, shareWithCloud = false).isEmpty())
        assertTrue(store.promptContext("biryani", toLocal = false, shareWithCloud = true).contains("biryani"))
    }
}
