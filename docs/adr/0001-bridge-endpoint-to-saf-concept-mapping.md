# ADR-0001: Bridge Endpoint to SAF Concept Mapping

## Status

Accepted

## Context

The Cameo SAF MCP Server exposes MCP tools (e.g. `saf_create_element`)
that accept a `kind` parameter such as `"system_requirement"` or
`"physical_system"`. These kind strings must map to:

1. A concrete SysML metaclass to instantiate (e.g. `Class`).
2. A SAF stereotype to apply (e.g. `SAF_SystemRequirement`).
3. The formal SAF concept in the specification that defines the element's
   semantics.

We need a documented, auditable mapping between these three layers so
that:
- The bridge code (CONCEPT_MAP) stays aligned with the SAF spec.
- Future tools (viewpoint export, consistency checks, traceability chains)
  can reliably resolve the same relationships.
- A human or AI can trace from an MCP tool call to the SAF spec definition.

## Decision

The mapping follows a three-layer architecture with explicit directionality
at each boundary.

### Layer 1: MCP Bridge (`CONCEPT_MAP`)

`saf_tools.groovy` contains a static `CONCEPT_MAP` dictionary:

```
kind_string → [SysML metaclass, SAF stereotype name]
```

The key is a short, programmatic, lowercase-with-underscores identifier
(e.g. `system_requirement`). These are the values accepted by
`saf_create_element` and are **not** part of the SAF specification
themselves — they are a bridge convenience.

The value pair `[metaclass, stereotype]` drives instantiation:
- The `SysML metaclass` tells the Cameo `ElementsFactory` what UML type
  to create (`Class`, `Activity`, `ProxyPort`, etc.).
- The `SAF stereotype name` tells `StereotypesHelper` which SAF profile
  stereotype to apply on creation (or `null` when no stereotype is needed).

### Layer 2: SAF Profile Stereotypes

SAF stereotypes live in the SAF MD ZIP profile installed in Cameo. They
are named with the `SAF_` prefix (e.g. `SAF_SystemRequirement`,
`SAF_PhysicalSystem`, `SAF_OperationalCapability`).

Each stereotype is the **Cameo-side realization** of one SAF specification
concept. The formal link is recorded in
`sources/SAF-Specification/src/_data/realizeconcept.json`:

```
SAF_SystemRequirement  → System Requirement  (concept ID _19_0_2_8710274_...)
SAF_ConceptualSystem   → Conceptual System   (concept ID _19_0_2_26f0132_...)
SAF_PhysicalSystem     → Physical Element    (concept ID _19_0_2_26f0132_...)
SAF_OperationalPerformer → Operational Performer (concept ID _19_0_2_26f0132_...)
...
```

`realizeconcept.json` is the single source of truth for this binding.

### Layer 3: SAF Specification Concepts

`sources/SAF-Specification/src/_data/concepts.json` contains every concept
in the SAF metamodel with a stable UUID-style `ID` and a human-readable
`Name`. These IDs are used as HTML anchors on the SAF devdoc pages:

```
https://saf.gfse.org/devdoc/concepts.html#_19_0_2_8710274_1558520012975_812587_44177
```

The concept name (e.g. "System Requirement", "Physical Element") is the
canonical semantic label.

### Traceability Flow

```
MCP tool call
  │  kind="system_requirement"
  ▼
CONCEPT_MAP[sysml_type="Class", stereotype="SAF_SystemRequirement"]
  │
  ├──► Cameo creates a Class and applies SAF_SystemRequirement stereotype
  │
  └──► realizeconcept.json:
       SAF_SystemRequirement → concept "System Requirement" (ID _19_0_2_...)
         │
         └──► concepts.json: Name="System Requirement",
               ID="_19_0_2_8710274_1558520012975_812587_44177"
                 │
                 └──► saf.gfse.org/devdoc/concepts.html#_19_0_2_...
```

### Notable Mappings

| CONCEPT_MAP key | SysML type | SAF stereotype | concepts.json Name | Notes |
|---|---|---|---|---|
| `system_requirement` | Class | SAF_SystemRequirement | System Requirement | 1:1 |
| `conceptual_system` | Class | SAF_ConceptualSystem | Conceptual System | 1:1 |
| `physical_system` | Class | SAF_PhysicalSystem | Physical Element | Stereotype name != concept name |
| `physical_product` | Class | SAF_PhysicalSystem | Physical Element | Alias — same stereotype |
| `operational_performer` | Class | SAF_OperationalPerformer | Operational Performer | 1:1 |
| `operational_capability` | Class | SAF_OperationalCapability | Operational Capability | 1:1 |
| `system_capability` | Class | SAF_SystemCapability | System Capability | 1:1 |
| `operational_story` | Class | SAF_OperationalStory | Operational Story | 1:1 |
| `system_function` | Activity | SAF_Function | System Function | Maps to Function stereotype |
| `operational_process` | Activity | SAF_OperationalProcess | Operational Process | 1:1 |
| `stakeholder` | Class | SAF_Stakeholder | System of Interest Stakeholder | 1:1 |
| `concern` | Comment | SAF_SystemOfInterestConcern | System of Interest Concern | 1:1 |
| `requirement` | Class | SAF_SystemRequirement | System Requirement | Generic alias |
| `proxy_port` | ProxyPort | (none) | — | No stereotype needed |
| `mission` | Class | (none) | — | No SAF stereotype |

## Consequences

1. **CONCEPT_MAP must be kept in sync** with `realizeconcept.json`.
   Adding a new stereotype to the SAF profile requires:
   - Adding its `realizeconcept.json` entry.
   - Adding the corresponding row to `CONCEPT_MAP`.
   - Adding the row to the Concept Map table in `README.md`.
   - Verifying the saf.gfse.org devdoc exists for the concept ID.

2. **Some stereotypes have misleading names**. `SAF_PhysicalSystem`
   realizes the concept "Physical Element" (not "Physical System"). This is
   a historical naming choice in the SAF profile; the bridge maps
   `physical_system` and `physical_product` both to `SAF_PhysicalSystem`.

3. **Bridge keys are not SAF spec terms**. The `kind` strings in
   `CONCEPT_MAP` keys are implementation details of the MCP bridge.
   Documentation and error messages should use the human-readable concept
   names (e.g. "System Requirement") not the underscore keys.

4. **The realizeconcept.json is the audit boundary**. Any claim that a
   bridge tool creates an element conforming to the SAF specification must
   be traceable through `realizeconcept.json` to a `concepts.json` entry.
   Elements without such a chain are bridge utilities (e.g. `proxy_port`,
   `mission`) and should be flagged as non-normative.

## Related

- `.scratch/saf-concept-map-alignment/issues/01-align-concept-map.md` —
  tracks expanding CONCEPT_MAP to the full SAF spec.
- `sources/SAF-Specification/src/_data/concepts.json` — SAF concept registry.
- `sources/SAF-Specification/src/_data/realizeconcept.json` — Stereotype →
  concept binding.
- `scripts/saf_tools.groovy` — CONCEPT_MAP and all SAF MCP tools.
- `README.md` — Concept Map table with documentation links.
