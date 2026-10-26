#!/usr/bin/env bash
# ============================================================================
# Stop the backend started by deploy/traditional/start.sh (Linux / macOS)
# Usage: bash deploy/traditional/stop.sh [--force]
# ============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_DIR="$(dirname "$SCRIPT_DIR")"
PID_FILE="$DEPLOY_DIR/logs/backend.pid"

if [ ! -f "$PID_FILE" ]; then
    echo "[info] no pid file; backend not started by start.sh (or already stopped)."
    exit 0
fi

PID="$(cat "$PID_FILE" | tr -d '[:space:]')"
if ! [[ "$PID" =~ ^[0-9]+$ ]]; then
    echo "[WARN] invalid pid file content: $PID ; removing it"
    rm -f "$PID_FILE"
    exit 0
fi

if ! kill -0 "$PID" 2>/dev/null; then
    echo "[info] process $PID is not running; removing stale pid file"
    rm -f "$PID_FILE"
    exit 0
fi

echo "[deploy] stopping pid=$PID (graceful)"
kill "$PID" 2>/dev/null || true
for _ in $(seq 1 20); do
    if ! kill -0 "$PID" 2>/dev/null; then
        rm -f "$PID_FILE"
        echo "[ok] backend stopped pid=$PID"
        exit 0
    fi
    sleep 1
done

echo "[info] graceful stop timed out; sending SIGKILL"
kill -9 "$PID" 2>/dev/null || true
rm -f "$PID_FILE"
echo "[ok] backend killed pid=$PID"
exit 0
