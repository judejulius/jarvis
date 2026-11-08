package com.jarvis.assistant.persona

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Jarvis's voice: how it greets and what short acknowledgements it uses. Pure logic. */
object Persona {
    enum class Daypart(val word: String) { MORNING("morning"), AFTERNOON("afternoon"), EVENING("evening") }

    fun daypart(hour: Int): Daypart = when (hour) {
        in 4..11 -> Daypart.MORNING
        in 12..16 -> Daypart.AFTERNOON
        else -> Daypart.EVENING
    }

    /** First wake of the day, when nothing has been asked yet. */
    fun greeting(hour: Int): String = "Hello sir, good ${daypart(hour).word}. What's the task for today?"

    /** First wake of the day, when a command came with it: the greeting is folded into the reply. */
    fun greetingPrefix(hour: Int): String = "Good ${daypart(hour).word}, sir."

    /** All three greetings, so the voice can pre-render them and speak them with no delay. */
    val ALL_GREETINGS: List<String> = Daypart.entries.map { "Hello sir, good ${it.word}. What's the task for today?" }

    /** Short acknowledgements for slow requests. Never used for instant commands. */
    val ACKNOWLEDGEMENTS = listOf("One moment.", "Let me check.", "Working on it.", "Give me a second.")

    /** Picks an acknowledgement, never the same one twice in a row. */
    class AckPicker(private val random: java.util.Random = java.util.Random()) {
        private var last = -1

        fun next(): String {
            var i = random.nextInt(ACKNOWLEDGEMENTS.size)
            if (i == last) i = (i + 1) % ACKNOWLEDGEMENTS.size
            last = i
            return ACKNOWLEDGEMENTS[i]
        }
    }
}

interface GreetingStore {
    /** The local date (yyyy-MM-dd) of the last greeting, or null if never. Must survive app restarts. */
    var lastDate: String?
}

/**
 * Greets once per local calendar day. The date is stored as text, so a change of time zone or clock simply means a
 * different date: a new day gets a greeting, the same date never gets two.
 */
class DailyGreeting(
    private val store: GreetingStore,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val now: () -> Instant = { Instant.now() },
) {
    fun today(): String = LocalDate.now(Clock.fixed(now(), zone())).toString()

    fun hour(): Int = now().atZone(zone()).hour

    fun isDue(): Boolean = store.lastDate != today()

    fun markGreeted() {
        store.lastDate = today()
    }

    /** The greeting to speak now, or null when today's greeting was already given. Marks it as given. */
    fun takeGreeting(): String? = if (isDue()) Persona.greeting(hour()).also { markGreeted() } else null

    /** Same, for a reply that already carries the user's command. */
    fun takePrefix(): String? = if (isDue()) Persona.greetingPrefix(hour()).also { markGreeted() } else null
}
