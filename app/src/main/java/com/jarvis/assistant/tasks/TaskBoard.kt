package com.jarvis.assistant.tasks

/**
 * What a task needs from the phone, which decides what may run at the same time:
 *  - INSTANT: finishes in moments and does not use the screen (volume, flashlight, time, memory). Runs alongside anything.
 *  - CHAT: a spoken answer from a language model. Runs in the background if asked; a new answer replaces it.
 *  - PHONE: controls the screen (open an app, tap, scroll, phone control). Only one at a time; a newer one replaces it.
 */
enum class TaskType { INSTANT, CHAT, PHONE }

enum class TaskState { RUNNING, DONE, FAILED, CANCELLED }

class TrackedTask(val id: Int, val text: String, val type: TaskType, val startedAt: Long) {
    @Volatile var state = TaskState.RUNNING
    @Volatile var background = false

    /** True once the task has done something that cannot be taken back (a message was sent, a call was placed). */
    @Volatile var irreversibleDone = false
    @Volatile var summary: String? = null
    @Volatile var finishedAt = 0L

    val running get() = state == TaskState.RUNNING
}

data class CancelOutcome(val task: TrackedTask, val wasRunning: Boolean) {
    /** What can honestly be said about the cancel. Nothing is claimed to be undone. */
    fun message(): String = when {
        !wasRunning -> "That was already finished."
        task.irreversibleDone -> "I stopped the rest, but part of it was already done and I can't undo that."
        else -> "Cancelled."
    }
}

/** Keeps the list of tasks and their states. Pure logic, safe to call from any thread. */
class TaskBoard(private val now: () -> Long, private val keep: Int = 50) {
    private val tasks = ArrayList<TrackedTask>()

    @Synchronized
    fun start(id: Int, text: String, type: TaskType): TrackedTask {
        val t = TrackedTask(id, text, type, now())
        tasks += t
        while (tasks.size > keep) tasks.removeAt(0)
        return t
    }

    @Synchronized fun get(id: Int): TrackedTask? = tasks.lastOrNull { it.id == id }

    /** The task the user is waiting on: the newest running CHAT or PHONE task that is not in the background. */
    @Synchronized
    fun foreground(): TrackedTask? = tasks.lastOrNull { it.running && !it.background && it.type != TaskType.INSTANT }

    @Synchronized fun running(): List<TrackedTask> = tasks.filter { it.running }

    @Synchronized fun recent(n: Int): List<TrackedTask> = tasks.takeLast(n)

    @Synchronized
    fun finish(id: Int, success: Boolean, summary: String?) {
        val t = tasks.lastOrNull { it.id == id } ?: return
        if (t.state != TaskState.RUNNING) return // a cancelled task stays cancelled
        t.state = if (success) TaskState.DONE else TaskState.FAILED
        t.summary = summary
        t.finishedAt = now()
    }

    @Synchronized
    fun cancel(id: Int): CancelOutcome? {
        val t = tasks.lastOrNull { it.id == id } ?: return null
        val was = t.running
        if (was) { t.state = TaskState.CANCELLED; t.finishedAt = now() }
        return CancelOutcome(t, was)
    }

    @Synchronized fun markIrreversible(id: Int) { tasks.lastOrNull { it.id == id }?.irreversibleDone = true }

    /** Only a spoken-answer task can go to the background: a screen task needs the screen. */
    @Synchronized
    fun moveToBackground(id: Int): Boolean {
        val t = tasks.lastOrNull { it.id == id } ?: return false
        if (!t.running || t.type != TaskType.CHAT) return false
        t.background = true
        return true
    }

    /**
     * A task with the same words that is still running, or that started only a moment ago (the same sentence heard
     * twice, or a wake word that fired twice). Repeating it would do the same thing twice.
     */
    @Synchronized
    fun duplicateOf(text: String, windowMs: Long): TrackedTask? {
        val key = normalize(text)
        return tasks.lastOrNull { normalize(it.text) == key && (it.running || now() - it.startedAt < windowMs) && it.state != TaskState.CANCELLED }
    }

