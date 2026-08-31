# Agent context

## Environment

- Running inside a **Podman container**.
- The host machine (Cameo + MCP plugin) is reachable at `host.containers.internal`.
- Tests must use `SERVER_URL=http://host.containers.internal:18750`.

## Agent behavior

- **Never commit, push, or perform any git-modifying operations** unless the user explicitly asks.
- **Read `README.md` and `plan.md`** at the start of each session for project context and plan.
- **Never modify `README.md` or `plan.md`** unless the user explicitly asks.
- Do not add emojis to files.
- When in doubt about a destructive operation, ask first.

## Agent skills

### Issue tracker

Local markdown files under `.scratch/<feature-slug>/`. See `docs/agents/issue-tracker.md`.

### Triage labels

Five canonical roles: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context repo. See `docs/agents/domain.md`.

## Build & Deploy

- **Cameo restart policy**: only restart Cameo when Java source code (`src/`) changes.
  Groovy scripts (`scripts/*.groovy`) are hot-loaded — no full restart needed.
- **IMPORTANT — the running Cameo does NOT load `scripts/` from this repo.**
  At runtime the plugin resolves its scripts dir from the deployed JAR location
  (`CameoMcpServer.determineDefaultScriptsDir()` → `<CAMEO_HOME>/plugins/com.haarer.saf.mcpserver/scripts`,
  overridable by VM property `cameo.mcp.server.scripts.dir`). Editing a script here
  has NO effect until it is copied into that deployed dir.
- **Hot-deploy a Groovy script into a running Cameo** with:
  `./deploy-scripts.sh [file.groovy ...]`
  (defaults to all of `scripts/`; uses `CAMEO_HOME`; waits ~3s for the 2s poller).
  The `GroovyScriptScanner` detects file changes (mtime) every 2s and atomically
  swaps the tool/resource/prompt lists without disconnecting MCP sessions.
- **`install.sh`** is the FULL deploy (rebuild JAR + copy everything) and requires a
  restart — do NOT use it for script-only changes.
