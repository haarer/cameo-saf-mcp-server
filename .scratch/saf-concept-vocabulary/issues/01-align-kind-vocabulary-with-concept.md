# Align the "kind" vocabulary with SAF "Concept"

Status: needs-triage

Direction: **option 1** — rename the field to `safConcept` and return the
real Concept name. The slug becomes an internal lookup key only.

## Problem

A "kind" is not a distinct entity. It is a SAF **Concept** name, lowercased
and slugged:

```groovy
// scripts/saf_tools.groovy:74
static String safKindKey(String conceptName) {   // parameter is literally conceptName
    return conceptName.toLowerCase()
        .replaceAll(/[^a-z0-9 ]/, "")
        .replaceAll(/ /, "_") ...
}
```

| Concept (SAF ontology) | `safKind` (saf_* tools) |
| --- | --- |
| `System Of Interest` | `system_of_interest` |
| `System Use Case` | `system_use_case` |
| `Functional Requirement` | `functional_requirement` |

The transform is lossy and not reversible: punctuation is stripped and
spaces become underscores, so a `safKind` cannot be mapped back to a
Concept without a lookup table.

## Inconsistency with the spec tools

The two halves of the surface use different words for the same entity,
split cleanly along tool-family lines:

| | "kind" | "concept" |
| --- | --- | --- |
| `saf_*` tools | **133** | 43 |
| `spec_*` tools | 5 | **48** |

Every `spec_*` tool says "concept" (`spec_get_concept`,
`spec_list_concepts`, `spec_get_viewpoint_concepts`, `conceptId`). The
`saf_*` tools say "kind". Even *within* `scripts/saf_tools.groovy` the
vocabulary is mixed (133 vs 43), because concept references are passed
through untouched as `concept:` and `concepts:`.

## Impact

1. **The model must bridge two vocabularies for one entity.** It sees
   `spec_get_viewpoint_concepts` return "Concept `System Context`", then
   `saf_get_element_semantics` return `safKind: "system_context"`.
   Nothing in either output states they are the same thing; the model
   must infer the slug rule.
2. **The ontology vocabulary is the point of the integration.** SAF
   viewpoints expose concepts, concepts realize stereotypes, and the model
   is expected to reason over that ontology. It currently cannot name a
   concept using the word the spec uses.
3. **The split leaks into documentation.** Tool descriptions use both
   words, so both are indexed and both match queries. It is a
   comprehension cost, not a retrieval cost.

## Scope

- **4 tools** return the affected fields: `saf_get_element_semantics`,
  `saf_find_elements_by_type`, `saf_export_viewpoint`,
  `saf_create_diagram` (safDomain only)
- **~29 internal sites**: `safKindKey`, `CONCEPT_MAP`, `STEREO_TO_KIND`,
  `KIND_TO_DOMAIN`, the `kinds:` returns, and the `kind` argument
- **Field names**: `safKind` (19), `candidateKinds` (12), `safDomain` (17)

### Blast radius beyond the plugin

Renaming reaches the validation suite merged from upstream, which pins
the surface:

- `validation/surfaces/surface-v1.json`
- `validation/tasks/T04-conceptual-system-ambiguity.json`
- `validation/models/ffds.json`
- `validation/PLAN.md`

`validation/lib/surface.py:242` treats `description` as a critical field
and reports `in_sync` on exact match, so the baseline must be
regenerated as part of this change.

Also referenced in: `docs/adr/0007-viewpoint-tools-redesign.md`,
`docs/adr/0016-resources-as-sole-generic-element-read.md`,
`.scratch/mcp-agent-efficiency/PRD.md`, `showcase/reverse-engineering/*`.

## Breaking change

This alters the wire format of 4 tools. Anything reading `safKind` or
`candidateKinds` must migrate. It should be done as a deliberate
breaking change, not folded into a documentation pass.

## Considered alternatives

- **Keep the names, document the slug rule** in the shared descriptions.
  Zero breakage, but keeps two vocabularies alive and leaves the model to
  do the mapping on every call.
- **Return both** `safKind` and the real Concept name. Costs tokens per
  row, still breaking for strict consumers, and does not converge on one
  vocabulary.

## Note

`_data/concepts.json` contains a concept named `including`, which looks
like a parsing artifact in the source ontology data. It would slug to
`including`. Worth checking while this is open, since it affects the
Concept vocabulary itself rather than the alias.
