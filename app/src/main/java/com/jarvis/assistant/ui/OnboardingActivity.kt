package com.jarvis.assistant.ui

import androidx.annotation.StringRes
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
import android.view.View
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.jarvis.assistant.R
import com.jarvis.assistant.Settings
import com.jarvis.assistant.databinding.ActivityOnboardingBinding
import com.jarvis.assistant.service.JarvisService

/**
 * First-run walkthrough. Asks for what Android lets an app request directly, and takes the user
 * to the right system screen for what it doesn't (phone control), ticking each step off itself.
 */
class OnboardingActivity : AppCompatActivity() {

    private val DOWNLOADS = listOf(Setup.Item.WAKE_WORD, Setup.Item.SPEECH, Setup.Item.VOICE)

    private enum class Step { WELCOME, MICROPHONE, PHONE_CONTROL, BACKGROUND, DOWNLOADS, MODEL, DONE }

    private lateinit var ui: ActivityOnboardingBinding
    private lateinit var settings: Settings
    private var step = Step.WELCOME
    private var refreshDownloads: (() -> Unit)? = null
    private val onDownload: () -> Unit = { refreshDownloads?.invoke(); render() }
    private var autoAdvance: Runnable? = null
    private val askedFor = mutableSetOf<Step>()
    private val attempted = mutableSetOf<Step>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(ui.root)
        ui.root.padForSystemBars()
        settings = Settings(this)
        step = savedInstanceState?.getString("step")?.let { Step.valueOf(it) } ?: Step.WELCOME
        ui.primaryButton.setOnClickListener { primaryAction() }
        ui.skipButton.setOnClickListener { next() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("step", step.name)
    }

    override fun onResume() {
        super.onResume()
        Downloads.listen(onDownload)
        render()
    }

