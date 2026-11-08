package com.jarvis.assistant.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactMatcherTest {
    private fun c(id: Long, name: String, vararg nums: PhoneNumber) = ContactEntry(id, name, nums.toList())
    private val rahulS = c(1, "Rahul Sharma", PhoneNumber("98100 00001", "mobile"), PhoneNumber("011 2345", "home"))
    private val rahulV = c(2, "Rahul Verma", PhoneNumber("98100 00002", "mobile"))
    private val mummy = c(3, "Mummy", PhoneNumber("98100 00003", "mobile", primary = true))
    private val priya = c(4, "Priya", PhoneNumber("98100 00004", "mobile"))
    private val drGupta = c(5, "Dr. Gupta", PhoneNumber("98100 00005", "work"))
    private val all = listOf(rahulS, rahulV, mummy, priya, drGupta)

    @Test fun exactAndWholeWordMatches() {
        assertEquals(listOf(priya), ContactMatcher.find("priya", all).matches)
        assertEquals(listOf(rahulS), ContactMatcher.find("rahul sharma", all).matches)
        assertEquals(listOf(drGupta), ContactMatcher.find("gupta", all).matches)
        assertEquals(listOf(drGupta), ContactMatcher.find("doctor gupta".replace("doctor", "dr"), all).matches)
    }

    @Test fun twoContactsWithTheSameFirstNameAreAmbiguous() {
        val m = ContactMatcher.find("rahul", all)
        assertTrue(m.ambiguous)
        assertEquals(listOf(rahulS, rahulV), m.matches)
        assertNull(m.single)
    }

    @Test fun familyWordsFindTheRightContact() {
        assertEquals(listOf(mummy), ContactMatcher.find("mom", all).matches)
        assertEquals(listOf(mummy), ContactMatcher.find("maa", all).matches)
        assertEquals(listOf(mummy), ContactMatcher.find("mummy", all).matches)
    }

    @Test fun mishearingsStillFindOneContact() {
        assertEquals(listOf(priya), ContactMatcher.find("pria", all).matches)
        assertEquals(listOf(priya), ContactMatcher.find("preya", all).matches)
        assertEquals(listOf(rahulS, rahulV), ContactMatcher.find("rahool", all).matches)
    }

    @Test fun nobodyMatchesNobody() {
        assertTrue(ContactMatcher.find("zorro", all).matches.isEmpty())
        assertTrue(ContactMatcher.find("", all).matches.isEmpty())
        assertTrue(ContactMatcher.find("rahul", emptyList()).matches.isEmpty())
    }

    @Test fun devanagariNamesMatchContactsSavedInEnglish() {
        assertEquals("rahul", Translit.toRoman("राहुल"))
        assertEquals("priya", Translit.toRoman("प्रिया"))
        assertEquals("sonu", Translit.toRoman("सोनू"))
        assertEquals(listOf(priya), ContactMatcher.find("प्रिया", all).matches)
        assertEquals(listOf(mummy), ContactMatcher.find("मम्मी", all).matches)
        assertTrue(ContactMatcher.find("राहुल", all).ambiguous)
    }

    @Test fun answeringAWhichOneQuestion() {
        val two = listOf(rahulS, rahulV)
        assertEquals(rahulV, ContactMatcher.pick("verma", two))
        assertEquals(rahulS, ContactMatcher.pick("Rahul Sharma", two))
        assertEquals(rahulS, ContactMatcher.pick("the first one", two))
        assertEquals(rahulV, ContactMatcher.pick("second", two))
        assertNull(ContactMatcher.pick("rahul", two))
        assertNull(ContactMatcher.pick("", two))
        assertNull(ContactMatcher.pick("somebody else", two))
    }

    @Test fun numberChoice() {
        assertEquals("98100 00001", ContactMatcher.bestNumber(rahulS)?.number)
        assertEquals("98100 00003", ContactMatcher.bestNumber(mummy)?.number)
        assertEquals("work", ContactMatcher.bestNumber(drGupta)?.type)
        assertNull(ContactMatcher.bestNumber(c(9, "Nobody")))
        assertEquals("Rahul Sharma, mobile", ContactMatcher.describe(rahulS, rahulS.numbers[0]))
    }
}
