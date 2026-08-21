#!/usr/bin/env bash
set -euo pipefail

BASE_DIR="${BASE_DIR:-$HOME/projects}"
TOKEN_FILE="${TOKEN_FILE:-$HOME/.relay_token}"
ADDRESS="${ADDRESS:-0.0.0.0}"
PORT="${PORT:-8090}"

HERE="$(cd "$(dirname "$0")" && pwd)"
JAR="$HERE/../build/libs/relay-server-1.0.0.jar"

if [ ! -f "$TOKEN_FILE" ]; then
    umask 177
    head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n' > "$TOKEN_FILE"
    chmod 600 "$TOKEN_FILE"
    echo "generated token file at $TOKEN_FILE"
fi

echo "token file: $TOKEN_FILE"
echo "listening : ${ADDRESS}:${PORT}"
echo "base dir  : $BASE_DIR"

exec java -jar "$JAR" \
    --server.address="$ADDRESS" \
    --server.port="$PORT" \
    --relay.auth-token-file="$TOKEN_FILE" \
    "--relay.base-dir=$BASE_DIR"
