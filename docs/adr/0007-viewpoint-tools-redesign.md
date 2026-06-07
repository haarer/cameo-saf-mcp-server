# ADR-0007: Viewpoint Tools Redesign — Composable Tools over Monolithic Viewpoint Query

## Status

Accepted

## Context

### Current implementation problems

The existing `saf_get_viewpoint_views` tool has several issues:

1. **Wrong name and semantics.** It does not find viewpoint-conforming diagrams/views. It filters model elements by a domain string using hardcoded kind lists. The name implies it searches for views (diagrams), but it actually queries elements.

2. **Hardcoded kind lists.** `getKindsForViewpoint()` uses a switch statement with manually curated kind strings per domain (e.g. `operational` → `["operational_performer", "operational_capability", ...]`). These lists are a crude approximation of what the SAF spec defines and are not derived from the spec data.

3. **Broken diagram content traversal.** `collectDiagramElements()` uses `diagram.getOwnedElement()`, which does not return diagram graphical elements in Cameo. Cameo stores diagram content in `PresentationElement` objects, not in the standard `ownedElement` hierarchy. Consequently `matchCount` is always 0 and no diagram ever qualifies.

4. **Ignore spec data.** The `_data/` JSON files (`viewpoints.json`, `concepts.json`, `realizeconcept.json`) contain the full SAF specification — viewpoint descriptions, domain×aspect cells, concerns framed by each viewpoint, concepts exposed by each viewpoint, and the stereotypes that realize those concepts. None of this is consulted at runtime by `saf_get_viewpoint_views`.

### SAF viewpoint model

SAF defines viewpoints as **domain × aspect** cells. Each viewpoint:

1. **Frames concerns** — engineering questions (e.g. "Who are the operational performers?", "What interfaces cross the system boundary?").
2. **Exposes concepts** — SAF concepts (e.g. `OperationalPerformer`, `ConceptualInterfaceDefinition`) that are relevant to those concerns.
3. Those concepts are **realized by stereotypes** (e.g. `SAF_OperationalPerformer`, `SAF_ConceptualInterfaceDefinition`) in the model.

The correct chain is:

```
concern → framed by → viewpoint → exposes → concept → realized by → stereotype → applied to → model element
```

### Tool design philosophy

A monolithic "do everything" viewpoint tool would be inflexible — it would re-run the same chain every call, waste tokens, and prevent the LLM from deciding which step to take next. LLMs work best with small, focused tools they can chain together based on context.

## Decision

Replace `saf_get_viewpoint_views` with a set of small, composable tools that follow the SAF spec chain:

1. **`saf_list_viewpoints`** — list all viewpoints in the model, optionally filter by domain or aspect. Returns id, name, domain, aspect, concerns (engineering questions), and descriptions. Sources from `_data/viewpoints.json`.

2. **`saf_get_viewpoint`** — drill into one viewpoint by ID or name. Returns the concerns it frames, the concepts it exposes, and the stereotypes that realize those concepts (all derived from `_data/viewpoints.json`, `_data/concepts.json`, `_data/realizeconcept.json`).

3. **`saf_query_by_concept`** — find model elements whose stereotypes realize a given concept. (Already largely covered by `saf_find_elements_by_type` + the `safKind` enrichment field — may only need an alias or thin wrapper.)

4. **`saf_resolve_concern`** (optional) — high-level tool that chains the full path for a given concern string: resolve concern → find framing viewpoint → get exposed concepts → query model elements. Useful when the LLM does not know or care about the intermediate steps.

The existing `saf_get_viewpoint_views` should be removed or renamed (e.g. to `saf_query_elements_by_domain`) and its hardcoded kind lists replaced by spec-data-driven resolution.

### Data sources

The `_data/` JSON files contain static spec information and MUST be used:

| File | Role |
|---|---|
| `viewpoints.json` | Viewpoint definitions: ID, name, domain, aspect, description, concern IDs |
| `concepts.json` | Concept definitions: Name, ClassType, InViewpoint (which viewpoints expose this concept) |
| `realizeconcept.json` | Concept → stereotype mappings (which stereotype realizes each concept) |
| `domains.json` | Domain definitions (architecture_management, operational, conceptual, physical) |
| `aspects.json` | Aspect definitions (requirement, structure, behavior, interface, context, traceability) |

The existing dynamic loading (`loadData()` in `saf_tools.groovy`) already reads three of these at startup. The tool implementations must use these in-memory maps rather than hardcoded strings.

## Consequences

1. **Removed:** `saf_get_viewpoint_views` (or renamed to `saf_query_elements_by_domain` for backward compatibility).
2. **Added:** `saf_list_viewpoints`, `saf_get_viewpoint`, `saf_query_by_concept`, optionally `saf_resolve_concern`.
3. **Fixed:** Diagram content traversal will use Cameo's diagram API (`PresentationElement.getModelElement()`) instead of `getOwnedElement()`.
4. **Spec-data-driven:** All viewpoint → concept → stereotype resolution uses the JSON spec data, not hardcoded lists.
5. **Composable:** LLMs chain the tools as needed. A single `saf_resolve_concern` call is available as a convenience path for common cases.
6. **Plan order:** This work is added as a new iteration before the current Iteration 4 (transport expansion).
