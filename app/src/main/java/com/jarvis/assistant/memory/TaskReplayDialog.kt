package com.jarvis.assistant.memory

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment

/** Reads the local event log. Replay controls only move the cursor through the saved steps. */
class TaskReplayDialog : DialogFragment() {
    private var position = 0

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        position = savedInstanceState?.getInt("position") ?: 0
        val args = requireArguments()
        val store = MemoryStore.get(requireContext())
        val id = args.getLong("id")
        val alert = AlertDialog.Builder(requireContext())
            .setTitle(args.getString("title"))
            .setMessage("Loading steps…")
            .setNegativeButton("Close", null)
            .setNeutralButton("Previous", null)
            .setPositiveButton("Next", null)
            .create()
        alert.setOnShowListener {
            val previous = alert.getButton(AlertDialog.BUTTON_NEUTRAL)
            val next = alert.getButton(AlertDialog.BUTTON_POSITIVE)
            previous.isEnabled = false
            next.isEnabled = false
            Thread {
                val result = runCatching { store.steps(id) }
                activity?.runOnUiThread {
                    if (!alert.isShowing) return@runOnUiThread
                    val steps = result.getOrNull().orEmpty()
                    fun showStep() {
                        alert.setMessage(when {
                            result.isFailure -> "Couldn’t load the recorded steps."
                            steps.isEmpty() -> "No steps were recorded for this task."
                            else -> "Step ${position + 1} of ${steps.size}\n\n${steps[position]}"
                        })
                        previous.isEnabled = position > 0
                        next.isEnabled = position < steps.lastIndex
                    }
                    position = position.coerceIn(0, steps.lastIndex.coerceAtLeast(0))
                    previous.setOnClickListener { position--; showStep() }
                    next.setOnClickListener { position++; showStep() }
                    showStep()
                }
            }.start()
        }
        return alert
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("position", position)
    }

    companion object {
        fun forTask(item: MemoryStore.Item) = TaskReplayDialog().apply {
            arguments = Bundle().apply { putLong("id", item.id); putString("title", item.title) }
        }
    }
}
