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
