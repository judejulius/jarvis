# Audit findings

Evidence: source ZIPs (31 Kotlin files, line counts equal to commit `9ed600d`), CI workflows, your Termux server log and timings, `07/08` evidence. "Confirmed" = visible in source or logs. "Hypothesis" = plausible, not measured.

| # | Finding | Status | Fixed by |
|---|---|---|---|
| 1 | "Open YouTube" went through the full model tool loop; no deterministic layer | Confirmed (source) | `router/` fast path |
| 2 | Nothing restarts `llama-server` after it dies or hangs; the boot script checks only that a process exists | Confirmed (boot script, server not running in first evidence) | `termux/` supervisor + `LocalServerManager` |
| 3 | llama-server ran with 4 slots and an 8192 MiB prompt-cache limit on a 7.4 GiB phone | Confirmed (server log) | `llama.conf` defaults |
| 4 | `-c 2048` is smaller than the phone-control prompt (system about 1.4k chars, tools about 3k chars, plus the screen list) | Source sizes confirmed; token count is an estimate | context 4096, compact prompt/tools/screen for the on-phone model |
| 5 | Date/time was inside the system prompt, changing the cached prefix every minute | Confirmed (source) | moved into the user message |
| 6 | The text-only local model was offered `look_at_screen` and raw-coordinate tools | Confirmed (source) | compact tool list |
| 7 | A new OkHttp client per session; 240 s read timeout for every provider | Confirmed (source) | shared client, per-kind timeouts |
| 8 | Microphone loop: read errors only slept; an exception ended the thread; no recovery | Confirmed (source) | watchdog + restart |
| 9 | `stop()` then `start()` could leave an old microphone thread running beside a new one if the 1.5 s join timed out | Hypothesis | generation counter |
| 10 | Cold request after about 16 min idle: 167 prompt tokens took 11.4 s at 14.6 tok/s versus 22.7 tok/s warm; server RSS 86 MB for a 941 MB model | Measured once; cause (weights paged out) is a hypothesis | wake-time warm-up |
| 11 | KWS, ASR and TTS models are English-only; Google recognizer had no language set | Confirmed (model names, source) | `speechLanguage` + hi-IN |
| 12 | Hinglish chat answer from Qwen2.5-1.5B was nonsense | Confirmed (one sample) | Auto mode routes Hindi/Hinglish chat to cloud |
| 13 | Memory was one free-text note; no corrections or aliases | Confirmed (source) | `memory/` |
| 14 | Confirmation before sending/calling existed only as prompt text | Confirmed (source) | `SensitiveActions` gate |
| 15 | CI ran tests and lint before building the APK, so one failing check hid the APK | Confirmed (workflow) | CI order |
| 16 | `RealEngineTest` and the Robolectric tests need the native libraries/Gradle and could not be run here | Limitation | see VERIFICATION.md |

## File ledger (07-git-evidence vs. uploaded ZIPs)

All 31 Kotlin files listed in `07-git-evidence.txt` exist in the ZIPs with identical line counts. Not in the ZIPs and not needed to build: `.gitignore`, `LICENSE`, `CONTRIBUTING.md`, `test-native/` (only for `-Preal` runs), launcher icons (the app uses adaptive vector icons), `app/libs/*.aar` (downloaded by Gradle at build time). Evidence of repository state: clean working tree, HEAD `9ed600d`.
