package com.jarvis.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppTipsTest {
    private val labels = mapOf("com.whatsapp" to "WhatsApp", "com.example.bank" to "My Bank")

    @Test fun userTipsMatchTheAppNameAndComeBeforeBuiltInOnes() {
        val tips = AppTips("whats app: Chats with Mum are pinned at the top\nMy Bank: Ask before every transfer", labels::get)
        val block = tips.forForeground("com.whatsapp")!!
        val lines = block.lines()
        assertEquals("Tips for WhatsApp:", lines[0])
        assertEquals("- Chats with Mum are pinned at the top", lines[1])
        assertEquals(1 + 1 + AppTips.BUILT_IN.getValue("com.whatsapp").size, lines.size)
        assertEquals(listOf("Ask before every transfer"), tips.tipsFor("com.example.bank", "My Bank"))
    }

    @Test fun eachAppsTipsAreGivenOnlyOnce() {
        val tips = AppTips("", labels::get)
        assertTrue(tips.forForeground("com.whatsapp") != null)
        assertNull(tips.forForeground("com.whatsapp"))
    }

    @Test fun appsWithoutTipsAndUnknownScreensGiveNothing() {
        val tips = AppTips("", labels::get)
        assertNull(tips.forForeground(null))
        assertNull(tips.forForeground("com.example.bank"))
        // An app first seen without tips is not marked as given.
        assertNull(tips.forForeground("com.example.bank"))
    }

    @Test fun userTipsCanNameThePackageAndAppsWithoutALabel() {
        val tips = AppTips("com.example.notes: The new note button is at the bottom", labels::get)
        assertEquals("Tips for com.example.notes:\n- The new note button is at the bottom", tips.forForeground("com.example.notes"))
    }

    @Test fun parseIgnoresLinesWithoutAnAppOrATip() {
        val parsed = AppTips.parse("no colon here\n: missing app\nChrome:\n Maps : Use https://maps.google.com when stuck \n")
        assertEquals(listOf("maps" to "Use https://maps.google.com when stuck"), parsed)
    }
}
