package com.jarvis.assistant.agent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Supervises the local Qwen llama-server running inside Termux.
 * Handles health checking, automatic startup via Termux RunCommandService,
 * crash recovery, and polling without locking the UI thread.
 */
object LocalServerManager {

    private const val TAG = "LocalServerManager"
    const val DEFAULT_LOCAL_BASE_URL = "http://127.0.0.1:8080"
    const val HEALTH_ENDPOINT = "/health"
    const val TERMUX_PACKAGE = "com.termux"
    const val TERMUX_RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
    const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(1200, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .build()

    @Volatile var isStarting = false
        private set

    private val startingLock = Any()

    /**
     * Checks if the local llama-server is healthy and ready to accept completions.
     */
    fun isServerHealthy(baseUrl: String = DEFAULT_LOCAL_BASE_URL): Boolean {
        val url = baseUrl.trimEnd('/') + HEALTH_ENDPOINT
        val req = Request.Builder().url(url).get().build()
        return try {
            httpClient.newCall(req).execute().use { resp ->
                resp.isSuccessful
            }
        } catch (e: Exception) {
            // Also test fallback endpoint /v1/models if /health is not implemented in custom build
            try {
                val fallbackReq = Request.Builder().url(baseUrl.trimEnd('/') + "/v1/models").get().build()
                httpClient.newCall(fallbackReq).execute().use { it.isSuccessful }
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * Ensures llama-server is running. If not responding, triggers automatic start
     * through Termux RUN_COMMAND or background intent and polls until healthy or timed out.
     */
    fun ensureServerRunning(
        context: Context,
        baseUrl: String = DEFAULT_LOCAL_BASE_URL,
        timeoutSeconds: Int = 8,
        onStatus: (String) -> Unit = {},
        onComplete: (Boolean) -> Unit
    ) {
        if (isServerHealthy(baseUrl)) {
            onComplete(true)
            return
        }

        synchronized(startingLock) {
            if (isStarting) {
                // Another request already triggered startup, just poll
                pollForHealth(baseUrl, timeoutSeconds, onStatus, onComplete)
                return
            }
            isStarting = true
        }

        onStatus("Starting local Qwen server in Termux…")
        val triggered = triggerTermuxStartup(context)

        if (!triggered) {
            isStarting = false
            onStatus("Could not trigger Termux. Please ensure Termux is installed.")
            onComplete(false)
            return
        }

        pollForHealth(baseUrl, timeoutSeconds, onStatus) { ready ->
            isStarting = false
            onComplete(ready)
        }
    }

    private fun triggerTermuxStartup(context: Context): Boolean {
        val pm = context.packageManager
        val isTermuxInstalled = runCatching {
            pm.getPackageInfo(TERMUX_PACKAGE, 0) != null
        }.getOrDefault(false)

        if (!isTermuxInstalled) {
            Log.w(TAG, "Termux is not installed")
            return false
        }

        val bashCommand = "pgrep -f llama-server >/dev/null || (tmux has-session -t jarvis-ai 2>/dev/null && tmux kill-session -t jarvis-ai; tmux new-session -d -s jarvis-ai 'llama-server -m ~/jarvis/models/Qwen2.5-1.5B-Instruct-Q4_K_M.gguf --host 127.0.0.1 --port 8080 -c 2048 -t 4 --n-predict 256')"

        return try {
            val intent = Intent().apply {
                setClassName(TERMUX_PACKAGE, TERMUX_RUN_COMMAND_SERVICE)
                action = ACTION_RUN_COMMAND
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", bashCommand))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
            }
            context.startService(intent)
            Log.i(TAG, "Sent RUN_COMMAND intent to Termux RunCommandService")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start Termux RunCommandService directly; attempting fallback", e)
            try {
                // Secondary fallback: launch Termux main intent if permission or background service blocked
                val launchIntent = pm.getLaunchIntentForPackage(TERMUX_PACKAGE)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    true
                } else false
            } catch (ex: Exception) {
                Log.e(TAG, "Fallback launch failed", ex)
                false
            }
        }
    }

    private fun pollForHealth(
        baseUrl: String,
        timeoutSeconds: Int,
        onStatus: (String) -> Unit,
        onComplete: (Boolean) -> Unit
    ) {
        val start = SystemClock.elapsedRealtime()
        val deadline = start + (timeoutSeconds * 1000L)

        Thread({
            var healthy = false
            var attempt = 1
            while (SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(800)
                if (isServerHealthy(baseUrl)) {
                    healthy = true
                    break
                }
                onStatus("Waiting for local Qwen to load (try $attempt)…")
                attempt++
            }
            onComplete(healthy)
        }, "qwen-health-poller").start()
    }
}
