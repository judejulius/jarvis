# Changelog

All notable changes to this project are listed here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- **Instant phone commands without the AI model**: open apps, back/home/recents, scroll, volume, flashlight, music controls, search, settings pages, camera, alarms and timers, time/date/battery, screenshot, lock, tap by label, type text. English, Hinglish and Hindi (Devanagari). Each action checks the result (app in front, volume level, torch state, music playing) before Jarvis says it worked. Anything it does not recognise still goes to the model as before.
- **Online / Offline / Automatic AI modes** (Settings > Assistant, or say "offline mode", "online mode", "automatic mode"). Automatic keeps short simple questions on the on-phone model and sends hard, current-events or Hindi questions to the cloud when the internet works. A provider that keeps failing is skipped for a while instead of waited on.
- **On-phone model server supervision**: Jarvis checks `llama-server` before using it, asks Termux to start it when it is down, never starts it twice, and falls back to a cloud model (or reports clearly) when it cannot. New `termux/` scripts restart the server after a crash or a hang and after a reboot.
- **Memory you control**: "when I say yt, open YouTube", "remember that I prefer short answers", "forget ...", "what do you remember". Stored on the phone only; view, edit and delete in Settings > Assistant > Memories. Cloud models see only answer-style notes unless you allow more.
- **Hindi / Hinglish commands** through Google's recognizer (Settings > Assistant, or say "Hindi mode").
- **Confirmation before Send / Call / Pay** is now enforced in code for phone control, not only requested in the prompt.
- Microphone recovery: if Android takes the microphone away or blocks it, Jarvis reopens it instead of staying deaf.
- Short commands finish as soon as you stop speaking instead of waiting for the full silence timeout.
- The on-phone model is warmed up the moment "Hey Jarvis" is heard, and the fixed part of its prompt is cacheable.
- Per-stage latency log (`JarvisLatency`, stage names and milliseconds only).

### Added (second round)
- **On-device model runtime built into the app**: `llama-server` ships inside the APK (built by GitHub Actions), runs as a supervised child process on `127.0.0.1:18080` behind a random API key, restarts after a crash or hang, and is stopped when idle or when Android is short of memory. The model downloads once (resumable, storage-checked, size/GGUF/SHA-256 verified). Termux is now optional.
- **Calling by contact name** with contact lookup (family words, Hindi names), "which one?" questions for ambiguous names, confirmation before dialling, and honest results.
- **Daily greeting** ("Hello sir, good morning. What's the task for today?") once per local day; no more "On it" before every request.
- **Streamed speech**: chat answers are spoken sentence by sentence while they are still being written.
- **Task manager**: a new command replaces the task in front (screen tasks never fight over the screen), instant commands run alongside, answers can go to the background, "cancel" never claims to undo something already done, double-heard commands run once.
- **"Open YouTube and play ..."** (search in the app, pick the first real video, confirm that sound starts) on a reusable app-action framework.
- **Understanding unclear speech**: Google's alternative transcriptions, "when I say X, I mean Y" corrections, sound-alike app names confirmed with "Did you mean ...?", app launching through the launcher activity, "not installed" detection.
- **Memory**: earlier tasks (last 100, local only, optional), secrets are never stored.
- Microphone: idle listening now uses the plain recognition source; the call-mode source with echo cancellation is used only while Jarvis speaks.

### Changed
- Phone control with the small on-phone model uses a shorter prompt, a shorter screen list and no screenshots; the date and your notes moved out of the system prompt so llama-server can cache it.
- Network clients are shared, local-server timeouts are short when it is down, and local requests ask llama.cpp to reuse its prompt cache.
- The voice model is kept for 10 minutes (was 2) and released at once when Android reports memory pressure.
- CI builds and uploads the APK before running tests and lint, so a failing check never hides an installable build.

- Talk over Jarvis to interrupt it, or say "stop". The microphone stays on while it speaks, using the phone's echo cancellation, and Jarvis tells its own voice apart from yours.
- Follow-ups without the wake word: after a reply Jarvis keeps listening for a few seconds and remembers the last few exchanges.
- "Hey Jarvis" during a task stops it and takes the new command.
- Cloud voices: OpenAI, ElevenLabs, Fish Audio and any OpenAI-compatible server, streamed so speech starts quickly, with automatic fallback to Kokoro or the phone's voice.
- Automatic gain for quiet voices (someone across the room).
- An iPhone-style Dynamic Island: expands into a card with the task and buttons, and its border moves with Jarvis's voice.

### Fixed
- Turning Jarvis off now stops the microphone, and Jarvis stays off until turned back on.
- Stopping Jarvis mid-sentence no longer leaves it stuck with the microphone muted.
- The island no longer covers the status bar and camera.

## [0.1.0] - 2026-10-15

First public release.

### Added
- "Hey Jarvis" wake word, offline speech recognition and the Kokoro voice, all on the device.
- An agent that controls the phone through the accessibility service: tap, type, scroll, swipe, open apps, screenshots, and asking the user questions.
- Support for Claude, OpenAI, LM Studio, Ollama, OpenRouter, Gemini, Groq and any OpenAI-compatible endpoint, with a primary model and automatic backups.
- A task island that shows progress and can stop the current task.
- A setup walkthrough, crash reports, and starting at boot.
