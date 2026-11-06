package com.jarvis.assistant.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.jarvis.assistant.CLOUD_VOICES
import com.jarvis.assistant.CloudVoiceConfig
import com.jarvis.assistant.KOKORO_VOICES
import com.jarvis.assistant.Settings
import com.jarvis.assistant.databinding.FragmentSettingsBinding
import com.jarvis.assistant.voice.Kokoro
import com.jarvis.assistant.voice.Speaker

class SettingsFragment : Fragment() {

    private var _ui: FragmentSettingsBinding? = null
    private val ui get() = _ui!!
    private lateinit var settings: Settings
    private val refreshers = mutableListOf<() -> Unit>()
    private var preview: Speaker? = null
    private val onDownload: () -> Unit = { refreshers.forEach { it() } }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _ui = FragmentSettingsBinding.inflate(inflater, container, false)
        settings = Settings(requireContext())
        val activity = requireActivity()

        // Voice
        ui.engineChips.addChoice("Kokoro · on phone", "kokoro")
        ui.engineChips.addChoice("System voice", "system")
        CLOUD_VOICES.forEach { ui.engineChips.addChoice(it.name, it.id) }
        ui.engineChips.select(settings.voiceEngine)
        ui.engineChips.setOnCheckedStateChangeListener { _, _ ->
            saveCloudFields()
            settings.voiceEngine = ui.engineChips.selectedTag ?: "kokoro"
            showVoiceSection()
        }
        refreshers += Setup.bindRows(activity, ui.kokoroRow, listOf(Setup.Item.VOICE))
        KOKORO_VOICES.forEach { ui.voiceChips.addChoice("${it.name} · ${if (it.accent.startsWith("British")) "UK" else "US"}", it.sid.toString()) }
        ui.voiceChips.select(settings.kokoroSpeaker.toString())
        ui.voiceChips.setOnCheckedStateChangeListener { _, _ ->
            ui.voiceChips.selectedTag?.toIntOrNull()?.let {
                settings.kokoroSpeaker = it
                Kokoro.prerender(requireContext(), it, settings.voiceSpeed)
            }
        }
        ui.speed.value = settings.voiceSpeed.coerceIn(0.7f, 1.5f).let { (Math.round(it * 20) / 20f) }
        ui.speedLabel.text = speedText(ui.speed.value)
        ui.speed.addOnChangeListener { _, v, _ ->
            settings.voiceSpeed = v
            ui.speedLabel.text = speedText(v)
        }
        ui.previewButton.setOnClickListener { previewVoice() }
        showVoiceSection()

        // Wake word
        refreshers += Setup.bindRows(activity, ui.wakeRow, listOf(Setup.Item.WAKE_WORD, Setup.Item.SPEECH))
        ui.wakeSwitch.isChecked = settings.wakeWordEnabled
        ui.bootSwitch.isChecked = settings.startOnBoot
        ui.wakeSwitch.setOnCheckedChangeListener { _, v -> settings.wakeWordEnabled = v }
        ui.bootSwitch.setOnCheckedChangeListener { _, v -> settings.startOnBoot = v }
        ui.bargeSwitch.isChecked = settings.interruptByTalking
        ui.bargeSwitch.setOnCheckedChangeListener { _, v -> settings.interruptByTalking = v }
        ui.followUpSwitch.isChecked = settings.followUp
        ui.followUpSwitch.setOnCheckedChangeListener { _, v -> settings.followUp = v }


        // Permissions
        refreshers += Setup.bindRows(activity, ui.permissionRows, listOf(Setup.Item.MICROPHONE, Setup.Item.PHONE_CONTROL, Setup.Item.BACKGROUND))

        // About you
        ui.notes.setText(settings.personalNotes)
        ui.maxSteps.setText(settings.maxSteps.toString())
        ui.islandSwitch.isChecked = settings.showIsland
        ui.islandSwitch.setOnCheckedChangeListener { _, v -> settings.showIsland = v }
        ui.screenshotSwitch.isChecked = settings.allowScreenshots
        ui.screenshotSwitch.setOnCheckedChangeListener { _, v -> settings.allowScreenshots = v }

