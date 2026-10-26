#!/usr/bin/env bash
# ============================================================================
# TeamFlow traditional deployment launcher (Linux / macOS)
# ============================================================================
# Starts the backend JAR in background with nohup, writes deploy/logs/backend.pid,
# and waits for GET /api/health to return 200.
#
# Environment:
#   ENV_FILE        optional KEY=VALUE file (default: <repo>/dev-local/.env.local if exists)
#   JAR             optional JAR path (default: teamflow-system/teamflow-server/target/...jar)
#   SERVER_PORT     optional port (default 8080)
#   DB_NAME etc.    standard application env vars (see deploy/ENV.md)
#   HEALTH_TIMEOUT_SECONDS  default 120
#
# Usage:
#   bash deploy/traditional/start.sh
#   ENV_FILE=/etc/teamflow/teamflow.env SERVER_PORT=8080 bash deploy/traditional/start.sh
# ============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_DIR="$(dirname "$SCRIPT_DIR")"
REPO_ROOT="$(dirname "$DEPLOY_DIR")"
LOG_DIR="$DEPLOY_DIR/logs"
PID_FILE="$LOG_DIR/backend.pid"

JAR="${JAR:-$REPO_ROOT/teamflow-system/teamflow-server/target/teamflow-server-0.1.0-SNAPSHOT.jar}"
ENV_FILE="${ENV_FILE:-$REPO_ROOT/dev-local/.env.local}"
HEALTH_TIMEOUT_SECONDS="${HEALTH_TIMEOUT_SECONDS:-120}"

step() { echo "[deploy] $*"; }

if [ ! -f "$JAR" ]; then
    echo "[FAIL] JAR not found: $JAR"
    echo "       Build it first: cd teamflow-system && mvn -B -DskipTests clean package"
    exit 1
fi

if [ -f "$ENV_FILE" ]; then
    step "loading env from $ENV_FILE"
    set -a
    # shellcheck disable=SC1090
    . "$ENV_FILE"
    set +a
else
    step "env file not found ($ENV_FILE); using current process environment"
fi

: "${SERVER_PORT:=8080}"
export SERVER_PORT

if [ -z "${JWT_SECRET:-}" ]; then
    echo "[FAIL] JWT_SECRET is required. Generate one with: openssl rand -hex 32"
    exit 1
fi
if [ "${#JWT_SECRET}" -lt 32 ]; then
    echo "[FAIL] JWT_SECRET must be at least 32 bytes (current: ${#JWT_SECRET})"
    exit 1
fi

: "${DB_HOST:=localhost}"
: "${DB_PORT:=3306}"
: "${DB_NAME:=teamflow}"
: "${REDIS_HOST:=localhost}"
: "${REDIS_PORT:=6379}"
export DB_HOST DB_PORT DB_NAME REDIS_HOST REDIS_PORT

STORAGE_PATH="${TEAMFLOW_FILE_STORAGE_PATH:-$REPO_ROOT/data/uploads}"
mkdir -p "$STORAGE_PATH"
export TEAMFLOW_FILE_STORAGE_PATH="$STORAGE_PATH"

mkdir -p "$LOG_DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"
LOG_FILE="$LOG_DIR/backend-$STAMP.log"

step "jar     : $JAR"
step "db      : $DB_HOST:$DB_PORT/$DB_NAME"
step "redis   : $REDIS_HOST:$REDIS_PORT"
step "port    : $SERVER_PORT"
step "storage : $STORAGE_PATH"
step "log     : $LOG_FILE"

cd "$REPO_ROOT"
nohup java -jar "$JAR" >> "$LOG_FILE" 2>&1 &
PID=$!
echo "$PID" > "$PID_FILE"
step "started pid=$PID (pid file: $PID_FILE)"

HEALTH_URL="http://127.0.0.1:$SERVER_PORT/api/health"
for i in $(seq 1 "$HEALTH_TIMEOUT_SECONDS"); do
    sleep 1
    if ! kill -0 "$PID" 2>/dev/null; then
        echo "[FAIL] backend process exited before becoming healthy"
        echo "--- log tail ---"
        tail -n 40 "$LOG_FILE" || true
        exit 1
    fi
    if command -v curl >/dev/null 2>&1; then
        if curl -fsS "$HEALTH_URL" >/dev/null 2>&1; then
            step "health check UP after ${i}s: $HEALTH_URL"
            step "pid=$PID log=$LOG_FILE"
            exit 0
        fi
    else
        if wget -qO- "$HEALTH_URL" >/dev/null 2>&1; then
            step "health check UP after ${i}s: $HEALTH_URL"
            step "pid=$PID log=$LOG_FILE"
            exit 0
        fi
    fi
done

echo "[FAIL] backend did not become healthy within ${HEALTH_TIMEOUT_SECONDS}s ($HEALTH_URL)"
echo "--- log tail ---"
tail -n 40 "$LOG_FILE" || true
exit 1
