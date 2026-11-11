# Install and set up

## 1. Apply the changes to your repository (from Termux)

The delivery has two patch sets. Check which one fits your repository:

```bash
cd ~/jarvis
git log --oneline -n 5 | cat
```

- If the log shows **"Jarvis: instant commands, AI modes, local server supervision, memory"** (the previous delivery), use the **series** in `patches/series/` (one commit per phase, applied with `git am`).
- If your history ends at `9ed600d` (the original code), use `patches/cumulative-from-original.patch` (everything in one go).

```bash
git status --short                 # must print nothing
git checkout -b jarvis-v2          # keep master untouched until the APK is tested
unzip -o ~/storage/shared/JARVIS-IMPLEMENTED-v2-complete.zip 'JARVIS-IMPLEMENTED-v2/patches/*' -d /tmp/jv

# A) previous delivery already applied:
git am /tmp/jv/JARVIS-IMPLEMENTED-v2/patches/series/*.patch
# B) original code only:
git apply --check /tmp/jv/JARVIS-IMPLEMENTED-v2/patches/cumulative-from-original.patch && \
git apply /tmp/jv/JARVIS-IMPLEMENTED-v2/patches/cumulative-from-original.patch

git diff --check                   # prints nothing when clean
git push origin jarvis-v2          # or master when you are ready
```

If `git am` stops, run `git am --abort` and use the full `project/` folder instead
(`cp -a` it over the repository, then `chmod +x termux/*.sh scripts/*.sh`) and review with `git diff`.

## 2. Get the APK

GitHub > Actions > the latest **CI** run:

1. The first run **builds `llama-server` for arm64 from source** (about 10 to 25 minutes) and caches it; later runs reuse it.
   If that step fails the run continues and the APK is built **without** the on-device runtime (Termux or cloud models are used).
2. Artifacts: `jarvis-debug-apk` (uploaded before the tests run), `test-and-lint-reports`.

## 3. First start on the phone

1. Open Jarvis, allow microphone, notifications and phone control (Accessibility) as before.
2. **On-device model**: on Wi-Fi it downloads by itself (about 1 GB; Settings > Assistant shows progress; on mobile data it waits unless you allow it or say "download the offline model"). Needs about 1.2 GB free storage.
3. **Calls**: Settings > Assistant > *Allow contacts and calling* (contacts, calling, and phone state so Jarvis can confirm that a call started).
4. Battery: set Jarvis to *Unrestricted*; on Realme also allow auto-launch / background activity and lock the app in recents.

## 4. Termux (optional now)

The built-in runtime replaces the Termux server. If you still want the Termux server as a fallback, run
`bash termux/install-termux.sh` once, as described in the previous release notes (kept in `termux/`).

## 5. Try it

"Hey Jarvis" · "open YouTube and play Despacito" · "call Rahul" · "put that in the background and open WhatsApp" ·
"stop that, open Chrome" · "what are you doing" · "when I say you too, I mean youtube" · "remember that I prefer short answers" ·
"offline mode" · "is the local model running".

## 6. Logs for me if something misbehaves

Tags: `JarvisLatency` (stage timings incl. first speech), `JarvisTiming` (model requests, stream first-token time), `EmbeddedRuntime`,
`LocalServer`, `FastAction`, `VoiceInput`, `PhoneAgent`. The runtime's own log is `llama.log` in the app's files folder.
They contain stage names and timings, not what you said.
