package com.jarvis.assistant.ai

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import com.jarvis.assistant.Settings

/**
 * Keeps the on-phone model server (llama-server in Termux) available: checks that it answers, asks Termux to start it
 * when it does not, and never starts it twice in a row. Android gives no guarantee that a background process stays
 * alive, so this checks before every use instead of assuming.
 */
class LocalServerManager(context: Context, private val settings: Settings) {
    enum class StartResult { SENT, THROTTLED, DISABLED, NO_TERMUX, NO_PERMISSION, FAILED }

    private val app = context.applicationContext
    private val throttle = StartThrottle(SystemClock::elapsedRealtime)

    @Volatile var lastStart: StartResult? = null
        private set

    fun health(baseUrl: String, timeoutMs: Int = 700): ServerHealth.State {
        val origin = Endpoints.origin(baseUrl) ?: return ServerHealth.State.DOWN
        return ServerHealth.check(origin, timeoutMs)
    }

    fun isTermuxInstalled(): Boolean = try {
        app.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Asks Termux to run the start script. The script itself refuses to start a second server. */
    fun requestStart(): StartResult {
        val result = when {
            !settings.localServerAutoStart -> StartResult.DISABLED
            !isTermuxInstalled() -> StartResult.NO_TERMUX
            !throttle.tryAcquire() -> StartResult.THROTTLED
            else -> try {
                val intent = Intent(RUN_COMMAND_ACTION)
                    .setClassName(TERMUX_PACKAGE, "com.termux.app.RunCommandService")
                    .putExtra("com.termux.RUN_COMMAND_PATH", settings.termuxScriptPath)
                    .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf<String>())
                    .putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
                    .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                if (app.startService(intent) != null) StartResult.SENT else StartResult.FAILED
            } catch (_: SecurityException) {
                StartResult.NO_PERMISSION
            } catch (e: RuntimeException) {
                Log.w(TAG, "could not ask Termux to start the server", e)
                StartResult.FAILED
            }
        }
        lastStart = result
        Log.i(TAG, "start request: $result")
        return result
    }

    /**
     * Gets the server at [baseUrl] ready. Returns true when it answers. If it is down, asks Termux to start it. With
     * [hasAlternative] (another model can answer) it does not wait; otherwise it waits up to [START_WAIT_MS].
     */
    fun prepare(baseUrl: String, hasAlternative: Boolean, status: (String) -> Unit): Boolean {
        var h = health(baseUrl)
        if (h == ServerHealth.State.OK) return true
        if (h == ServerHealth.State.DOWN) requestStart()
        if (hasAlternative) return false
        status("Starting my local model…")
        val deadline = SystemClock.elapsedRealtime() + START_WAIT_MS
        try {
            while (SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(500)
                h = health(baseUrl)
                if (h == ServerHealth.State.OK) return true
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return false
    }

    /** One sentence for the user about why the local model is not available. */
    fun explainFailure(): String = when (lastStart) {
        StartResult.NO_TERMUX -> "Termux isn't installed, so I can't start the local model."
        StartResult.NO_PERMISSION -> "Jarvis needs the Termux 'run commands' permission to start the local model."
        StartResult.DISABLED -> "The local model isn't running and auto-start is off."
        StartResult.FAILED, StartResult.SENT, StartResult.THROTTLED ->
            "The local model didn't start. Run the Termux setup once, as described in the install notes."
        null -> "The local model isn't running."
    }

    companion object {
        private const val TAG = "LocalServer"
        const val TERMUX_PACKAGE = "com.termux"
        const val RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"
        const val START_WAIT_MS = 30_000L
    }
}
