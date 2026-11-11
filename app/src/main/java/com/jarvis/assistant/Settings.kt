package com.jarvis.assistant

import android.content.Context
import com.jarvis.assistant.ai.AiMode
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** The kind of wire API a provider speaks. */
enum class ApiKind { ANTHROPIC, OPENAI_COMPATIBLE }

/** A built-in provider preset. Every field except [kind] can be overridden by the user. */
data class ProviderPreset(
    val id: String,
    val label: String,
    val kind: ApiKind,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val needsKey: Boolean,
)

val PROVIDERS = listOf(
    ProviderPreset("anthropic", "Claude (Anthropic)", ApiKind.ANTHROPIC, "https://api.anthropic.com", "claude-opus-5-5", true),
    ProviderPreset("openai", "OpenAI", ApiKind.OPENAI_COMPATIBLE, "https://api.openai.com/v1", "gpt-5", true),
    ProviderPreset("lmstudio", "LM Studio (local)", ApiKind.OPENAI_COMPATIBLE, "http://192.168.1.100:1234/v1", "local-model", false),
    ProviderPreset("ollama", "Ollama (local)", ApiKind.OPENAI_COMPATIBLE, "http://192.168.1.100:11434/v1", "qwen2.5:14b", false),
    ProviderPreset("openrouter", "OpenRouter", ApiKind.OPENAI_COMPATIBLE, "https://openrouter.ai/api/v1", "anthropic/claude-opus-5-5", true),
    ProviderPreset("gemini", "Google Gemini", ApiKind.OPENAI_COMPATIBLE, "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash", true),
    ProviderPreset("groq", "Groq", ApiKind.OPENAI_COMPATIBLE, "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", true),
    ProviderPreset("custom", "Custom (OpenAI-compatible)", ApiKind.OPENAI_COMPATIBLE, "http://localhost:8080/v1", "", false),
)

/** A model the user has saved. Several can exist; one is primary, the rest are fallbacks in order. */
data class ModelProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val providerId: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val effort: String = "medium",
) {
    val preset get() = PROVIDERS.firstOrNull { it.id == providerId } ?: PROVIDERS.last()
    fun toConfig() = ProviderConfig(preset, baseUrl, apiKey, model, if (preset.kind == ApiKind.ANTHROPIC) effort else "")

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("provider", providerId).put("base", baseUrl)
        .put("key", apiKey).put("model", model).put("effort", effort)

    companion object {
        fun fromJson(o: JSONObject) = ModelProfile(
            o.getString("id"), o.optString("name"), o.optString("provider", "custom"), o.optString("base"),
            o.optString("key"), o.optString("model"), o.optString("effort", "medium"),
        )
    }
}

/** Resolved settings for the provider the agent should talk to. */
data class ProviderConfig(
    val preset: ProviderPreset,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    /** Claude only: "low" | "medium" | "high" | "xhigh" | "max" | "" (omit). */
    val effort: String,
)

