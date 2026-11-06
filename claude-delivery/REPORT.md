# JARVIS implementation report

**Access used:** the uploaded ZIPs only (no GitHub access, no phone). The 5 nested ZIPs + `06-ci-wrapper.zip` were merged into one working repository; all 31 Kotlin files match the line counts in `07-git-evidence.txt` (HEAD `9ed600d`, clean tree). Nothing was pushed, built by Gradle, or installed.

## What was done

| Goal | Implemented |
|---|---|
| Fast deterministic phone actions | `router/`: English/Hinglish/Devanagari parser; open app (built-in names, your installed apps, taught names, small mis-hearings), back/home/recents/notifications, scroll, volume, flashlight, media keys, search, settings pages, camera, alarm/timer, time/date/battery, screenshot/lock, tap-by-label, type text, mode and language switches. Results are checked where Android allows (app in front, volume read-back, torch state, music playing). Unknown requests fall through to the model unchanged. |
| Online / offline / auto | `ai/RoutePolicy` + `AiRouter` + `CircuitBreaker`; Settings > Assistant and voice commands; `ModelGate` also drives the phone agent. |
| Qwen server availability | `LocalServerManager` (health check, throttled start via Termux, no double start, fall back to cloud or report why), `LocalWarmup`, and `termux/` supervisor with crash restart, hang watchdog, boot start, log rotation, flags guarded by `--help`. |
| Qwen efficiency | cacheable static system prompt (date/notes moved out), compact prompt/tools/screen list and no screenshots for the on-phone model, one-slot server, context 4096, 512 MiB cache, shared HTTP client, shorter timeouts, `cache_prompt`, lower temperature. |
| Wake word / microphone | watchdog: reopen on read errors, dead object, stopped recording or 5 s of exact zeros (blocked mic), growing pause, status text, no duplicate threads (generation counter). Keyword model and thresholds were left unchanged: no evidence justified changing them. |
| Latency | early accept for short complete commands (0.6-0.8 s of quiet instead of 1.3 s), no "On it." before instant commands, wake-time warm-up, per-stage `JarvisLatency` log. |
| Hindi / Hinglish | Google recognizer in `hi-IN` (Settings or "Hindi mode"); router understands Devanagari and Roman Hinglish. Offline Hindi was not added (no model could be tested). |
| TTS | Kokoro kept; nine more short replies pre-rendered; model kept 10 min and freed on memory pressure. |
| Memory / learning | `memory/`: SQLite store, taught app names, notes, FTS4 search with LIKE fallback, auto-learning of a mis-heard name only after a successful retry and only if the names sound alike, view/edit/delete screen, local-only by default (cloud gets style notes only unless you allow more), memory written only from explicit user sentences. |
| Safeguards | calls by voice shortcut open the dialer only; Send/Call/Pay-style taps in phone control are blocked in code until you say yes. |
| Build | CI builds and uploads the APK first, then tests and lint; reports uploaded. |
| Tests | 9 new test classes (58 pure-Kotlin tests run here, plus a Robolectric memory test that could not be run here). |

## Verified vs not

See `docs/VERIFICATION.md`. In short: **verified here** = 58 unit tests pass; app sources type-check against the real `android.jar` (with stubs for AndroidX/Material/OkHttp); Termux scripts pass 9/9 against a fake server; XML/YAML/shell syntax valid; patch applies cleanly to the baseline and `git diff --check` is clean. **Not verified** = Gradle build, lint, Robolectric tests, the GitHub Actions run, and every behaviour on the phone (wake word, mic recovery, Termux start permission on the Play build, Hindi recognition, accessibility checks, speed).

## Not implemented (and why)

LLM streaming into speech, offline Hindi models, contact-name calling without the model, in-app search without the model, persistent chat history/RAG, openWakeWord/other models/JNI llama.cpp. See `docs/LIMITATIONS.md` and the decision notes in `docs/ARCHITECTURE.md`.

## Risks to know

- Termux Play build may reject RUN_COMMAND; the scripts and boot start still work, the app then reports it and uses the cloud.
- Auto mode changes who answers: with internet, hard/current/Hindi chat and all phone-control tasks go to your cloud models first.
- `SettingsFragment` and the new layout are unbuilt; they are wrapped so a failure there cannot break the Settings page, but the Assistant card would be missing.

## Next step

Apply per `docs/INSTALL.md`, push, open the CI run, and send me the result (green/red and the failing test names if any). Then run the Termux setup and try the commands in INSTALL.md section 4.
