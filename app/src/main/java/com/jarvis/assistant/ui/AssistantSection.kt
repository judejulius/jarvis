package com.jarvis.assistant.ui

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.jarvis.assistant.R
import com.jarvis.assistant.Settings
import com.jarvis.assistant.ai.AiMode
import com.jarvis.assistant.ai.Endpoints
import com.jarvis.assistant.ai.LocalServerManager
import com.jarvis.assistant.ai.ServerHealth
import com.jarvis.assistant.contacts.PermissionActivity
import com.jarvis.assistant.memory.MemoryActivity
import com.jarvis.assistant.runtime.EmbeddedRuntime

/** The "Assistant" card on the Settings page: AI mode, voice language, safety and memory switches, on-phone model status. */
object AssistantSection {
    /** Adds the card to [parent] and returns a function that refreshes it (called when the page is shown). */
    fun attach(context: Context, settings: Settings, parent: ViewGroup): () -> Unit {
        val view = LayoutInflater.from(context).inflate(R.layout.view_assistant_section, parent, false)
        parent.addView(view)

        val modeChips = view.findViewById<ChipGroup>(R.id.aiModeChips)
        val modeHint = view.findViewById<TextView>(R.id.aiModeHint)
        modeChips.addChoice(context.getString(R.string.mode_auto), AiMode.AUTO.name)
        modeChips.addChoice(context.getString(R.string.mode_online), AiMode.ONLINE.name)
        modeChips.addChoice(context.getString(R.string.mode_offline), AiMode.OFFLINE.name)
        modeChips.select(settings.aiMode.name)
        modeHint.setText(modeText(settings.aiMode))
        modeChips.setOnCheckedStateChangeListener { _, _ ->
            val mode = AiMode.parse(modeChips.selectedTag)
            settings.aiMode = mode
            modeHint.setText(modeText(mode))
        }

        val langChips = view.findViewById<ChipGroup>(R.id.speechLangChips)
        val langHint = view.findViewById<TextView>(R.id.speechLangHint)
        langChips.addChoice(context.getString(R.string.lang_en), "en")
        langChips.addChoice(context.getString(R.string.lang_hi), "hi")
        langChips.select(settings.speechLanguage)
        langHint.setText(langText(settings.speechLanguage))
        langChips.setOnCheckedStateChangeListener { _, _ ->
            val lang = langChips.selectedTag ?: "en"
            settings.speechLanguage = lang
            langHint.setText(langText(lang))
        }

        view.findViewById<MaterialSwitch>(R.id.confirmSwitch).apply {
            isChecked = settings.confirmSensitive
            setOnCheckedChangeListener { _, checked -> settings.confirmSensitive = checked }
        }
        view.findViewById<MaterialSwitch>(R.id.shareMemorySwitch).apply {
            isChecked = settings.shareMemoryWithCloud
            setOnCheckedChangeListener { _, checked -> settings.shareMemoryWithCloud = checked }
        }
        view.findViewById<MaterialSwitch>(R.id.autoStartSwitch).apply {
            isChecked = settings.localServerAutoStart
            setOnCheckedChangeListener { _, checked -> settings.localServerAutoStart = checked }
        }
        view.findViewById<MaterialButton>(R.id.permissionsButton).setOnClickListener {
            context.startActivity(PermissionActivity.intent(context))
        }
        view.findViewById<MaterialButton>(R.id.memoriesButton).setOnClickListener {
            context.startActivity(Intent(context, MemoryActivity::class.java))
        }

        view.findViewById<MaterialSwitch>(R.id.rememberTasksSwitch).apply {
            isChecked = settings.rememberTasks
            setOnCheckedChangeListener { _, checked -> settings.rememberTasks = checked }
        }
        view.findViewById<MaterialSwitch>(R.id.embeddedSwitch).apply {
            isChecked = settings.embeddedRuntimeEnabled
            setOnCheckedChangeListener { _, checked -> settings.embeddedRuntimeEnabled = checked }
        }
        view.findViewById<MaterialSwitch>(R.id.mobileDownloadSwitch).apply {
            isChecked = settings.downloadModelOnMobile
            setOnCheckedChangeListener { _, checked -> settings.downloadModelOnMobile = checked }
        }
        val runtimeStatus = view.findViewById<TextView>(R.id.runtimeStatus)
        val runtimeButton = view.findViewById<MaterialButton>(R.id.runtimeButton)
        val runtime = EmbeddedRuntime.get(context)
        runtimeButton.setOnClickListener {
            if (runtime.status.phase == EmbeddedRuntime.Phase.DOWNLOADING) runtime.cancelDownload()
            else runtime.startDownloadIfNeeded(settings.downloadModelOnMobile || true) // the user pressed the button
            view.postDelayed({ showRuntime(runtime, runtimeStatus, runtimeButton) }, 600)
        }
        // While this page is open, keep the download/runtime line up to date.
        val ticker = object : Runnable {
            override fun run() {
                showRuntime(runtime, runtimeStatus, runtimeButton)
                if (view.isAttachedToWindow) view.postDelayed(this, 1500)
            }
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { v.post(ticker) }
            override fun onViewDetachedFromWindow(v: View) { v.removeCallbacks(ticker) }
        })

