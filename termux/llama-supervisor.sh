#!/data/data/com.termux/files/usr/bin/bash
# Keeps llama-server running: restarts it when it exits or stops answering, with a growing pause between quick crashes.
# Started inside tmux by start-llama.sh. Settings come from ~/.jarvis/llama.conf. Log: ~/.jarvis/llama.log
JARVIS_DIR="${JARVIS_DIR:-$HOME/.jarvis}"
# shellcheck disable=SC1091
[ -f "$JARVIS_DIR/llama.conf" ] && . "$JARVIS_DIR/llama.conf"
: "${MODEL:=$HOME/jarvis/models/Qwen2.5-1.5B-Instruct-Q4_K_M.gguf}"
: "${HOST:=127.0.0.1}" "${PORT:=8080}" "${CTX:=4096}" "${THREADS:=4}" "${PARALLEL:=1}" "${CACHE_RAM:=512}"
: "${WATCHDOG_GRACE:=90}" "${WATCHDOG_EVERY:=20}"
LOG="$JARVIS_DIR/llama.log"
mkdir -p "$JARVIS_DIR"

# Only pass options this build of llama-server knows, so a missing flag can never cause a crash loop.
HELP="$(llama-server --help 2>&1)"
ARGS=(-m "$MODEL" --host "$HOST" --port "$PORT" -c "$CTX" -t "$THREADS")
case "$HELP" in *--parallel*) ARGS+=(-np "$PARALLEL") ;; esac
case "$HELP" in *--cache-ram*) ARGS+=(--cache-ram "$CACHE_RAM") ;; esac
# shellcheck disable=SC2206
ARGS+=($EXTRA_ARGS)

if [ ! -f "$MODEL" ]; then
  echo "$(date '+%F %T') model not found: $MODEL" >>"$LOG"
  exit 1
fi

backoff=2
while true; do
  if [ -f "$LOG" ] && [ "$(stat -c %s "$LOG" 2>/dev/null || echo 0)" -gt 2000000 ]; then mv -f "$LOG" "$LOG.1"; fi
  echo "$(date '+%F %T') starting llama-server" >>"$LOG"
  llama-server "${ARGS[@]}" >>"$LOG" 2>&1 &
  pid=$!
  started=$(date +%s)

  # Watchdog: three missed health checks in a row means the server is hung, so stop it and let the loop restart it.
  (
    sleep "$WATCHDOG_GRACE"
    bad=0
    while kill -0 "$pid" 2>/dev/null; do
      if curl -sf --max-time 3 "http://$HOST:$PORT/health" >/dev/null 2>&1; then bad=0; else bad=$((bad + 1)); fi
      if [ "$bad" -ge 3 ]; then
        echo "$(date '+%F %T') watchdog: server not answering, restarting it" >>"$LOG"
        kill "$pid" 2>/dev/null
        break
      fi
      sleep "$WATCHDOG_EVERY"
    done
  ) &
  watchdog=$!

  wait "$pid"
  kill "$watchdog" 2>/dev/null
  ran=$(($(date +%s) - started))
  echo "$(date '+%F %T') llama-server exited after ${ran}s" >>"$LOG"
  if [ "$ran" -gt 60 ]; then backoff=2; else backoff=$((backoff * 2 > 60 ? 60 : backoff * 2)); fi
  sleep "$backoff"
done
