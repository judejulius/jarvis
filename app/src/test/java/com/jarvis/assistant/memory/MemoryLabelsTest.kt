package com.jarvis.assistant.memory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvis.assistant.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MemoryLabelsTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    @Test @Config(qualifiers = "hi")
    fun storedEnglishLabelsAreShownInHindi() {
        val task = MemoryStore.Item(MemoryStore.Kind.TASK, 1, "open maps", "Completed · 23/11/2026 08:00")
        assertEquals("पूरा हुआ · 23/11/2026 08:00", MemoryLabels.detail(ctx, task))
        val alias = MemoryStore.Item(MemoryStore.Kind.ALIAS, 2, "\"yt\" opens YouTube", "App name")
        assertEquals("\"yt\" से YouTube खुलता है", MemoryLabels.title(ctx, alias))
        assertEquals("ऐप का नाम", MemoryLabels.detail(ctx, alias))
        assertEquals("रद्द किया गया", MemoryLabels.word(ctx, "Cancelled"))
        assertEquals("कदम 2 / 3\n\nOpened Maps.", ctx.getString(R.string.step_of, 2, 3, "Opened Maps."))
    }

    @Test
    fun englishIsUnchangedAndUnknownTextPassesThrough() {
        val task = MemoryStore.Item(MemoryStore.Kind.TASK, 1, "open maps", "Interrupted · 23/11/2026 08:00")
        assertEquals("Interrupted · 23/11/2026 08:00", MemoryLabels.detail(ctx, task))
        assertEquals("Opened Maps.", MemoryLabels.word(ctx, "Opened Maps."))
        val fact = MemoryStore.Item(MemoryStore.Kind.FACT, 3, "I prefer short answers", "How to answer")
        assertEquals("I prefer short answers", MemoryLabels.title(ctx, fact))
    }
}
