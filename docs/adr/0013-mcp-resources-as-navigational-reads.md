# ADR-0013: MCP Resources as Navigational Reads (facts, slices, uniform listings)

## Status

Accepted

## Context

The `cameo://*` resource surface is the read/context path for agents: headless
LLM jobs read these resources into context repeatedly, and the payloads are
their primary model exposure. Until now the endpoint shapes had drifted into a
set of ad-hoc dumps:

- `cameo://element/{id}` returned a full depth-2 owned-element tree — 22.6 KB
  for FFDS Context, dominated by anonymous technical nodes (`Literal Integer`/
  `Literal Unlimited Natural` pairs under every part property, unnamed
  `Connector`s, unnamed `Message Occurrence Specification`s) that carry no
  navigation value.
- Content was duplicated (`documentation` == the `Rationale` comment body),
  node shapes varied within one tree (Comment/Problem nodes had no
  `qualifiedName`/`taggedValues`), and `type` meant different things across
  endpoints.
- Addressability was ambiguous once multiple projects can be loaded: element
  and diagram reads resolve only against the **active** project.

Meanwhile the tools remain the *act* path: `get_element_details` keeps the
faithful deep dump. The resource layer does not need to mirror it.

## Decision

Shape every resource as either a **fact** (identity + claims) or a **slice**
(a list), with a single navigation grammar:

1. **Fact → slice → deeper.** A fact advertises its slices via explicit URIs
   and counts (e.g. `children: {count, byMetaclass, uri}`,
   `relationships: {count, uri}`). Drill down = follow the slice URI, then
   recurse into a child's fact.
2. **Always return ids** for identifiable elements so the LLM can act on them
   (drill, reference, create) — never name-only.
3. **Uniform element-listing shape** across `element/{id}/children`,
   `diagram/{id}`, and `selection`: `{id, name, metaclass, type}` (+
   `qualifiedName`, `stereotypes` where relevant). See ADR-0014 for the
   metaclass/type contract.
4. **Roll up anonymous/technical children** into per-metaclass counts
   (`byMetaclass`) in facts; `/children` still enumerates them by id/name so
   nothing is hidden, only compacted.
5. **Multi-project addressing:** `cameo://projects` lists all loaded projects
   (name, id, active flag, primary root, location, writable); `cameo://project`
   answers the active project; `cameo://project/{id}` and
   `cameo://project/{id}/packages` drill into a project's packages (with
   `origin: owned|shared` and module names for shared packages). Note:
   `Project.getID()` is session-scoped — it changes across restarts, so
   `cameo://projects` is the bootstrap.
6. **Drop** `cameo://model/summary` and `cameo://requirements` — redundant /
   not navigable.
7. Relationship slices use `{type (humanType), direction (outgoing|incoming|
   general), target|source, targetId|sourceId, stereotypes}`.

## Consequences

1. Context-friendly, predictable payloads; a sealed end-to-end job
   (`tests/agent-jobs/resource_navigation.md`) navigates the model via
   resources only.
2. The full-tre morphology lives only in the tool layer
   (`get_element_details`); resources stay compact by design.
3. Uniform listings make schema-free prompting ("give me the ids") work across
   endpoints.
4. Anonymous-technical nodes remain visible as counts, so nothing is silently
   dropped.
5. **Open:** read→mirror→create coherence — see ADR-0014.

## Related

- ADR-0014 (`metaclass` vs `type`).
- `docs/cameo-2026x-api-notes.md` § "MCP resources".
- `tests/agent-jobs/resource_navigation.md`, `tests/run_agent_job.sh`.