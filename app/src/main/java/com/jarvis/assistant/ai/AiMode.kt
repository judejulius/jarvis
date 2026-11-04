package com.jarvis.assistant.ai

/** How Jarvis chooses between the on-phone model and cloud providers. Simple phone actions never depend on this. */
enum class AiMode {
    /** Decide per request: local Qwen for short simple chat, cloud for hard, current-info or Hindi/Hinglish requests. */
    AUTO,

    /** Prefer cloud providers when the internet works; the local model is the fallback. */
    ONLINE,

    /** Only models running on the phone or the local network. Nothing leaves the device. */
    OFFLINE;

    companion object {
        fun parse(value: String?): AiMode = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: AUTO
    }
}

enum class TaskKind { CHAT, AGENT }
