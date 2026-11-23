package com.jarvis.assistant.ui

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import com.jarvis.assistant.R
import com.jarvis.assistant.databinding.ViewIslandBinding

/**
 * An iPhone-style Dynamic Island just below the camera. It shows what Jarvis is doing (listening,
 * working, speaking, done), bounces its sound bars with your voice and with Jarvis's, and lights
 * up its border while Jarvis talks. Tap it to expand it into a card with the task and buttons;
 * tap outside (or the header) to shrink it back.
 *
 * Drawn as an accessibility overlay, so it needs no extra permission. Main thread only.
 */
class TaskIsland(private val service: AccessibilityService) {

    enum class Mode { LISTENING, WORKING, DONE, FAILED }

    private val wm = service.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val density = service.resources.displayMetrics.density
    private var ui: ViewIslandBinding? = null
    private var params: WindowManager.LayoutParams? = null
    private var animator: ValueAnimator? = null

    private var mode = Mode.WORKING
    private var task: String? = null
    private var status = ""
    private var speaking = false
    private var partial: (() -> String)? = null
    private var micLevel: (() -> Float)? = null

    var expanded = false
        private set

    /** Stop the current task (or cancel listening, or stop talking). */
    var onStop: () -> Unit = {}

    private val dismiss = Runnable { hide() }
    private val autoCollapse = Runnable { collapse() }

    val isShowing get() = ui != null

    // ---- States ----

    /** Jarvis heard "Hey Jarvis" (or asked a question) and is listening. */
    fun listening(partialText: (() -> String)? = null, level: (() -> Float)? = null) {
        mode = Mode.LISTENING
        partial = partialText
        micLevel = level
        if (task == null) status = "" // during a task, keep showing the question being answered
        render()
    }

    /** Jarvis is working on [task] (null keeps the current task, e.g. after asking a question). */
    fun working(task: String? = null) {
        if (task != null) this.task = task
        mode = Mode.WORKING
        partial = null
        micLevel = null
        render()
    }

    /** A progress update while working. */
    fun status(text: String) {
        status = text
        render()
    }

    /** The outcome. The island goes away a few seconds after Jarvis finishes saying it. */
    fun finish(success: Boolean, text: String) {
        mode = if (success) Mode.DONE else Mode.FAILED
        status = text
        partial = null
        micLevel = null
        render()
        scheduleDismiss()
    }

    /** Jarvis is saying [text]: the border moves with its voice. */
    fun speaking(text: String, level: () -> Float?) {
        speaking = true
        if (mode != Mode.LISTENING) status = text
        render()
        ui?.island?.speechLevel = level
        ui?.islandBars?.level = level
        ui?.expBars?.level = level
    }

    fun speakingDone() {
        speaking = false
        ui?.island?.speechLevel = null
        render()
        scheduleDismiss()
    }

    fun hide() {
        val b = ui ?: return
        ui = null
        params = null
        expanded = false
        animator?.cancel()
        main.removeCallbacks(dismiss)
        main.removeCallbacks(autoCollapse)
        task = null
        status = ""
        speaking = false
        b.island.onFrame = null
        b.island.animate().scaleX(0.5f).scaleY(0.5f).alpha(0f).setDuration(200)
            .setInterpolator(DecelerateInterpolator()).withEndAction { runCatching { wm.removeView(b.root) } }.start()
    }

    private fun scheduleDismiss() {
        main.removeCallbacks(dismiss)
        if ((mode == Mode.DONE || mode == Mode.FAILED) && !speaking && !expanded) main.postDelayed(dismiss, 4000)
    }

    // ---- Drawing the content ----

    private fun render() {
        val b = ui ?: create() ?: return
        val done = mode == Mode.DONE || mode == Mode.FAILED

        b.islandOrb.visibility = if (done) View.GONE else View.VISIBLE
        b.islandResult.visibility = if (done) View.VISIBLE else View.GONE
        if (done) {
            b.islandResult.setImageResource(if (mode == Mode.DONE) R.drawable.ic_check else R.drawable.ic_stop)
            b.islandResult.imageTintList = ColorStateList.valueOf(service.color(if (mode == Mode.DONE) R.color.ok else R.color.warn))
        }
        val orbMode = if (mode == Mode.LISTENING) ArcReactorView.Mode.LISTENING else ArcReactorView.Mode.WORKING
        b.islandOrb.mode = orbMode
        b.expOrb.mode = if (done) ArcReactorView.Mode.IDLE else orbMode

        val heard = partial?.invoke().orEmpty()
        b.islandText.text = when {
            mode == Mode.LISTENING -> heard.ifEmpty { service.getString(R.string.island_listening) }
            status.isNotEmpty() -> status
            else -> task ?: service.getString(R.string.island_working)
        }

        // Sound bars: your voice while listening, Jarvis's while speaking.
        val showBars = mode == Mode.LISTENING || speaking
        b.islandBars.visibility = if (showBars) View.VISIBLE else View.GONE
        b.expBars.visibility = if (showBars) View.VISIBLE else View.GONE
        if (!speaking) {
            val mic = micLevel?.let { m -> { m() as Float? } }
            b.islandBars.level = mic
            b.expBars.level = mic
        }

        b.expState.text = when {
            speaking -> service.getString(R.string.state_speaking)
            mode == Mode.LISTENING -> service.getString(R.string.phase_listening)
            mode == Mode.WORKING -> service.getString(R.string.phase_working)
            mode == Mode.DONE -> service.getString(R.string.done)
            else -> service.getString(R.string.state_failed)
        }
        b.expTask.text = when (mode) {
            Mode.LISTENING -> heard.ifEmpty { if (task != null) status.ifEmpty { service.getString(R.string.go_ahead) } else service.getString(R.string.go_ahead) }
            else -> task ?: status
        }
        b.expStatus.text = if (mode == Mode.LISTENING) "" else status.takeIf { it != b.expTask.text }.orEmpty()
        b.expStatus.visibility = if (b.expStatus.text.isNullOrEmpty()) View.GONE else View.VISIBLE

        // Buttons
        val stopLabel = when {
            mode == Mode.LISTENING -> service.getString(R.string.cancel)
            mode == Mode.WORKING -> service.getString(R.string.stop)
            speaking -> service.getString(R.string.stop_talking)
            else -> null
        }
        if (stopLabel != null) {
            b.expPrimary.text = stopLabel
            b.expPrimary.setTextColor(if (mode == Mode.WORKING) service.color(R.color.danger) else 0xFFFFFFFF.toInt())
            b.expPrimary.setOnClickListener { onStop(); if (mode != Mode.WORKING) collapse() }
        } else {
            b.expPrimary.setText(R.string.dismiss)
            b.expPrimary.setTextColor(0xFFFFFFFF.toInt())
            b.expPrimary.setOnClickListener { hide() }
        }
        b.expSecondary.setText(R.string.open_jarvis)
        b.expSecondary.setOnClickListener { openApp() }
    }

