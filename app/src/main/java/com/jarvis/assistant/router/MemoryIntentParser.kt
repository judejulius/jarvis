package com.jarvis.assistant.router

/**
 * Recognises "teach Jarvis" sentences. Only the user's own spoken words ever reach this parser, and only a few explicit
 * patterns create memories, so text from a web page, a notification or a model reply cannot write to memory.
 */
object MemoryIntentParser {
    private val OPT = RegexOption.IGNORE_CASE
    private val GREETING = Regex("""^(?:(?:hey|ok|okay)\s+)?jarvis[,.]?\s+""", OPT)

    private val TEACH_WHEN = Regex(
        """^(?:when|if|whenever) i (?:say|said|tell you|ask for|call)\s+["“']?(.+?)["”']?,?\s+(?:then\s+|you should\s+|please\s+)?""" +
            """(?:open|launch|start|go to)\s+(?:the\s+)?["“']?(.+?)["”']?(?:\s+app|\s+application)?[.!]?$""",
        OPT,
    )
    private val TEACH_MEANS = Regex(
        """^["“']?(.+?)["”']?\s+(?:means|mean|refers to|is another name for|stands for)\s+(?:the\s+)?["“']?(.+?)["”']?\s+app[.!]?$""",
        OPT,
    )
    private val TEACH_HI = Regex(
        """^(?:jab main|jab maine|agar main|jab)\s+(.+?)\s+(?:bolun|bolu|bolta hoon|bolta hu|kahun|kahu|bole|bolo|kahoon)""" +
            """(?:\s+to|\s+tab)?\s+(?:to\s+)?(.+?)\s+(?:kholo|khol do|khol dena|khol de|open karo|chalao|chalana)[.!]?$""",
        OPT,
    )
    private val PHRASE_EN = Regex("""^(?:when|if|whenever) i (?:say|said) ["“']?(.+?)["”']?,?\s+(?:i|what i) mean(?:t)?\s+["“']?(.+?)["”']?[.!]?$""", OPT)
    private val PHRASE_HI = Regex("""^(?:jab main|jab maine|jab)\s+(.+?)\s+(?:bolun|bolu|bolta hoon|bolta hu|kahun|kahu)(?:\s+to)?\s+mera matlab\s+(.+?)\s+(?:hota hai|hai|hota)[.!]?$""", OPT)
    private val TEACH_HI_MATLAB = Regex("""^(.+?)\s+ka matlab\s+(.+?)\s+(?:app\s+)?hai[.!]?$""", OPT)

    private val REMEMBER = Regex("""^(?:please\s+)?(?:remember|note|keep in mind|don't forget|dont forget)(?:\s+that)?[,:]?\s+(.+)$""", OPT)
    private val REMEMBER_HI = Regex("""^(?:yaad rakho|yaad rakhna|yaad kar lo|yaad rakh lo)(?:\s+ki)?[,:]?\s+(.+)$""", OPT)
    private val FORGET = Regex("""^(?:please\s+)?(?:forget|delete|remove|erase)(?:\s+the)?(?:\s+(?:memory|memories|note))?(?:\s+(?:about|that|of))?\s+(.+)$""", OPT)
    private val FORGET_HI = Regex("""^(?:bhool jao|bhool ja|mita do|hata do)\s+(.+)$""", OPT)
    private val LIST = Regex(
        """^(?:what do you (?:remember|know about me)|what all do you remember|show (?:me )?(?:my |your )?memor(?:y|ies)|""" +
            """list (?:my )?memories|tum kya yaad rakhte ho|meri memory dikhao|kya kya yaad hai)[?.!]?$""",
        OPT,
    )
    private val STYLE_TOPIC = Regex("""\b(short|brief|concise|detailed|long answers?|hindi|hinglish|english|formal|casual|simple|one line)\b""", OPT)
    private val STYLE_CUE = Regex("""\b(prefer|like|want|answers?|repl(?:y|ies)|respond|speak|talk|pasand|chahiye|bolo)\b""", OPT)
    private val FORGET_VAGUE = setOf("it", "that", "this", "everything", "all", "anything")
    private val EMPTY_FACTS = setOf("that", "this", "it", "me", "ki", "to")

    private const val MAX_FACT = 200

    fun parse(raw: String): MemoryIntent? {
        val s = raw.trim().replace(GREETING, "").trim()
        if (s.isEmpty()) return null
        teach(s)?.let { return it }
        FORGET.matchEntire(s)?.groupValues?.get(1)?.let { return forget(it) }
        FORGET_HI.matchEntire(s)?.groupValues?.get(1)?.let { return forget(it) }
        if (LIST.matches(s)) return MemoryIntent.ListAll
        val fact = (REMEMBER.matchEntire(s) ?: REMEMBER_HI.matchEntire(s))?.groupValues?.get(1)?.trim() ?: return null
        teach(fact)?.let { return it }
        val text = fact.replace(Regex("\\s+"), " ").trimEnd('.', '!', ' ')
        if (text.length < 3 || text.lowercase() in EMPTY_FACTS) return null
        val style = STYLE_TOPIC.containsMatchIn(text) && STYLE_CUE.containsMatchIn(text)
        return MemoryIntent.Remember(text.take(MAX_FACT), style)
    }

    private fun teach(s: String): MemoryIntent? {
        (PHRASE_EN.matchEntire(s) ?: PHRASE_HI.matchEntire(s))?.let { p ->
            val phrase = clean(p.groupValues[1]); val meaning = clean(p.groupValues[2])
            if (phrase.isNotEmpty() && meaning.isNotEmpty() && phrase.split(' ').size <= 4 && meaning.split(' ').size <= 6) {
                return MemoryIntent.TeachPhrase(phrase, meaning)
            }
        }
        val m = TEACH_WHEN.matchEntire(s) ?: TEACH_MEANS.matchEntire(s) ?: TEACH_HI.matchEntire(s) ?: TEACH_HI_MATLAB.matchEntire(s)
            ?: return null
        val phrase = clean(m.groupValues[1])
        val target = clean(m.groupValues[2])
        if (phrase.isEmpty() || target.isEmpty()) return null
        if (phrase.split(' ').size > 4 || target.split(' ').size > 4) return null
        return MemoryIntent.TeachApp(phrase, target)
    }

    private fun clean(s: String) = s.trim().trim('"', '\'', '“', '”', ',', '.', '!').trim().lowercase()

    private fun forget(what: String): MemoryIntent? {
        val w = what.trim().trimEnd('.', '!', ' ')
        return if (w.length < 3 || w.lowercase() in FORGET_VAGUE) null else MemoryIntent.Forget(w)
    }
}
