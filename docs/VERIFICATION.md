# What was verified, and how

Environment: a Linux sandbox with a JRE and Kotlin 2.0.21 (the version `app/build.gradle.kts` uses). Maven Central, Google Maven and Gradle
services are **not reachable** from it, so Gradle, lint, Robolectric and GitHub Actions could **not** be run, no APK could be built, and nothing
was run on a phone.

## Verified (executed here)

| Check | Result | Evidence |
|---|---|---|
| Pure-Kotlin unit tests, compiled with `kotlinc` and run through a small JUnit shim (a fresh test instance per test, like JUnit): command router, app matcher, memory parser, phrase corrector, memory prompt, sensitive filter, AI routing policy, circuit breaker, start throttle, server health, latency trace, confirmation gate, **model downloader** (resume after a dropped connection, checksum, storage, redirect, cancel/resume, HTTPS-only), runtime command line and restart policy, **contact matcher** (Hindi transliteration, family words, ambiguity), **daily greeting** (time zones, date change), **task board** (interruption, background, irreversible, duplicates, control phrases), **YouTube result picker**, **SSE parser, sentence chunker and reply streamer** | **120 passed, 0 failed** | `evidence/unit-tests.log` |
| All app sources that do not need generated code (service, voice, agent, accessibility, memory, router, ai, runtime, contacts, tasks, apps, persona, Settings, the Settings section) type-checked against the real `android.jar` (API 34) and the Sherpa-ONNX classes, with stubs for AndroidX/Material/OkHttp/Anthropic SDK | **compiles, exit 0** | `evidence/android-compile.log` |
| Termux scripts against a fake `llama-server` and fake `tmux` (crash, hang, healthy; idempotent install; no duplicate server) | **9 of 9 passed** | `evidence/termux-scripts-test.log` |
| Manifest, new layout, both workflows, all shell scripts | well-formed | `evidence/xml-yaml-check.log` |
| `git diff --check` over the new commits; the patch series applies with `git am` to the previous delivery, and the cumulative patch applies to the original code | clean | this folder's `patches/` |

Also checked with the real model files from GitHub: the wake-word keyword tokenization (`▁HE Y ▁JA R VI S`) equals what the model's own `bpe.model`
produces. llama-server option names used by the runtime (`--api-key`, `--cache-ram`, `--parallel`, `--threads`, `--ctx-size`, `--port`, `--host`,
`--no-webui`) exist in llama.cpp `b12099`'s argument parser, and the CMake options used by `scripts/build-llama-android.sh` configure on a host build.

## Not verified (needs Gradle/GitHub Actions or the phone)

- The Gradle build, `lintDebug`, the Robolectric tests (the new `MemoryStoreTest` cases and all existing ones), the GitHub Actions run, the APK.
- The Android cross-compile of llama-server, and running it from the app's library folder.
- The edited `SettingsFragment` and the new layout (resource linking, view-binding generation).
- Everything on the phone: wake-word reliability and latency, the new microphone mode, Termux/embedded runtime behaviour, calls, contacts permissions, the YouTube action on your YouTube version, streamed speech timing, task interruption flows, Hindi recognition, battery and memory use.
- **Latency before/after**: not measured. The pipeline is instrumented (`JarvisLatency` stages incl. `firstSpeech`, `JarvisTiming` model first-token time); numbers need a run on the phone.
