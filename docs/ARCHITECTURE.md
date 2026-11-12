# Architecture

New code lives in four packages; the rest of the app keeps its structure.

| Package | What it does |
|---|---|
| `router/` | `Lexicon`, `FastCommandRouter`, `AppMatcher`, `MemoryIntentParser` (pure Kotlin, unit-tested) and `FastActionExecutor` (Android APIs) |
| `ai/` | `AiMode`, `RoutePolicy` + `CircuitBreaker` + `StartThrottle` (pure), `AiRouter`, `LocalServerManager`, `ServerHealth`, `LocalWarmup`, `ModelGate` |
| `memory/` | `MemoryStore` (SQLite), `MemoryPrompt` (pure), `MemoryActivity` |
| `agent/` | `SensitiveActions` (confirmation gate); `PhoneAgent` now takes a `ModelGate` |

## 1. Voice pipeline
```mermaid
flowchart LR
  MIC[AudioRecord 16 kHz] --> KWS[Sherpa keyword spotter: Hey Jarvis]
  KWS -->|wake| TR[LatencyTrace begins + local model warm-up]
  TR --> LANG{Speech language}
  LANG -->|English| ASR[Sherpa Kroko streaming ASR]
  LANG -->|Hindi/Hinglish| GOO[Google SpeechRecognizer hi-IN]
  ASR --> EARLY[early accept: short complete command after 0.6-0.8 s of quiet]
  EARLY --> ROUTE
  GOO --> ROUTE
  ROUTE{runTask} -->|teach / remember / forget| MEM[MemoryStore]
  ROUTE -->|simple command| FAST[FastActionExecutor + verification]
  ROUTE -->|anything else| AI[AiRouter: chat or PhoneAgent]
  MEM --> TTS
  FAST --> TTS
  AI --> TTS[Kokoro / cloud voice / system voice]
  TTS --> BACK[afterReply: follow-up or wake word]
```

## 2. AI routing
```mermaid
flowchart TD
  T[Request that needs a model] --> M{AI mode}
  M -->|Offline| L[Local profiles only]
  M -->|Online| O{Internet?}
  O -->|yes| C1[Cloud, then local]
  O -->|no| L
  M -->|Auto| A{Internet?}
  A -->|no| L
  A -->|yes| K{Kind}
  K -->|phone control| C1
  K -->|chat| S{Short, simple, English?}
  S -->|yes| L2[Local, then cloud]
  S -->|no: hard, current, Hindi| C1
  L --> B[Tripped providers moved to the back]
  C1 --> B
  L2 --> B
  B --> R[Try each: local ready? start via Termux, else skip. Report success/failure to the breaker]
```

## 3. Phone-control execution
```mermaid
sequenceDiagram
  participant U as User
  participant S as JarvisService
  participant F as FastActionExecutor
  participant P as PhoneAgent
  participant A as AccessibilityService
  U->>S: "open YouTube"
  S->>F: parsed FastCommand (no model)
  F->>A: openPackage, waitForForeground(3 s)
  F-->>S: "Opened YouTube." or "couldn't confirm"
  U->>S: "text Mum I'm on my way"
  S->>P: not a fast command -> agent (cloud first when online)
  P->>A: type_text, then tap Send
  A-->>P: label "Send" -> BLOCKED until the user says yes
  P->>U: ask_user "Send it to Mum?"
  U-->>P: "yes"
  P->>A: tap Send (gate resets for the next send)
```

## 4. Microphone state machine
```mermaid
stateDiagram-v2
  [*] --> Idle
  Idle --> ListeningCommand: wake word / Talk button
  ListeningCommand --> Working: command heard
  ListeningCommand --> Idle: silence
  Working --> Speaking: result ready
  Speaking --> ListeningCommand: follow-up window (English mode)
  Speaking --> Idle: done
  state Idle {
    [*] --> MicOpen
    MicOpen --> Reopen: read error / dead object / 5 s of exact zeros
    Reopen --> MicOpen: pause 0.5 s, 1 s ... max 5 s
    Reopen --> MicOpenNoSilenceCheck: blocked 3 times in a row
  }
```

## 5. Local Qwen server lifecycle
```mermaid
stateDiagram-v2
  [*] --> Unknown
  Unknown --> Healthy: GET /health 200
  Unknown --> Down: refused / timeout
  Down --> Starting: Termux RUN_COMMAND start-llama.sh (throttled: 1 per 20 s, 4 per 10 min)
  Starting --> Healthy: model loaded (about 3 s when cached)
  Starting --> Down: not up in 30 s (only waits if no other model can answer)
  Healthy --> Down: crash or hang
  note right of Down: Termux supervisor restarts it, watchdog kills a hung one, boot script starts it after reboot
```

## 6. Memory and learning
```mermaid
flowchart LR
  SAY[User sentence] --> P{MemoryIntentParser: explicit pattern?}
  P -->|no| X[nothing stored]
  P -->|yes| V{valid and unambiguous?}
  V -->|no, e.g. app not found or two matches| Q[spoken reply, nothing saved]
  V -->|yes| DB[(SQLite: alias, fact, FTS4)]
  FAIL[open X failed] --> RETRY[user repeats with the right name within 20 s] --> SIM{names sound alike?}
  SIM -->|yes| DB
  DB --> CTX[promptContext]
  CTX -->|style notes| ALL[any model]
  CTX -->|other notes| LOC[local model, or cloud only if you allow it]
```

## Design decisions

