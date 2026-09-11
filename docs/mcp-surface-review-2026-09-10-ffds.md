# MCP Surface Review — 2026-09-10 FFDS modeling session (operational availability)

Provenance: written after a session that assessed what the FFDS must support
from the O3_OSTA "Operational State" state machine and then implemented the
accepted changes in `SAF_FFDS.mdzip` (wiring fixes, a new system function,
three derived requirements, and a documented state mapping). The session made
heavy use of `saf_*`, `spec_*`, and low-level `create_*`/`modify_*` tools, plus
`cameo://*` resources, with a continuation-style "continue if you have next
steps" loop.

The intent of this page: learn which parts of the MCP surface behave as an LLM
would expect, which parts silently mislead, and which operations cannot be
expressed at all through the current surface. Each finding is a candidate
either for a server-side fix or for an LLM-guidance rule in `AGENTS.md` /
tool-clinical guidance.

## What worked as expected

| Tool | Notes |
|---|---|
| `saf_create_element` | Reliable across all kinds exercised (`system_function`, `functional_requirement`, `nonfunctional_requirement`, `package`). Kind names resolved correctly. |
| `saf_create_relationship` | `dependency` type created cleanly. `refine` created the Abstraction (see the gap below for the stereotype). |
| `apply_stereotype` | Applied `SAF_SystemFunctionalRequirementRefinement` on existing Abstraction elements without issue. |
| `saf_set_requirement_tags` | Set `id`/`text` on all three new requirements; `tagsSet: 2` verified. |
| `get_stereotype_tags` | Reliable write-back verification for applied stereotypes and tagged values. |
| `saf_get_element_details` | Good drill-down on known elements; `ownedElements` + `traceability` arrays are the main model-inspection surface. |
| `saf_find_elements_by_type` | Reliable by name/stereotype across the model. |
| `get_element_info` | Good for qualified-path lookup and relationship listing. |
| `list_model_stereotypes` | Useful for discovering what is actually applied. |
| `saf_check_consistency` | Works scoped to a subtree via `parentId`; returned 0 issues. |
| `modify_element` | Clean documentation updates (beware: replaces, does not append). |
| `create_element` | Basic SysML creation (Package) fine. |
| `spec_get_concept_stereotypes` | Clean verification of stereotype-to-concept mapping (`Non-functional Requirement`). |
| `question`, `todowrite` | Session-control tools; worked as expected. |

## What failed or was misleading

| Tool / Pattern | What happened | Impact |
|---|---|---|
| `cameo-model_get_element_details` | **Does not exist.** Called repeatedly; hard errors. The real tool is `saf_get_element_details`. | Several wasted turns. The name is plausible but absent. |
| `saf_create_relationship(type='refine')` | Created the Abstraction but applied generic `Refine`, **not** `SAF_SystemFunctionalRequirementRefinement`. The tool docs promise "applies the correct ... SAF stereotype" for SAF types; for `refine` this did not happen. Manual `apply_stereotype` needed afterwards. | Model ended up correct only because the agent chased it; convention violation was one step away. |
| `cameo://diagram/...` resources | `elementCount: 0` for every diagram URI tried (operational state diagram, system modes and states). | Diagram shapes / rendering are unreadable; the agent worked purely from element data and could never see what was actually on screen. |
| `cameo://transition/...` resources | Returns the transition element but **no trigger, guard, effect, or name**. | State-machine semantics were opaque; impossible to verify which transition corresponds to which state change without reading raw data element-by-element. |
| Item-flow creation — no `create_information_flow` | No tool exists to create an `InformationFlow` with source/target participants, item kind, and direction at package level. Closest tools (`create_connector`, `create_relationship(type='connector')`) are for internal-structure wiring, not conceptual item flows. | **Item flows are effectively unmodelable via the current surface.** Only a bare `InformationFlow` + `ItemFlow` stereotype stub could be created (matching existing model convention, which is itself bare — see Surprises). |
| `create_item_flow` / equivalent | **Does not exist.** | The `flow for SystemOperationalMode` element exists as a stub with no wired ends, no source/target, no direction. |