        ui.rerunSetup.setOnClickListener {
            startActivity(Intent(requireContext(), OnboardingActivity::class.java))
        }
        // Assistant behaviour: AI mode, voice language, safety and memory switches, on-phone model status.
        runCatching {
            refreshers += AssistantSection.attach(requireContext(), settings, ui.scroll.getChildAt(0) as ViewGroup)
        }.onFailure { android.util.Log.e("Settings", "could not add the Assistant section", it) }
        return ui.root
    }

    override fun onResume() {
        super.onResume()
        Kokoro.warmUp(requireContext()) // so Preview responds quickly
        Downloads.listen(onDownload)
        refreshers.forEach { it() }
    }

    override fun onPause() {
        super.onPause()
        Downloads.unlisten(onDownload)
        if (_ui == null) return
        if (settings.voiceEngine == "kokoro") Kokoro.prerender(requireContext(), settings.kokoroSpeaker, settings.voiceSpeed)
        saveCloudFields()
        settings.personalNotes = ui.notes.text.toString()
        settings.maxSteps = ui.maxSteps.text.toString().toIntOrNull()?.coerceIn(5, 200) ?: 40
    }

    override fun onDestroyView() {
        super.onDestroyView()
        preview?.stop()
        preview = null
        refreshers.clear()
        _ui = null
    }

    /** The cloud voice whose settings are in the fields right now, if any. */
    private var shownCloud: String? = null

    private fun showVoiceSection() {
        val engine = settings.voiceEngine
        ui.kokoroSection.visibility = if (engine == "kokoro") View.VISIBLE else View.GONE
        val preset = CLOUD_VOICES.firstOrNull { it.id == engine }
        ui.cloudSection.visibility = if (preset != null) View.VISIBLE else View.GONE
        ui.voiceError.visibility = View.GONE
        shownCloud = preset?.id
        if (preset == null) return
        val config = settings.cloudVoice(preset.id)
        ui.cloudHint.text = preset.keyHint
        ui.cloudKeyLayout.hint = if (preset.needsKey) "API key" else "API key (optional)"
        ui.cloudKey.setText(config.apiKey)
        ui.cloudVoice.setText(config.voice)
        ui.cloudVoiceHint.text = preset.voiceHint
        ui.cloudModel.setText(config.model)
        ui.cloudUrl.setText(config.baseUrl)
        ui.cloudUrlLayout.visibility = if (preset.editableUrl) View.VISIBLE else View.GONE
    }

    private fun saveCloudFields() {
        val id = shownCloud ?: return
        val ui = _ui ?: return
        val preset = CLOUD_VOICES.first { it.id == id }
        settings.saveCloudVoice(CloudVoiceConfig(
            id = id,
            apiKey = ui.cloudKey.text.toString(),
            model = ui.cloudModel.text.toString().ifBlank { preset.defaultModel },
            voice = ui.cloudVoice.text.toString(),
            baseUrl = if (preset.editableUrl) ui.cloudUrl.text.toString().ifBlank { preset.defaultBaseUrl } else preset.defaultBaseUrl,
        ))
    }

    private fun speedText(v: Float) = "Speed %.2f×".format(v)

    private fun previewVoice() {
        saveCloudFields()
        val cloud = CLOUD_VOICES.firstOrNull { it.id == settings.voiceEngine }
        if (cloud != null && cloud.needsKey && settings.cloudVoice(cloud.id).apiKey.isBlank()) {
            Toast.makeText(requireContext(), "Add your ${cloud.name} API key first.", Toast.LENGTH_SHORT).show()
            return
        }
        if (settings.voiceEngine == "kokoro" && !Kokoro.isInstalled(requireContext())) {
            Toast.makeText(requireContext(), "Download the Kokoro voice first. Playing the system voice for now.", Toast.LENGTH_SHORT).show()
        }
        val voice = when {
            cloud != null -> "${cloud.name}"
            else -> KOKORO_VOICES.firstOrNull { it.sid == settings.kokoroSpeaker }?.name ?: "Jarvis"
        }
        ui.previewButton.isEnabled = false
        ui.previewButton.text = "Speaking…"
        val speaker = preview ?: Speaker(requireContext()).also { preview = it }
        speaker.speak("Good evening. This is the $voice voice. All systems are online and ready when you are.") {
            _ui?.let {
                it.previewButton.isEnabled = true
                it.previewButton.text = "Preview voice"
                // If the voice had a problem, say so here rather than failing silently.
                val error = speaker.lastError
                it.voiceError.visibility = if (error != null) View.VISIBLE else View.GONE
                it.voiceError.text = if (cloud != null) "$error\nPlayed the backup voice instead." else "Kokoro problem: $error"
            }
        }
    }
}