    /** Is this task still wanted? Instant and background tasks are, unless cancelled; others only while they are the foreground one. */
    @Synchronized
    fun isStale(id: Int, foregroundId: Int): Boolean {
        val t = tasks.lastOrNull { it.id == id } ?: return id != foregroundId
        if (t.state == TaskState.CANCELLED) return true
        if (t.type == TaskType.INSTANT || t.background) return false
        return id != foregroundId
    }

    /** One sentence about what is running, for "what are you doing?". */
    @Synchronized
    fun describeRunning(): String {
        val r = tasks.filter { it.running }
        if (r.isEmpty()) return "Nothing is running right now."
        return r.joinToString(" ") { t ->
            val where = if (t.background) "in the background" else "now"
            "I'm working on \"${t.text.take(60)}\" $where."
        }
    }

    private fun normalize(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
}

/** What happens to the task that is running when a new command arrives. */
object Interrupt {
    data class Decision(val cancel: TrackedTask?, val note: String?)

    fun forNewTask(newType: TaskType, foreground: TrackedTask?): Decision = when {
        newType == TaskType.INSTANT || foreground == null -> Decision(null, null)
        foreground.irreversibleDone -> Decision(foreground, "Part of the earlier task was already done, and I can't undo that.")
        else -> Decision(foreground, null)
    }
}

/** Spoken commands about the tasks themselves. [then] is a new command that came in the same sentence. */
sealed interface TaskControl {
    data class Cancel(val then: String?) : TaskControl
    data class Background(val then: String?) : TaskControl
    data object Status : TaskControl

    companion object {
        private val OPT = RegexOption.IGNORE_CASE
        private val CANCEL = Regex("""^\W*(?:please\s+)?(?:cancel|stop|abort|drop|forget|never\s?mind|rehne\s+do|rehne\s+de|chhodo|chodo)(?:\s+(?:that|it|this))?(?:\s+(?:task|request|command|action))?(?:\s+karo)?\W*$""", OPT)
        private val CANCEL_THEN = Regex("""^\W*(?:please\s+)?(?:cancel|stop|abort|drop|forget|never\s?mind|rehne\s+do|rehne\s+de|chhodo|chodo)(?:\s+(?:that|it|this))?(?:\s+(?:task|request|command|action))?[\s,.;:-]+(?:and\s+|then\s+|aur\s+)?(.+?)\W*$""", OPT)
        private val BACKGROUND = Regex("""^\W*(?:please\s+)?(?:(?:put|run|do|keep|move|send|leave)(?:\s+(?:that|it|this))?(?:\s+task)?\s+(?:in|to|into|on)\s+(?:the\s+)?background|background\s+(?:it|that|this)|(?:isko|usko|ise|use)?\s*background\s+(?:me|mein)\s+(?:daal\s+do|chalao|chalne\s+do|rakho))(?:[\s,.;:-]+(?:and\s+|then\s+|aur\s+)?(.+?))?\W*$""", OPT)
        private val STATUS = Regex("""^\W*(?:what(?:'s| is| are)?\s+(?:you\s+doing|running|happening)|what\s+are\s+you\s+working\s+on|any\s+tasks?\s+running|task\s+status|status\s+of\s+(?:the\s+)?task|kya\s+kar\s+rahe\s+ho)\W*$""", OPT)

        fun parse(raw: String): TaskControl? {
            val text = raw.trim()
            if (text.isEmpty()) return null
            BACKGROUND.matchEntire(text)?.let { return Background(it.groupValues[1].trim().ifEmpty { null }) }
            if (STATUS.matches(text)) return Status
            if (CANCEL.matches(text)) return Cancel(null)
            CANCEL_THEN.matchEntire(text)?.let { m ->
                val rest = m.groupValues[1].trim()
                if (rest.isNotEmpty()) return Cancel(rest)
            }
            return null
        }
    }
}
