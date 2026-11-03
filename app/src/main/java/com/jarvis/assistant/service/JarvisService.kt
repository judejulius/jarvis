package com.jarvis.assistant.service

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.Settings
import com.jarvis.assistant.OperatingMode
import com.jarvis.assistant.agent.AgentHost
import com.jarvis.assistant.agent.FastCommandRouter
import com.jarvis.assistant.agent.LocalServerManager
import com.jarvis.assistant.agent.PhoneAgent
import com.jarvis.assistant.agent.Llm
import com.jarvis.assistant.agent.ChatSession
import com.jarvis.assistant.memory.MemoryManager
import com.jarvis.assistant.ui.MainActivity
import com.jarvis.assistant.voice.CommandListener
import com.jarvis.assistant.voice.Speaker
import com.jarvis.assistant.voice.VoiceInput
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Long-running foreground service: waits for "Hey Jarvis", listens for the command, runs the
 * phone agent on a worker thread, and speaks the result. Voice work happens on the main thread.
 */
class JarvisService : Service() {

    private enum class State { IDLE, LISTENING_COMMAND, WORKING }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var settings: Settings
    private lateinit var speaker: Speaker
    private lateinit var voice: VoiceInput
    /** Fallback when the offline speech model isn't downloaded. */
    private lateinit var google: CommandListener
    @Volatile private var state = State.IDLE
    @Volatile private var cancelled = false
    private var startFailed = false
    private var wakeLock: PowerManager.WakeLock? = null
    /** Set in onDestroy; stops late callbacks (speech finishing, models loading) from listening again. */
    @Volatile private var destroyed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Models from earlier versions that are no longer used.
        listOf("vosk-model", "kws.old").forEach { java.io.File(filesDir, it).takeIf { f -> f.exists() }?.deleteRecursively() }
        settings = Settings(this)
        speaker = Speaker(this)
        voice = VoiceInput(this) { onWakeWord() }
        google = CommandListener(this)
        createChannel()
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification("Starting…"),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            )
        } catch (e: Exception) {
            // Android refuses microphone services without the permission, or when started from the background.
            Log.w(TAG, "could not start in the foreground", e)
            startFailed = true
            stopSelf()
            return
        }
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:wake_continuous")
            wakeLock?.acquire(24 * 60 * 60 * 1000L)
        } catch (e: Exception) {
            Log.w(TAG, "could not acquire wake lock", e)
        }
        running = true
        resumeListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (startFailed) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_STOP_TASK -> {
                cancelled = true
                taskId++
                activeSession?.cancel()
                activeSession = null
                voice.cancelCommand()
                google.cancel()
                stopSpeaking()
                state = State.IDLE
                island { it.hide() }
                update("Cancelled")
                if (settings.switchedOn) {
                    resumeListening()
                }
            }
            ACTION_SHUTDOWN -> {
                settings.switchedOn = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RUN -> intent.getStringExtra(EXTRA_TASK)?.let { runTask(it) }
            ACTION_LISTEN -> if (state == State.IDLE) onWakeWord()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        running = false
        phase = Phase.OFF
        status = "Offline"
        statusListener?.invoke(status)
        cancelled = true
        taskId++
        activeSession?.cancel()
        activeSession = null
        island { it.hide() }
        if (!startFailed) {
            voice.release()
            google.cancel()
            speaker.shutdown()
        }
        worker.shutdownNow()
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}
        wakeLock = null
        super.onDestroy()
    }

    // ---- Voice flow ----
    //
    // A conversation: "Hey Jarvis" (or a follow-up straight after a reply) → listen → work → reply.
    // The microphone stays open while Jarvis works and while it replies, so the user can interrupt
    // at any point: by saying "Hey Jarvis" or, during a reply, simply by talking over it.

    /** What to do when the current speech ends naturally (also run if it's stopped from the island). */
    private var afterSpeech: (() -> Unit)? = null
    private var speechId = 0
    /** Increases with every task; a result from a task that was interrupted is never spoken. */
    @Volatile private var taskId = 0
    @Volatile private var activeSession: ChatSession? = null
    /** Recent requests and replies, so follow-ups like "and tell her I'll be late" make sense. */
    private val conversation = ArrayDeque<Pair<String, String>>()
    private var lastExchangeAt = 0L

    /**
     * Speaks [text]. If [interruptible] (and the user allows it), the microphone stays open and
     * talking over Jarvis stops it and becomes the next command; otherwise the microphone is muted
     * so Jarvis never hears itself.
     */
    private fun say(text: String, interruptible: Boolean = false, then: () -> Unit = {}) {
        val id = ++speechId
        val bargeIn = interruptible && settings.interruptByTalking && voice.canTranscribe && voice.isListening
        if (bargeIn) voice.listenWhileSpeaking(text) { stop -> onBargeIn(stop) } else voice.muted = true
        afterSpeech = {
            voice.stopListeningWhileSpeaking()
            // After muted speech, let the speaker's echo die away before listening again.
            main.postDelayed({ voice.muted = false; then() }, if (bargeIn) 0L else 150L)
        }
        island { it.speaking(text) { speaker.level() } }
        speaker.speak(text) { if (id == speechId) finishSpeech() }
    }

    private fun finishSpeech() {
        val then = afterSpeech ?: return
        afterSpeech = null
        island { if (it.isShowing) it.speakingDone() }
        then()
    }

    /** Stops talking now, carrying on as if the speech had finished. */
    private fun stopSpeaking() {
        speechId++
        speaker.stop()
        if (afterSpeech != null) finishSpeech() else island { if (it.isShowing) it.speakingDone() }
    }

    /** Stops talking now and forgets what was going to happen next (the user has taken over). */
    private fun interruptSpeech() {
        speechId++
        afterSpeech = null
        voice.stopListeningWhileSpeaking()
        voice.muted = false
        speaker.stop()
        island { if (it.isShowing) it.speakingDone() }
    }

    private fun onWakeWord() {
        if (destroyed) return
        when (state) {
            State.LISTENING_COMMAND -> return
            State.WORKING -> {
                // "Hey Jarvis" during a task: stop it and take the new command.
                cancelled = true
                taskId++
                activeSession?.cancel()
                activeSession = null
                interruptSpeech()
                listen(promptIfQuiet = true)
            }
            State.IDLE -> {
                interruptSpeech()
                listen(promptIfQuiet = true)
            }
        }
    }

    /** The user talked over Jarvis: what they're saying is the next command, or they said "stop". */
    private fun onBargeIn(stop: Boolean) {
        if (destroyed) return
        interruptSpeech()
        if (state == State.WORKING) {
            cancelled = true
            taskId++
            activeSession?.cancel()
            activeSession = null
        }
        if (stop) {
            state = State.IDLE
            voice.cancelCommand()
            island { it.hide() }
            update("Okay")
            resumeListening()
        } else {
            listen(promptIfQuiet = false, interrupted = true)
        }
    }

    /**
     * Transcribes the next thing the user says. After "Hey Jarvis" ([promptIfQuiet]) Jarvis says
     * "Yes?" if they pause; for a [followUp] it waits quietly for a few seconds and gives up silently.
     */
    private fun listen(promptIfQuiet: Boolean, followUp: Boolean = false, interrupted: Boolean = false) {
        state = State.LISTENING_COMMAND
        update("Listening…")
        speaker.warmUp() // load the voice now, so the reply has no delay
        island { it.listening(if (voice.canTranscribe) ({ voice.partialText }) else null, { voice.level }) }
        if (voice.canTranscribe) {
            voice.start()
            // Keeps transcribing from the wake word on, so "Hey Jarvis, call mum" works in one breath.
            voice.listenForCommand(if (followUp) FOLLOW_UP_MS else 7000) { text -> onHeard(text, followUp, interrupted) }
            if (promptIfQuiet) main.postDelayed({
                // Not muted: if the user starts talking over it, nothing is lost (a stray "yes" is stripped).
                if (state == State.LISTENING_COMMAND && !voice.speechSinceWake && voice.partialText.isEmpty()) {
                    island { it.speaking("Yes?") { speaker.level() } }
                    speaker.speak("Yes?") { island { if (it.isShowing) it.speakingDone() } }
                }
            }, 700)
        } else {
            voice.stop() // Google's recognizer needs the microphone
            val heard: (String?) -> Unit = { text -> onHeard(text, followUp) }
            if (followUp) google.listen(heard) else say("Yes?") { google.listen(heard) }
        }
    }

    private fun onHeard(text: String?, followUp: Boolean = false, interrupted: Boolean = false) {
        if (state != State.LISTENING_COMMAND) return
        when {
            // A one-word interruption is almost always "stop", "wait" or "no", often half drowned out
            // by Jarvis's voice. Don't act on it; just listen for what comes next.
            interrupted && text != null && VoiceInput.words(text).size <= 1 && !VoiceInput.isStopCommand(text) ->
                listen(promptIfQuiet = false, followUp = true)
            text.isNullOrBlank() && followUp -> {
                // Nothing more was said after a reply: go back to waiting, quietly.
                state = State.IDLE
                island { it.hide() }
                resumeListening()
            }
            text.isNullOrBlank() -> {
                state = State.IDLE
                update("Didn't catch that")
                say("Sorry, I didn't catch that.", interruptible = true) { island { it.hide() }; resumeListening() }
            }
            VoiceInput.isStopCommand(text) -> {
                state = State.IDLE
                island { it.hide() }
                update("Okay")
                resumeListening()
            }
            else -> runTask(text)
        }
    }


    /** Route clear phone actions to the phone agent; ordinary conversation goes straight to the LLM. */
    private fun isPhoneAction(task: String): Boolean {
        val t = task.lowercase()
        val patterns = listOf(
            Regex("""\b(open|launch|close|exit|switch to|go to|tap|click|press|scroll|swipe|type|enter|send|text|call|dial|hang up|answer|reject|play|pause|skip|next|previous|turn on|turn off|enable|disable|increase|decrease|raise|lower|volume|brightness|screenshot|lock|unlock|navigate|share|copy|paste|set alarm|set a reminder|set reminder|set timer|change wallpaper)\b"""),
            Regex("""(kholo|khol do|band karo|bhejo|bhej do|call karo|phone karo|lagao|laga do|bajao|chalao|chala do|on karo|off karo|badha do|kam karo|ghata do|screenshot lo|photo lo|record karo|mute karo|unmute karo|silent kar do|alarm laga|reminder laga)""")
        )
        return patterns.any { it.containsMatchIn(t) }
    }

    /** Lightweight conversational path: no accessibility, screen observation or phone tools. */
    private fun runChatTask(
        task: String,
        earlier: List<Pair<String, String>>,
        id: Int
    ): com.jarvis.assistant.agent.AgentResult {
        val allModels = settings.modelsInOrder()
        if (allModels.isEmpty()) {
            return com.jarvis.assistant.agent.AgentResult(false, "No AI model is set up yet.")
        }

        val isOffline = settings.operatingMode == OperatingMode.OFFLINE
        val isOnline = settings.operatingMode == OperatingMode.ONLINE

        val models = when {
            isOffline -> allModels.filter { it.baseUrl.contains("localhost") || it.baseUrl.contains("127.0.0.1") || it.providerId == "termux_qwen" || it.providerId == "custom" }
            isOnline -> allModels.filter { !(it.baseUrl.contains("localhost") || it.baseUrl.contains("127.0.0.1") || it.providerId == "termux_qwen") } +
                allModels.filter { it.baseUrl.contains("localhost") || it.baseUrl.contains("127.0.0.1") || it.providerId == "termux_qwen" }
            else -> { // AUTOMATIC mode
                val localModel = allModels.firstOrNull { it.baseUrl.contains("localhost") || it.baseUrl.contains("127.0.0.1") || it.providerId == "termux_qwen" }
                val isLocalHealthy = localModel != null && LocalServerManager.isServerHealthy(localModel.baseUrl)
                if (isLocalHealthy) {
                    listOf(localModel!!) + allModels.filter { it.id != localModel.id }
                } else {
                    val cloudModels = allModels.filter { !(it.baseUrl.contains("localhost") || it.baseUrl.contains("127.0.0.1") || it.providerId == "termux_qwen") }
                    if (cloudModels.isNotEmpty()) cloudModels + (if (localModel != null) listOf(localModel) else emptyList())
                    else allModels
                }
            }
        }.let {
            if (settings.fallbackEnabled) it else it.take(1)
        }

        if (models.isEmpty()) {
            return com.jarvis.assistant.agent.AgentResult(false, if (isOffline) "Offline mode is active, but no local Qwen model is available." else "No AI models available for current mode.")
        }

        val memoryContext = MemoryManager.get(this).getPromptContext()
        val systemPrompt = "You are Jarvis, a helpful Android phone assistant. " +
            "Reply naturally and concisely in the user's language, including Hindi or Hinglish. " +
            "You are in conversation mode and cannot operate the phone in this mode. " +
            "Never claim you performed a phone action." +
            (if (memoryContext.isNotEmpty()) "\n\n$memoryContext\n" else "")

        var lastError = ""
        for ((index, profile) in models.withIndex()) {
            if (cancelled || id != taskId) {
                return com.jarvis.assistant.agent.AgentResult(false, "Cancelled.")
            }

            val config = profile.toConfig()
            if (config.preset.needsKey && config.apiKey.isBlank()) {
                lastError = "${profile.name} has no API key"
                continue
            }

            // Supervise and ensure local server is running if using Termux Qwen
            val isLocal = profile.baseUrl.contains("localhost") || profile.baseUrl.contains("127.0.0.1") || profile.providerId == "termux_qwen"
            if (isLocal && !LocalServerManager.isServerHealthy(profile.baseUrl)) {
                if (settings.autoStartLocalServer) {
                    val readyLatch = CountDownLatch(1)
                    var serverReady = false
                    main.post { if (id == taskId && !destroyed) update("Warming up local Qwen in Termux…") }
                    LocalServerManager.ensureServerRunning(this, profile.baseUrl, timeoutSeconds = 7, onStatus = { st ->
                        main.post { if (id == taskId && !destroyed) update(st) }
                    }) { ok ->
                        serverReady = ok
                        readyLatch.countDown()
                    }
                    readyLatch.await(8, TimeUnit.SECONDS)
                    if (!serverReady) {
                        lastError = "Local Qwen server in Termux could not be started"
                        continue
                    }
                }
            }

            try {
                main.post {
                    if (id == taskId && !destroyed) update("Thinking with ${profile.name}…")
                }

                val session = Llm.newSession(config, systemPrompt, emptyList())
                session.seedHistory(earlier)
                activeSession = session
                if (cancelled || id != taskId) session.cancel()
                val turn = try {
                    session.send(task, emptyList())
                } finally {
                    if (activeSession === session) activeSession = null
                }
                if (turn.toolCalls.isNotEmpty()) {
                    lastError = "${profile.name} unexpectedly returned a tool call"
                    continue
                }

                val reply = turn.text.trim()
                if (reply.isNotBlank()) {
                    return com.jarvis.assistant.agent.AgentResult(true, reply)
                }

                lastError = turn.stopNote ?: "${profile.name} returned an empty reply"
            } catch (e: Throwable) {
                if (cancelled || id != taskId) {
                    return com.jarvis.assistant.agent.AgentResult(false, "Okay, I stopped.")
                }
                Log.e(TAG, "chat failed with ${profile.name}", e)
                lastError = e.message ?: "Connection failed"
            }
        }

        return com.jarvis.assistant.agent.AgentResult(
            false, "I couldn't reach any of your AI models. $lastError"
        )
    }

    private fun runTask(task: String) {
        if (destroyed || state == State.WORKING) return
        state = State.WORKING
        cancelled = false
        val id = ++taskId
        activeSession = null
        voice.cancelCommand()
        lastTask = task

        // 1. FAST COMMAND LAYER: execute instant deterministic actions (< 50ms)
        val fastResult = FastCommandRouter.execute(this, task)
        if (fastResult.handled) {
            lastResult = fastResult.summary
            remember(task, fastResult.summary)
            island { it.finish(fastResult.success, fastResult.summary) }
            update(fastResult.summary)
            say(fastResult.summary, interruptible = true) {
                state = State.IDLE
                afterReply()
            }
            return
        }

        // 2. Complex AI reasoning or multi-step UI automation
        island { it.working(task) }
        update("Working on: $task")
        say("On it.")
        val earlier = recentConversation()
        val phoneAction = isPhoneAction(task)

        worker.execute {
            val result = try {
                if (phoneAction) {
                    waitForUnlock()
                    PhoneAgent(settings, hostFor(id)).run(task, earlier)
                } else {
                    runChatTask(task, earlier, id)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "task failed", e)
                com.jarvis.assistant.agent.AgentResult(false, "Something went wrong: ${e.message}")
            }

            main.post {
                if (id != taskId || destroyed) return@post
                lastResult = result.summary
                remember(task, result.summary)
                island { it.finish(result.success, result.summary) }
                update(result.summary)
                say(result.summary, interruptible = true) {
                    state = State.IDLE
                    afterReply()
                }
            }
        }
    }

    /** After a reply: listen for a follow-up without the wake word, or go back to waiting. */
    private fun afterReply() {
        if (destroyed) return
        if (settings.followUp && voice.canTranscribe) listen(promptIfQuiet = false, followUp = true)
        else resumeListening()
    }

    private fun remember(task: String, reply: String) {
        conversation.addLast(task to reply)
        while (conversation.size > 3) conversation.removeFirst()
        lastExchangeAt = System.currentTimeMillis()
    }

    /** The last few exchanges, if the conversation is still going (within 10 minutes). */
    private fun recentConversation(): List<Pair<String, String>> {
        if (System.currentTimeMillis() - lastExchangeAt > 10 * 60_000) conversation.clear()
        return conversation.toList()
    }

    /** Goes back to waiting for "Hey Jarvis" (loading any models downloaded since). */
    private fun resumeListening() {
        if (destroyed || !settings.switchedOn || state != State.IDLE) return
        voice.echoCancellation = settings.interruptByTalking
        voice.wakeEnabled = settings.wakeWordEnabled

        // Fast path: if wake word model is already active, resume listening immediately (<1ms)
        if (voice.hasWakeWord) {
            voice.start()
            update("Say \"Hey Jarvis\"")
            return
        }

        voice.load { error ->
            if (destroyed || state != State.IDLE) return@load
            voice.wakeEnabled = settings.wakeWordEnabled
            when {
                error != null -> update(error)
                !settings.wakeWordEnabled -> { voice.stop(); update("Ready. Tap the mic to talk.") }
                !voice.hasWakeWord -> update("Ready. Download the wake word in Settings to use \"Hey Jarvis\".")
                else -> { voice.start(); update("Say \"Hey Jarvis\"") }
            }
        }
    }

    /** Accessibility services can't act behind the lock screen, so wake the screen and wait for an unlock. */
    @Suppress("DEPRECATION")
    private fun waitForUnlock() {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isKeyguardLocked) return
        val pm = getSystemService(PowerManager::class.java)
        pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "jarvis:wake")
            .acquire(3000)
        speakAndWait("Please unlock your phone and I'll get started.")
        val deadline = System.currentTimeMillis() + 30_000
        while (keyguard.isKeyguardLocked && System.currentTimeMillis() < deadline && !cancelled) Thread.sleep(300)
    }

    /** For the worker thread: speak and block until done. */
    private fun speakAndWait(text: String) {
        val done = CountDownLatch(1)
        main.post { say(text) { done.countDown() } }
        done.await(30, TimeUnit.SECONDS)
    }

    /** Bridges the agent (worker thread) to the voice UI (main thread) for task [id]. */
    private fun hostFor(id: Int) = object : AgentHost {
        override val isCancelled get() = cancelled || id != taskId
        override fun attachSession(session: ChatSession) {
            activeSession = session
            if (cancelled || id != taskId) session.cancel()
        }
        override fun status(text: String) {
            main.post {
                if (!destroyed && !cancelled && id == taskId) update(text)
            }
        }
        override fun ask(question: String): String? {
            val latch = CountDownLatch(1)
            var answer: String? = null
            main.post {
                if (destroyed || cancelled || id != taskId) {
                    latch.countDown()
                    return@post
                }
                update("Asking: $question")
                say(question) {
                    island { it.listening(if (voice.canTranscribe) ({ voice.partialText }) else null, { voice.level }) }
                    val got: (String?) -> Unit = { text ->
                        if (!destroyed && !cancelled && id == taskId) {
                            answer = text
                            island { it.working() }
                        }
                        latch.countDown()
                    }
                    if (voice.canTranscribe) voice.listenForCommand(10_000) { got(it) }
                    else { voice.stop(); google.listen { got(it); if (voice.hasWakeWord) voice.start() } }
                }
            }
            // Give up after a minute, or straight away if the task is stopped.
            val deadline = System.currentTimeMillis() + 60_000
            while (!latch.await(200, TimeUnit.MILLISECONDS)) {
                if (destroyed || cancelled || id != taskId || System.currentTimeMillis() > deadline) break
            }
            return answer
        }
    }

    // ---- Notification ----

    /** Runs [block] on the task island, if phone control is on and the island is enabled. */
    private fun island(block: (com.jarvis.assistant.ui.TaskIsland) -> Unit) {
        if (!settings.showIsland) return
        JarvisAccessibilityService.instance?.island?.let {
            it.onStop = { start(this, ACTION_STOP_TASK) }
            runCatching { block(it) }
        }
    }

    private fun update(text: String) {
        status = text
        if (state == State.WORKING && !text.startsWith("Working on:")) island { it.status(text) }
        phase = when (state) {
            State.IDLE -> Phase.IDLE
            State.LISTENING_COMMAND -> Phase.LISTENING
            State.WORKING -> Phase.WORKING
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
        statusListener?.invoke(text)
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL, "Jarvis", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        fun action(action: String, code: Int) =
            PendingIntent.getService(this, code, Intent(this, JarvisService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle("Jarvis")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Talk", action(ACTION_LISTEN, 1))
            .addAction(0, "Stop task", action(ACTION_STOP_TASK, 2))
            .addAction(0, "Turn off", action(ACTION_SHUTDOWN, 3))
            .build()
    }

    companion object {
        private const val TAG = "JarvisService"
        private const val CHANNEL = "jarvis"
        private const val NOTIFICATION_ID = 1
        const val ACTION_RUN = "com.jarvis.assistant.RUN"
        const val ACTION_LISTEN = "com.jarvis.assistant.LISTEN"
        const val ACTION_STOP_TASK = "com.jarvis.assistant.STOP_TASK"
        const val ACTION_SHUTDOWN = "com.jarvis.assistant.SHUTDOWN"
        const val EXTRA_TASK = "task"
        /** How long to wait for a follow-up after a reply. */
        private const val FOLLOW_UP_MS = 6000L

        enum class Phase { OFF, IDLE, LISTENING, WORKING }

        @Volatile var running = false
            private set
        @Volatile var phase = Phase.OFF
            private set
        @Volatile var status = "Off"
            private set
        @Volatile var lastTask: String? = null
            private set
        @Volatile var lastResult: String? = null
            private set
        @androidx.annotation.VisibleForTesting
        fun resetForTest() { lastTask = null; lastResult = null; status = "Off"; phase = Phase.OFF }

        /** Set by the visible activity to mirror status changes. Called on the main thread. */
        var statusListener: ((String) -> Unit)? = null

        /** Returns false if Jarvis can't start yet because microphone permission is missing. */
        fun start(context: Context, action: String? = null, task: String? = null): Boolean {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
            Settings(context).switchedOn = true
            val intent = Intent(context, JarvisService::class.java).setAction(action).putExtra(EXTRA_TASK, task)
            ContextCompat.startForegroundService(context, intent)
            return true
        }

        /** Turns Jarvis off and keeps it off until the user turns it on again. */
        fun stop(context: Context) {
            Settings(context).switchedOn = false
            // stopService never creates the service, unlike sending it a "shut down" intent.
            context.stopService(Intent(context, JarvisService::class.java))
        }

        /** For automatic starts (boot, phone control connecting): only if the user left Jarvis on. */
        fun startIfSwitchedOn(context: Context) {
            if (Settings(context).switchedOn) start(context)
        }
    }
}
