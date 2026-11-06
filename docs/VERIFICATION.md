# What was verified, and how

Environment: Linux sandbox with a JRE and Kotlin 2.0.21 (same compiler version as `app/build.gradle.kts`). Maven Central, Google Maven and Gradle services are **not reachable** from it, so Gradle, lint, Robolectric and GitHub Actions could **not** be run. Nothing here was run on a phone.

## Verified (actually executed here)

| Check | Result | Evidence |
|---|---|---|
| Pure-Kotlin unit tests (router, app matcher, memory parser, memory prompt, AI routing policy, circuit breaker, start throttle, server health, latency trace, confirmation gate): compiled with `kotlinc` and run through a small JUnit shim | **58 passed, 0 failed** | `evidence/unit-tests.log` |
| All app sources that do not need generated code (service, voice, agent, accessibility, memory, router, ai, Settings, the new Settings section) type-checked against the real `android.jar` (API 34) and Sherpa-ONNX classes, with stubs for AndroidX/Material/OkHttp/Anthropic SDK | **compiles, exit 0** | `evidence/android-compile.log` |
| Termux scripts run against a fake `llama-server` (first run crashes, second hangs, third healthy) and a fake `tmux` | **9 of 9 checks passed**: recovers after crash and after hang, no second server, flags passed, installer idempotent with backups | `evidence/termux-scripts-test.log` |
| `AndroidManifest.xml`, new layout, `ci.yml` | well-formed XML / YAML | `evidence/xml-yaml-check.log` |

Reproduce: `verify/run-unit-tests.sh <project> <kotlinc/bin>`, `verify/compile-android.sh ...`, `verify/test-termux-scripts.sh <project>`.

## Not verified (needs Gradle/GitHub Actions or the phone)

- The Gradle build, `lintDebug`, and the Robolectric tests (`MemoryStoreTest` was written but never run; the existing Robolectric tests were not re-run against the changes). The CI run is the first real check: it builds and uploads the APK first, then runs them.
- The edited `SettingsFragment` and the new layout `view_assistant_section.xml` (resource linking and view-binding code generation).
- Everything about real behaviour: wake-word reliability and latency, microphone recovery, Termux `RUN_COMMAND` on the Play build of Termux / Android 16 / Realme UI, Google Hindi recognition, accessibility actions and the "app came to the front" check on your apps, speed of the on-phone model with the new settings, battery use.
- The checks the sandbox used are stricter in some places and weaker in others than Gradle: stubs only describe signatures, so a wrong assumption about a real AndroidX/Material API would not show up here.
