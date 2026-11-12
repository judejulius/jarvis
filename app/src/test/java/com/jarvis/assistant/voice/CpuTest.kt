package com.jarvis.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class CpuTest {
    private fun cores(vararg groups: Pair<Int, Long>) = groups.flatMap { (n, freq) -> List(n) { freq } }

    @Test
    fun aPhoneWithTwoFastCoresUsesTwoThreads() {
        assertEquals(2, voiceThreads(cores(6 to 1_800_000L, 2 to 2_200_000L), 8))
    }

    @Test
    fun fastAndMiddleCoresBothCountUpToFour() {
        assertEquals(4, voiceThreads(cores(4 to 1_800_000L, 3 to 2_800_000L, 1 to 3_200_000L), 8))
        assertEquals(4, voiceThreads(cores(4 to 2_000_000L, 4 to 2_400_000L), 8))
        assertEquals(4, voiceThreads(cores(2 to 2_000_000L, 5 to 2_800_000L, 1 to 3_300_000L), 8))
    }

    @Test
    fun neverFewerThanTwo() {
        assertEquals(2, voiceThreads(cores(7 to 1_800_000L, 1 to 2_400_000L), 8))
        assertEquals(2, voiceThreads(emptyList(), 2))
    }

    @Test
    fun coresThatAreAllAlikeOrUnknownUseHalf() {
        assertEquals(4, voiceThreads(cores(8 to 2_000_000L), 8))
        assertEquals(2, voiceThreads(cores(4 to 1_500_000L), 4))
        assertEquals(4, voiceThreads(emptyList(), 8))
    }
}
