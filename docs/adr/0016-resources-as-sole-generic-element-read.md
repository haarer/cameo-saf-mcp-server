# ADR-0016: Resources as the Sole Generic Element Read

## Status

Accepted

## Context

Element-dump tools (`get_element_details`, `get_elements_details_batch`,
`saf_get_element_details`) advertised "everything about one element in one call"
and trained agents to hoard blobs instead of navigating. Observed in the FFDS
session (2026-09-10, see `docs/mcp-surface-review-2026-09-10-ffds.md`): calls to
`cameo-model_get_element_details` failed (no such tool at all), the actual
`get_elements_details_batch` was a dead end, and agents fell back to
N+1 drill-downs that the `cameo://*` resource layer was built to replace.

The factor that made the heaps dangerous is not payload size alone — it is the
**query bound**. Element-dump tools answer "everything about one element", which
invites hoarding; resources answer "this specific fact", which invites
navigation. The read surface should therefore be shaped by query bounds, not by
element dumps.

## Decision

1. **Resources are the only generic element-read path.** `cameo://element/{id}`
   (fact sheet), `/children` (owned slices), `/relationships` (relationship
   slices), and `cameo://diagram/{id}` are how agents learn facts about model
   content. See ADR-0013 for the fact/slice grammar.
2. **Element-dump tools are forbidden.** `get_element_details` and
   `get_elements_details_batch` are removed. `saf_get_element_details` is removed.
   No new tool that returns "everything about one element" may be added.
3. **One narrow inference tool serves the SAF interpretation layer** the
   resources intentionally do not carry: `saf_get_element_semantics(elementIds[])`
   returns, per element, its `safKind`, `safDomain`, and the viewpoints that use
   the element, resolved server-side via the SafDataStore
   stereotype→concept→viewpoint indexes. Batch input annotates finder results in
   one call. It is an inference tool, not a dump: no tags, no owned children, no
   relationship edges.
4. **Knowledge split** (the read-path hierarchy):
   - `cameo://*` resources — generic navigation and facts.
   - `spec_*` tools — the SAF ontology (what concepts/viewpoints mean).
   - `saf_get_element_semantics` — interpretation (what a located element is).
   - CRUD tools — writes.
   - `saf_build_traceability_chain` — graph collection (multi-hop walks).
5. Relationship slices expose **symmetric** target context: incoming edges carry
   target stereotypes too, matching `collectTraceability` semantics.
6. Tool descriptions that previously pointed at the removed dumps now point at
   the resource URIs (`cameo://element/{id}` etc.).

## Consequences

1. Agents navigate via facts, then drill down deliberately — the intended
   resource-first pattern from ADR-0013.
2. SAF interpretation is cheap (one batch call annotates a finder result),
   removing the temptation to resurrect a dump tool.
3. The generic element facts remain reachable for LLM reasoning and CRUD
   targeting through resources, keeping the read path schema-free and uniform.
4. Tests and agent-jobs (`tests/test_saf_tools.py`, `tests/test_mcp_server.py`,
   `tests/agent-jobs/selection_analyze.md`) must be updated to the new surface.

## Related

- ADR-0013 (fact/slice grammar).
- ADR-0014 (`metaclass` vs `type`).
- `plan.md` Iteration 9 — Read-Surface Reform.
- `docs/mcp-surface-review-2026-09-10-ffds.md`.
- `AGENTS.md` § Model navigation read path.