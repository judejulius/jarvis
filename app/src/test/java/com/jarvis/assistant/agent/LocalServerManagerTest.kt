package com.jarvis.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalServerManagerTest {

    @Test
    fun verifiesServerConstants() {
        assertEquals("http://127.0.0.1:8080", LocalServerManager.DEFAULT_LOCAL_BASE_URL)
        assertEquals("/health", LocalServerManager.HEALTH_ENDPOINT)
        assertEquals("com.termux", LocalServerManager.TERMUX_PACKAGE)
        assertEquals("com.termux.RUN_COMMAND", LocalServerManager.ACTION_RUN_COMMAND)
    }
}
