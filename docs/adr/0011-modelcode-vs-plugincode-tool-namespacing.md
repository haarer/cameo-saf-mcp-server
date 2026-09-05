# ADR-0011: Tool Namespacing by Code Location — `modelcode_*` vs `plugincode_*`

## Status

Accepted

## Context

The MCP tool surface grows a second way to "run code". Traditionally the
plugin exposes on-disk handler scripts (`scripts/*.groovy`) that the
`GroovyScriptScanner` hot-loads. Iteration on validation rules added a second
world: executable code **stored inside the model** — Constraint bodies,
OpaqueExpressions, OpaqueBehaviors (validation rules, simulation behavior,
docgen expressions).

Two code-authoring/execution concerns now coexist:

1. **`plugincode`** — the on-disk `.groovy` handler scripts that *define the
   tool surface itself*.
2. **`modelcode`** — code bodies stored in model elements, executed against the
   live model.

We needed a way to navigate this surface that does not grow unbounded tool
guides or an ad-hoc taxonomy. The guiding philosophy (from
`agent-architecture-revised-strategy.md`): no category field, no "tool-guide"
resource, no premature abstraction — just consistent naming, rich tool
descriptions, and one section of agent guidance.

## Decision

Group code-authoring/execution tools into two namespaces keyed by **where the
code lives**, not by its function:

- **`modelcode_*`** — code stored **in the model** (Constraints,
  OpaqueExpressions, OpaqueBehaviors).
  - `modelcode_spec_update` — write/read a code body in any model element.
  - `modelcode_validation_run` — run a rule through the real validation engine.
  - `modelcode_validation_eval` — debug a single rule's logic per-target via
    GroovyShell (see ADR-0012).
- **`plugincode_*`** — the **on-disk** MCP handler `.groovy` scripts.
  - `plugincode_introspect` — reflect on a class in the live JVM. Shared by both
    flows; the `cameo-api_*` Javadoc MCP server is tried first for signature
    lookup.

Explicitly **not** chosen:

- No a `category`/`platform` field on the `@McpTool` annotation.
- No "tool-guide" MCP resource enumerating the surface.
- No hierarchical/sub-tool abstraction layer.

The navigation contract lives in `AGENTS.md` § "Groovy code authoring"
(tool table + workflow) and the tools' own rich descriptions.

## Consequences

1. Tool names self-describe the deployment target of the code they manage —
   an LLM can pick `modelcode_spec_update` vs `plugincode_*` from the name
   alone.
2. The `cameo-api_*` Javadoc server remains the primary, read-only source of
   truth for API signatures; `plugincode_introspect` is the fallback that
   reflects on the live JVM.
3. No tool-guide resource to keep in sync; surface navigation is stable.
4. The `modelcode_*` family carries an implicit execution-environment caveat
   (see ADR-0012): not every `modelcode_*` tool executes in the same
   classloader/runtime as the MCP handler scripts.
5. **Generic CRUD over purpose-built validation tools (verified 2026-09).** A
   one-off `validation_set_scope` tool was removed in favour of two generic,
   validation-agnostic CRUD tools: `get_metaclass_by_name` (resolves a UML2
   metaclass via `StereotypesHelper.getUML2MetaClassByName`) and
   `set_constrained_element` (clears + sets a `Constraint`'s
   `getConstrainedElement()` reference). Metaclass resolution and
   constrained-element manipulation are plain CRUD; the *how to author a
   validation rule* knowledge lives in the authoring recipe
   (`docs/cameo-2026x-api-notes.md`), not encoded in a purpose-built tool.
   This is a direct instance of this ADR's "no premature abstraction, guidance
   in docs" decision (see ADR-0012 for the rule-scope engine contract the
   recipe operationalises).

## Related

- ADR-0012 (validation debug harness + rule-scope engine contract).
- ADR-0002 (Java core + Groovy plugin split).
- `AGENTS.md` § "Groovy code authoring".
- `agent-architecture-revised-strategy.md` (naming philosophy).
- `docs/cameo-2026x-api-notes.md` § "Recipe: authoring a Cameo validation rule".
