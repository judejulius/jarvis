package com.jarvis.assistant.router

import java.text.Normalizer

/**
 * Turns what the speech recognizer heard (English, Hinglish in Roman letters, or Devanagari) into one plain,
 * lower-case Roman form the command parser can match. Pure logic, no Android classes.
 */
object Lexicon {
    private const val DEVANAGARI_DIGITS = "०१२३४५६७८९"
    private val AM_PM = Regex("""\b([ap])\.\s?m\b\.?""")
    private val PUNCTUATION = Regex("""[^\p{L}\p{M}\p{N}:+\s]""")
    private val LEADING_PHRASES = listOf("can you", "could you", "would you", "will you", "can u")
    private val LEADING = setOf("hey", "hi", "ok", "okay", "jarvis", "please", "plz", "pls", "kindly", "zara", "jara", "mujhe")
    private val TRAILING = setOf("please", "plz", "pls", "na", "yaar", "jaldi", "now", "abhi")

    /** Devanagari spellings are matched without nukta, so keys and input go through [devKey]. */
    private fun devKey(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace("\u093C", "")

    private val WORDS: Map<String, String> = mapOf(
        // verbs and helpers
        "खोलो" to "kholo", "खोल" to "kholo", "खोलिए" to "kholo", "खोलें" to "kholo", "खोलना" to "kholo",
        "चालू" to "chalu", "शुरू" to "shuru", "बंद" to "band", "बन्द" to "band",
        "करो" to "karo", "करें" to "karo", "कीजिए" to "karo", "कीजिये" to "karo", "कर" to "kar",
        "दो" to "do", "दीजिए" to "do", "दीजिये" to "do",
        "चलाओ" to "chalao", "चला" to "chala", "बजाओ" to "bajao", "बजा" to "baja",
        "रोको" to "roko", "रुको" to "roko", "रोक" to "roko",
        "बढ़ाओ" to "badhao", "बढाओ" to "badhao", "बढ़ा" to "badha", "घटाओ" to "ghatao", "घटा" to "ghata", "कम" to "kam",
        "जलाओ" to "jalao", "जला" to "jala", "बुझाओ" to "bujhao", "बुझा" to "bujha",
        "लगाओ" to "lagao", "लगा" to "laga", "जाओ" to "jao", "जा" to "ja", "चलो" to "chalo",
        "दिखाओ" to "dikhao", "दिखा" to "dikha", "बताओ" to "batao", "बता" to "bata",
        "ओपन" to "open", "कॉल" to "call", "डायल" to "dial", "लॉन्च" to "launch", "स्टार्ट" to "start", "ऑन" to "on", "ऑफ" to "off", "बैक" to "back",
        "होम" to "home", "पीछे" to "peeche", "वापस" to "wapas", "ऊपर" to "upar", "उपर" to "upar", "नीचे" to "neeche",
        "स्क्रॉल" to "scroll", "स्क्रीनशॉट" to "screenshot", "सर्च" to "search", "खोजो" to "khojo", "ढूंढो" to "dhundo",
        "को" to "ko", "का" to "ka", "में" to "mein", "पर" to "par", "पे" to "pe", "क्या" to "kya", "है" to "hai", "हैं" to "hain",
        "कितने" to "kitne", "कितनी" to "kitni", "आज" to "aaj", "बजे" to "baje",
        // device words
        "आवाज़" to "awaaz", "आवाज" to "awaaz", "वॉल्यूम" to "volume", "वोल्यूम" to "volume", "म्यूट" to "mute", "अनम्यूट" to "unmute",
        "टॉर्च" to "torch", "टोर्च" to "torch", "फ्लैशलाइट" to "flashlight",
        "म्यूजिक" to "music", "म्यूज़िक" to "music", "गाना" to "gaana", "गाने" to "gaana", "अगला" to "agla", "पिछला" to "pichla",
        "टाइमर" to "timer", "अलार्म" to "alarm", "मिनट" to "minute", "घंटा" to "ghanta", "घंटे" to "ghante", "सेकंड" to "second",
        "सुबह" to "subah", "शाम" to "shaam", "रात" to "raat", "दोपहर" to "dopahar", "टाइम" to "time", "बैटरी" to "battery",
        "वाईफाई" to "wifi", "वाईफ़ाई" to "wifi", "वाई-फाई" to "wifi", "ब्लूटूथ" to "bluetooth", "जार्विस" to "jarvis", "जारविस" to "jarvis",
        // apps
        "यूट्यूब" to "youtube", "व्हाट्सएप" to "whatsapp", "व्हाट्सऐप" to "whatsapp", "वाट्सएप" to "whatsapp", "वॉट्सएप" to "whatsapp",
        "व्हाट्सअप" to "whatsapp", "इंस्टाग्राम" to "instagram", "फेसबुक" to "facebook", "क्रोम" to "chrome", "कैमरा" to "camera",
        "सेटिंग" to "settings", "सेटिंग्स" to "settings", "सेटिंगस" to "settings", "गूगल" to "google", "मैप" to "maps", "मैप्स" to "maps",
        "जीमेल" to "gmail", "टेलीग्राम" to "telegram", "स्पॉटिफाई" to "spotify", "कैलकुलेटर" to "calculator", "गैलरी" to "gallery",
        "फोटो" to "photos", "फोटोज" to "photos", "फोन" to "phone", "मैसेज" to "messages", "मैसेजेस" to "messages", "प्ले" to "play",
        "स्टोर" to "store", "नेटफ्लिक्स" to "netflix", "अमेज़न" to "amazon", "अमेजन" to "amazon", "पेटीएम" to "paytm", "फोनपे" to "phonepe",
        "ट्विटर" to "twitter", "स्नैपचैट" to "snapchat", "घड़ी" to "clock", "घडी" to "clock",
    ).mapKeys { devKey(it.key) }

    /** Lower-case, no punctuation, Devanagari words and digits turned into Roman, single spaces. */
    fun normalize(raw: String): String {
        var t = devKey(raw.lowercase())
        t = buildString(t.length) {
            for (c in t) {
                val d = DEVANAGARI_DIGITS.indexOf(c)
                append(if (d >= 0) '0' + d else c)
            }
        }
        t = AM_PM.replace(t) { "${it.groupValues[1]}m" }
        t = PUNCTUATION.replace(t, " ")
        return t.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ") { WORDS[it] ?: it }
    }

    /** [normalize] plus politeness words dropped from the start and end ("hey jarvis, can you open youtube please"). */
    fun clean(raw: String): String {
        val words = normalize(raw).split(' ').filter { it.isNotEmpty() }.toMutableList()
        var changed = true
        while (changed && words.isNotEmpty()) {
            changed = false
            val head2 = words.take(2).joinToString(" ")
            if (head2 in LEADING_PHRASES) {
                repeat(2) { words.removeAt(0) }
                changed = true
            } else if (words.first() in LEADING) {
                words.removeAt(0)
                changed = true
            }
        }
        while (words.isNotEmpty() && words.last() in TRAILING) words.removeAt(words.lastIndex)
        return words.joinToString(" ")
    }

    /** True if the text contains Devanagari letters. */
    fun hasDevanagari(text: String) = text.any { it in '\u0900'..'\u097F' }
}
