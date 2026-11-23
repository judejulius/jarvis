package com.jarvis.assistant.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.jarvis.assistant.CrashReporter
import com.jarvis.assistant.R
import com.jarvis.assistant.Settings
import com.jarvis.assistant.memory.MemoryActivity
import com.jarvis.assistant.databinding.FragmentHomeBinding
import com.jarvis.assistant.service.JarvisService
import com.jarvis.assistant.service.JarvisService.Companion.Phase

class HomeFragment : Fragment() {

    private var _ui: FragmentHomeBinding? = null
    private val ui get() = _ui!!
    private lateinit var settings: Settings

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _ui = FragmentHomeBinding.inflate(inflater, container, false)
        settings = Settings(requireContext())
        ui.powerButton.setOnClickListener {
            if (JarvisService.running) JarvisService.stop(requireContext()) else startJarvis()
            ui.root.postDelayed({ if (_ui != null) refresh() }, 400)
        }
        ui.abortButton.setOnClickListener { if (JarvisService.running) startJarvis(JarvisService.ACTION_STOP_TASK) }
        ui.talkButton.setOnClickListener { startJarvis(JarvisService.ACTION_LISTEN) }
        ui.historyButton.setOnClickListener {
            startActivity(Intent(requireContext(), MemoryActivity::class.java).putExtra(MemoryActivity.EXTRA_HISTORY, true))
        }
        ui.sendButton.setOnClickListener { runTypedCommand() }
        ui.commandInput.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEND) { runTypedCommand(); true } else false
        }
        ui.crashShare.setOnClickListener {
            val report = CrashReporter.pending(requireContext()) ?: return@setOnClickListener
            startActivity(android.content.Intent.createChooser(
                android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(android.content.Intent.EXTRA_SUBJECT, "Jarvis crash report")
                    .putExtra(android.content.Intent.EXTRA_TEXT, report),
                "Share crash report",
            ))
            CrashReporter.dismiss(requireContext())
            refresh()
        }
        ui.crashDismiss.setOnClickListener { CrashReporter.dismiss(requireContext()); refresh() }
        ui.modelPill.setOnClickListener { (activity as? MainActivity)?.goTo(R.id.nav_models) }
        ui.setupBanner.setOnClickListener {
            val needsModel = settings.primary() == null
            val needsPermissions = Setup.remaining(requireContext()).any { it.required }
            (activity as? MainActivity)?.goTo(if (needsModel && !needsPermissions) R.id.nav_models else R.id.nav_settings)
        }
        return ui.root
    }

    override fun onResume() {
        super.onResume()
        if (_ui == null) return
        ui.reactor.animate = true
        JarvisService.statusListener = { if (_ui != null) refresh() }
        refresh()
    }

    override fun onPause() {
        super.onPause()
        JarvisService.statusListener = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _ui = null
    }

    private fun refresh() {
        val ctx = requireContext()
        ui.wakeExample.text = "Try “${settings.wakePromptPhrase}, text Mum I’m on my way”"
        val running = JarvisService.running
        val phase = if (running) JarvisService.phase else Phase.OFF
        ui.reactor.mode = when (phase) {
            Phase.OFF -> ArcReactorView.Mode.OFF
            Phase.IDLE -> ArcReactorView.Mode.IDLE
            Phase.LISTENING -> ArcReactorView.Mode.LISTENING
            Phase.WORKING -> ArcReactorView.Mode.WORKING
        }
        ui.statusLabel.text = when (phase) {
            Phase.OFF -> "Off"
            Phase.IDLE -> "Ready"
            Phase.LISTENING -> "Listening"
            Phase.WORKING -> "Working"
        }
        ui.statusText.text = if (running) JarvisService.status else "Turn Jarvis on to start listening"
        val last = JarvisService.lastResult
        ui.lastResult.visibility = if (last != null && phase != Phase.WORKING) View.VISIBLE else View.GONE
        ui.lastResult.text = "Last task: $last"
        ui.abortButton.visibility = if (phase == Phase.WORKING) View.VISIBLE else View.GONE

        ui.powerButton.text = if (running) "Turn off" else "Turn on"
        // Primary button when off; quiet outlined button when on.
        ui.powerButton.backgroundTintList = ColorStateList.valueOf(ctx.color(if (running) R.color.surface_high else R.color.accent))
        val fg = ctx.color(if (running) R.color.text else R.color.on_accent)
        ui.powerButton.setTextColor(fg)
        ui.powerButton.iconTint = ColorStateList.valueOf(fg)

        ui.crashCard.visibility = if (CrashReporter.pending(ctx) != null) View.VISIBLE else View.GONE

        val primary = settings.primary()
        ui.modelPill.text = primary?.name ?: "Add a model"

        val missing = Setup.remaining(ctx).filter { it.required }.map { it.title.lowercase() } +
            (if (primary == null) listOf("an AI model") else emptyList())
        ui.setupBanner.visibility = if (missing.isEmpty()) View.GONE else View.VISIBLE
        ui.setupBannerText.text = "Jarvis still needs ${missing.joinToString(" and ")}. Tap to finish setup."
    }

    private fun runTypedCommand() {
        val task = ui.commandInput.text.toString().trim()
        if (task.isEmpty()) return
        // Get out of the way so the agent can use the phone.
        if (startJarvis(JarvisService.ACTION_RUN, task)) {
            ui.commandInput.setText("")
            requireActivity().moveTaskToBack(true)
        }
    }

    private fun startJarvis(action: String? = null, task: String? = null): Boolean {
        val started = JarvisService.start(requireContext(), action, task)
        if (!started) {
            Toast.makeText(requireContext(), "Jarvis needs microphone permission first.", Toast.LENGTH_LONG).show()
            requireActivity().requestPermissions(Setup.PERMISSIONS, 1)
        }
        return started
    }
}
