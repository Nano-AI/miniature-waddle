#!/usr/bin/env bash
set -euo pipefail

REMOTE="${REMOTE:-${1:-}}"
KEY="${KEY:-$HOME/lab_tunnel_key}"
LPORT="${LPORT:-8090}"
RPORT="${RPORT:-8090}"

if [ -z "$REMOTE" ]; then
    echo "usage: REMOTE=user@host [KEY=~/lab_tunnel_key] [LPORT=8090] [RPORT=8090] $0" >&2
    exit 2
fi
if [ ! -f "$KEY" ]; then
    echo "key not found: $KEY" >&2
    exit 2
fi

exec ssh -i "$KEY" -N -T \
    -o IdentitiesOnly=yes \
    -o ExitOnForwardFailure=yes \
    -o ServerAliveInterval=30 \
    -o ServerAliveCountMax=3 \
    -L "127.0.0.1:${LPORT}:127.0.0.1:${RPORT}" \
    "$REMOTE"