## Surprising / non-obvious findings

| Finding | Detail |
|---|---|
| NFR kind name trap | `saf_create_element(kind='non_functional_requirement')` fails with "Unknown SAF kind". The valid kind is `nonfunctional_requirement` (no underscore). The error helpfully lists all valid kinds, but the convention is inconsistent with the concept name "Non-functional Requirement". |
| Requirement text is opaque via MCP | Existing requirements carry their text in EAP-migrated `StringTaggedValue` children whose values no MCP tool exposes. `get_stereotype_tags` on them returns only `base_Class` with empty values. `saf_set_requirement_tags` works for *new* requirements but there is no read path for the migrated text. |
| `saf_create_relationship` stereotype application is subtype-inconsistent | For some SAF types it applies the SAF stereotype automatically; for `refine` it does not (generic `Refine` only). The tool description over-promises. |
| `saf_check_consistency` scope behavior | `parentId` scoped the check to a subtree (6 elements); un-scoped behavior unclear. Duplicated, in subtrees the check still flagged nothing even though the item flow is unwired — i.e. wiring gaps (source/target/direction) are invisible to the consistency tooling. |
| Diagram resources are write-only | `saf_create_diagram` / `saf_add_association_paths` can create and modify diagrams, but `cameo://diagram/...` reads return nothing usable. Write works; read does not. |
| No path-based lazy listing | `list_owned_elements` needs an element ID; listing children of a package by name requires a preceding `get_element_info` roundtrip. |
| `modify_element` documentation overwrites | Replaces (does not append) the documentation; the full prior text must be re-supplied to keep it. |
| EAP-migrated tagged values create hidden children | Setting `id`/`text` via `saf_set_requirement_tags` adds `Element Tagged Value` / `String Tagged Value` children that appear in `ownedElements` but are opaque (no content). |

## Recommendations for the MCP surface

1. **Add `create_information_flow`** — create a package-level `InformationFlow`
   with source/target participants, item kind, and direction. Single biggest
   gap; conceptual item flows are currently unmodelable.
2. **Fix `saf_create_relationship(type='refine')`** — apply
   `SAF_SystemFunctionalRequirementRefinement` (or at minimum the generic
   `Refine`) consistently with the documented promise of automatic SAF
   stereotype application.
3. **Add/alias `get_element_details`** — the missing-name gotcha cost repeated
   failed turns. Either add the tool or alias a descriptive name to
   `saf_get_element_details`.
4. **Expose diagram shapes on `cameo://diagram/...`** — at minimum the element
   IDs of shapes on the diagram, ideally shape-level metadata. Currently
   write-only for read purposes.
5. **Expose requirement text** — surface `id`/`text` from `SAF_SystemRequirement`
   tagged values in `saf_get_element_details`, including EAP-migrated
   `StringTaggedValue` content. Both read (existing reqs) and write (new reqs)
   paths are inconsistent today.
6. **Add path-based listing** — accept a qualified name in `list_owned_elements`
   (or similar) to skip the lookup roundtrip.
7. **Enrich transition resources** — include trigger/guard/effect/name so state
   machines are readable without per-OccurrenceSpecification drilling.

## LLM guidance rules (candidates for AGENTS.md / tool cards)

- **Never call `get_element_details`** — it does not exist. Use
  `saf_get_element_details`.
- **`refine` via `saf_create_relationship` builds the Abstraction but applies
  only the generic `Refine` stereotype** — always follow up with
  `apply_stereotype('SAF_SystemFunctionalRequirementRefinement')` (or the
  correct SAF stereotype for the requirement type being linked).
- **Item flows must be created as a bare `InformationFlow` with the `ItemFlow`
  stereotype applied; no tool wires participants** — plan the resulting stub
  into the work, or block and flag that the flow is unwired.
- **NFR kind is `nonfunctional_requirement`** (no underscore:
  `non_functional_requirement` is invalid).
- **`modify_element` documentation replaces the entire text** — re-supply prior
  content when appending.
- **Existing requirement text is not readable** through the current surface
  (EAP-migrated tagged values). Name and relationship edges are the readable
  ground truth.