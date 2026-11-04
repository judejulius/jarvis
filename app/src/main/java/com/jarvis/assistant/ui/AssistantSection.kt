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
import com.jarvis.assistant.memory.MemoryActivity

/** The "Assistant" card on the Settings page: AI mode, voice language, safety and memory switches, on-phone model status. */
object AssistantSection {
    /** Adds the card to [parent] and returns a function that refreshes it (called when the page is shown). */
    fun attach(context: Context, settings: Settings, parent: ViewGroup): () -> Unit {
        val view = LayoutInflater.from(context).inflate(R.layout.view_assistant_section, parent, false)
        parent.addView(view)

        val modeChips = view.findViewById<ChipGroup>(R.id.aiModeChips)
        val modeHint = view.findViewById<TextView>(R.id.aiModeHint)
        modeChips.addChoice("Auto", AiMode.AUTO.name)
        modeChips.addChoice("Online", AiMode.ONLINE.name)
        modeChips.addChoice("Offline", AiMode.OFFLINE.name)
        modeChips.select(settings.aiMode.name)
        modeHint.text = modeText(settings.aiMode)
        modeChips.setOnCheckedStateChangeListener { _, _ ->
            val mode = AiMode.parse(modeChips.selectedTag)
            settings.aiMode = mode
            modeHint.text = modeText(mode)
        }

        val langChips = view.findViewById<ChipGroup>(R.id.speechLangChips)
        val langHint = view.findViewById<TextView>(R.id.speechLangHint)
        langChips.addChoice("English · offline", "en")
        langChips.addChoice("Hindi / Hinglish · Google", "hi")
        langChips.select(settings.speechLanguage)
        langHint.text = langText(settings.speechLanguage)
        langChips.setOnCheckedStateChangeListener { _, _ ->
            val lang = langChips.selectedTag ?: "en"
            settings.speechLanguage = lang
            langHint.text = langText(lang)
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
        view.findViewById<MaterialButton>(R.id.memoriesButton).setOnClickListener {
            context.startActivity(Intent(context, MemoryActivity::class.java))
        }

        val status = view.findViewById<TextView>(R.id.localStatus)
        return { refreshStatus(context, settings, view, status) }
    }

    private fun refreshStatus(context: Context, settings: Settings, view: View, status: TextView) {
        val profile = settings.modelsInOrder().firstOrNull { Endpoints.isLoopback(it.baseUrl) }
        if (profile == null) {
            status.text = "No on-phone model is set up. Add one on the Models page, for example http://127.0.0.1:8080/v1."
            return
        }
        status.text = "On-phone model: checking…"
        val manager = LocalServerManager(context, settings)
        Thread({
            val state = manager.health(profile.baseUrl, 1000)
            val text = when (state) {
                ServerHealth.State.OK -> "On-phone model (${profile.name}): running."
                ServerHealth.State.LOADING -> "On-phone model (${profile.name}): loading…"
                ServerHealth.State.DOWN ->
                    "On-phone model (${profile.name}): not running. Jarvis starts it through Termux when needed."
            }
            view.post { status.text = text }
        }, "jarvis-status").apply { isDaemon = true }.start()
    }

    private fun modeText(mode: AiMode) = when (mode) {
        AiMode.AUTO -> "Short, simple questions use the model on your phone. Hard, current-events or Hindi questions use your cloud models when the internet works."
        AiMode.ONLINE -> "Cloud models first, the on-phone model if the internet is down."
        AiMode.OFFLINE -> "Only the on-phone model answers. Nothing leaves your phone."
    }

    private fun langText(lang: String) =
        if (lang == "hi") "Uses Google speech recognition (needs the Google app; it may use the internet). Wake word still works offline. Replies are spoken in English."
        else "Offline recognition on your phone. English only."
}
