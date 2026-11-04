package com.jarvis.assistant.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.ApiKind
import com.jarvis.assistant.ModelProfile
import com.jarvis.assistant.PROVIDERS
import com.jarvis.assistant.ProviderPreset
import com.jarvis.assistant.R
import com.jarvis.assistant.Settings
import com.jarvis.assistant.agent.Llm
import com.jarvis.assistant.databinding.ActivityModelEditBinding

/** Add a new model, or edit/delete an existing one. */
class ModelEditActivity : AppCompatActivity() {

    private lateinit var ui: ActivityModelEditBinding
    private lateinit var settings: Settings
    private var existing: ModelProfile? = null
    private var preset: ProviderPreset = PROVIDERS.first()
    private val efforts = listOf("low", "medium", "high", "xhigh", "max")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityModelEditBinding.inflate(layoutInflater)
        setContentView(ui.root)
        ui.root.padForSystemBars()
        settings = Settings(this)
        existing = intent.getStringExtra(EXTRA_ID)?.let { id -> settings.profiles().firstOrNull { it.id == id } }

        ui.pageTitle.text = if (existing == null) "Add model" else "Edit model"
        ui.delete.visibility = if (existing == null) View.GONE else View.VISIBLE
        ui.back.setOnClickListener { finish() }
        ui.delete.setOnClickListener { confirmDelete() }
        ui.testButton.setOnClickListener { test() }
        ui.saveButton.setOnClickListener { save() }

        PROVIDERS.forEach { ui.providerChips.addChoice(it.label.substringBefore(" ("), it.id) }
        efforts.forEach { ui.effortChips.addChoice(if (it == "xhigh") "X-High" else it.replaceFirstChar(Char::uppercase), it) }

        val start = existing
        if (start != null) {
            preset = start.preset
            ui.name.setText(start.name)
            ui.apiKey.setText(start.apiKey)
            ui.model.setText(start.model)
            ui.baseUrl.setText(start.baseUrl)
            ui.effortChips.select(start.effort)
            ui.primarySwitch.isChecked = settings.primaryId == start.id
        } else {
            applyPresetDefaults(preset, previous = null)
            ui.effortChips.select("medium")
            ui.primarySwitch.isChecked = settings.profiles().isEmpty()
        }
        ui.providerChips.select(preset.id)
        showFieldsFor(preset)

        ui.providerChips.setOnCheckedStateChangeListener { _, _ ->
            val next = PROVIDERS.firstOrNull { it.id == ui.providerChips.selectedTag } ?: return@setOnCheckedStateChangeListener
            if (next == preset) return@setOnCheckedStateChangeListener
            applyPresetDefaults(next, previous = preset)
            preset = next
            showFieldsFor(next)
            ui.testResult.visibility = View.GONE
        }
    }

    /** Fill in the new provider's defaults, but keep anything the user typed themselves. */
    private fun applyPresetDefaults(next: ProviderPreset, previous: ProviderPreset?) {
        fun untouched(current: String, oldDefault: String?) = current.isBlank() || current == oldDefault
        if (untouched(ui.name.text.toString(), previous?.label?.substringBefore(" ("))) ui.name.setText(next.label.substringBefore(" ("))
        if (untouched(ui.model.text.toString(), previous?.defaultModel)) ui.model.setText(next.defaultModel)
        if (untouched(ui.baseUrl.text.toString(), previous?.defaultBaseUrl)) ui.baseUrl.setText(next.defaultBaseUrl)
    }

    private fun showFieldsFor(p: ProviderPreset) {
        val local = p.id in setOf("lmstudio", "ollama", "custom")
        // Cloud providers have a fixed address, so only local/custom servers show it.
        ui.baseUrlLayout.visibility = if (local) View.VISIBLE else View.GONE
        ui.apiKeyLayout.visibility = if (p.needsKey || p.id == "custom") View.VISIBLE else View.GONE
        ui.apiKeyLayout.hint = if (p.needsKey) "API key" else "API key (optional)"
        ui.effortSection.visibility = if (p.kind == ApiKind.ANTHROPIC) View.VISIBLE else View.GONE
        ui.providerHint.text = HINTS[p.id] ?: ""
    }

    private fun current(): ModelProfile? {
        ui.apiKeyLayout.error = null
        ui.name.error = null
        val key = ui.apiKey.text.toString().trim()
        val model = ui.model.text.toString().trim()
        if (preset.needsKey && key.isBlank()) { ui.apiKeyLayout.error = "Paste your API key"; return null }
        if (model.isBlank()) { ui.model.error = "Enter the model ID"; return null }
        return ModelProfile(
            id = existing?.id ?: java.util.UUID.randomUUID().toString(),
            name = ui.name.text.toString().trim().ifBlank { preset.label.substringBefore(" (") },
            providerId = preset.id,
            baseUrl = ui.baseUrl.text.toString().trim().ifBlank { preset.defaultBaseUrl },
            apiKey = key,
            model = model,
            effort = ui.effortChips.selectedTag ?: "medium",
        )
    }

    private fun test() {
        val profile = current() ?: return
        ui.testButton.isEnabled = false
        ui.testButton.text = "Testing…"
        ui.testResult.visibility = View.GONE
        Thread {
            val result = runCatching { Llm.test(profile.toConfig()) }
            runOnUiThread {
                ui.testButton.isEnabled = true
                ui.testButton.text = "Test connection"
                ui.testResult.visibility = View.VISIBLE
                result.onSuccess {
                    ui.testResult.setTextColor(color(R.color.ok))
                    ui.testResult.text = "✓ Connected. ${profile.name} replied: “${it.take(60)}”"
                }.onFailure {
                    ui.testResult.setTextColor(color(R.color.danger))
                    ui.testResult.text = "✗ ${it.message?.take(300) ?: it.javaClass.simpleName}"
                }
            }
        }.start()
    }

    private fun save() {
        val profile = current() ?: return
        settings.saveProfile(profile)
        if (ui.primarySwitch.isChecked) settings.primaryId = profile.id
        finish()
    }

    private fun confirmDelete() {
        val profile = existing ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete ${profile.name}?")
            .setMessage("Jarvis will stop using this model.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                settings.deleteProfile(profile.id)
                finish()
            }
            .show()
    }

    companion object {
        const val EXTRA_ID = "id"
        private val HINTS = mapOf(
            "anthropic" to "Get an API key at console.anthropic.com → API Keys. Low effort makes phone tasks noticeably faster.",
            "openai" to "Get an API key at platform.openai.com → API keys. Use any model that supports tool calling.",
            "lmstudio" to "On your computer: open LM Studio → Developer → start the server and turn on \"Serve on Local Network\". Enter your computer's IP address below and load a model that supports tool calling (e.g. Qwen).",
            "ollama" to "On your computer, start Ollama with OLLAMA_HOST=0.0.0.0 so the phone can reach it, then enter your computer's IP address below. Use a model that supports tool calling.",
            "openrouter" to "Get an API key at openrouter.ai/keys. Use any model ID listed on the site.",
            "gemini" to "Get an API key at aistudio.google.com → Get API key.",
            "groq" to "Get an API key at console.groq.com/keys.",
            "custom" to "Any server with an OpenAI-compatible /chat/completions endpoint and tool calling.",
        )
    }
}
