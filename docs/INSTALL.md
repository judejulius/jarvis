# Install and set up

## 1. Apply the changes to your repository (from Termux)

The delivery ZIP contains the full project (`project/`), one patch per file and one combined patch (`patches/`).
Your repository was clean at commit `9ed600d` when the evidence was taken, so the combined patch applies directly.

```bash
cd ~/jarvis                      # your repository
git status --short               # must print nothing; if it prints changes, commit or stash them first
git checkout -b jarvis-upgrade   # optional: keep master untouched until you have tested the APK

unzip -o ~/storage/shared/JARVIS-IMPLEMENTED-complete.zip 'JARVIS-IMPLEMENTED/patches/all-changes.patch' -d /tmp/jv
git apply --check /tmp/jv/JARVIS-IMPLEMENTED/patches/all-changes.patch   # prints nothing if it fits
git apply /tmp/jv/JARVIS-IMPLEMENTED/patches/all-changes.patch
git diff --check                 # whitespace problems (should print nothing)
git status --short               # review the list
git add -A && git commit -m "Jarvis: instant commands, AI modes, local server supervision, memory"
git push origin jarvis-upgrade   # or master, when you are ready
```

If `git apply --check` complains, do not force it. Copy the files from `project/` instead
(`cp -a /tmp/jv/JARVIS-IMPLEMENTED/project/. ~/jarvis/` after unzipping that folder), then run `chmod +x termux/*.sh` (copying can drop the executable bit; the patch keeps it) and review with `git diff`.

## 2. Get the APK

GitHub > Actions > the latest **CI** run > Artifacts:

- `jarvis-debug-apk` – the app. It is uploaded **before** the tests run, so you get an APK whenever the code compiles.
- `test-and-lint-reports` – unit-test and lint reports. If the run is red, open this and send me the failing test names.

Install the APK as usual. Do not treat it as confirmed working until you have tried it on the phone.

## 3. One-time Termux setup (local model)

Needs the Termux packages `llama-cpp`, `tmux`, `curl` (you already have them) and the Termux:Boot app (opened once).

```bash
cd /tmp/jv && unzip -o ~/storage/shared/JARVIS-IMPLEMENTED-complete.zip 'JARVIS-IMPLEMENTED/termux/*'
bash /tmp/jv/JARVIS-IMPLEMENTED/termux/install-termux.sh
```

The installer copies three scripts to `~/.jarvis/`, writes `~/.jarvis/llama.conf` (model path, port, context 4096,
one slot, 512 MiB prompt cache), sets `allow-external-apps = true` in `~/.termux/termux.properties`, replaces
`~/.termux/boot/start-jarvis` (the old one is kept as `.bak-<time>`), and starts the server. It never deletes anything.

Then, on the phone (Android makes you do these yourself):

1. Settings > Apps > Jarvis > Permissions > Additional permissions: allow **Run commands in Termux environment**.
2. Settings > Battery: set **Termux** and **Jarvis** to *Unrestricted*; on Realme also allow auto-launch / background activity for both, and lock both in the recent-apps list.
3. In Jarvis > Models: your Qwen profile's base URL should be `http://127.0.0.1:8080/v1` (or `localhost`). The model name can be anything.
4. In Jarvis > Settings > Assistant: the status line should read "On-phone model: running".

Check the server at any time with `bash ~/.jarvis/status.sh`. Tune it in `~/.jarvis/llama.conf` (then stop the old session with
`tmux kill-session -t jarvis-ai` and run `bash ~/.jarvis/start-llama.sh`).

## 4. Try it

"Hey Jarvis, open YouTube" · "scroll down" · "volume up" · "turn on the flashlight" · "set a timer for 5 minutes" ·
"offline mode" · "when I say yt, open YouTube" · "remember that I prefer short answers" · "what do you remember".

## 5. If something misbehaves

Useful log tags (adb logcat or the crash-report share): `JarvisLatency` (stage timings), `JarvisTiming` (model requests,
warm-up), `LocalServer` (start requests), `FastAction`, `VoiceInput`, `PhoneAgent`. They contain stage names and
milliseconds, not what you said.
