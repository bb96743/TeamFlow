#!/usr/bin/env bash
# ============================================================================
# Backup a TeamFlow database with mysqldump (Linux / macOS)
#
# Usage:
#   bash deploy/traditional/backup.sh [DB_NAME]
# Env: DB_HOST / DB_PORT / DB_USERNAME / DB_PASSWORD (or ENV_FILE=/path/to/env)
#      BACKUP_DIR (default: deploy/backup), KEEP (default: 10)
# ============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_DIR="$(dirname "$SCRIPT_DIR")"
REPO_ROOT="$(dirname "$DEPLOY_DIR")"
ENV_FILE="${ENV_FILE:-$REPO_ROOT/dev-local/.env.local}"
BACKUP_DIR="${BACKUP_DIR:-$DEPLOY_DIR/backup}"
KEEP="${KEEP:-10}"

if [ -f "$ENV_FILE" ]; then
    set -a
    # shellcheck disable=SC1090
    . "$ENV_FILE"
    set +a
fi

DB_NAME="${1:-${DB_NAME:-teamflow}}"
DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"
DB_USERNAME="${DB_USERNAME:-root}"

if [ -z "${DB_PASSWORD:-}" ]; then
    echo "[FAIL] DB_PASSWORD is not set (process env or $ENV_FILE)"
    exit 1
fi
export MYSQL_PWD="$DB_PASSWORD"

command -v mysqldump >/dev/null 2>&1 || { echo "[FAIL] mysqldump not found in PATH"; exit 1; }
command -v mysql >/dev/null 2>&1 || { echo "[FAIL] mysql not found in PATH"; exit 1; }

EXISTS="$(mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USERNAME" -N -B -e "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='$DB_NAME';")"
if [ "$EXISTS" != "1" ]; then
    echo "[FAIL] database '$DB_NAME' does not exist."
    exit 1
fi

mkdir -p "$BACKUP_DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"
FILE="$BACKUP_DIR/teamflow-$DB_NAME-$STAMP.sql"

echo "[deploy] dumping $DB_HOST:$DB_PORT/$DB_NAME -> $FILE"
mysqldump -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USERNAME" \
    --default-character-set=utf8mb4 --single-transaction --routines --triggers --no-tablespaces \
    "$DB_NAME" > "$FILE"

SIZE="$(wc -c < "$FILE" | tr -d ' ')"
if [ "$SIZE" -lt 1024 ]; then
    echo "[FAIL] dump is suspiciously small ($SIZE bytes); check credentials/permissions."
    exit 1
fi
echo "[ok] backup created: $FILE"
echo "[ok] size: $SIZE bytes"
echo "[hint] restore with: bash deploy/traditional/restore.sh \"$FILE\" $DB_NAME --drop --force"

if [ "$KEEP" -gt 0 ]; then
    ls -1t "$BACKUP_DIR"/teamflow-"$DB_NAME"-*.sql 2>/dev/null | tail -n +$((KEEP + 1)) | while read -r old; do
        rm -f "$old"
        echo "[info] pruned old backup: $old"
    done
fi

exit 0