    private fun openApp() {
        collapse()
        runCatching {
            service.startActivity(Intent(service, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    // ---- Expanding and collapsing ----

    fun expand() {
        val b = ui ?: return
        val p = params ?: return
        if (expanded) return
        expanded = true
        main.removeCallbacks(dismiss)
        // Grow the window to the card's size first; the shape then springs out inside it.
        p.width = expandedWidth()
        p.flags = p.flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        b.expanded.visibility = View.VISIBLE
        b.expanded.alpha = 0f
        runCatching { wm.updateViewLayout(b.root, p) }
        animateTo(1f, 520, SpringInterpolator(0.68f))
        main.removeCallbacks(autoCollapse)
        main.postDelayed(autoCollapse, 12_000)
    }

    fun collapse() {
        val b = ui ?: return
        if (!expanded) return
        expanded = false
        main.removeCallbacks(autoCollapse)
        animateTo(0f, 420, SpringInterpolator(0.82f)) {
            val p = params ?: return@animateTo
            b.expanded.visibility = View.GONE
            p.width = WindowManager.LayoutParams.WRAP_CONTENT
            p.flags = p.flags and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH.inv()
            runCatching { wm.updateViewLayout(b.root, p) }
            scheduleDismiss()
        }
    }

    private fun animateTo(target: Float, duration: Long, interpolator: android.view.animation.Interpolator, then: () -> Unit = {}) {
        val b = ui ?: return
        animator?.cancel()
        animator = ValueAnimator.ofFloat(b.island.expansion, target).apply {
            this.duration = duration
            this.interpolator = interpolator
            addUpdateListener {
                val v = it.animatedValue as Float
                b.island.expansion = v
                // Compact content fades out quickly; the card's content fades in near the end.
                b.compact.alpha = (1f - v * 3f).coerceIn(0f, 1f)
                b.expanded.alpha = ((v - 0.45f) / 0.45f).coerceIn(0f, 1f)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) { if (!cancelled) then() }
            })
            start()
        }
    }

    private fun expandedWidth(): Int {
        val screen = service.resources.displayMetrics.widthPixels
        return minOf(screen - (12 * density).toInt(), (420 * density).toInt())
    }

    // ---- Window ----

    private fun create(): ViewIslandBinding? {
        // Inflate with the app's theme: the service context has none.
        val b = ViewIslandBinding.inflate(LayoutInflater.from(ContextThemeWrapper(service, R.style.Theme_Jarvis)))
        b.compact.setOnClickListener { expand() }
        b.expHeader.setOnClickListener { collapse() }
        b.root.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) { collapse(); true } else false
        }
        // Keep live content (what you're saying) fresh while listening.
        var lastHeard = ""
        b.island.onFrame = {
            val heard = partial?.invoke().orEmpty()
            if (heard != lastHeard) { lastHeard = heard; main.post { if (ui === b) render() } }
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            // The frame has 8 dp of padding (room for the speaking glow); keep the pill itself where topOffset says.
            y = topOffset(service) - (8 * density).toInt()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            title = "Jarvis task island"
        }
        return try {
            wm.addView(b.root, p)
            // Pop in from the camera, like the iPhone's island.
            b.island.scaleX = 0.5f; b.island.scaleY = 0.5f; b.island.alpha = 0f
            b.island.pivotY = 0f
            b.island.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(420).setInterpolator(SpringInterpolator(0.6f)).start()
            ui = b
            params = p
            b
        } catch (e: Exception) {
            null // e.g. the accessibility service was just turned off
        }
    }

    companion object {
        /**
         * Places the pill just below the status bar and camera cut-out, so it never covers the
         * notification icons or the camera.
         */
        fun topOffset(context: Context): Int {
            val res = context.resources
            val gap = (10 * res.displayMetrics.density).toInt()
            var top = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching {
                    val insets = context.getSystemService(WindowManager::class.java).currentWindowMetrics.windowInsets
                    top = insets.getInsetsIgnoringVisibility(
                        android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.displayCutout()
                    ).top
                }
            }
            if (top <= 0) {
                val id = res.getIdentifier("status_bar_height", "dimen", "android")
                top = if (id > 0) res.getDimensionPixelSize(id) else (24 * res.displayMetrics.density).toInt()
            }
            return top + gap
        }
    }
}
