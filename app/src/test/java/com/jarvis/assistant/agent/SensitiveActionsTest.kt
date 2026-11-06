package com.jarvis.assistant.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveActionsTest {
    @Test fun sendingCallingAndPayingNeedConfirmation() {
        for (label in listOf("Send", "send message", "Call", "Dial", "Post", "Reply", "Pay now", "Place order", "Delete", "  Send  ")) {
            assertTrue(label, SensitiveActions.needsConfirmation(label))
        }
    }

    @Test fun ordinaryControlsDoNot() {
        for (label in listOf("", "Search", "Back", "Messages", "Calls", "Sender name", "Type a message", "Share your location settings".lowercase().replace("share", "view"))) {
            assertFalse(label, SensitiveActions.needsConfirmation(label))
        }
    }

    @Test fun onlyAClearYesConfirms() {
        for (a in listOf("yes", "Yeah go ahead", "ok send it", "haan", "theek hai", "हाँ", "हां कर दो")) assertTrue(a, SensitiveActions.isYes(a))
        for (a in listOf(null, "", "no", "no don't send it", "yes no wait", "maybe", "nahi", "नहीं", "hmm let me think")) assertFalse(a.toString(), SensitiveActions.isYes(a))
    }
}
