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
