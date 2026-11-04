#!/data/data/com.termux/files/usr/bin/bash
# Starts the local model server if it is not already running. Safe to run any number of times: it does nothing when
# the server answers or a supervisor is already starting it. Called by the Jarvis app (through Termux) and at boot.
JARVIS_DIR="${JARVIS_DIR:-$HOME/.jarvis}"
# shellcheck disable=SC1091
[ -f "$JARVIS_DIR/llama.conf" ] && . "$JARVIS_DIR/llama.conf"
: "${HOST:=127.0.0.1}" "${PORT:=8080}"
SESSION=jarvis-ai

# 1. Already answering? Done.
if curl -sf --max-time 1 "http://$HOST:$PORT/health" >/dev/null 2>&1; then exit 0; fi

# 2. A supervisor is already running (the model is loading, or it is restarting after a crash)? Leave it alone.
if tmux has-session -t "$SESSION" 2>/dev/null && pgrep -f "llama-supervisor.sh" >/dev/null 2>&1; then exit 0; fi

# 3. Leftover session without a supervisor: clear it, then start fresh.
tmux kill-session -t "$SESSION" 2>/dev/null
command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock
tmux new-session -d -s "$SESSION" "bash '$JARVIS_DIR/llama-supervisor.sh'"
