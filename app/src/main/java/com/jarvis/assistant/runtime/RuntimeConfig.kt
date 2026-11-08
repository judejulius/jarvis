package com.jarvis.assistant.runtime

import java.security.SecureRandom

/** Fixed settings for the on-device model server. Pure, so the command line can be unit-tested. */
object RuntimeConfig {
    /** An unusual loopback port, so it never clashes with a Termux server on 8080. */
    const val PORT = 18080
    const val MODEL_FILE = "qwen2.5-1.5b-instruct-q4_k_m.gguf"
    const val MODEL_NAME = "qwen2.5-1.5b-instruct"
    const val BINARY = "libllama_server.so"
    const val BUILTIN_PROFILE_ID = "builtin-on-device-qwen"

    /** Tried in order. Both are public Hugging Face repositories for the same model (Qwen2.5-1.5B-Instruct, Q4_K_M). */
    val MODEL_URLS = listOf(
        "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
        "https://huggingface.co/bartowski/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-Q4_K_M.gguf",
    )

    /** Four threads on an 8-core phone: the big cores do the work, the small ones keep the rest of the phone responsive. */
    fun threadsFor(cores: Int): Int = (cores / 2).coerceIn(2, 4)

    /**
     * Command-line options for llama-server. Listens on the loopback address only and demands an API key, so no other
     * device (and no other app that does not know the key) can use it.
     */
    fun args(model: String, port: Int, apiKey: String, threads: Int, context: Int = 4096): List<String> = listOf(
        "-m", model,
        "--host", "127.0.0.1",
        "--port", port.toString(),
        "-c", context.toString(),
        "-t", threads.toString(),
        "-np", "1",
        "--cache-ram", "512",
        "--api-key", apiKey,
        "--no-webui",
    )

    fun newApiKey(): String {
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

/** What the runtime supervisor should do after a periodic check. */
object RuntimePolicy {
    enum class Action { NONE, RESTART, KILL_HUNG, STOP_IDLE }

    fun next(processAlive: Boolean, failedHealthChecks: Int, idleMs: Long, keepWarmMs: Long): Action = when {
        !processAlive -> if (keepWarmMs > 0 && idleMs > keepWarmMs) Action.STOP_IDLE else Action.RESTART
        failedHealthChecks >= 3 -> Action.KILL_HUNG
        keepWarmMs > 0 && idleMs > keepWarmMs -> Action.STOP_IDLE
        else -> Action.NONE
    }

    /** 2 s, 4 s, 8 s ... at most a minute. */
    fun restartDelayMs(consecutiveCrashes: Int): Long = (2000L shl (consecutiveCrashes - 1).coerceIn(0, 5)).coerceAtMost(60_000L)

    /** After this many crashes in a row the runtime stays down and says why, instead of restarting forever. */
    fun giveUp(consecutiveCrashes: Int) = consecutiveCrashes >= 5
}
