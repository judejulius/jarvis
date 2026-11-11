package com.jarvis.assistant.runtime

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.jarvis.assistant.Settings
import com.jarvis.assistant.ai.Connectivity
import com.jarvis.assistant.ai.ServerHealth
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs the on-device model server (llama-server) as a child process of the app and keeps it healthy: starts it on
 * demand, reuses one that is already answering, restarts it after a crash or a hang (with a growing pause and a limit),
 * and stops it after a while without use to give the memory back. The binary ships inside the APK; the model file is
 * downloaded once, with progress, resume and verification.
 *
 * Android does not promise that any background process survives. This is a supervisor inside the app's own process,
 * so it lives exactly as long as the Jarvis service does; if Android kills the app, the next use starts it again.
 * Call the blocking methods from a worker thread.
 */
class EmbeddedRuntime private constructor(context: Context) {
    enum class Phase { NO_BINARY, NO_MODEL, DOWNLOADING, STOPPED, STARTING, RUNNING, FAILED }

    data class Status(val phase: Phase, val detail: String = "", val percent: Int = -1)

    private val app = context.applicationContext
    private val settings = Settings(app)
    private val lock = Any()
    private val downloadRunning = AtomicBoolean(false)

    @Volatile private var process: Process? = null
    @Volatile private var supervisor: Thread? = null
    @Volatile private var lastUse = SystemClock.elapsedRealtime()
    @Volatile private var crashes = 0
    @Volatile private var downloader: GgufDownloader? = null

    @Volatile var status: Status = Status(if (binary() == null) Phase.NO_BINARY else if (hasModel()) Phase.STOPPED else Phase.NO_MODEL)
        private set

    @Volatile var onStatus: ((Status) -> Unit)? = null

    private fun set(s: Status) {
        status = s
        onStatus?.invoke(s)
    }

    // ---- what is installed ----

    fun binary(): File? = File(app.applicationInfo.nativeLibraryDir, RuntimeConfig.BINARY).takeIf { it.isFile }

    fun isSupported(): Boolean = Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a") && binary() != null

    fun modelFile(): File = File(File(app.filesDir, "models"), RuntimeConfig.MODEL_FILE)

    fun hasModel(): Boolean = GgufDownloader("https://invalid.example/", modelFile()).isComplete()

    fun baseUrl(): String = "http://127.0.0.1:${RuntimeConfig.PORT}/v1"

    fun apiKey(): String = settings.runtimeApiKey.ifBlank { RuntimeConfig.newApiKey().also { settings.runtimeApiKey = it } }

    /** Marks the runtime as in use now, so the idle timer starts over. */
    fun touch() {
        lastUse = SystemClock.elapsedRealtime()
    }

    // ---- model download ----

    /**
     * Downloads the model if it is missing. Returns at once; progress arrives through [onStatus]. Does nothing if a
     * download is already running, the model is present, or there is no usable connection. On mobile data it waits
     * for [allowMobileData] (the user's choice) because the file is about 1 GB.
     */
    fun startDownloadIfNeeded(allowMobileData: Boolean) {
        if (hasModel()) { if (status.phase == Phase.NO_MODEL) set(Status(Phase.STOPPED)); return }
        if (!Connectivity.isOnline(app)) { set(Status(Phase.NO_MODEL, "Connect to the internet to download the on-device model.")); return }
        if (!allowMobileData && Connectivity.isMetered(app)) {
            set(Status(Phase.NO_MODEL, "The on-device model (about 1 GB) will download on Wi-Fi, or allow mobile data in Settings."))
            return
        }
        if (!downloadRunning.compareAndSet(false, true)) return
        Thread({
            try {
                downloadNow()
            } finally {
                downloadRunning.set(false)
            }
        }, "jarvis-model-download").apply { isDaemon = true }.start()
    }

    fun cancelDownload() {
        downloader?.cancel()
    }

    private fun downloadNow() {
        var lastReason = "unknown"
        for (url in RuntimeConfig.MODEL_URLS) {
            val d = GgufDownloader(url, modelFile())
            downloader = d
            var lastPct = -1
            val result = d.download(
                onProgress = { done, total ->
                    val pct = if (total > 0) (done * 100 / total).toInt() else -1
                    if (pct != lastPct) { lastPct = pct; set(Status(Phase.DOWNLOADING, "Downloading the on-device model", pct)) }
                },
                onPhase = { p -> set(Status(Phase.DOWNLOADING, p, lastPct)) },
            )
            when (result) {
                is GgufDownloader.Result.Ok -> { set(Status(Phase.STOPPED)); return }
                GgufDownloader.Result.Cancelled -> { set(Status(Phase.NO_MODEL, "Download paused. It will resume where it stopped.")); return }
                is GgufDownloader.Result.Failed -> {
                    lastReason = result.reason
                    Log.w(TAG, "download from one source failed: ${result.reason}")
                    if (!result.retryable) break // storage and file-system problems are the same for every source
                }
            }
        }
        set(Status(Phase.NO_MODEL, lastReason))
    }

    // ---- running the server ----

    /** True once the server answers. Starts it (and waits up to [timeoutMs]) if it is not running. */
    fun ensureRunning(timeoutMs: Long = START_WAIT_MS): Boolean {
        touch()
        if (health() == ServerHealth.State.OK) { if (status.phase != Phase.RUNNING) set(Status(Phase.RUNNING)); return true }
        val bin = binary() ?: run { set(Status(Phase.NO_BINARY, "This build has no on-device runtime.")); return false }
        if (!hasModel()) { set(Status(Phase.NO_MODEL, status.detail)); return false }
        synchronized(lock) {
            if (health() == ServerHealth.State.OK) return true // someone else just started it
            if (RuntimePolicy.giveUp(crashes)) {
                set(Status(Phase.FAILED, "The on-device model keeps crashing. See ${logFile().name} in the app's files."))
                return false
            }
            if (process?.isAlive != true) spawn(bin) ?: return false
            return waitUntilHealthy(timeoutMs)
        }
    }

