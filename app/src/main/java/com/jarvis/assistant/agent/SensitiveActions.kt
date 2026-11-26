package com.jarvis.assistant.agent

/** Known navigation labels and explicit spoken affirmations for app-generated confirmation prompts. */
object SensitiveActions {
    const val MANUAL = "Please review and complete that action on your phone."
    private val navigationLabels = setOf("search", "back", "home", "menu", "close", "next page", "previous page",
        "खोजें", "वापस", "मेन्यू")

    fun canNavigate(label: String, editable: Boolean): Boolean =
        (editable && !needsConfirmation(label)) || label.trim().lowercase() in navigationLabels

    private val SENSITIVE = Regex(
        """^\s*(send|call|dial|post|tweet|publish|share|reply|pay|transfer|place order|buy now|confirm payment|delete|submit)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val affirmative = setOf("yes", "yeah", "yep", "yup", "sure", "ok", "okay", "confirm", "confirmed",
        "go ahead", "yes go ahead", "yeah go ahead", "do it", "send it", "ok send it", "haan", "han", "ha",
        "theek hai", "thik hai", "kar do", "bhej do", "हाँ", "हां", "ठीक है", "कर दो", "भेज दो", "हां कर दो")

    /** True if tapping a control with this label sends, places or deletes something. */
    fun needsConfirmation(label: String): Boolean = label.isNotBlank() && SENSITIVE.containsMatchIn(label)

    /** True only for a clear yes. Anything unclear, or containing a no, counts as "not confirmed". */
    fun isYes(answer: String?): Boolean {
        return answer?.trim()?.trimEnd('.', '!', '?')?.lowercase()?.replace(Regex("\\s+"), " ") in affirmative
    }
}
