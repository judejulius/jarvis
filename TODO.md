# TODO

Things to improve, roughly in priority order. The main focus right now is **voice output (TTS) speed**:
how long it takes from "Jarvis has something to say" to "sound comes out of the speaker".

## 1. Measure first

- [x] **Log time-to-first-audio for every utterance.** (Done for Kokoro: `Kokoro: timing firstAudio=...`. Cloud and system voices still to do.)
- [ ] (original note) Record the time in `Speaker.speak()` and the time of
      the first `AudioOut.Playback.write()` that actually plays samples; log the difference with the engine
      name (kokoro / cloud id / system) and whether it was a cache hit. Without this, every item below is a guess.
- [x] (Done: `Kokoro: made N chars in ...`, and `Kokoro.realTimeFactor`.) Log Kokoro's per-sentence generation time vs. the sentence's audio length (real-time factor), so we know
      whether the phone generates faster than it plays.
- [ ] (Partly done: after a preview, Settings says when Kokoro is slower than its own speech.) Add a debug line in Settings showing the last few latencies (next to `lastError`).

## 2. Kokoro (on-device) — biggest wins

- [x] **Don't cold-load the model mid-conversation.** (Done: 10 minutes, and released on memory pressure.)
- [ ] (original note)  `Kokoro.IDLE_UNLOAD_MS` is 2 minutes, so a reply after
      a short pause pays the full model load. Options: make the timeout a setting, keep it loaded while the
      service is in the foreground, or only unload on `onTrimMemory()` instead of a timer.
- [ ] **Warm up earlier.** `Speaker.warmUp()` is only called in `JarvisService.listen()`. Also call it on the
      wake word itself and when a task starts, so loading overlaps with the user talking / the agent working.
- [x] **Cache more stock phrases.** (Done: nine short replies added.)
- [ ] (original note)  Only `"Yes?"`, `"On it."` and the unlock prompt are pre-rendered
      (`Kokoro.STOCK_PHRASES`). Add the other fixed lines Jarvis says, e.g. `"Sorry, I didn't catch that."`,
      `"Okay."`, `"Done."`.
- [x] **Shorter first chunk.** (Done: `SpeechChunks` and `SpeechPacer`; the first piece is at most 40 characters.)
- [ ] (original note) Generation is already streamed sentence by sentence (`maxNumSentences = 1`), but a
      long first sentence still delays the first sound. Split the first sentence at a comma/clause boundary so the
      first chunk is short, then stream the rest normally.
- [ ] **Benchmark `numThreads`.** (Now one thread per fast core, 2..4, in `Cpu.kt`. Still needs checking on a real phone.) It's `cores / 2` clamped to 2..4. Try 2, 4 and 6 on a real phone with the
      latency logging above and pick what's fastest (big.LITTLE cores make more threads not always better).
- [ ] **Try the full-precision model.** On a desktop CPU the int8 model spends 87% of its time in quantized convolutions (`ConvInteger`) and the float model (`kokoro-en-v0_19`, 330 MB, about 200 MB more memory) is 2.3 times faster. Phone processors may differ: read `Kokoro: made ... x its length` from logcat on a phone before deciding.
- [ ] Try running the int8 model with the XNNPACK / NNAPI provider in sherpa-onnx and compare.
- [ ] Faster phrase cache loading: `readPcm()` reads one float at a time through `DataInputStream`. Read the file
      into a `ByteBuffer` and copy with `asFloatBuffer()` instead. Also key the cache on something stronger than
      `text.hashCode()` to avoid collisions.

## 3. Cloud voices

- [ ] **Fall back quickly when the provider is slow.** Right now fallback only happens on an error, and
      `connectTimeout` is 10 s. Add a time-to-first-byte budget (e.g. ~1.5 s): if no audio has arrived by then,
      cancel and speak with Kokoro/system instead.
- [ ] **Pre-warm the connection.** `Speaker.warmUp()` does nothing for cloud voices. Open the TLS connection
      (cheap request to the provider's base URL) during `listen()` so the real request skips the handshake.
- [ ] **Play stock phrases locally / from cache.** `"Yes?"` and `"On it."` currently go over the network every
      time. Cache the cloud-rendered audio on disk (like Kokoro does), or always use the local voice for them.
- [ ] Check each provider's low-latency options (e.g. Fish Audio's `latency` mode, ElevenLabs' streaming latency
      settings and flash models) and use the fastest by default.
- [ ] Smaller first read: the stream buffer is 200 ms (`ByteArray(9600)`); verify the first chunk is written as
      soon as it arrives rather than waiting to fill.

## 4. Whole-pipeline speed (perceived responsiveness)

- [ ] **Trim dead time after speech.** `Playback.finish()` waits `endAt + 120 ms` (polling every 20 ms), and
      `say()` then waits another 150 ms before listening again. Tune these down and check echo doesn't come back.
- [ ] **Speak while working.** For long tasks, speak short progress updates from `AgentHost.status()` (e.g. "Opening
      WhatsApp…") so the wait feels shorter. Make it optional in Settings.
- [ ] **Stream the final reply.** The summary is only spoken once the agent's `finish` tool call is complete. If the
      LLM API supports streaming tool arguments, start speaking the first sentence of `summary` as it arrives.
- [ ] Warm up the system voice too: `SystemVoice` binds to Android's TTS engine lazily on first `speak()`; create it
      in `warmUp()` when it's the selected engine (or the likely fallback).

## 5. Tests

- [ ] Add a unit test for the first-chunk splitting logic.
- [ ] Extend `RealEngineTest` to report Kokoro time-to-first-audio so regressions show up.
- [ ] Test the cloud time-to-first-byte fallback in `CloudVoiceTest` with a mock server that stalls.
