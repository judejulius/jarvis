package com.jarvis.assistant.tasks

import android.content.Context
import android.util.Log
import com.jarvis.assistant.Settings
import com.jarvis.assistant.memory.MemoryStore
import java.util.concurrent.Executors

/** Queues history writes in execution order, including when a new service replaces an old one. */
class TaskHistory(context: Context) {
    private val store = MemoryStore.get(context)
    private val settings = Settings(context)
    // Service task IDs restart at zero. Only this service's writer resolves them to database IDs.
    private val rows = mutableMapOf<Int, Long>()

    fun start(id: Int, title: String, forContext: Boolean) {
        if (settings.rememberTasks) write {
            if (settings.rememberTasks) store.startTask(title, forContext)?.let { rows[id] = it }
        }
    }

    fun step(id: Int, label: String) = write {
        val row = rows[id] ?: return@write
        if (settings.rememberTasks) store.appendStep(row, label)
        else {
            rows.remove(id)
            store.delete(MemoryStore.Kind.TASK, row)
        }
    }

    fun finish(id: Int, state: String) = write {
        val row = rows.remove(id) ?: return@write
        closeRow(row, state)
    }

    fun interruptAll() = write {
        rows.values.forEach { closeRow(it, "Interrupted") }
        rows.clear()
    }

    private fun closeRow(row: Long, state: String) {
        if (settings.rememberTasks) store.finishTask(row, state)
        else store.delete(MemoryStore.Kind.TASK, row)
    }

    private fun write(block: () -> Unit) {
        io.execute { runCatching(block).onFailure { Log.w("TaskHistory", "Could not record task history", it) } }
    }

    companion object {
        private val io = Executors.newSingleThreadExecutor { Thread(it, "jarvis-history") }
    }
}