        val status = view.findViewById<TextView>(R.id.localStatus)
        return { refreshStatus(context, settings, view, status); showRuntime(runtime, runtimeStatus, runtimeButton) }
    }

    private fun showRuntime(rt: EmbeddedRuntime, text: TextView, button: MaterialButton) {
        val s = rt.status
        val c = text.context
        val (line, label) = when {
            !rt.isSupported() -> c.getString(R.string.runtime_unsupported) to null
            s.phase == EmbeddedRuntime.Phase.DOWNLOADING ->
                (if (s.percent >= 0) c.getString(R.string.runtime_downloading_percent, s.percent, s.detail)
                else c.getString(R.string.runtime_downloading, s.detail)) to c.getString(R.string.pause_download)
            s.phase == EmbeddedRuntime.Phase.RUNNING -> c.getString(R.string.runtime_running) to null
            s.phase == EmbeddedRuntime.Phase.STARTING -> c.getString(R.string.runtime_starting) to null
            s.phase == EmbeddedRuntime.Phase.FAILED -> c.getString(R.string.runtime_failed, s.detail) to null
            rt.hasModel() -> c.getString(R.string.runtime_installed) to null
            else -> (s.detail.ifBlank { c.getString(R.string.runtime_not_downloaded) }) to c.getString(R.string.download_embedded_model)
        }
        text.text = line
        button.visibility = if (label == null) View.GONE else View.VISIBLE
        if (label != null) button.text = label
    }

    private fun refreshStatus(context: Context, settings: Settings, view: View, status: TextView) {
        val profile = settings.modelsInOrder().firstOrNull { Endpoints.isLoopback(it.baseUrl) }
        if (profile == null) {
            status.setText(R.string.local_none)
            return
        }
        status.setText(R.string.local_checking)
        val manager = LocalServerManager(context, settings)
        Thread({
            val state = manager.health(profile.baseUrl, 1000)
            val text = when (state) {
                ServerHealth.State.OK -> context.getString(R.string.local_running, profile.name)
                ServerHealth.State.LOADING -> context.getString(R.string.local_loading, profile.name)
                ServerHealth.State.DOWN ->
                    context.getString(R.string.local_down, profile.name)
            }
            view.post { status.text = text }
        }, "jarvis-status").apply { isDaemon = true }.start()
    }

    private fun modeText(mode: AiMode) = when (mode) {
        AiMode.AUTO -> R.string.mode_auto_hint
        AiMode.ONLINE -> R.string.mode_online_hint
        AiMode.OFFLINE -> R.string.mode_offline_hint
    }

    private fun langText(lang: String) =
        if (lang == "hi") R.string.lang_hi_hint else R.string.lang_en_hint
}
