package com.jarvis.assistant.ui

import android.Manifest
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.jarvis.assistant.R
import com.jarvis.assistant.Settings
import com.jarvis.assistant.databinding.ActivityRoutinesBinding
import com.jarvis.assistant.router.FastActionExecutor
import com.jarvis.assistant.routines.Routine
import com.jarvis.assistant.routines.RoutineStep
import com.jarvis.assistant.routines.RoutineStore
import com.jarvis.assistant.service.JarvisService
import org.json.JSONObject

class RoutinesActivity : AppCompatActivity() {
    private lateinit var ui: ActivityRoutinesBinding
    private val store by lazy { RoutineStore(this) }
    private val actions by lazy { FastActionExecutor(this, Settings(this)) { null } }
    private var draft: Routine? = null
    private var pendingRun: String? = null
    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val name = pendingRun
        pendingRun = null
        if (granted && name != null) runRoutine(name)
        else Toast.makeText(this, R.string.needs_mic, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityRoutinesBinding.inflate(layoutInflater)
        setContentView(ui.root)
        ui.root.padForSystemBars()
        draft = savedInstanceState?.getString("draft")?.let { Routine.read(JSONObject(it)) }
        pendingRun = savedInstanceState?.getString("pendingRun")
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (draft == null) finish() else showList()
            }
        })
        ui.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("draft", draft?.json()?.toString())
        outState.putString("pendingRun", pendingRun)
    }

    private fun showList() {
        draft = null
        render()
    }

    private fun render() {
        ui.content.removeAllViews()
        val item = draft
        if (item == null) {
            ui.toolbar.setTitle(R.string.routines)
            ui.content.addView(TextView(this).apply {
                setText(R.string.routine_voice_hint)
                setPadding(0, 0, 0, dp(12))
            })
            val items = store.all()
            for (routine in items) {
                val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                ui.content.addView(row)
                val edit = button(routine.name) { draft = routine; render() }
                edit.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                edit.contentDescription = getString(R.string.routine_edit_named, routine.name)
                row.addView(edit, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(button(getString(R.string.routine_run)) { runRoutine(routine.name) }.apply {
                    contentDescription = getString(R.string.routine_run_named, routine.name)
                })
                val steps = routine.steps.joinToString("\n") { it.label(this) }
                ui.content.addView(TextView(this).apply { text = steps; setPadding(0, 0, 0, dp(16)) })
            }
            ui.content.addView(button(getString(R.string.routine_create)) { draft = Routine.create(); render() })
            return
        }

        ui.toolbar.setTitle(if (store.all().any { it.id == item.id }) R.string.routine_edit else R.string.routine_create)
        val name = EditText(this).apply {
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(Routine.MAX_NAME))
            setText(item.name)
            doAfterTextChanged { draft = draft?.copy(name = it.toString()) }
        }
        val field = TextInputLayout(this).apply {
            hint = getString(R.string.routine_name)
            addView(name)
        }
        ui.content.addView(field)
        for ((index, step) in item.steps.withIndex()) {
            val label = getString(R.string.routine_step_row, index + 1, step.label(this))
            ui.content.addView(button(label) { stepMenu(index) }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL })
        }
        if (item.steps.size < Routine.MAX_STEPS) {
            if (item.id == Routine.study.id && item.steps.none { it is RoutineStep.App }) {
                ui.content.addView(button(getString(R.string.routine_add_notes)) { chooseApp { setStep(null, it) } })
            }
            ui.content.addView(button(getString(R.string.routine_add)) { chooseStep(null) })
        }
        ui.content.addView(button(getString(R.string.routine_preview)) {
            val current = requireNotNull(draft)
            when {
                Routine.key(current.name).isEmpty() -> { field.error = getString(R.string.routine_name_needed); name.requestFocus() }
                current.steps.isEmpty() -> toast(R.string.routine_steps_needed)
                else -> preview(current.copy(name = current.name.trim()))
            }
        })
        if (store.all().any { it.id == item.id }) {
            ui.content.addView(button(getString(R.string.delete)) {
                MaterialAlertDialogBuilder(this).setMessage(getString(R.string.routine_delete_ask, item.name))
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.delete) { _, _ -> store.delete(item.id); showList() }.show()
            })
        }
    }

    private fun preview(item: Routine) {
        val lines = item.steps.mapIndexed { i, step -> getString(R.string.routine_step_row, i + 1, step.label(this)) }
        MaterialAlertDialogBuilder(this).setTitle(item.name).setMessage(lines.joinToString("\n"))
            .setNegativeButton(R.string.back, null)
            .setPositiveButton(R.string.save) { _, _ ->
                if (store.save(item)) showList() else toast(R.string.routine_duplicate)
            }.show()
    }

    private fun stepMenu(index: Int) {
        val item = requireNotNull(draft)
        val options = buildList {
            add(R.string.routine_change)
            if (index > 0) add(R.string.routine_up)
            if (index < item.steps.lastIndex) add(R.string.routine_down)
            add(R.string.routine_remove)
        }
        MaterialAlertDialogBuilder(this).setTitle(item.steps[index].label(this))
            .setItems(options.map(::getString).toTypedArray()) { _, choice ->
                val steps = item.steps.toMutableList()
                when (options[choice]) {
                    R.string.routine_change -> { chooseStep(index); return@setItems }
                    R.string.routine_up -> steps.add(index - 1, steps.removeAt(index))
                    R.string.routine_down -> steps.add(index + 1, steps.removeAt(index))
                    R.string.routine_remove -> steps.removeAt(index)
                }
                draft = item.copy(steps = steps)
                render()
            }.show()
    }

    private fun setStep(index: Int?, step: RoutineStep) {
        val item = draft ?: return
        val steps = item.steps.toMutableList()
        if (index == null) steps.add(step) else steps[index] = step
        draft = item.copy(steps = steps)
        render()
    }

    private fun chooseStep(index: Int?) {
        val previous = index?.let { draft?.steps?.get(it) }
        val types = arrayOf(getString(R.string.routine_volume), getString(R.string.routine_timer), getString(R.string.routine_app))
        MaterialAlertDialogBuilder(this).setTitle(R.string.routine_action).setItems(types) { _, choice ->
            when (choice) {
                0 -> number(R.string.routine_volume, 0..100, (previous as? RoutineStep.Volume)?.percent ?: 20) { setStep(index, RoutineStep.Volume(it)) }
                1 -> number(R.string.routine_timer, 1..1440, (previous as? RoutineStep.Timer)?.minutes ?: 25) { setStep(index, RoutineStep.Timer(it)) }
                2 -> chooseApp { setStep(index, it) }
            }
        }.show()
    }

    private fun number(title: Int, range: IntRange, initial: Int, picked: (Int) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(java.text.NumberFormat.getIntegerInstance().apply { isGroupingUsed = false }.format(initial))
            selectAll()
        }
        val dialog = MaterialAlertDialogBuilder(this).setTitle(title).setView(input)
            .setPositiveButton(android.R.string.ok, null).setNegativeButton(R.string.cancel, null).show()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = input.text.toString().toIntOrNull()
            if (value == null || value !in range) input.error = getString(R.string.routine_range, range.first, range.last)
            else { dialog.dismiss(); picked(value) }
        }
    }

    private fun chooseApp(picked: (RoutineStep.App) -> Unit) {
        val editing = draft?.id ?: return
        Thread {
            val apps = actions.installedApps().sortedBy { it.label.lowercase() }
            runOnUiThread {
                if (isDestroyed || isFinishing || draft?.id != editing) return@runOnUiThread
                if (apps.isEmpty()) { toast(R.string.routine_no_apps); return@runOnUiThread }
                val labels = apps.groupingBy { it.label }.eachCount()
                MaterialAlertDialogBuilder(this).setTitle(R.string.routine_app)
                    .setItems(apps.map { if (labels[it.label] == 1) it.label else "${it.label} (${it.packageName})" }.toTypedArray()) { _, i ->
                        picked(RoutineStep.App(apps[i].label, apps[i].packageName))
                    }.show()
            }
        }.start()
    }

    private fun runRoutine(name: String) {
        if (JarvisService.start(this, JarvisService.ACTION_RUN, "run routine $name")) moveTaskToBack(true)
        else { pendingRun = name; microphone.launch(Manifest.permission.RECORD_AUDIO) }
    }

    private fun button(label: String, click: () -> Unit) = MaterialButton(this, null,
        com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        text = label
        setOnClickListener { click() }
    }

    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
}
