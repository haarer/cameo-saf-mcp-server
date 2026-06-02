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
  Groovy scripts (`scripts/*.groovy`) are hot-loaded — no restart needed.
