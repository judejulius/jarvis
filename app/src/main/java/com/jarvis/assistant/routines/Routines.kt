package com.jarvis.assistant.routines

import android.content.Context
import com.jarvis.assistant.R
import com.jarvis.assistant.router.FastCommand
import com.jarvis.assistant.router.VolumeChange
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

sealed interface RoutineStep {
    data class Volume(val percent: Int) : RoutineStep { init { require(percent in 0..100) } }
    data class Timer(val minutes: Int) : RoutineStep { init { require(minutes in 1..1440) } }
    data class App(val label: String, val pkg: String) : RoutineStep { init { require(label.isNotBlank() && pkg.isNotBlank()) } }

    fun label(context: Context): String = when (this) {
        is Volume -> context.getString(R.string.routine_volume_value, percent)
        is Timer -> context.getString(R.string.routine_timer_value, minutes)
        is App -> context.getString(R.string.routine_app_value, label)
    }

    fun command(): FastCommand = when (this) {
        is Volume -> FastCommand.Volume(VolumeChange.SET, percent)
        is Timer -> FastCommand.SetTimer(minutes * 60)
        is App -> FastCommand.OpenInstalledApp(pkg)
    }

    fun json(): JSONObject = when (this) {
        is Volume -> JSONObject().put("volume", percent)
        is Timer -> JSONObject().put("timer", minutes)
        is App -> JSONObject().put("package", pkg).put("label", label)
    }

    companion object {
        fun read(o: JSONObject): RoutineStep = when {
            o.has("volume") -> Volume(o.getInt("volume"))
            o.has("timer") -> Timer(o.getInt("timer"))
            else -> App(o.getString("label"), o.getString("package"))
        }
    }
}

data class Routine(val id: String, val name: String, val steps: List<RoutineStep>) {
    fun json(): JSONObject = JSONObject().put("id", id).put("name", name)
        .put("steps", JSONArray().also { a -> steps.forEach { a.put(it.json()) } })

    companion object {
        const val MAX_STEPS = 5
        const val MAX_NAME = 60
        val study = Routine("study", "Study time", listOf(RoutineStep.Volume(20), RoutineStep.Timer(25)))
        fun create() = Routine(UUID.randomUUID().toString(), "", emptyList())
        fun read(o: JSONObject): Routine {
            val a = o.getJSONArray("steps")
            return Routine(o.getString("id"), o.getString("name"), List(a.length()) { RoutineStep.read(a.getJSONObject(it)) })
        }
        fun key(text: String) = text.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ").trim()
    }
}

class RoutineStore(context: Context) {
    private val prefs = context.getSharedPreferences("personal_routines", Context.MODE_PRIVATE)

    fun all(): List<Routine> {
        val saved = prefs.getString("items", null) ?: return listOf(Routine.study)
        val a = JSONArray(saved)
        return List(a.length()) { Routine.read(a.getJSONObject(it)) }
    }

    fun save(routine: Routine): Boolean = synchronized(prefs) {
        val name = Routine.key(routine.name)
        require(name.isNotEmpty() && routine.name.length <= Routine.MAX_NAME && routine.steps.size in 1..Routine.MAX_STEPS)
        val saved = all().toMutableList()
        if (saved.any { it.id != routine.id && Routine.key(it.name) == name }) return false
        val index = saved.indexOfFirst { it.id == routine.id }
        if (index < 0) saved.add(routine) else saved[index] = routine
        write(saved)
        true
    }

    fun delete(id: String) = synchronized(prefs) { write(all().filterNot { it.id == id }) }

    private fun write(items: List<Routine>) {
        val a = JSONArray()
        items.forEach { a.put(it.json()) }
        prefs.edit().putString("items", a.toString()).apply()
    }
}

/** A failed step has already been attempted. Continuing starts at the next step, even for a timer. */
data class RoutineRun(val routine: Routine, val next: Int = 0, val skipped: Int = 0) {
    data class Result(val success: Boolean, val message: String, val remaining: RoutineRun? = null)

    fun execute(context: Context, canAct: () -> Boolean, perform: (RoutineStep, String) -> String?): Result {
        for (i in next until routine.steps.size) {
            if (!canAct()) return Result(false, context.getString(R.string.routine_stopped))
            val step = routine.steps[i]
            val progress = context.getString(R.string.routine_progress, i + 1, routine.steps.size, step.label(context))
            val failure = perform(step, progress) ?: continue
            val rest = if (i < routine.steps.lastIndex) copy(next = i + 1, skipped = skipped + 1) else null
            val message = context.getString(R.string.routine_failed, progress, failure)
            return Result(false, if (rest == null) message else "$message ${context.getString(R.string.routine_continue_hint)}", rest)
        }
        return if (skipped == 0) Result(true, context.getString(R.string.routine_done, routine.name))
        else Result(false, context.resources.getQuantityString(R.plurals.routine_partial, skipped, routine.name, skipped))
    }
}
