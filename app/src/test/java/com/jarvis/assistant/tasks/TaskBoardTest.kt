package com.jarvis.assistant.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskBoardTest {
    private var now = 0L
    private val board = TaskBoard({ now })

    @Test fun tracksTheLifeOfATask() {
        val t = board.start(1, "open chrome and search cats", TaskType.PHONE)
        assertEquals(TaskState.RUNNING, t.state)
        assertEquals(t, board.foreground())
        board.finish(1, true, "Done")
        assertEquals(TaskState.DONE, t.state)
        assertNull(board.foreground())
        board.start(2, "x", TaskType.CHAT); board.finish(2, false, "failed")
        assertEquals(TaskState.FAILED, board.get(2)?.state)
    }

    @Test fun aNewScreenOrChatTaskReplacesTheForegroundOne() {
        val a = board.start(1, "open chrome and search cats", TaskType.PHONE)
        val d = Interrupt.forNewTask(TaskType.PHONE, board.foreground())
        assertEquals(a, d.cancel)
        assertNull(d.note)
        assertEquals(a, Interrupt.forNewTask(TaskType.CHAT, board.foreground()).cancel)
    }

    @Test fun instantCommandsNeverCancelAnything() {
        board.start(1, "long answer", TaskType.CHAT)
        assertNull(Interrupt.forNewTask(TaskType.INSTANT, board.foreground()).cancel)
        assertNull(Interrupt.forNewTask(TaskType.PHONE, null).cancel)
    }

    @Test fun cancellingATaskThatAlreadyActedDoesNotPretendItWasUndone() {
        val a = board.start(1, "text mum", TaskType.PHONE)
        board.markIrreversible(1)
        val d = Interrupt.forNewTask(TaskType.PHONE, board.foreground())
        assertTrue(d.note!!.contains("can't undo"))
        val out = board.cancel(1)!!
        assertTrue(out.message().contains("can't undo"))
        assertEquals(TaskState.CANCELLED, a.state)
        assertEquals("That was already finished.", board.cancel(1)!!.message())
        board.finish(1, true, "late result") // a cancelled task stays cancelled
        assertEquals(TaskState.CANCELLED, a.state)
    }

    @Test fun cancellingAnUntouchedTaskIsSimple() {
        board.start(1, "x", TaskType.CHAT)
        assertEquals("Cancelled.", board.cancel(1)!!.message())
        assertNull(board.cancel(99))
    }

    @Test fun onlyAnswersCanGoToTheBackground() {
        board.start(1, "write a long story", TaskType.CHAT)
        board.start(2, "open whatsapp", TaskType.PHONE)
        assertFalse(board.moveToBackground(2))
        assertTrue(board.moveToBackground(1))
        assertEquals(board.get(2), board.foreground()) // the screen task is still the one in front
        board.finish(2, true, "ok")
        assertNull(board.foreground()) // background task is not "foreground"
        assertTrue(board.get(1)!!.running)
    }

    @Test fun staleChecksKeepBackgroundAndInstantTasksAlive() {
        board.start(1, "story", TaskType.CHAT); board.moveToBackground(1)
        board.start(2, "volume up", TaskType.INSTANT)
        board.start(3, "open youtube", TaskType.PHONE)
        assertFalse(board.isStale(1, foregroundId = 3))
        assertFalse(board.isStale(2, foregroundId = 3))
        assertFalse(board.isStale(3, foregroundId = 3))
        board.start(4, "open chrome", TaskType.PHONE)
        assertTrue(board.isStale(3, foregroundId = 4))
        board.cancel(1)
        assertTrue(board.isStale(1, foregroundId = 4))
    }

    @Test fun theSameSentenceTwiceIsNotRunTwice() {
        board.start(1, "Open YouTube!", TaskType.PHONE)
        assertEquals(1, board.duplicateOf("open youtube", 3000)?.id)
        board.finish(1, true, "Opened")
        now = 1000
        assertEquals(1, board.duplicateOf("open youtube", 3000)?.id) // finished a second ago: probably heard twice
        now = 10_000
        assertNull(board.duplicateOf("open youtube", 3000)) // a genuine repeat later is fine
        assertNull(board.duplicateOf("open chrome", 3000))
    }

    @Test fun describesWhatIsRunning() {
        assertEquals("Nothing is running right now.", board.describeRunning())
        board.start(1, "search for cats", TaskType.PHONE)
        assertTrue(board.describeRunning().contains("search for cats"))
    }

    @Test fun keepsOnlyTheMostRecentTasks() {
        val small = TaskBoard({ 0L }, keep = 3)
        repeat(6) { small.start(it, "t$it", TaskType.CHAT) }
        assertEquals(3, small.recent(10).size)
        assertNull(small.get(0))
    }

    @Test fun taskControlPhrases() {
        assertEquals(TaskControl.Cancel(null), TaskControl.parse("cancel that"))
        assertEquals(TaskControl.Cancel(null), TaskControl.parse("never mind"))
        assertEquals(TaskControl.Cancel(null), TaskControl.parse("Stop that task."))
        assertEquals(TaskControl.Cancel("open YouTube"), TaskControl.parse("Stop that, open YouTube."))
        assertEquals(TaskControl.Cancel("open chrome"), TaskControl.parse("cancel that and open chrome"))
        assertEquals(TaskControl.Background(null), TaskControl.parse("put that in the background"))
        assertEquals(TaskControl.Background("open WhatsApp"), TaskControl.parse("Put that in the background and open WhatsApp"))
        assertEquals(TaskControl.Background("open whatsapp"), TaskControl.parse("isko background mein daal do aur open whatsapp"))
        assertEquals(TaskControl.Status, TaskControl.parse("what are you doing"))
        assertEquals(TaskControl.Status, TaskControl.parse("what's running"))
        assertNull(TaskControl.parse("open youtube"))
        assertEquals(TaskControl.Cancel(null), TaskControl.parse("stop"))
        assertNull(TaskControl.parse("play some music"))
        assertNull(TaskControl.parse(""))
    }
}
