#!/data/data/com.termux/files/usr/bin/bash
# ==============================================================================
# JARVIS Local Qwen Supervisor Script for Termux
# Starts llama-server in a managed tmux session, preventing duplicate instances.
# ==============================================================================

MODEL_PATH="$HOME/jarvis/models/Qwen2.5-1.5B-Instruct-Q4_K_M.gguf"
PORT=8080
HOST="127.0.0.1"
SESSION_NAME="jarvis-ai"

# 1. Check if model exists
if [ ! -f "$MODEL_PATH" ]; then
    echo "[!] Model file not found at: $MODEL_PATH"
    exit 1
fi

# 2. Check if llama-server is already running
if pgrep -f "llama-server" >/dev/null; then
    echo "[✓] llama-server is already running."
    exit 0
fi

# 3. Kill stale tmux session if it exists
tmux has-session -t "$SESSION_NAME" 2>/dev/null && tmux kill-session -t "$SESSION_NAME"

# 4. Start llama-server inside detached tmux session
echo "[*] Starting llama-server for Qwen2.5-1.5B on $HOST:$PORT..."
tmux new-session -d -s "$SESSION_NAME" "llama-server -m '$MODEL_PATH' --host $HOST --port $PORT -c 2048 -t 4 --n-predict 256"

# 5. Wait briefly and verify health
sleep 2
if curl -s "http://$HOST:$PORT/health" >/dev/null 2>&1 || curl -s "http://$HOST:$PORT/v1/models" >/dev/null 2>&1; then
    echo "[✓] Qwen server is healthy and responding on http://$HOST:$PORT"
else
    echo "[*] Server launched. Initializing weights (attach via: tmux attach -t $SESSION_NAME)"
fi
