#!/data/data/com.termux/files/usr/bin/bash
# One-time setup, run inside Termux:   bash install-termux.sh
# - installs the start/supervisor scripts into ~/.jarvis
# - lets the Jarvis app start them (Termux "allow-external-apps")
# - makes Termux:Boot start the server after a reboot
# Existing files are backed up with a .bak-<time> suffix before they are changed. Nothing is deleted.
set -u
SRC="$(cd "$(dirname "$0")" && pwd)"
DEST="$HOME/.jarvis"
STAMP="$(date +%Y%m%d-%H%M%S)"

backup() { [ -e "$1" ] && cp -p "$1" "$1.bak-$STAMP" && echo "  backed up $1"; }

mkdir -p "$DEST" "$HOME/.termux/boot"

echo "1. Scripts -> $DEST"
for f in llama-supervisor.sh start-llama.sh status.sh; do
  backup "$DEST/$f"
  cp "$SRC/$f" "$DEST/$f" && chmod 700 "$DEST/$f"
done

echo "2. Settings -> $DEST/llama.conf"
if [ -f "$DEST/llama.conf" ]; then
  echo "  kept your existing llama.conf"
else
  cat >"$DEST/llama.conf" <<CONF
# Local model server settings. Edit, then run: bash ~/.jarvis/start-llama.sh (after stopping the old session).
MODEL="\$HOME/jarvis/models/Qwen2.5-1.5B-Instruct-Q4_K_M.gguf"
HOST=127.0.0.1
PORT=8080
CTX=4096        # prompt + answer budget. The phone-control prompt is long; 2048 was too small for it.
THREADS=4
PARALLEL=1      # one conversation at a time: less memory, and the prompt cache stays warm
CACHE_RAM=512   # MiB of prompt cache (llama.cpp's default is far larger than this phone's free RAM)
EXTRA_ARGS=""
CONF
  echo "  wrote defaults (model path: ~/jarvis/models/Qwen2.5-1.5B-Instruct-Q4_K_M.gguf)"
fi

echo "3. Let the Jarvis app start the server (allow-external-apps)"
PROPS="$HOME/.termux/termux.properties"
if [ -f "$PROPS" ] && grep -Eq '^[[:space:]]*allow-external-apps[[:space:]]*=[[:space:]]*true' "$PROPS"; then
  echo "  already enabled"
else
  backup "$PROPS"
  if [ -f "$PROPS" ] && grep -Eq '^[[:space:]]*#?[[:space:]]*allow-external-apps' "$PROPS"; then
    sed -i -E 's/^[[:space:]]*#?[[:space:]]*allow-external-apps.*/allow-external-apps = true/' "$PROPS"
  else
    printf '\nallow-external-apps = true\n' >>"$PROPS"
  fi
  command -v termux-reload-settings >/dev/null 2>&1 && termux-reload-settings
  echo "  enabled"
fi

echo "4. Start after reboot (needs the Termux:Boot app, opened once)"
BOOT="$HOME/.termux/boot/start-jarvis"
backup "$BOOT"
cat >"$BOOT" <<BOOTSCRIPT
#!/data/data/com.termux/files/usr/bin/bash
termux-wake-lock
bash "\$HOME/.jarvis/start-llama.sh"
BOOTSCRIPT
chmod 700 "$BOOT"

echo "5. Starting the server now"
bash "$DEST/start-llama.sh"
sleep 8
bash "$DEST/status.sh"

cat <<'NEXT'

Done. Two things Android asks you to do yourself:
  a) Settings > Apps > Jarvis > Permissions > Additional permissions: allow "Run commands in Termux environment".
  b) Settings > Battery: set both Termux and Jarvis to "Unrestricted" (and allow auto-launch / background activity on Realme).
Check at any time with:  bash ~/.jarvis/status.sh
NEXT
