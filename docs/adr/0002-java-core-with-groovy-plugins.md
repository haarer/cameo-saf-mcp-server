# ADR-0002: Java Core with Groovy Plugin Extensions

## Status

Accepted

## Context

The Cameo SAF MCP Server needs to expose MCP tools, resources, and
prompts that interact with the Cameo model. These features span two
distinct audiences:

- **Infrastructure**: HTTP server, JSON-RPC protocol, session management,
  annotation scanning — stable, seldom changed.
- **Model-domain tools**: CRUD operations, SAF stereotype handling,
  traceability chains, viewpoint queries — change frequently as the
  SAF profile or tool set evolves.

We also face a classloader constraint: Cameo bundles its own Groovy 4.0
engine, making Groovy scripts immediately executable without adding a
runtime dependency. The same is not true for Java — any Java change
requires a full plugin rebuild and Cameo restart.

## Decision

The plugin is split into two layers connected by a fixed contract:

### Layer 1: Java Core (`src/com/haarer/saf/mcpserver/`)

Contains everything that does not change per-tool:

- Plugin lifecycle (`CameoMcpServerPlugin`).
- HTTP transport (`StreamableMcpTransportProvider` on port 18750).
- JSON-RPC protocol handler (`McpProtocolHandler`).
- Session management (`McpSession`, `McpSession.Manager`).
- Definition records (`McpToolDefinition`, `McpResourceDefinition`,
  `McpPromptDefinition`).
- `@McpTool`, `@McpResource`, `@McpPrompt` annotations.
- `GroovyScriptScanner` — the bridge: compiles Groovy scripts and
  reflects their annotated methods into the definition records.
- `CameoMcpServer` — owns all the above plus the hot-reload loop.

Java dependencies are minimal: two Cameo proprietary classes (`Plugin`,
`Application`) and two open-source jars (Jackson `ObjectMapper`, Groovy
`GroovyClassLoader`). See README § Dependencies.

### Layer 2: Groovy Scripts (`scripts/*.groovy`)

Each script is a standalone Groovy class with methods annotated by
`@McpTool`, `@McpResource`, or `@McpPrompt`. The `GroovyScriptScanner`
compiles them at startup and every 2 seconds thereafter (hot-reload).

All model-facing logic lives here:
- `echo.groovy`, `logging_demo.groovy` — basic tools.
- `model_info.groovy`, `model_query.groovy` — model introspection.
- `element_crud.groovy` — generic SysML CRUD (7 tools).
- `saf_tools.groovy` — SAF-specific tools (10 tools).

Groovy scripts freely use the full Cameo Open API (via stereotype helpers,
elements factory, session manager, etc.) because they execute inside
Cameo's own Groovy runtime, which already has all Cameo jars on the
classpath.

### Boundary Contract

The Java `GroovyScriptScanner` expects each Groovy method to return
either a plain value (serialised via `.toString()` as fallback) or a
`Map`/`List` (serialised via Cameo's Jackson `ObjectMapper`).
No Java ↔ Groovy shared types exist beyond this contract.

### Why Not Pure Java

Every tool added in Java would require:
1. Editing Java source in `src/`.
2. Rebuilding the jar (`gradle assemblePlugin`).
3. Restarting Cameo (reload `build/plugin-dist/`).
4. Reopening the model.

With Groovy scripts, steps 2–4 are eliminated: edit the `.groovy` file in
`scripts/` and the hot-reload loop picks it up within 2 seconds.

### Why Not Pure Groovy

The plugin infrastructure (HTTP server, classloader scanning, session
management) cannot be expressed in a Groovy script — it must hook into
Cameo's `Plugin` lifecycle interface, which is Java-only. The transport
and protocol layers also benefit from Java's static typing for the
JSON-RPC routing logic, where a serialisation bug would be hard to
diagnose.

### Fast Iteration Without Restart

Every tool added in Java would require:
1. Editing Java source in `src/`.
2. Rebuilding the jar (`gradle assemblePlugin`).
3. Restarting Cameo (reload `build/plugin-dist/`).
4. Reopening the model.

With Groovy scripts, steps 2–4 are eliminated: edit the `.groovy` file in
`scripts/` and the hot-reload loop picks it up within 2 seconds. This is
the primary motivation for the split — tool authors can iterate without
disrupting their modelling session.

### Minimal Java Surface

The Java source references only two Cameo proprietary classes:
`com.nomagic.magicdraw.plugins.Plugin` and
`com.nomagic.magicdraw.core.Application`. All other Cameo API usage
(Groovy's `StereotypesHelper`, `ModelElementsManager`, `ElementsFactory`,
etc.) lives in Groovy scripts, which execute in Cameo's own Groovy
runtime where all Cameo jars are already on the classpath. This means:

- CI only needs stubs for two classes, not the entire Cameo SDK.
- Adding a new tool that calls Cameo APIs never requires a Java
  compilation or a CI stub update.
- The Java core stays stable even as the Groovy tool set grows.

## Consequences

1. **Cameo restart needed only for Java changes**. This is documented in
   `AGENTS.md` and was confirmed during Iteration 1.

2. **Groovy scripts are hot-loaded**. The `GroovyScriptScanner` polls
   `scripts/*.groovy` every 2 seconds and replaces all session tool/resource
   lists atomically. Existing MCP sessions see updated tools without
   disconnecting.

3. **Java core must stay thin**. Adding a new Cameo API dependency to the
   Java layer requires a stub for CI compilation and a Cameo restart to
   deploy. Groovy scripts have no such constraint. The rule of thumb: if
   it calls `StereotypesHelper` or `ModelElementsManager`, it belongs in
   a Groovy script.

4. **CI can compile Java without the full Cameo SDK**. The Java source
   references only `Plugin` and `Application` from Cameo, so CI stubs
   are manageable. See `ci/prepare-ci-libs.sh` and `ci.yml`.

## Related

- README § Internal Architecture describes the component layering.
- README § Dependencies lists the two Cameo jars used by Java.
- `AGENTS.md` § Build & Deploy records the restart policy.
- `plan.md` § Technical Approach and Iteration 1.
