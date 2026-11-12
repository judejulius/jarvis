# Limitations (read this before trusting a claim)

**Android**
- No app can be guaranteed to run forever in the background. Jarvis uses a foreground service and checks the microphone and the model server before use, and recovers when it can. Battery optimisation, the Realme process manager or low memory can still stop things; the settings in INSTALL.md reduce this, they do not remove it.
- Android 10+ blocks apps from switching Wi-Fi, Bluetooth or airplane mode directly; Jarvis opens the right settings page and says so.
- Calls are never placed automatically by voice shortcuts: the dialer opens with the number and you tap call. Phone-control "Send / Call / Pay" taps are blocked in code until you say yes.
- Alarms and timers are handed to your clock app; Android gives no confirmation, so Jarvis says "I've asked your clock app".
- "Hey Jarvis" with the screen off works only while the microphone service is allowed to keep running; some phones silence it. Jarvis detects a silenced microphone and reopens it, but whether it keeps hearing you on your Realme with the screen off is a device question.

**Local model (Termux)**
- Starting `llama-server` from the app needs the Termux "run commands" permission and `allow-external-apps`. Whether the **Google Play build of Termux** (yours: `googleplay.2026.10.09`) accepts these external commands on Android 16 could not be checked here. If it does not, the Termux:Boot script and `start-llama.sh` still work; the app then reports "the local model didn't start" and uses a cloud model if the internet works.
- Qwen2.5-1.5B on this phone measured about 14–23 prompt tokens/s and 13–16 generated tokens/s (your evidence). A phone-control prompt is long, so phone control by the local model is slow and less reliable than the instant commands or a cloud model. Automatic mode therefore sends phone-control tasks to the cloud when the internet works.
- A 1.5B model answered a Hinglish chat prompt with nonsense in your test. Automatic mode sends Hindi/Hinglish chat to the cloud.

**Speech**
- Offline recognition (Kroko) and offline voice (Kokoro v0_19) are English-only. Hindi/Hinglish commands use Google's recognizer (needs the Google app, may use the internet). Replies are spoken in English. Offline Hindi would need a new recognizer and voice model; none was added because none could be tested here.
- "Hey Jarvis" is detected offline; in Hindi mode the command that follows is recognised by Google, and follow-up listening without the wake word is turned off.

**Not implemented**
- Streaming the model's answer into speech sentence by sentence (Kokoro already speaks a finished answer sentence by sentence).
- Calling or messaging a contact by name without the model (needs READ_CONTACTS); those requests go to the phone agent, with the confirmation gate.
- Searching inside other apps without the model; those go to the phone agent.
- Saving whole conversations or searching old chats (by design only explicit notes and taught app names are stored).
- openWakeWord, JNI-embedded llama.cpp, other model sizes: evaluated on paper only (see ARCHITECTURE.md); no measurement supports a change.
- `type_text` with "press enter" can still submit a form; the confirmation gate covers tapping Send/Call/Pay-style buttons.

---

# Second round: honest limits

**On-device runtime**
- `llama-server` is compiled for Android only in GitHub Actions. That cross-compile, the CPU flags (`armv8.2-a+dotprod+fp16`, chosen for your chip's cores), and running the binary from the app's library folder on Android 16 / Realme UI are **not verified**. If the build step fails the APK still builds without it.
- The two model download addresses are public Hugging Face files (`Qwen/Qwen2.5-1.5B-Instruct-GGUF` and a bartowski mirror). Their reachability and exact size could not be checked from here; the downloader reads size and SHA-256 from the server and verifies the file.
- Android can still kill a background app, and Android 12+ may stop child processes under memory pressure (the "phantom process" limit). The supervisor restarts the model on next use; it cannot prevent a kill.
- About 1 GB of RAM while loaded. It stops after 20 idle minutes and when Android asks apps to trim memory.

**Wake word**
- No claim of reliability is made. The change is a hypothesis-driven fix (see ARCHITECTURE). Screen-off behaviour depends on the phone's background-microphone policy. Detection rates exist for synthetic speech only (`RealEngineTestTuning`: Kokoro voices at several volumes over added hiss); there are none for a real voice on a phone. In a noisy room a quiet voice is still missed: gain cannot separate speech from noise that is as loud as it is.

**Calls and contacts**
- Names are matched in Latin and Devanagari scripts; nicknames you use that are not in the contact list are not guessed. A call is never placed on a guess (weak matches are always confirmed). Without the call permission Jarvis opens the dialer instead. "Call connected" is claimed only if the phone-state permission lets it see the call.
- Calls through WhatsApp or other apps are not supported by this fast path.

**Tasks**
- Only spoken-answer tasks can run in the background. A task that drives the screen cannot, because two tasks cannot share one screen; a newer screen task replaces it. A download that was already started in a browser or the system downloader keeps going by itself.
- Cancel stops the remaining steps. It never claims that a sent message or placed call was undone.
- If the agent asks you a question and you answer with a new command instead, the new command is read as the answer.

**YouTube**
- The first real video result is chosen from the way YouTube describes results to accessibility. If YouTube changes that, nothing is tapped and Jarvis says so. Ads, sign-in prompts and consent screens can delay or block playback; "Playing ..." is said only when sound actually starts.

**Memory**
- Notes, taught app names, "I mean" corrections and the last 100 task lines are stored locally. Whole conversations are **not** persisted across restarts (only the last three exchanges are kept in memory while the service runs). Cloud models get only answer-style notes unless "Let cloud AI see my memories" is on. Secrets (passwords, one-time codes, card/ID numbers) are never stored.

**Speech**
- Offline recognition and voice are still English only. For unclear speech Jarvis uses Google's alternative transcriptions (Hindi mode), your "when I say X, I mean Y" corrections, sound-alike app confirmation and fuzzy app and contact matching; the offline recognizer gives one guess only.
