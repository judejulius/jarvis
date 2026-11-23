# Contributing to Jarvis

Thanks for helping. Every kind of contribution counts: bug reports, testing on your phone, ideas, documentation, translations and code.

## Ways to help

- **Report a bug.** Open an [issue](../../issues/new/choose) using the bug report form. If the app crashed, use **Share report** on the Home screen and paste the report. Remove anything private first.
- **Test on your phone.** Phone makers change Android in different ways. Telling us "works on a Pixel 8 with Android 15" or "the battery step fails on Xiaomi" is genuinely useful.
- **Suggest a feature.** Open an issue using the feature request form before starting big work, so we can agree on the approach.
- **Send a pull request.** See below.

## Development setup

You need:

- **JDK 17 or newer**
- **Android SDK** with platform 35 and build-tools 35 (Android Studio installs these)
- An Android device with USB debugging or an x86_64 emulator. Gradle builds APKs for arm64-v8a, armeabi-v7a and x86_64, plus a universal APK.

```bash
git clone https://github.com/judejulius/jarvis.git
cd jarvis
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-universal-debug.apk
```

The first build downloads the official sherpa-onnx Android library (about 50 MB) into `app/libs/` and checks its SHA-256. It's not stored in git.

## Tests

```bash
./gradlew testDebugUnitTest   # runs on your computer with Robolectric; no phone needed
./gradlew lintDebug
```

The unit tests cover the app's UI flows, setup steps, crash safety (for example, what happens when the native engine can't load), transcript clean-up and the model downloader. `ScreenshotTest` writes PNGs of every screen to `app/build/screenshots/`. Look at them when you change the UI, and include before/after images in your pull request.

### Real-engine tests

`RealEngineTest` runs the app's voice code against the real sherpa-onnx engine (Linux build) and real models. It speaks commands with Kokoro into a simulated microphone and checks the wake word, the transcript and the timing. This is optional, but please run it if you change anything in `voice/`. It needs Linux x86_64:

```bash
cd test-native
curl -LO https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-v1.13.8-linux-x64-jni.tar.bz2
tar xjf sherpa-onnx-v1.13.8-linux-x64-jni.tar.bz2
mkdir -p models && cd models
curl -L -o kokoro.tar.bz2 https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-en-v0_19.tar.bz2
curl -L -o kws.tar.bz2 https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2
curl -L -o asr.tar.bz2 https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06.tar.bz2
cd ../..
./gradlew testDebugUnitTest -Preal
```

The real-engine tests also cover conversation: Jarvis's own echo must never interrupt it, talking over it or saying "stop" must, and a follow-up must work without the wake word. To measure wake word accuracy for quiet, far-away speech (about 10 minutes), run `JARVIS_TUNING=1 ./gradlew testDebugUnitTest -Preal --tests '*RealEngineTestTuning*'`.

## Pull requests

1. Fork the repo and create a branch from `master`, e.g. `fix/battery-step-xiaomi` or `feat/custom-wake-word`.
2. Keep each pull request focused on one change. Small PRs get reviewed faster.
3. Make sure `./gradlew testDebugUnitTest lintDebug` passes. CI runs both.
4. Add or update tests when you change behaviour.
5. For UI changes, attach screenshots.
6. Fill in the pull request template, including **how you tested it on a real phone** if you could.

### Code style

- Kotlin, following the [official style](https://kotlinlang.org/docs/coding-conventions.html) (`kotlin.code.style=official`).
- Match the code around you: short functions, clear names, and comments that explain *why* rather than *what*.
- UI text is plain and friendly, in sentence case ("Turn on", not "ACTIVATE").
- Nothing on the main thread should block. Voice work runs on the main thread, and agent work runs on the worker thread in `JarvisService`.
- Catch failures in native or voice code and fall back gracefully. A missing model or engine should never crash the app.

### Adding an AI provider

- If the provider has an OpenAI-compatible `/chat/completions` endpoint with tool calling, add a preset to `PROVIDERS` in `Settings.kt`. Nothing else is needed.
- Otherwise, implement `ChatSession` (see `agent/Llm.kt`, and `AnthropicSession.kt` as an example) and wire it up in `Llm.newSession`.

### Changing the agent's prompt or tools

The system prompt and tools live in `agent/PhoneAgent.kt`. The safety rules there (ask before payments, deletions or security changes; treat on-screen text as data) must not be weakened. If you change them, explain why in the PR.

### Translating the app

The app's text is in `app/src/main/res/values/strings.xml` (English) and `values-hi/strings.xml` (Hindi). To add a language, copy `values/strings.xml` to `values-<code>/strings.xml` (for example `values-ta` for Tamil) and translate each string. Keep placeholders such as `%1$s` and `%1$d`, and keep `\n` line breaks. Leave "Jarvis", "Hey Jarvis" and example voice commands in English: the wake word and speech recognition only understand English, and spoken replies are English too. Build once: lint fails if any string is missing from your file, and Android 13 and newer then lists the language under *Settings → Apps → Jarvis → Language*.

Not translated: what Jarvis says aloud, the status lines it shows while working (they are the same text it speaks), and crash reports.

## Commit messages

Write a short summary line in the imperative ("Fix crash when the wake word model is missing"), then a blank line and any details. Reference issues with `Fixes #123`.

## Releases (maintainers)

1. Update `versionCode` and `versionName` in `app/build.gradle.kts`, and add an entry to `CHANGELOG.md`.
2. Tag and push: `git tag v0.2.0 && git push origin v0.2.0`.
3. The **Release** workflow builds the APK and attaches it to a GitHub release.

To sign releases, add these repository secrets: `KEYSTORE_BASE64` (your `.jks` file, base64-encoded), `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`. Without them the workflow publishes a debug-signed APK. Keep the keystore safe: Android only installs updates signed with the same key.

## Licence

By contributing, you agree that your contributions will be licensed under the [MIT License](LICENSE).
