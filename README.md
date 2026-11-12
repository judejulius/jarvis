<div align="center">

<img src="docs/images/icon.png" alt="Jarvis" width="96" />

# Jarvis

**A voice assistant for Android that uses your phone for you.**

Say *"Hey Jarvis, text Mum I'm on my way"* and Jarvis opens the app, finds the chat, types the message, sends it and tells you when it's done. It works with Claude, OpenAI, or a model running on your own computer.

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](#requirements)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)


[Download](#install) · [Features](#features) · [How it works](#how-it-works) · [Contributing](CONTRIBUTING.md)

<br />

<img src="docs/images/home.png" alt="Home screen" width="200" />&nbsp;
<img src="docs/images/models.png" alt="Models" width="200" />&nbsp;
<img src="docs/images/settings.png" alt="Settings" width="200" />&nbsp;
<img src="docs/images/downloads.png" alt="Setup" width="200" />

</div>

---

## Table of contents

- [Why Jarvis?](#why-jarvis)
- [Features](#features)
- [Install](#install)
- [First-time setup](#first-time-setup)
- [AI providers](#ai-providers)
- [How it works](#how-it-works)
- [Voice speed](#voice-speed)
- [Building from source](#building-from-source)
- [Project structure](#project-structure)
- [Privacy and safety](#privacy-and-safety)
- [Limitations](#limitations)
- [Roadmap](#roadmap)
- [Contributing](#contributing)
- [Acknowledgements](#acknowledgements)
- [License](#license)

## Why Jarvis?

Phone assistants can answer questions, but most of them can't use your apps for you. Jarvis can. It reads the screen through Android's accessibility service, decides what to tap and type with the AI model you choose, and keeps going until the job is done.

- **Hands-free.** Wake word detection, speech recognition and the voice all run on the phone.
- **Your choice of model.** Use Claude, OpenAI, Gemini, Groq or OpenRouter, or keep everything at home with LM Studio or Ollama.
- **Works with any app.** Jarvis uses apps the way you do, so it doesn't need special integrations.
- **Open source** under the MIT licence.

## Features

### Voice and conversation
- **"Hey Jarvis" wake word.** Offline keyword spotting with [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx), so no audio leaves the phone while it waits.
- **Talk naturally.** Talk over Jarvis to interrupt it, or just say "stop". After it answers, carry on without the wake word ("...and tell her I'll be late"); it remembers the last few exchanges. Say "Hey Jarvis" during a task to stop or redirect it.
- **One-breath commands.** "Hey Jarvis, turn on the flashlight" works without waiting for a prompt. If you pause after "Hey Jarvis", it asks "Yes?".
- **Offline speech recognition** with a streaming Zipformer model. Quiet voices, like someone across the room, are boosted automatically. Google's recognizer is used only if you skip that download.
- **Your choice of voice.** [Kokoro](https://huggingface.co/hexgrad/Kokoro-82M) runs on the phone with 11 voices (British and American), or stream a cloud voice from **OpenAI**, **ElevenLabs**, **Fish Audio** or any OpenAI-compatible server. If a cloud voice can't be reached, Jarvis falls back to Kokoro or the phone's own voice.
- **A voice that keeps up with the conversation.** Kokoro starts speaking after the first clause of a reply and makes each next sentence while the current one plays, so there is no silence between them. It measures how fast your phone makes speech and adjusts to it. See [Voice speed](#voice-speed).
- Jarvis uses the phone's echo cancellation and tells its own voice apart from yours, so it doesn't interrupt itself.

### Phone control
- Taps, long-presses, types, scrolls, swipes, presses Back/Home and opens apps.
- Can take screenshots for vision models when the screen text isn't enough.
- Asks you a spoken question when it needs to, for example *"Which Sarah, Sarah Jones or Sarah Lee?"*
- Wakes the screen and asks you to unlock when the phone is locked.

### Many models, with backups
- Add as many models as you like and star one as the primary.
- **Backup models.** If the primary fails mid-task (offline, out of credit, server down), the next one takes over and is told what's already been done.
- Each model has a **Test connection** button.

### Built for everyday use
- **Dynamic Island.** A black pill just below the camera shows what Jarvis is doing. Its sound bars move with your voice while it listens, and its border lights up and moves with Jarvis's voice while it talks. Tap it to open a card with the task, live progress and buttons to stop or open the app.
- Runs as a foreground service, and can start at boot.
- A setup walkthrough covers permissions, phone control, battery settings and downloads.
- **"About you" notes** help with names, e.g. *"My wife is saved as 'Amaka' in WhatsApp."*
- **Crash reports.** If the app crashes, the next launch offers a report you can share.

<div align="center">
<img src="docs/images/island-listening.png" alt="Island while listening" width="260" />&nbsp;&nbsp;
<img src="docs/images/island-speaking.png" alt="Island while speaking" width="260" />
<br /><br />
<img src="docs/images/island-expanded.png" alt="Island expanded" width="420" />
</div>

## Install

### Requirements
- Android 8.0 (API 26) or newer.
- A 64-bit ARM phone (`arm64-v8a`). That's nearly every phone from the last several years.
- About 300 MB of free space: the app plus 175 MB of voice models, downloaded in the app.
- An API key for a cloud model, **or** a computer running LM Studio or Ollama.

### Download
1. Get the latest `Jarvis-*.apk` from [**Releases**](https://github.com/judejulius/jarvis/releases).
2. Open it on your phone. If asked, allow your browser or file manager to **install unknown apps**.
3. Open Jarvis and follow the setup walkthrough.

> Jarvis is not on the Play Store. Google restricts apps that use the accessibility service the way Jarvis does, so it's distributed for sideloading.

## First-time setup

The walkthrough guides you through each step and ticks it off when it's done. You can run it again from Settings.

| Step | What it's for |
|---|---|
| **Microphone and notifications** | Hearing "Hey Jarvis", and the status notification. |
| **Phone control** (accessibility service) | Reading the screen and tapping and typing for you. On Android 13+ you may see *"Restricted setting"*. Fix it in *Settings → Apps → Jarvis → ⋮ → Allow restricted settings*, then try again. |
| **Background running** | Stops Android from closing Jarvis. On Samsung, Xiaomi, Huawei and similar phones, also set Jarvis to *Unrestricted* in its battery settings. |
| **Downloads** (175 MB, use Wi-Fi) | Wake word (17 MB), speech recognition (57 MB) and the Kokoro voice (100 MB). |
| **A model** | Choose a provider, paste a key, and tap *Test connection*. |

<div align="center">
<img src="docs/images/onboarding.png" alt="Welcome" width="200" />&nbsp;
<img src="docs/images/model-edit.png" alt="Adding a model" width="200" />
</div>

## AI providers

| Provider | Default model | Notes |
|---|---|---|
| **Claude** (Anthropic) | `claude-opus-5-5` | Uses the official Anthropic Java SDK. The *Effort* setting trades speed for care; *Low* is noticeably faster for simple tasks. |
| **OpenAI** | `gpt-5` | Any model on your account that supports tool calling. |
| **LM Studio** | `local-model` | Turn on *Serve on local network* and use `http://<your-PC-IP>:1234/v1`. Pick a model that supports tool calling, such as Qwen 3 or Llama 3.1+. |
| **Ollama** | `qwen2.5:14b` | Run with `OLLAMA_HOST=0.0.0.0` and use `http://<your-PC-IP>:11434/v1`. |
| **OpenRouter, Gemini, Groq, Custom** | editable | Any OpenAI-compatible `/chat/completions` endpoint with tool calling. |

Small local models struggle with long, multi-step phone tasks. If results are unreliable, try a bigger model, or turn on screenshots with a vision model.

## Instant commands, memory and AI modes

Simple commands run without the AI model, so they are fast and work offline: "open YouTube", "go back", "scroll down", "volume up", "turn on the flashlight", "pause the music", "set a timer for 5 minutes", "what time is it", "search for pizza near me". Hinglish ("YouTube kholo", "awaaz kam karo") and Devanagari Hindi work too. Anything else goes to the AI as before.

- **AI mode** (Settings > Assistant): *Auto* uses the on-phone model for short simple questions and cloud models for hard, current-events or Hindi ones; *Online* prefers the cloud; *Offline* never leaves the phone. Say "offline mode" / "online mode" / "automatic mode" to switch.
- **Memory**: "when I say yt, open YouTube", "remember that I prefer short answers", "forget my wifi name", "what do you remember". Manage everything in Settings > Assistant > Memories. It stays on the phone.
- **On-phone model**: see [docs/INSTALL.md](docs/INSTALL.md) for the one-time Termux setup that lets Jarvis start and restart `llama-server`.
- Design notes and honest limits: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [docs/LIMITATIONS.md](docs/LIMITATIONS.md).

## How it works

```mermaid
flowchart LR
    A["Microphone"] --> B["Wake word<br/>(on device)"]
    B --> C["Speech to text<br/>(on device)"]
    C --> D{"Agent loop"}
    D -- "read screen" --> E["Accessibility<br/>service"]
    E -- "numbered list of<br/>on-screen elements" --> D
    D -- "screen + task" --> F["AI model<br/>(Claude, OpenAI, local…)"]
    F -- "next action:<br/>tap 12, type, scroll…" --> D
    D -- "perform action" --> E
    D -- "finished" --> G["Kokoro voice<br/>(on device)"]
```

1. **Listen.** One microphone stream feeds the wake word detector. After "Hey Jarvis", the same audio, including the moment just before the wake word, goes to the speech recognizer, so nothing you say is cut off.
2. **Look.** The accessibility service turns the current screen into a compact numbered list, like `[12] Button "Send"`.
3. **Think.** The model gets the task, the screen and a set of tools: `tap`, `type_text`, `scroll`, `swipe`, `open_app`, `press_key`, `look_at_screen`, `ask_user`, `finish` and more. It picks one action.
4. **Act.** Jarvis performs the action, reads the screen again and repeats until the model calls `finish`.
5. **Reply.** The result is spoken aloud and shown on the task island.

## Voice speed

Kokoro is a large model, and on many phones it makes speech only about as fast as it plays. Jarvis is built around that, so a reply sounds continuous instead of stopping and starting.

| | Before | Now |
|---|---|---|
| Wait before the second sentence of a streamed answer | 2.7 s | 0.2 s |
| First sound, for a reply that opens with a long sentence | 9.2 s | 2.0 s |
| Total silence inside that reply | 5.4 s | 2.1 s |

Measured with the real Kokoro model (int8, 4 threads) on a desktop CPU where it makes speech about as fast as it plays, which is roughly a phone that only just keeps up. Times on a phone will differ.

How it gets there:

- **The next sentence is made while this one plays.** Speech is made on one thread and played on another. For a streamed answer, Jarvis hands Kokoro the sentence that comes next, so it is ready when the current one ends.
- **Speech starts after the first clause.** A reply is cut into pieces. The first is at most 40 characters, cut at a comma where there is one, so the first sound doesn't wait for a whole sentence.
- **Pieces fit the phone.** Kokoro times itself as it speaks. A piece is only as long as can be made before the speech already waiting runs out. On a fast phone pieces grow to 150 characters. On a phone that makes speech slower than it plays they stay at 60 or fewer, so the pauses that can't be avoided are short and mostly fall at commas and full stops.
- **Lighter on modest phones.** The model runs on the phone's fast cores only (2 to 4 threads). On phones with 4 GB of memory or less it makes shorter pieces, which need less memory.

After a voice preview, Settings tells you if Kokoro takes longer to make speech than to play it on your phone. If it does, a cloud voice or the phone's own voice won't pause.

## Building from source

You need **JDK 17+** and the **Android SDK** (API 35). Android Studio provides both.

```bash
git clone https://github.com/judejulius/jarvis.git
cd jarvis
./gradlew assembleDebug        # the first build downloads sherpa-onnx (50 MB)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Run the tests:

```bash
./gradlew testDebugUnitTest    # UI flows, crash safety, layout checks; screenshots go to app/build/screenshots
./gradlew lintDebug
```

You can also run the voice pipeline against the real speech engine and models on your computer. See [CONTRIBUTING.md](CONTRIBUTING.md#real-engine-tests).

## Project structure

```
app/src/main/java/com/jarvis/assistant/
├── agent/
│   ├── PhoneAgent.kt              # the agent loop, system prompt and tools
│   ├── Llm.kt                     # ChatSession interface and connection test
│   ├── AnthropicSession.kt        # Claude via the official SDK
│   └── OpenAiCompatibleSession.kt # OpenAI, LM Studio, Ollama, OpenRouter, Gemini, Groq
├── accessibility/
│   ├── JarvisAccessibilityService.kt  # taps, typing, gestures, screenshots
│   └── ScreenReader.kt                # screen → numbered element list
├── voice/
│   ├── VoiceInput.kt         # microphone, wake word and offline speech recognition
│   ├── MicGain.kt            # lifts a normal or quiet voice to the level the models expect
│   ├── Kokoro.kt             # on-device neural voice
│   ├── SpeechChunks.kt       # cuts text into short pieces so speech starts sooner
│   ├── SpeechPacer.kt        # sizes each piece to how fast this phone makes speech
│   ├── Cpu.kt                # how many threads the voice model uses (the phone's fast cores)
│   ├── CloudVoice.kt         # OpenAI, ElevenLabs, Fish Audio and OpenAI-compatible voices, streamed
│   ├── AudioOut.kt           # shared speaker output, interruption and loudness for the island
│   ├── Voice.kt, SystemVoice.kt
│   ├── CommandListener.kt    # Google speech recognizer fallback
│   └── ModelDownloader.kt    # downloads and safely unpacks the models
├── service/
│   ├── JarvisService.kt      # foreground service: wake → listen → act → reply
│   └── BootReceiver.kt
├── ui/                       # Home, Models, Settings, onboarding, task island
├── Settings.kt               # preferences, providers and model profiles
└── CrashReporter.kt
```

## Privacy and safety

- **What stays on your phone:** the microphone audio, the wake word, speech recognition and the voice.
- **What gets sent:** while a task is running, the text on your screen (and screenshots, if you turn them on) goes to the model provider you chose. If you pick a cloud voice, the sentences Jarvis speaks go to that voice provider. For full privacy, use LM Studio or Ollama and the Kokoro voice.
- **API keys** are stored in the app's private storage and only sent to the provider they belong to.
- **Safety rules.** Jarvis is told to ask before payments, purchases, deletions or security changes, to ask when a contact is ambiguous, and to treat text on screen as content, not instructions. These rules live in the prompt, and **models can still make mistakes**. Watch what it does until you trust it. You can stop a task at any time from the task island or the notification.

Found a security problem? Please see [SECURITY.md](SECURITY.md).

## Limitations

- **Android only.** iOS doesn't allow one app to control others.
- **The phone must be unlocked** for Jarvis to act. Android doesn't allow accessibility actions behind the lock screen.
- **Wake word accuracy** was measured with synthetic speech (18/18 detected, 0/60 false alarms on sound-alike phrases). Real rooms are noisier. The mic button and the notification's *Talk* button always work.
- **Android 14+** may block a microphone service from starting at boot. If so, open the app once.
- **Interrupting** works best on phones with good echo cancellation (most recent phones). With the speaker very loud, "stop" may occasionally need repeating; tapping the island always works.
- **Battery:** listening for the wake word uses a little power continuously.

## Roadmap

Ideas we'd love help with:

- [ ] More languages for the wake word, speech recognition and voice
- [ ] Custom wake words
- [ ] 32-bit ARM and x86_64 builds
- [ ] Task history with replay of each step
- [ ] Per-app tips that help the model (e.g. "In WhatsApp, the search icon is…")
- [ ] Translations of the app interface

## Contributing

Contributions are welcome: bug reports, testing on more phones, new providers, translations and code. See **[CONTRIBUTING.md](CONTRIBUTING.md)** for how to build, test and send a pull request. Everyone taking part is expected to follow the [Code of Conduct](CODE_OF_CONDUCT.md).

## Acknowledgements

Jarvis is built on these open-source projects and models. Each keeps its own licence.

| Project | Used for | Licence |
|---|---|---|
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | On-device wake word, speech recognition and text-to-speech engine | Apache-2.0 |
| [Kokoro-82M](https://huggingface.co/hexgrad/Kokoro-82M) | The voice | Apache-2.0 |
| [Zipformer keyword spotter](https://k2-fsa.github.io/sherpa/onnx/kws/index.html) (gigaspeech 3.3M) | "Hey Jarvis" detection | See model page |
| [Kroko streaming Zipformer](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models) | Speech recognition | See model page |
| [Anthropic Java SDK](https://github.com/anthropics/anthropic-sdk-java) | Claude API | MIT |
| [OkHttp](https://github.com/square/okhttp) | OpenAI-compatible APIs | Apache-2.0 |
| [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/) | Unpacking model downloads | Apache-2.0 |
| [Material Components](https://github.com/material-components/material-components-android) | UI | Apache-2.0 |

The voice and speech models aren't included in this repository. The app downloads them from the sherpa-onnx releases on first use.

*Jarvis is an independent project. It is not affiliated with or endorsed by Marvel, Disney, Anthropic, OpenAI or Google.*

## License

[MIT](LICENSE). Use it, change it and share it.

<div align="center">
<br />
If Jarvis is useful to you, please consider giving it a star.
</div>