    override fun onPause() {
        super.onPause()
        Downloads.unlisten(onDownload)
        autoAdvance?.let { ui.root.removeCallbacks(it) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    private fun isDone(s: Step): Boolean = when (s) {
        Step.MICROPHONE -> Setup.isDone(this, Setup.Item.MICROPHONE)
        Step.PHONE_CONTROL -> Setup.isDone(this, Setup.Item.PHONE_CONTROL)
        Step.BACKGROUND -> Setup.isDone(this, Setup.Item.BACKGROUND)
        Step.DOWNLOADS -> DOWNLOADS.all { Setup.isDone(this, it) }
        Step.MODEL -> settings.primary() != null
        else -> false
    }

    private fun render() {
        val total = Step.entries.size - 1
        ui.stepProgress.setProgressCompat(step.ordinal * 100 / total, true)
        ui.stepCount.text = if (step == Step.WELCOME) "" else "${step.ordinal} / $total"
        ui.reactor.visibility = if (step == Step.WELCOME || step == Step.DONE) View.VISIBLE else View.GONE
        ui.reactor.mode = if (step == Step.DONE) ArcReactorView.Mode.LISTENING else ArcReactorView.Mode.IDLE
        ui.stepIcon.visibility = if (ui.reactor.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        ui.stepExtra.removeAllViews()
        refreshDownloads = null
        val done = isDone(step)
        ui.stepDone.visibility = if (done) View.VISIBLE else View.GONE
        ui.skipButton.visibility = if (step == Step.WELCOME || done) View.GONE else View.VISIBLE
        ui.skipButton.text = getString(if (step == Step.DONE) R.string.skip_activation else R.string.skip_for_now)

        when (step) {
            Step.WELCOME -> show(0, R.string.ob_welcome_title, getString(R.string.ob_welcome_body), R.string.get_started)
            Step.MICROPHONE -> {
                show(R.drawable.ic_mic, R.string.ob_mic_title, getString(R.string.ob_mic_body),
                    if (done) R.string.continue_ else R.string.allow_microphone)
                // Ask straight away; Android shows its own permission prompt.
                if (!done && askedFor.add(step)) requestPermissions(Setup.PERMISSIONS, 1)
            }
            Step.PHONE_CONTROL -> {
                show(R.drawable.ic_touch, R.string.ob_control_title, getString(R.string.ob_control_body),
                    if (done) R.string.continue_ else R.string.open_settings)
                if (!done && Build.VERSION.SDK_INT >= 33) {
                    addNote(getString(R.string.ob_control_note))
                    addButton(getString(R.string.open_app_info)) {
                        startActivity(Intent(ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    }
                }
            }
            Step.BACKGROUND -> show(R.drawable.ic_battery, R.string.ob_bg_title, getString(R.string.ob_bg_body),
                when { done -> R.string.continue_; step in attempted -> R.string.continue_anyway; else -> R.string.allow })
            Step.DOWNLOADS -> {
                val downloading = Downloads.progress.isNotEmpty()
                show(R.drawable.ic_voice, R.string.ob_dl_title, getString(R.string.ob_dl_body),
                    when { done || downloading -> R.string.continue_; else -> R.string.download_all })
                refreshDownloads = Setup.bindRows(this, ui.stepExtra, DOWNLOADS).also { it() }
            }
            Step.MODEL -> {
                val primary = settings.primary()
                show(R.drawable.ic_memory, R.string.ob_model_title,
                    if (primary == null) getString(R.string.ob_model_body_none) else getString(R.string.ob_model_body, primary.name),
                    if (primary == null) R.string.add_a_model else R.string.continue_)
            }
            Step.DONE -> show(0, R.string.ob_done_title, getString(R.string.ob_done_body), R.string.turn_on_jarvis)
        }

        // Tick a step off as soon as the user comes back having done it.
        autoAdvance?.let { ui.root.removeCallbacks(it) }
        if (done && step in setOf(Step.MICROPHONE, Step.PHONE_CONTROL, Step.BACKGROUND, Step.DOWNLOADS)) {
            autoAdvance = Runnable { next() }.also { ui.root.postDelayed(it, 900) }
        }
    }

    private fun show(@DrawableRes icon: Int, @StringRes title: Int, body: String, @StringRes button: Int) {
        if (icon != 0) ui.stepIcon.setImageResource(icon)
        ui.stepTitle.setText(title)
        ui.stepBody.text = body
        ui.primaryButton.setText(button)
    }

    private fun addNote(text: String) {
        ui.stepExtra.addView(TextView(this).apply {
            this.text = text
            setTextColor(color(R.color.text_dim))
            textSize = 13f
            setLineSpacing(0f, 1.2f)
            setPadding(0, 0, 0, (12 * resources.displayMetrics.density).toInt())
        })
    }

    private fun addButton(text: String, onClick: () -> Unit) {
        ui.stepExtra.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.text = text
            isAllCaps = false
            setTextColor(color(R.color.accent))
            setOnClickListener { onClick() }
        })
    }

    private fun primaryAction() {
        // Optional steps can't trap the user: after one attempt the button moves on.
        if (step == Step.BACKGROUND && step in attempted && !isDone(step)) { next(); return }
        if (isDone(step) || step == Step.WELCOME) {
            if (step == Step.DONE) finishOnboarding(activate = true) else next()
            return
        }
        when (step) {
            Step.MICROPHONE -> Setup.act(this, Setup.Item.MICROPHONE)
            Step.PHONE_CONTROL -> Setup.act(this, Setup.Item.PHONE_CONTROL)
            Step.BACKGROUND -> { attempted += step; Setup.act(this, Setup.Item.BACKGROUND) }
            Step.DOWNLOADS -> {
                if (Downloads.progress.isNotEmpty()) { next(); return }
                DOWNLOADS.filter { !Setup.isDone(this, it) }.forEach { Setup.act(this, it) }
            }
            Step.MODEL -> startActivity(Intent(this, ModelEditActivity::class.java))
            Step.DONE -> finishOnboarding(activate = true)
            else -> next()
        }
    }

    private fun next() {
        if (step == Step.DONE) { finishOnboarding(activate = false); return }
        step = Step.entries[step.ordinal + 1]
        render()
    }

    private fun finishOnboarding(activate: Boolean) {
        settings.onboarded = true
        if (activate) JarvisService.start(this)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
