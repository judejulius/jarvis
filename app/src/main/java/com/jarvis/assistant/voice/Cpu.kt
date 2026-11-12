package com.jarvis.assistant.voice

import java.io.File

/**
 * How many threads a voice model should use, given each core's top speed in [maxFreqs] (empty if
 * unknown) and the number of [cores].
 *
 * Most phones have a few fast cores and several slow ones. A model run is split evenly between its
 * threads and waits for the slowest, so a thread on a slow core holds the fast ones back: on a
 * phone with two fast cores, two threads beat four. So: one thread per core that isn't in the
 * slowest group, between 2 and 4.
 */
internal fun voiceThreads(maxFreqs: List<Long>, cores: Int): Int {
    val slowest = maxFreqs.minOrNull()
    val fast = maxFreqs.count { it > (slowest ?: 0) }
    // All cores alike, or nothing known: half of them, as some are busy with the microphone and the screen.
    return (if (fast > 0) fast else cores / 2).coerceIn(2, 4)
}

/** [voiceThreads] for this phone. */
internal val VOICE_THREADS: Int by lazy {
    val cores = Runtime.getRuntime().availableProcessors()
    val freqs = (0 until cores).mapNotNull { cpu ->
        runCatching { File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull()
    }
    voiceThreads(freqs, cores)
}
