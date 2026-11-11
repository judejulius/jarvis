package com.jarvis.assistant.persona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class PersonaTest {
    private class MemoryStore(override var lastDate: String? = null) : GreetingStore

    private fun at(iso: String) = Instant.parse(iso)

    @Test fun greetingFollowsTheTimeOfDay() {
        assertEquals("Hello sir, good morning. What's the task for today?", Persona.greeting(8))
        assertEquals("Hello sir, good afternoon. What's the task for today?", Persona.greeting(13))
        assertEquals("Hello sir, good evening. What's the task for today?", Persona.greeting(19))
        assertEquals("Hello sir, good evening. What's the task for today?", Persona.greeting(2))
        assertEquals(Persona.Daypart.MORNING, Persona.daypart(4))
        assertEquals(Persona.Daypart.MORNING, Persona.daypart(11))
        assertEquals(Persona.Daypart.AFTERNOON, Persona.daypart(12))
        assertEquals(Persona.Daypart.EVENING, Persona.daypart(17))
        assertEquals("Good morning, sir.", Persona.greetingPrefix(9))
        assertEquals(3, Persona.ALL_GREETINGS.toSet().size)
    }

    @Test fun greetsOnceAndOnlyOncePerDay() {
        val store = MemoryStore()
        var now = at("2026-11-05T03:30:00Z") // 09:00 in India
        val g = DailyGreeting(store, { ZoneId.of("Asia/Kolkata") }, { now })
        assertEquals("Hello sir, good morning. What's the task for today?", g.takeGreeting())
        assertNull(g.takeGreeting())
        now = at("2026-11-05T12:00:00Z")
        assertNull(g.takeGreeting())
        assertNull(g.takePrefix())
        assertEquals("2026-11-05", store.lastDate)
    }

    @Test fun aNewLocalDayGetsANewGreeting() {
        val store = MemoryStore("2026-11-05")
        // 23:00 UTC on Nov 5 is already Nov 6 in India (04:30)
        val g = DailyGreeting(store, { ZoneId.of("Asia/Kolkata") }, { at("2026-11-05T23:00:00Z") })
        assertTrue(g.isDue())
        assertEquals("Good morning, sir.", g.takePrefix()) // 04:30 on Nov 6
        assertFalse(g.isDue())
    }

    @Test fun theLocalDateDecidesNotTheUtcDate() {
        val store = MemoryStore()
        val india = DailyGreeting(store, { ZoneId.of("Asia/Kolkata") }, { at("2026-11-05T20:00:00Z") })
        assertEquals("2026-11-06", india.today())
        val la = DailyGreeting(store, { ZoneId.of("America/Los_Angeles") }, { at("2026-11-05T20:00:00Z") })
        assertEquals("2026-11-05", la.today())
        assertEquals(12, la.hour())
    }

    @Test fun travellingEastOrWestNeverGreetsTwiceOnTheSameDate() {
        val store = MemoryStore()
        var zone = ZoneId.of("Asia/Kolkata")
        val g = DailyGreeting(store, { zone }, { at("2026-11-05T05:00:00Z") })
        assertEquals("Hello sir, good morning. What's the task for today?", g.takeGreeting()) // 10:30 Nov 5
        zone = ZoneId.of("Europe/London") // same instant, still Nov 5 there
        assertNull(g.takeGreeting())
    }

    @Test fun acknowledgementsVaryAndNeverRepeatBackToBack() {
        val p = Persona.AckPicker(java.util.Random(3))
        var prev = ""
        repeat(200) {
            val a = p.next()
            assertTrue(a in Persona.ACKNOWLEDGEMENTS)
            assertNotEquals(prev, a)
            prev = a
        }
        assertTrue(Persona.ACKNOWLEDGEMENTS.none { it.contains("On it") })
    }
}
