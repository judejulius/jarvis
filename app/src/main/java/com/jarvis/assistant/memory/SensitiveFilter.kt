package com.jarvis.assistant.memory

/**
 * Keeps secrets out of Jarvis's memory. Passwords, one-time codes, card and ID numbers are never saved, even if the
 * user says "remember". Pure logic.
 */
object SensitiveFilter {
    private val WORDS = Regex(
        """\b(password|passcode|pass word|pin|otp|one time (?:password|code)|cvv|cvc|card number|credit card|debit card|aadhaar|aadhar|""" +
            """pan card|ssn|social security|bank account|account number|ifsc|upi pin|secret key|private key|api key|access token|login code|recovery code)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val LONG_NUMBER = Regex("""(?:\d[ -]?){12,}""")

    fun isSensitive(text: String): Boolean = WORDS.containsMatchIn(text) || LONG_NUMBER.containsMatchIn(text)

    const val REFUSAL = "I won't store that, because it looks like a password or a private number."
}