class Settings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    var maxSteps: Int
        get() = prefs.getInt("max_steps", 40)
        set(v) = prefs.edit().putInt("max_steps", v).apply()

    /** Whether the model may request screenshots (needs a vision-capable model and Android 11+). */
    var allowScreenshots: Boolean
        get() = prefs.getBoolean("screenshots", true)
        set(v) = prefs.edit().putBoolean("screenshots", v).apply()

    var wakeWordEnabled: Boolean
        get() = prefs.getBoolean("wake_enabled", true)
        set(v) = prefs.edit().putBoolean("wake_enabled", v).apply()

    /** Whether the user has Jarvis switched on. Only the user changes this, so "Turn off" sticks. */
    var switchedOn: Boolean
        get() = prefs.getBoolean("switched_on", false)
        set(v) = prefs.edit().putBoolean("switched_on", v).apply()

    var startOnBoot: Boolean
        get() = prefs.getBoolean("boot", false)
        set(v) = prefs.edit().putBoolean("boot", v).apply()

    /** Free-form facts the agent should know, e.g. "My wife is saved as 'Amaka' in WhatsApp". */
    var personalNotes: String
        get() = prefs.getString("notes", "")!!
        set(v) = prefs.edit().putString("notes", v).apply()

    /** Show the task island at the top of the screen while Jarvis works. */
    var showIsland: Boolean
        get() = prefs.getBoolean("island", true)
        set(v) = prefs.edit().putBoolean("island", v).apply()

    var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", false)
        set(v) = prefs.edit().putBoolean("onboarded", v).apply()

    // ---- Models ----

    /** Try the next saved model if the primary one fails mid-task. */
    var fallbackEnabled: Boolean
        get() = prefs.getBoolean("fallback", true)
        set(v) = prefs.edit().putBoolean("fallback", v).apply()

    fun profiles(): List<ModelProfile> {
        migrateLegacyProvider()
        val arr = JSONArray(prefs.getString("profiles", "[]"))
        return (0 until arr.length()).map { ModelProfile.fromJson(arr.getJSONObject(it)) }
    }

    var primaryId: String?
        get() = prefs.getString("primary", null)?.takeIf { id -> profiles().any { it.id == id } } ?: profiles().firstOrNull()?.id
        set(v) = prefs.edit().putString("primary", v).apply()

    fun primary(): ModelProfile? = profiles().let { all -> all.firstOrNull { it.id == primaryId } ?: all.firstOrNull() }

    /** Primary first, then the others in the order they were added. */
    fun modelsInOrder(): List<ModelProfile> {
        val all = profiles()
        val p = primary() ?: return all
        return listOf(p) + all.filter { it.id != p.id }
    }

    fun saveProfile(profile: ModelProfile) {
        val all = profiles().toMutableList()
        val i = all.indexOfFirst { it.id == profile.id }
        if (i >= 0) all[i] = profile else all += profile
        writeProfiles(all)
        if (all.size == 1) primaryId = profile.id
    }

    fun deleteProfile(id: String) {
        writeProfiles(profiles().filter { it.id != id })
        if (prefs.getString("primary", null) == id) primaryId = profiles().firstOrNull()?.id
    }

    private fun writeProfiles(list: List<ModelProfile>) =
        prefs.edit().putString("profiles", JSONArray(list.map { it.toJson() }).toString()).apply()

    /** Earlier versions stored one setting per provider; turn any that were filled in into profiles. */
    private fun migrateLegacyProvider() {
        if (prefs.contains("profiles")) return
        val migrated = PROVIDERS.mapNotNull { p ->
            val key = prefs.getString("p.${p.id}.key", "")!!
            val model = prefs.getString("p.${p.id}.model", null)
            if (key.isBlank() && (p.needsKey || model == null)) return@mapNotNull null
            ModelProfile(
                name = p.label.substringBefore(" ("), providerId = p.id,
                baseUrl = prefs.getString("p.${p.id}.base", null) ?: p.defaultBaseUrl, apiKey = key,
                model = model ?: p.defaultModel, effort = prefs.getString("p.${p.id}.effort", "medium")!!.ifEmpty { "medium" },
            )
        }
        val legacyPrimary = prefs.getString("provider", null)
        prefs.edit()
            .putString("profiles", JSONArray(migrated.map { it.toJson() }).toString())
            .putString("primary", migrated.firstOrNull { it.providerId == legacyPrimary }?.id ?: migrated.firstOrNull()?.id)
            .apply()
    }

    // ---- Voice ----

    /**
     * "kokoro" (natural on-device neural voice), "system" (Android text-to-speech), or the id of a
     * cloud voice in [CLOUD_VOICES] ("openai", "elevenlabs", "fish", "custom").
     */
    var voiceEngine: String
        get() = prefs.getString("voice_engine", "kokoro")!!
        set(v) = prefs.edit().putString("voice_engine", v).apply()

    var kokoroSpeaker: Int
        get() = prefs.getInt("kokoro_sid", KOKORO_VOICES.first().sid)
        set(v) = prefs.edit().putInt("kokoro_sid", v).apply()

    var voiceSpeed: Float
        get() = prefs.getFloat("voice_speed", 1.0f)
        set(v) = prefs.edit().putFloat("voice_speed", v).apply()

    // ---- Cloud voices ----

    fun cloudVoice(id: String): CloudVoiceConfig {
        val preset = CLOUD_VOICES.first { it.id == id }
        return CloudVoiceConfig(
            id = id,
            apiKey = prefs.getString("tts_${id}_key", "")!!.ifEmpty { if (id == "openai") openAiKeyFromModels() else "" },
            model = prefs.getString("tts_${id}_model", "")!!.ifEmpty { preset.defaultModel },
            voice = prefs.getString("tts_${id}_voice", null) ?: preset.defaultVoice,
            baseUrl = prefs.getString("tts_${id}_url", "")!!.ifEmpty { preset.defaultBaseUrl },
        )
    }

    fun saveCloudVoice(c: CloudVoiceConfig) {
        prefs.edit()
            .putString("tts_${c.id}_key", c.apiKey.trim())
            .putString("tts_${c.id}_model", c.model.trim())
            .putString("tts_${c.id}_voice", c.voice.trim())
            .putString("tts_${c.id}_url", c.baseUrl.trim().trimEnd('/'))
            .apply()
    }

    /** Re-use the key of an OpenAI model the user already added, so they don't have to paste it twice. */
    private fun openAiKeyFromModels(): String =
        modelsInOrder().firstOrNull { it.providerId == "openai" && it.apiKey.isNotBlank() }?.apiKey.orEmpty()

    // ---- Conversation ----

    /** Let the user talk over Jarvis to interrupt it (the microphone stays on while it speaks). */
    var interruptByTalking: Boolean
        get() = prefs.getBoolean("barge_in", true)
        set(v) = prefs.edit().putBoolean("barge_in", v).apply()

    /** After answering, keep listening for a few seconds so a follow-up needs no wake word. */
    var followUp: Boolean
        get() = prefs.getBoolean("follow_up", true)
        set(v) = prefs.edit().putBoolean("follow_up", v).apply()

    // ---- Assistant behaviour ----

    /** AUTO, ONLINE or OFFLINE. Controls which model answers; simple phone actions never use a model. */
    var aiMode: AiMode
        get() = AiMode.parse(prefs.getString("ai_mode", null))
        set(v) = prefs.edit().putString("ai_mode", v.name).apply()

    /** "en" = offline English recognizer; "hi" = Google recognizer in Hindi (also understands Hinglish). */
    var speechLanguage: String
        get() = prefs.getString("speech_lang", "en") ?: "en"
        set(v) = prefs.edit().putString("speech_lang", if (v == "hi") "hi" else "en").apply()

    /** Ask before placing calls or sending messages. */
    var confirmSensitive: Boolean
        get() = prefs.getBoolean("confirm_sensitive", true)
        set(v) = prefs.edit().putBoolean("confirm_sensitive", v).apply()

    /** Let cloud models see memories you asked Jarvis to keep. Off by default: memories stay on the phone. */
    var shareMemoryWithCloud: Boolean
        get() = prefs.getBoolean("share_memory_cloud", false)
        set(v) = prefs.edit().putBoolean("share_memory_cloud", v).apply()

    // ---- Local model server (llama-server in Termux) ----

    /** Start the local server through Termux when it is needed and not running. */
    var localServerAutoStart: Boolean
        get() = prefs.getBoolean("local_autostart", true)
        set(v) = prefs.edit().putBoolean("local_autostart", v).apply()

    /** Script installed by termux/install-termux.sh. Must be an absolute path inside Termux. */
    var termuxScriptPath: String
        get() = prefs.getString("termux_script", DEFAULT_TERMUX_SCRIPT) ?: DEFAULT_TERMUX_SCRIPT
        set(v) = prefs.edit().putString("termux_script", v.ifBlank { DEFAULT_TERMUX_SCRIPT }).apply()

    /** Local date (yyyy-MM-dd) of the last daily greeting. Kept across restarts so Jarvis greets once per day. */
    var lastGreetingDate: String?
        get() = prefs.getString("last_greeting_date", null)
        set(v) = prefs.edit().putString("last_greeting_date", v).apply()

    /** Speak a chat answer sentence by sentence as it is written, instead of waiting for the whole answer. */
    var streamReplies: Boolean
        get() = prefs.getBoolean("stream_replies", true)
        set(v) = prefs.edit().putBoolean("stream_replies", v).apply()

    /** Remember short lines about the tasks Jarvis did (the last 100), to answer "what did I ask about X?". Local only. */
    var rememberTasks: Boolean
        get() = prefs.getBoolean("remember_tasks", true)
        set(v) = prefs.edit().putBoolean("remember_tasks", v).apply()

    // ---- On-device model runtime (llama-server shipped inside the app) ----

    /** Use the model server that ships inside the app. Turn off to rely on Termux or cloud models only. */
    var embeddedRuntimeEnabled: Boolean
        get() = prefs.getBoolean("embedded_runtime", true)
        set(v) = prefs.edit().putBoolean("embedded_runtime", v).apply()

    /** Random key the app uses to talk to its own model server; no other app knows it. */
    var runtimeApiKey: String
        get() = prefs.getString("runtime_key", "") ?: ""
        set(v) = prefs.edit().putString("runtime_key", v).apply()

    /** Minutes without a request before the model is unloaded to free about 1 GB of memory. 0 = keep it loaded. */
    var runtimeKeepWarmMinutes: Int
        get() = prefs.getInt("runtime_keep_warm_min", 20)
        set(v) = prefs.edit().putInt("runtime_keep_warm_min", v.coerceAtLeast(0)).apply()

    /** Allow the 1 GB model download on mobile data. Off by default: it waits for Wi-Fi. */
    var downloadModelOnMobile: Boolean
        get() = prefs.getBoolean("model_download_mobile", false)
        set(v) = prefs.edit().putBoolean("model_download_mobile", v).apply()

    companion object {
        const val DEFAULT_TERMUX_SCRIPT = "/data/data/com.termux/files/home/.jarvis/start-llama.sh"
    }
}