**Local server: which approach?** (1) Termux + tmux + Boot alone restarts only at boot and cannot see a hung server (that is the script you had). (2) App-side supervision alone cannot start a process by itself. (3) Start-on-demand alone makes the first question wait. (4) Embedding llama.cpp via JNI would remove Termux but means shipping and maintaining native builds, more RAM inside the app process, and your existing Termux model/flags would be unused; nothing in your evidence says Termux is the problem, only that nothing restarts it. **Chosen: 1 + 2 + 3 together.** The Termux supervisor restarts crashes and hangs; the app checks health before every use, asks Termux to start it when down (throttled, never twice), warms it on "Hey Jarvis", and falls back to the cloud or reports why.

**Server flags** (from your server log: `n_parallel auto = 4` slots, prompt-cache limit 8192 MiB on a 7.4 GiB phone, `-c 2048`): one slot, 512 MiB cache, context 4096. Context 4096 costs about 117 MB of KV cache for this model (28 layers x 2 KV heads x 128 dims x 2 x 2 bytes = about 28 KB/token); prefill speed, not memory, is the real limit, so the prompt was also made smaller and cacheable instead of just making the window bigger.

**Why not a bigger/other model, openWakeWord, other ASR:** no measurement here shows a benefit. The measured weak points are Hindi quality (needs a different model family, not a bigger Qwen) and prefill speed (prompt size and caching).

---

# Second round: what was added

| Package | What it does |
|---|---|
| `runtime/` | `GgufDownloader` (resume, storage check, verification), `RuntimeConfig` + `RuntimePolicy` (command line, restart rules), `EmbeddedRuntime` (child process supervisor) |
| `contacts/` | `ContactMatcher` + `Translit` (pure), `ContactResolver`, `PermissionActivity` |
| `tasks/` | `TaskBoard`, `Interrupt`, `TaskControl` (pure); the service owns the threads |
| `apps/` | `AppAction` framework and `YouTubeAction` |
| `persona/` | `Persona`, `DailyGreeting` |
| `voice/` | `SentenceChunker`, `ReplyStreamer`; adaptive microphone mode in `VoiceInput` |
| `agent/` | `SseParser`; streaming in `OpenAiCompatibleSession`; `SensitiveActions` |

## On-device runtime lifecycle
```mermaid
stateDiagram-v2
  [*] --> NoBinary: APK built without llama-server
  [*] --> NoModel: binary present, model missing
  NoModel --> Downloading: Wi-Fi (or user asked) and storage ok
  Downloading --> NoModel: cancelled / failed (resumes later)
  Downloading --> Stopped: size, GGUF header and SHA-256 verified
  Stopped --> Starting: wake word, or a request needs it
  Starting --> Running: /health ok (API key set, 127.0.0.1 only)
  Starting --> Failed: exits while loading
  Running --> Starting: crash (backoff 2 s ... 60 s) or hung (3 failed checks)
  Running --> Stopped: idle 20 min, or Android reports low memory
  Starting --> Failed: 5 crashes in a row
```

## Tasks and interruption
```mermaid
flowchart TD
  CMD[New command heard] --> CTRL{Task-control phrase?}
  CTRL -->|cancel / stop that| CAN[cancel foreground, say honestly what could not be undone] --> THEN{followed by a command?}
  THEN -->|yes| NEWT
  CTRL -->|put that in the background| BG{foreground is a chat answer?}
  BG -->|yes| BGOK[answer continues; announced when done]
  BG -->|no, uses the screen| BGNO[say so; a new command replaces it]
  CTRL -->|what are you doing| ST[describe running tasks]
  CTRL -->|no| DUP{same words running or started <1.2 s ago?}
  DUP -->|yes| IGN[ignore: heard twice]
  DUP -->|no| NEWT[classify: INSTANT / CHAT / PHONE]
  NEWT -->|INSTANT| ALONG[run alongside, no cancel]
  NEWT -->|CHAT or PHONE| REPL[replace foreground task] --> UI{PHONE?}
  UI -->|yes| LOCK[wait up to 5 s for the screen lock] --> RUN
  UI -->|no| RUN[run on its own thread]
```
A wake word while a task runs only opens the microphone: the task is replaced when a *command* is heard, not by a stray detection. A reply that arrives while the user is speaking is held, then spoken or dropped if a new command follows.

## Streamed speech
`OpenAiCompatibleSession` streams plain chat (`stream: true`); `SseParser` reads each line; `SentenceChunker` forms whole sentences (at least 24 characters, cut at `. ! ? ।`, not at decimals or "Dr."); `ReplyStreamer` speaks them one after another through the normal `say()`; the text of each sentence is shown as it is spoken. While one sentence is spoken, the streamer hands the next to `Speaker.prepare()`, and Kokoro makes it meanwhile, so it starts without a silence. Replies that use tools (phone control) are not streamed.

## Wake word: what the code audit found
- The keyword tokens in `VoiceInput` (`▁HE Y ▁JA R VI S`) were checked against the model's real `bpe.model`: they are the correct tokenization. No change needed there.
- The microphone was always opened with the *voice call* source and echo cancellation (the setting "interrupt by talking" is on by default). That path applies call-quality noise suppression and gain control to the signal, which commonly weakens quiet or distant speech. It is now used only while Jarvis speaks; idle listening uses the plain recognition source. This is a root-cause hypothesis from the code, **not measured** on your phone.
- The plain recognition source has no gain control, so a normal voice arrives far quieter than a shout, and the app's own gain was too slow and too small to lift a short "Hey Jarvis". `MicGain` now rests at the highest gain the room's noise allows (up to 32 times) and drops at once for loud sound. Measured with the real wake word model on synthetic speech (`RealEngineTestTuning`): 17/18 instead of 11/18 phrases heard at 1/40 volume in a quiet room, no false alarms. **Not measured** on a phone.
- Recovery from read errors, blocked microphone and thread overlap was added in the first round.
