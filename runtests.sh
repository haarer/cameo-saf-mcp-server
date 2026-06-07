#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")/tests"

server_url="http://localhost:18750"
if [ $# -gt 0 ] && ! echo "$1" | grep -q '^-'; then
    case "$1" in
        *://*) server_url="$1" ;;
        *:*)   server_url="http://$1" ;;
        *)     server_url="http://$1:18750" ;;
    esac
    shift
fi

export SERVER_URL="$server_url"

if ! command -v uv >/dev/null 2>&1; then
    python3 -m venv /tmp/uv-venv
    /tmp/uv-venv/bin/pip install uv -q
    PATH="/tmp/uv-venv/bin:$PATH"
fi

PY_VER=$(python3 --version | sed 's/Python //' | cut -d. -f1-2)
export UV_LINK_MODE=copy

echo "=== Syncing dependencies (Python $PY_VER) ==="
uv sync --dev --quiet --python-preference only-system --python "$PY_VER"

echo "=== Running tests against $SERVER_URL ==="
uv run --frozen --python-preference only-system --python "$PY_VER" pytest -v "$@"