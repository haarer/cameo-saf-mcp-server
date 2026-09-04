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

## Groovy code authoring (MCP surface navigation)

The MCP surface groups code-authoring/execution tools into two namespaces by
**where the code lives**:

- **`modelcode_*`** — code **stored in the model** (Constraints, OpaqueExpressions,
  OpaqueBehaviors: validation rules, simulation bodies, docgen expressions).
- **`plugincode_*`** — the **on-disk** MCP handler `.groovy` scripts that define the
  tool surface itself.

When authoring or executing Groovy/other-language code, pick the tool by **sub-case**:

| Goal | Tool |
|---|---|
| Write any code body into a model element | `modelcode_spec_update` |
| Verify a validation rule against the real engine (returns violations) | `modelcode_validation_run` |
| Debug/iterate a single rule's logic per-target (GroovyShell) | `modelcode_validation_eval` |
| Confirm a JVM API signature before writing code (either flow) | `plugincode_introspect` |

Workflow guidance:

1. **First** verify a `com.nomagic.*` / `groovy.*` / `jackson` API signature using the
   Javadoc MCP server tools (`cameo-api_search_docs` / `cameo-api_lookup_symbol` /
   `cameo-api_get_members`) — that indexed docs are the primary, read-only source of truth.
2. Use `plugincode_introspect` only when you need signatures not in the Javadoc index,
   or to confirm how a class actually resolves in the live JVM — never guess from
   memory.
3. To write a validation-rule or other in-model body, call `modelcode_spec_update`
   (find candidates first via `find_elements_by_type` with `type='Constraint'`,
   optionally `specLanguage='Groovy'`).
4. While authoring, debug the rule with `modelcode_validation_eval` (per-target
   pass/fail). For authoritative verification use `modelcode_validation_run`.
5. The `cameo-api_*` tools (a separate MCP server) provide navigation through the
   Cameo Javadoc; `plugincode_introspect` reflects on classes in the live JVM.

Do not duplicate SAF semantics or model knowledge into these tools; keep semantic
reasoning in `AGENTS.md`/the `spec_*` tools and model manipulation in the CRUD layer.

## SAF modeling

The SAF knowledge layer is served authoritatively by the `spec_*` tools in
`saf_spec_tools.groovy` (viewpoints, concepts, concerns, stakeholders,
stereotypes, special implementations). AGENTS.md does **not** contain the SAF
ontology — it only teaches navigation; the `spec_*` tools hold the actual knowledge.

When implementing a SAF viewpoint:

1. Identify the SAF viewpoint.
2. Call `spec_get_viewpoint`.
3. Call `spec_get_viewpoint_concepts`.
4. Treat the returned concepts and relationships as authoritative.
5. Drill into individual concepts only when additional semantic
   or implementation detail is required.
6. Use `spec_get_concept_stereotypes` to determine model-level
   realizations.
7. Use `spec_get_stereotype` or
   `spec_get_special_implementations` when necessary.
8. Inspect the existing Cameo model before creating elements.
9. Prefer reusing existing model elements over creating duplicates.
10. Create or modify the semantic model before creating the diagram.
11. Validate the resulting model and view against the viewpoint.

Do not duplicate SAF specification data into MCP resources or into the CRUD
layer. Reason in terms of SAF concepts (e.g. `PhysicalSystem`, `PhysicalSOIRole`)
and use the low-level model/Cameo tools only to perform the actual operations.

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
