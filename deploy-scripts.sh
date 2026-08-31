#!/bin/bash
# Hot-deploy Groovy scripts into a RUNNING Cameo instance.
#
# Groovy scripts (*.groovy) are hot-loaded by the plugin's GroovyScriptScanner,
# which polls the DEPLOYED scripts directory every 2s and atomically swaps the
# tool/resource/prompt lists. Editing scripts/ in this repo has no effect on a
# running Cameo until the file is copied into the deployed plugin dir.
#
# Usage:
#   ./deploy-scripts.sh                 # copy ALL changed scripts, then wait for reload
#   ./deploy-scripts.sh saf_tools.groovy [more files...]   # copy specific files
#   CAMEO_HOME=/path/to/Cameo ./deploy-scripts.sh          # override plugin home
#
# This script does NOT rebuild the JAR and does NOT require a Cameo restart.
# Restart is only needed when Java source (src/) changes -- see build.gradle /
# install.sh for that.
#
# CAMEO_HOME resolution: an explicit CAMEO_HOME env var wins; otherwise the script
# probes a few likely locations for a live plugin dir. If it cannot find one it
# PROMPTS for the path rather than guessing -- deploying to the wrong (or absent)
# dir silently does nothing and wastes time. If Cameo is not reachable from this
# container you will need to run the equivalent copy on the host instead.
set -eu

SRC_DIR="$(cd "$(dirname "$0")" && pwd)/scripts"
PLUGIN_REL="plugins/com.haarer.saf.mcpserver/scripts"

candidate_homes=()
if [ -n "${CAMEO_HOME:-}" ]; then
    candidate_homes=("$CAMEO_HOME")
else
    candidate_homes=( \
        "/workspace/MSOSA2026xHF2" \
        "/opt/Cameo Systems Modeler 2026x" \
        "/opt/cameo" \
        "/Applications/Cameo Systems Modeler.app" \
        "${HOME}/.local/share/cameo" \
    )
    # Any dir that already looks like a Cameo install with the plugin deployed.
    # Unmatched globs stay as literal patterns and simply fail the [ -d ] check.
    for d in /workspace/* "/opt/"* "$HOME"/* "/Applications/"*; do
        [ -d "$d/plugins/com.haarer.saf.mcpserver" ] && candidate_homes+=("$d")
    done
    # De-dupe preserving order.
    candidate_homes=($(printf '%s\n' "${candidate_homes[@]}" | awk '!seen[$0]++'))
fi

TARGET_DIR=""
for h in "${candidate_homes[@]}"; do
    if [ -d "$h/$PLUGIN_REL" ]; then
        TARGET_DIR="$h/$PLUGIN_REL"
        CAMEO_HOME="$h"
        break
    fi
done

if [ -z "$TARGET_DIR" ]; then
    echo "ERROR: could not locate the deployed plugin scripts dir." >&2
    echo "Expected somewhere like \$CAMEO_HOME/$PLUGIN_REL." >&2
    echo "Tried: ${candidate_homes[@]:-<none>}" >&2
    if [ -t 0 ]; then
        read -r -p "Enter the CAMEO_HOME path (the Cameo installation root): " CAMEO_HOME
        CAMEO_HOME="$(printf '%s' "$CAMEO_HOME" | sed 's/[[:space:]]*$//')"
        if [ -n "$CAMEO_HOME" ] && [ -d "$CAMEO_HOME/$PLUGIN_REL" ]; then
            TARGET_DIR="$CAMEO_HOME/$PLUGIN_REL"
        fi
    fi
    if [ -z "$TARGET_DIR" ]; then
        echo "ERROR: no valid deployed scripts dir configured. Deploying to a wrong/absent" >&2
        echo "       path silently does nothing -- aborting instead." >&2
        echo "If Cameo is not reachable from this container, deploy on the host:" >&2
        echo "  cp <repo>/scripts/<file>.groovy <Cameo>/plugins/com.haarer.saf.mcpserver/scripts/" >&2
        exit 1
    fi
fi

# Default to deploying every .groovy file in the repo source scripts dir.
if [ $# -eq 0 ]; then
    FILES=("$SRC_DIR"/*.groovy)
else
    FILES=("$@")
fi

count=0
for f in "${FILES[@]}"; do
    name="$(basename "$f")"
    if [ ! -f "$SRC_DIR/$name" ]; then
        echo "WARN: $SRC_DIR/$name does not exist, skipping" >&2
        continue
    fi
    cp "$SRC_DIR/$name" "$TARGET_DIR/$name"
    echo "deployed $name -> $TARGET_DIR/$name"
    count=$((count + 1))
done

if [ "$count" -eq 0 ]; then
    echo "Nothing to deploy."
    exit 0
fi

# The scanner polls every 2s (CameoMcpServer.HOT_RELOAD_INTERVAL_MS). Give it a
# beat so the next tool call runs the freshly loaded script.
echo "Waiting 3s for hot-reload to pick up the changes..."
sleep 3

echo "Done. Changes are live (no Cameo restart needed)."
echo "To verify a specific tool reloaded, exercise it via the MCP endpoint (e.g. SERVER_URL=http://host.containers.internal:18750)."