data class CloudVoiceConfig(val id: String, val apiKey: String, val model: String, val voice: String, val baseUrl: String)

data class CloudVoicePreset(
    val id: String,
    val name: String,
    val defaultModel: String,
    val defaultVoice: String,
    val defaultBaseUrl: String,
    /** Where to get a key. */
    val keyHint: String,
    /** What to put in the voice field. */
    val voiceHint: String,
    val needsKey: Boolean = true,
    val editableUrl: Boolean = false,
)

/** Voices that run in the cloud. Audio streams back as it's generated, so speech starts quickly. */
val CLOUD_VOICES = listOf(
    CloudVoicePreset(
        "openai", "OpenAI", "gpt-4o-mini-tts", "onyx", "https://api.openai.com/v1",
        keyHint = "Get a key at platform.openai.com → API keys. If you've added an OpenAI model, its key is used automatically.",
        voiceHint = "alloy, ash, ballad, coral, echo, fable, onyx, nova, sage, shimmer or verse",
    ),
    CloudVoicePreset(
        "elevenlabs", "ElevenLabs", "eleven_flash_v2_5", "JBFqnCBsd6RMkjVDRZzb", "https://api.elevenlabs.io/v1",
        keyHint = "Get a key at elevenlabs.io → Developers → API keys.",
        voiceHint = "A voice ID from your ElevenLabs voice library. The default is George, a warm British voice.",
    ),
    CloudVoicePreset(
        "fish", "Fish Audio", "s2.1-pro", "", "https://api.fish.audio/v1",
        keyHint = "Get a key at fish.audio → API keys.",
        voiceHint = "The ID of a voice on fish.audio (open the voice and copy the ID from its page). Leave empty for the default voice.",
    ),
    CloudVoicePreset(
        "custom", "Custom", "tts-1", "alloy", "http://192.168.1.10:8880/v1",
        keyHint = "Any server with an OpenAI-compatible /audio/speech endpoint, such as Kokoro-FastAPI on your computer. A key is optional.",
        voiceHint = "The voice name your server expects.",
        needsKey = false, editableUrl = true,
    ),
)

data class KokoroVoice(val sid: Int, val name: String, val accent: String)

/** Speakers in the kokoro-en-v0_19 model; sid is the model's speaker id. British male first: it suits Jarvis. */
val KOKORO_VOICES = listOf(
    KokoroVoice(9, "George", "British male"),
    KokoroVoice(10, "Lewis", "British male"),
    KokoroVoice(7, "Emma", "British female"),
    KokoroVoice(8, "Isabella", "British female"),
    KokoroVoice(5, "Adam", "American male"),
    KokoroVoice(6, "Michael", "American male"),
    KokoroVoice(1, "Bella", "American female"),
    KokoroVoice(2, "Nicole", "American female"),
    KokoroVoice(3, "Sarah", "American female"),
    KokoroVoice(4, "Sky", "American female"),
    KokoroVoice(0, "Default", "American female"),
)
