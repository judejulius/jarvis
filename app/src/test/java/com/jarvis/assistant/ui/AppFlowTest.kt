package com.jarvis.assistant.ui

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.jarvis.assistant.R
import com.jarvis.assistant.Settings
import com.jarvis.assistant.ModelProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppFlowTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var settings: Settings

    @Before
    fun setUp() {
        ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().clear().commit()
        settings = Settings(ctx)
    }

    @Test
    fun firstLaunchOpensWalkthrough() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        assertEquals(OnboardingActivity::class.java.name, shadowOf(activity).nextStartedActivity.component?.className)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun walkthroughCanBeCompletedBySkipping() {
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val primary = activity.findViewById<android.view.View>(R.id.primaryButton)
                val skip = activity.findViewById<android.view.View>(R.id.skipButton)
                primary.performClick() // welcome -> microphone
                repeat(5) { skip.performClick(); ShadowLooper.idleMainLooper() } // mic, phone, battery, downloads, model
                skip.performClick() // done -> "go to app without activating"
                assertTrue(settings.onboarded)
                assertEquals(MainActivity::class.java.name, shadowOf(activity).nextStartedActivity.component?.className)
            }
        }
    }

    @Test
    fun allPagesOpen() {
        settings.onboarded = true
        settings.saveProfile(ModelProfile(name = "Claude", providerId = "anthropic", baseUrl = "https://api.anthropic.com", apiKey = "k", model = "claude-opus-5-5"))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                for (tab in listOf(R.id.nav_models, R.id.nav_settings, R.id.nav_home)) {
                    activity.goTo(tab)
                    ShadowLooper.idleMainLooper()
                }
            }
            scenario.recreate()
        }
    }

    @Test
    fun addEditPrimaryAndDeleteModels() {
        // Add a Claude model.
        ActivityScenario.launch(ModelEditActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                a.findViewById<android.widget.EditText>(R.id.apiKey).setText("sk-test")
                a.findViewById<android.view.View>(R.id.saveButton).performClick()
            }
        }
        val claude = settings.profiles().single()
        assertEquals("anthropic", claude.providerId)
        assertEquals(claude.id, settings.primaryId) // the first model becomes primary

        // Add LM Studio as a second model and make it primary.
        ActivityScenario.launch(ModelEditActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                val chips = a.findViewById<ChipGroup>(R.id.providerChips)
                (0 until chips.childCount).map { chips.getChildAt(it) as Chip }.first { it.tag == "lmstudio" }.performClick()
                ShadowLooper.idleMainLooper()
                assertEquals("LM Studio", a.findViewById<android.widget.EditText>(R.id.name).text.toString())
                a.findViewById<android.widget.EditText>(R.id.baseUrl).setText("https://192.168.1.50:1234/v1")
                a.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.primarySwitch).isChecked = true
                a.findViewById<android.view.View>(R.id.saveButton).performClick()
            }
        }
        val local = settings.profiles().first { it.providerId == "lmstudio" }
        assertEquals("https://192.168.1.50:1234/v1", local.baseUrl)
        assertEquals(local.id, settings.primaryId)
        assertEquals(listOf(local.id, claude.id), settings.modelsInOrder().map { it.id })

        // A missing key is caught before saving.
        ActivityScenario.launch(ModelEditActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                a.findViewById<android.view.View>(R.id.saveButton).performClick()
                assertFalse(a.isFinishing)
            }
        }
        assertEquals(2, settings.profiles().size)

        // Deleting the primary promotes the remaining model.
        settings.deleteProfile(local.id)
        assertEquals(claude.id, settings.primaryId)
        settings.deleteProfile(claude.id)
        assertNull(settings.primary())
    }

    @Test
    fun oldSettingsAreMigrated() {
        ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit()
            .putString("provider", "lmstudio")
            .putString("p.anthropic.key", "sk-old")
            .putString("p.lmstudio.base", "http://10.0.0.5:1234/v1")
            .putString("p.lmstudio.model", "qwen")
            .commit()
        val names = settings.profiles().map { it.providerId }
        assertEquals(listOf("anthropic", "lmstudio"), names)
        assertEquals("lmstudio", settings.primary()?.providerId)
    }

    @Test
    fun notificationIntentOpensRequestedTab() {
        settings.onboarded = true
        val intent = Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, R.id.nav_models)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { a ->
                assertEquals(R.id.nav_models, a.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.nav).selectedItemId)
            }
        }
    }
}
