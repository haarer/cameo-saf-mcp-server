# ADR-0010: Revise Viewpoint Tools Approach — Fix Existing Tool, Drop Composition Chain

## Status

Accepted

## Supersedes

ADR-0007

## Context

### What ADR-0007 proposed

ADR-0007 recommended replacing `saf_get_viewpoint_views` with four small composable tools (`saf_list_viewpoints`, `saf_get_viewpoint`, `saf_query_by_concept`, `saf_resolve_concern`) that chain through the SAF spec chain: concern → viewpoint → concept → stereotype → model element. The rationale was that a monolithic "do everything" viewpoint tool is inflexible and wastes tokens.

### What changed since ADR-0007

**Iteration 3c/4 added a full set of `spec_*` ontology tools.** These 15 tools (`spec_list_concepts`, `spec_get_viewpoint`, `spec_get_concern`, `spec_get_stakeholder`, etc.) backed by the Java `SafDataStore` now cover the "explain the ontology" concern completely. An LLM that needs to understand what concepts a viewpoint exposes, what concerns it frames, or what stereotypes realize a concept can call `spec_get_viewpoint_concepts` or `spec_get_concept_stereotypes` directly.

The composition chain that ADR-0007 envisioned (concern → viewpoint → concept → stereotype → model element) is therefore already addressable through the existing tool set — no new tools are needed for ontology explanation.

### What is actually missing

The one practical gap that remains is: **find actual diagrams in the loaded model that conform to a viewpoint, and report what elements they contain.** This is exactly what `saf_get_viewpoint_views` aims to do, but it has three implementation problems:

1. **Hardcoded kind lists.** `getKindsForViewpoint()` uses a switch statement with manually curated SAF-kind strings per domain. These drift from the spec.
2. **Broken diagram content traversal.** `collectDiagramElements()` uses `diagram.getOwnedElement()`, which returns model-level owned elements, not diagram graphical elements. Cameo stores diagram content in `PresentationElement` objects reachable through the diagram's `DiagramPresentationElement` API.
3. **Does not consult spec data.** The viewpoint → concept → stereotype mapping from the JSON files is ignored in favor of the hardcoded lists.

## Decision

### 1. Drop the composition chain (ADR-0007 items 1-4)

Do not create `saf_list_viewpoints`, `saf_get_viewpoint`, `saf_query_by_concept`, or `saf_resolve_concern`. The `spec_*` tools already cover ontology explanation. The composition chain can be accomplished by the LLM calling multiple existing tools.

### 2. Keep and fix `saf_get_viewpoint_views`

The tool has the right name and purpose — it just has implementation bugs. Fix them:

1. **Replace `getKindsForViewpoint()`** — derive viewpoint→concept→stereotype mappings from `SafDataStore` instead of hardcoded switch statements. For a given domain (e.g. `operational`), query which viewpoints exist in that domain, which concepts they expose, and which stereotypes realize those concepts. The resulting set of stereotype names is the "relevant kinds" for conformance matching.

2. **Fix diagram content traversal** — use `PresentationElement.getModelElement()` to resolve diagram graphical elements to their underlying model elements. This requires accessing the diagram's `DiagramPresentationElement` through the Cameo diagram API. Diagrams conform to a viewpoint if their contained model elements carry stereotypes that realize concepts exposed by that viewpoint.

3. **Keep the query model** — the tool searches diagrams, checks each diagram's elements against the resolved kinds, computes a conformance score, and returns results sorted by conformance. This model is correct; only the implementation needs fixing.

### 3. Data sources

The `saf_get_viewpoint_views` tool MUST use `SafDataStore` as its single source for spec-data queries:

| What it needs | Source |
|---|---|
| Viewpoint ID → domain, aspect, exposed concepts | `SafDataStore.getViewpoint(id)` or `getCurrentIndex().allViewpoints()` |
| Concept → stereotypes that realize it | `SafDataStore.getDirectStereotypesForConcept(name)` |
| Stereotype name → SAF kind | Already handled by `SafTools.resolveSafKind()` or SafDataStore's concept→stereotype mapping |

## Consequences

1. **Kept:** `saf_get_viewpoint_views` — improved implementation, same API contract.
2. **Not created:** `saf_list_viewpoints`, `saf_get_viewpoint`, `saf_query_by_concept`, `saf_resolve_concern` — superseded by `spec_*` tools.
3. **Fixed:** Diagram content traversal uses `PresentationElement.getModelElement()` instead of `getOwnedElement()`.
4. **Spec-data-driven:** Hardcoded `getKindsForViewpoint()` is replaced by `SafDataStore` derived mappings.
5. **Plan updated:** Iteration 3c/4 completed; viewpoint-tool fixes moved into a follow-up task.