    fun health(timeoutMs: Int = 600): ServerHealth.State = ServerHealth.check("http://127.0.0.1:${RuntimeConfig.PORT}", timeoutMs)

    private fun spawn(bin: File): Process? {
        set(Status(Phase.STARTING, "Starting the on-device model"))
        val cmd = listOf(bin.path) + RuntimeConfig.args(
            modelFile().path, RuntimeConfig.PORT, apiKey(), RuntimeConfig.threadsFor(Runtime.getRuntime().availableProcessors()),
        )
        return try {
            val pb = ProcessBuilder(cmd).redirectErrorStream(true).directory(app.filesDir)
            pb.environment()["LD_LIBRARY_PATH"] = app.applicationInfo.nativeLibraryDir
            val p = pb.start()
            process = p
            writePid(p)
            drainToLog(p)
            startSupervisor()
            p
        } catch (e: Exception) {
            Log.e(TAG, "could not start the on-device runtime", e)
            crashes++
            set(Status(Phase.FAILED, "Could not start the on-device model: ${e.message}"))
            null
        }
    }

    private fun waitUntilHealthy(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        try {
            while (SystemClock.elapsedRealtime() < deadline) {
                if (health() == ServerHealth.State.OK) { crashes = 0; set(Status(Phase.RUNNING)); return true }
                if (process?.isAlive != true) {
                    crashes++
                    set(Status(Phase.FAILED, "The on-device model stopped while starting. ${lastLogLine()}"))
                    return false
                }
                Thread.sleep(300)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        set(Status(Phase.STARTING, "Still loading the model…"))
        return false
    }

    /** Watches the process: restarts it after a crash, kills a hung one, stops an idle one. */
    private fun startSupervisor() {
        if (supervisor?.isAlive == true) return
        supervisor = Thread({
            var failedChecks = 0
            try {
                while (true) {
                    Thread.sleep(CHECK_EVERY_MS)
                    val p = process
                    if (p == null && status.phase == Phase.STOPPED) return@Thread
                    val idle = SystemClock.elapsedRealtime() - lastUse
                    failedChecks = if (health() == ServerHealth.State.OK) 0 else failedChecks + 1
                    when (RuntimePolicy.next(p?.isAlive == true, failedChecks, idle, settings.runtimeKeepWarmMinutes * 60_000L)) {
                        RuntimePolicy.Action.NONE -> Unit
                        RuntimePolicy.Action.STOP_IDLE -> { stop("idle"); return@Thread }
                        RuntimePolicy.Action.KILL_HUNG -> { Log.w(TAG, "server not answering: restarting it"); p?.destroyForcibly(); failedChecks = 0 }
                        RuntimePolicy.Action.RESTART -> {
                            crashes++
                            if (RuntimePolicy.giveUp(crashes)) { set(Status(Phase.FAILED, "The on-device model keeps crashing.")); return@Thread }
                            Log.w(TAG, "server exited: restarting (crash $crashes)")
                            Thread.sleep(RuntimePolicy.restartDelayMs(crashes))
                            synchronized(lock) { binary()?.let { if (process?.isAlive != true) spawn(it) } }
                        }
                    }
                }
            } catch (_: InterruptedException) {
                // stopped on purpose
            }
        }, "jarvis-runtime-supervisor").apply { isDaemon = true; start() }
    }

    /** Stops the server and frees its memory. It starts again on the next use. */
    fun stop(reason: String = "requested") {
        synchronized(lock) {
            Log.i(TAG, "stopping the on-device runtime ($reason)")
            process?.destroy()
            process = null
            killRecordedPid()
            if (status.phase != Phase.DOWNLOADING) set(Status(if (hasModel()) Phase.STOPPED else Phase.NO_MODEL))
        }
        supervisor?.interrupt()
        supervisor = null
    }

    // ---- bookkeeping ----

    private fun logFile() = File(app.filesDir, "llama.log")
    private fun pidFile() = File(app.filesDir, "llama.pid")

    private fun lastLogLine(): String = runCatching { logFile().readLines().lastOrNull { it.isNotBlank() }.orEmpty().take(160) }.getOrDefault("")

    private fun drainToLog(p: Process) {
        Thread({
            try {
                val log = logFile()
                if (log.length() > 1_000_000) log.renameTo(File(log.path + ".1"))
                log.appendText("--- start ${java.util.Date()} ---\n")
                p.inputStream.bufferedReader().useLines { lines -> lines.forEach { log.appendText(it + "\n") } }
            } catch (_: Exception) {
                // process ended
            }
        }, "jarvis-runtime-log").apply { isDaemon = true; start() }
    }

    /** Remembers the child's process id (Android 13+), so a server left over from a killed app can be stopped. */
    private fun writePid(p: Process) {
        runCatching {
            val pid = Process::class.java.getMethod("pid").invoke(p) as Long
            pidFile().writeText(pid.toString())
        }
    }

    private fun killRecordedPid() {
        runCatching {
            val f = pidFile()
            if (f.isFile) { android.os.Process.killProcess(f.readText().trim().toInt()); f.delete() }
        }
    }

    companion object {
        private const val TAG = "EmbeddedRuntime"
        private const val START_WAIT_MS = 45_000L
        private const val CHECK_EVERY_MS = 15_000L

        @Volatile private var instance: EmbeddedRuntime? = null

        fun get(context: Context): EmbeddedRuntime =
            instance ?: synchronized(this) { instance ?: EmbeddedRuntime(context).also { instance = it } }
    }
}
