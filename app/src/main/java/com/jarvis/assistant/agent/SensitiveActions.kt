package com.jarvis.assistant.agent

/**
 * Code-level safeguard for actions that cannot be undone: sending, calling, posting, paying. While "Ask before calls
 * and messages" is on, the phone agent is not allowed to tap such a button until the user has said yes to a question
 * asked in this task. This does not depend on the language model following its instructions.
 */
object SensitiveActions {
    private val SENSITIVE = Regex(
        """^\s*(send|call|dial|post|tweet|publish|share|reply|pay|transfer|place order|buy now|confirm payment|delete|submit)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val YES = Regex(
        """\b(yes|yeah|yep|yup|sure|ok|okay|confirm|confirmed|go ahead|do it|send it|haan|han|ha|theek hai|thik hai|kar do|bhej do)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val NO = Regex("""\b(no|nope|don'?t|cancel|stop|wait|nahi|nahin|mat|ruko)\b""", RegexOption.IGNORE_CASE)
    private val YES_HINDI = listOf("हाँ", "हां", "ठीक है", "कर दो", "भेज दो")
    private val NO_HINDI = listOf("नहीं", "मत", "रुको")

    /** True if tapping a control with this label sends, places or deletes something. */
    fun needsConfirmation(label: String): Boolean = label.isNotBlank() && SENSITIVE.containsMatchIn(label)

    /** True only for a clear yes. Anything unclear, or containing a no, counts as "not confirmed". */
    fun isYes(answer: String?): Boolean {
        if (answer.isNullOrBlank()) return false
        val no = NO.containsMatchIn(answer) || NO_HINDI.any { it in answer }
        val yes = YES.containsMatchIn(answer) || YES_HINDI.any { it in answer }
        return yes && !no
    }
}
