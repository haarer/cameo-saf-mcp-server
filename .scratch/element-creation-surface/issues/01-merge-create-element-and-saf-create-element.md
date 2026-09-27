# Merge create_element and saf_create_element into one creation tool

Status: needs-triage

## Premise

`create_element` and `saf_create_element` are the same capability split
across two tools. The model's intent is "create a system requirement"; it
should not have to know that means `Class` + `SAF_SystemRequirement`.

Both call the **same primitive**. `saf_create_element` resolves
`kind -> [metaclass, stereotype]` and then invokes the identical
`createByType(...)` helper that `create_element` uses
(`scripts/element_crud.groovy:131`), followed by the same
`addElement`, `addStereotype` and `setComment` steps.

The SAF path adds exactly three things over the CRUD path:

1. `kind -> (metaclass, stereotype)` resolution
2. `CONCEPT_MAP` validation, with an error listing valid kinds
3. `resolveMetaclassFor(...)` — a SAF-specific metaclass override keyed on
   the stereotype

Nothing else. It is one capability with a convenience layer and an escape
hatch.

## The split is actively harmful

A model that picks a `kind` that does not exist gets
`[error: "Unknown SAF kind: ..."]`, then falls back to
`create_element` with a **guessed** stereotype — which has no
`CONCEPT_MAP` validation and silently produces an element the SAF spec
does not recognise. The two-tool split pushes work toward the weaker,
unchecked path. This is not hypothetical: see the kind-list defect below.

## Proposed shape

One tool, two mutually exclusive arguments, one precedence rule.

| argument | meaning | validation |
| --- | --- | --- |
| `kind` | SAF concept slug; derives metaclass **and** stereotype | `CONCEPT_MAP`; errors with the valid list |
| `type` | raw UML metaclass (Class, Package, Port, ProxyPort, …) | `createByType` switch |
| `stereotype` | only meaningful with `type`; applied literally | `findStereotype`; errors if absent |

**Precedence: `kind` wins and ignores `type`.** This must be explicit,
because of `resolveMetaclassFor`: it can *change* the metaclass based on
the stereotype, so `type=Class, stereotype=SAF_SystemRequirement` is
ambiguous unless the rule is stated. `kind` derives both; `type` is
literal and unchecked.

Return shape becomes the union:
`{id, name, kind, type, stereotype, parentId}`, with `kind` null when
`type` was used.

## Decisions required

1. **Precedence** — as above. Non-negotiable to specify.
2. **Hard cutover or deprecation?** The project convention is clean
   cutover with no shims, so: delete `saf_create_element` and migrate
   description, docs and prompts. This removes a tool name from the wire.
3. **Blast radius** — `validation/surfaces/surface-v1.json` pins tool
   names, plus `scripts/tool_contract_resources.groovy`,
   `docs/adr/0007-viewpoint-tools-redesign.md`,
   `docs/adr/0016-resources-as-sole-generic-element-read.md` and
   `validation/tasks/*`. Same baseline regeneration as the vocabulary
   rename.
4. **BM25 selection** — merging two near-duplicate tools into one should
   help selection, by removing a confusable pair. Worth measuring after
   rather than assuming.
5. **Sequencing** — if `kind` becomes an argument to a *general-purpose*
   creation tool, the slug-vs-Concept confusion becomes more prominent,
   not less. The `kind -> concept` rename in
   `.scratch/saf-concept-vocabulary/` should land first, or in the same
   change. Doing the merge while `kind` is still a slug would bake the
   confusion in more deeply.

## Recommendation

Sequence as: rename `kind -> concept` and `sysmlType -> umlMetaclass`,
then merge the two creation tools, with **one** baseline regeneration at
the end of both rather than one per step.

## Supporting finding: the supported-kind count is wrong

`saf_create_element`'s description claims *"all ~90+ SAF concepts are
supported"*. The real number is **156**.

`CONCEPT_MAP` is built in `buildMapsFromIndex` from every concept that has
both a name and a `ClassType`, then **skips any whose `ClassType` is
absent from `CLASSTYPE_TO_SYSML`**:

| | count |
| --- | --- |
| concepts with Name + ClassType | 372 |
| ClassType present in `CLASSTYPE_TO_SYSML` | **156** |
| skipped | 216 (`Association` 168, `AssociationClass` 48) |
| after slug + first-wins dedupe | **156** |

So "~90+" understates the supported set, and "372" overstates it. Every
mappable concept has `ClassType: Class`; the two relationship-ish
metaclasses are not in the map at all. The merged description must state
156, or better, avoid a count and point at the discovery tool.

## Supporting finding: the example kind list is half fabricated

The same description lists 15 example kinds. Checking each against
`CONCEPT_MAP` (not merely against `concepts.json` — a concept only counts
as a kind if its `ClassType` is mappable):

| claimed kind | verdict |
| --- | --- |
| `system_requirement`, `conceptual_system`, `physical_system`, `operational_performer` | real |
| `operational_capability`, `stakeholder`, `concern` | real |
| `requirement`, `conceptual_function`, `physical_process` | **absent** |
| `mission`, `conceptual_interface`, `proxy_port`, `connector` | **absent** |

**7 of 15 do not exist as kinds.** `proxy_port` and `connector` are UML
metaclasses from `CLASSTYPE_TO_SYSML`, listed as if they were SAF concepts
— the same kind/concept confusion this repo already has an issue for,
leaking into an example list.

The first correction attempt at this list was itself wrong. Verifying
candidate replacements against `concepts.json` alone admitted three
non-kinds — `conceptual_connection`, `operational_connection`,
`physical_connection` all have `ClassType: Association` and are skipped by
`buildMapsFromIndex`. **Verify replacements against `CONCEPT_MAP`, not
against the concept list.**

Verified-real replacements, checked with `safKindKey`'s own slug rule:

- Requirements: `system_requirement`, `stakeholder_requirement`,
  `functional_requirement`
- Systems: `conceptual_system`, `physical_system`, `operational_performer`
- Capabilities: `operational_capability`, `system_capability`
- Functions/Processes: `context_function`, `general_function`,
  `operational_process`
- Exchanges: `conceptual_item_exchange`
- Context/Stakeholders: `stakeholder`, `concern`

No description edit is being made for this yet: the merged description
supersedes it, and writing it twice would be wasted.
