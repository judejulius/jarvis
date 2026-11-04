#!/data/data/com.termux/files/usr/bin/bash
# Shows whether the local model server is healthy, and the end of its log.
JARVIS_DIR="${JARVIS_DIR:-$HOME/.jarvis}"
# shellcheck disable=SC1091
[ -f "$JARVIS_DIR/llama.conf" ] && . "$JARVIS_DIR/llama.conf"
: "${HOST:=127.0.0.1}" "${PORT:=8080}"
echo "health : $(curl -s --max-time 2 "http://$HOST:$PORT/health" || echo 'not answering')"
echo "tmux   : $(tmux ls 2>&1 | head -3)"
echo "procs  : $(pgrep -af 'llama-server|llama-supervisor' | cut -c1-120)"
echo "--- last log lines ---"
tail -n 15 "$JARVIS_DIR/llama.log" 2>/dev/null || echo "(no log yet)"
