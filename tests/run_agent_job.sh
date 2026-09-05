#!/bin/bash
# Reproducible LLM<->MCP job runner.
#
# Spawns a headless `opencode run` session (fresh process, own context, same
# global config -> the cameo/cameo-api/thunderbird MCP servers attach) and gives
# it a job prompt. Used to test end-to-end agentic behavior (e.g. "act on the
# current selection") without cluttering an interactive session.
#
# Usage:
#   MODEL=opencode/big-pickle ./run_agent_job.sh /path/to/job.md
#   PROMPT='...' ./run_agent_job.sh
#
# Env: MODEL (default opencode/big-pickle), PROMPT (inline prompt), DIR (log dir,
# default /tmp/opencode-agent-jobs), TIMEOUT seconds (default 240).
set -eu

MODEL="${MODEL:-opencode/big-pickle}"
ROOT="$(cd "$(dirname "$0")" && pwd)"
DIR="${DIR:-/tmp/opencode-agent-jobs}"
mkdir -p "$DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"
LOG="$DIR/run-$STAMP.log"

if [ -n "${PROMPT:-}" ] && [ $# -gt 0 ]; then
    echo "ERROR: pass either PROMPT or a prompt file, not both" >&2
    exit 2
fi
if [ -n "${PROMPT:-}" ]; then
    JOB="$PROMPT"
elif [ $# -ge 1 ]; then
    JOB="$(cat "$1")"
else
    JOB="$(cat "$ROOT/agent-jobs/selection_analyze.md")"
fi

echo "model=$MODEL"
echo "job=$JOB"
echo "log=$LOG"

timeout "${TIMEOUT:-240}" opencode run --model "$MODEL" "$JOB" 2>&1 | tee "$LOG"
echo "exit=$?"