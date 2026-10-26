#!/usr/bin/env bash
# ============================================================================
# Restore a TeamFlow database from a mysqldump file (Linux / macOS)
#
# Usage:
#   bash deploy/traditional/restore.sh <backup.sql> [DB_NAME] [--drop] [--force]
#
# Safety:
#   · --force is REQUIRED (otherwise nothing happens);
#   · --drop is required when the target database already contains tables
#     (prevents accidental overwrite);
#   · after restore, verification data is printed.
# ============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_DIR="$(dirname "$SCRIPT_DIR")"
REPO_ROOT="$(dirname "$DEPLOY_DIR")"
ENV_FILE="${ENV_FILE:-$REPO_ROOT/dev-local/.env.local}"

FILE="${1:-}"
ARG_DB_NAME="${2:-}"
DROP="false"
FORCE="false"
for arg in "$@"; do
    case "$arg" in
        --drop) DROP="true" ;;
        --force|-f) FORCE="true" ;;
    esac
done

if [ -z "$FILE" ]; then
    echo "Usage: bash deploy/traditional/restore.sh <backup.sql> [DB_NAME] [--drop] [--force]"
    exit 1
fi
if [ "$FORCE" != "true" ]; then
    echo "[ABORT] restore overwrites data; pass --force to confirm."
    exit 1
fi
if [ ! -f "$FILE" ]; then
    echo "[FAIL] backup file not found: $FILE"
    exit 1
fi
SIZE="$(wc -c < "$FILE" | tr -d ' ')"
if [ "$SIZE" -lt 1024 ]; then
    echo "[FAIL] backup file is suspiciously small; refusing to restore."
    exit 1
fi

if [ -f "$ENV_FILE" ]; then
    set -a
    # shellcheck disable=SC1090
    . "$ENV_FILE"
    set +a
fi

# positional argument wins over the env file's DB_NAME
DB_NAME="${ARG_DB_NAME:-${DB_NAME:-teamflow}}"
DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"
DB_USERNAME="${DB_USERNAME:-root}"

if [ -z "${DB_PASSWORD:-}" ]; then
    echo "[FAIL] DB_PASSWORD is not set (process env or $ENV_FILE)"
    exit 1
fi
export MYSQL_PWD="$DB_PASSWORD"

command -v mysql >/dev/null 2>&1 || { echo "[FAIL] mysql not found in PATH"; exit 1; }

EXISTS="$(mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USERNAME" -N -B -e "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='$DB_NAME';")"
if [ "$EXISTS" = "1" ]; then
    TABLES="$(mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USERNAME" -N -B -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DB_NAME';")"
    if [ "$TABLES" -gt 0 ] && [ "$DROP" != "true" ]; then
        echo "[ABORT] database '$DB_NAME' already has $TABLES tables."
        echo "        Pass --drop to drop and recreate it (DESTRUCTIVE), or restore into an empty database."
        exit 1
    fi
    echo "[deploy] target database exists ($TABLES tables)"
else
    echo "[deploy] target database does not exist; it will be created"
fi

if [ "$DROP" = "true" ]; then
    echo "[deploy] DROP DATABASE $DB_NAME (confirmed by --force --drop)"
    mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USERNAME" -e "DROP DATABASE IF EXISTS \`$DB_NAME\`;"
fi
mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USERNAME" -e "CREATE DATABASE IF NOT EXISTS \`$DB_NAME\` DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci;"

echo "[deploy] restoring $FILE -> $DB_NAME"
mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USERNAME" --default-character-set=utf8mb4 "$DB_NAME" < "$FILE"

echo "[ok] restore finished; verification:"
mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USERNAME" -N -B -e "SELECT 'tables', COUNT(*) FROM information_schema.tables WHERE table_schema='$DB_NAME';"
FLYWAY="$(mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USERNAME" -N -B -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DB_NAME' AND table_name='flyway_schema_history';")"
if [ "$FLYWAY" = "1" ]; then
    mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USERNAME" -N -B -e "SELECT 'flyway_max_version', MAX(CAST(version AS UNSIGNED)) FROM \`$DB_NAME\`.flyway_schema_history WHERE version IS NOT NULL;"
    mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USERNAME" -N -B -e "SELECT 'flyway_rows', COUNT(*) FROM \`$DB_NAME\`.flyway_schema_history;"
fi
for TABLE in sys_user sys_menu sys_role_menu; do
    CHECK="$(mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USERNAME" -N -B -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DB_NAME' AND table_name='$TABLE';")"
    if [ "$CHECK" = "1" ]; then
        mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USERNAME" -N -B -e "SELECT '$TABLE', COUNT(*) FROM \`$DB_NAME\`.$TABLE;"
    fi
done

exit 0
