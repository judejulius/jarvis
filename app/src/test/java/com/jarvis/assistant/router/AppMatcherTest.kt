package com.jarvis.assistant.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppMatcherTest {
    private val installed = listOf(
        AppEntry("YouTube", "com.google.android.youtube"),
        AppEntry("WhatsApp", "com.whatsapp"),
        AppEntry("WhatsApp Business", "com.whatsapp.w4b"),
        AppEntry("Instagram", "com.instagram.android"),
        AppEntry("Chrome", "com.android.chrome"),
        AppEntry("Settings", "com.android.settings"),
        AppEntry("Zomato", "com.application.zomato"),
        AppEntry("Photo Editor", "x.editor"),
        AppEntry("Photo Frame", "x.frame"),
    )

    private fun pkg(q: String, learned: (String) -> String? = { null }) = AppMatcher.match(q, installed, learned).app?.packageName

    @Test fun wellKnownNamesAndSpellings() {
        assertEquals("com.google.android.youtube", pkg("youtube"))
        assertEquals("com.google.android.youtube", pkg("you tube"))
        assertEquals("com.google.android.youtube", pkg("yutub"))
        assertEquals("com.whatsapp", pkg("whatsapp"))
        assertEquals("com.whatsapp.w4b", pkg("whatsapp business"))
        assertEquals("com.instagram.android", pkg("insta"))
        assertEquals("com.android.settings", pkg("settings"))
    }

    @Test fun labelMatching() {
        assertEquals("com.application.zomato", pkg("zomato"))
        assertEquals("com.application.zomato", pkg("zom"))
        assertEquals("com.application.zomato", pkg("zomatto"))
    }

    @Test fun soundAlikeAndSmallTypos() {
        assertEquals("com.whatsapp", pkg("whtsapp"))
        assertEquals("com.instagram.android", pkg("instagrm"))
    }

    @Test fun ambiguousNamesAreNotGuessed() {
        val m = AppMatcher.match("photo", installed)
        assertNull(m.app)
        assertEquals(2, m.ambiguous.size)
    }

    @Test fun whatTheUserTaughtWins() {
        assertEquals("com.instagram.android", pkg("yt") { if (it == "yt") "com.instagram.android" else null })
        assertNull(pkg("yt") { if (it == "yt") "not.installed" else null })
    }

    @Test fun notInstalledIsNotFound() {
        assertNull(pkg("snapchat"))
        assertNull(pkg("door"))
    }

    @Test fun learnsOnlyPlausibleMishearings() {
        assertTrue(AppMatcher.similar("whtsup", "WhatsApp"))
        assertFalse(AppMatcher.similar("maps", "WhatsApp"))
        assertFalse(AppMatcher.similar("whatsapp", "WhatsApp"))
    }

    @Test fun skeletonIgnoresVowelsAndVW() {
        assertEquals(AppMatcher.skeleton("youtube"), AppMatcher.skeleton("yutub"))
        assertEquals(AppMatcher.skeleton("whatsapp"), AppMatcher.skeleton("vhatsapp"))
        assertNotNull(AppMatcher.skeleton("chrome"))
    }
}
