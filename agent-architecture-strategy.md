# SAF MCP Agent Architecture — Resource & Recipe Strategy

## Goal

Use the `cameo-saf-mcp-server` to let an LLM create and validate SAF-compliant architecture views in Cameo.

The core principle is:

> **`AGENTS.md` defines how the agent should behave. SAF MCP resources define what SAF means. Recipes define how SAF is realized in Cameo. MCP tools perform the actual model operations.**

---

## 1. Do not load all SAF concepts into the LLM context

Do **not** put every SAF concept into every prompt.

For example, having hundreds of resources such as:

```text
saf://concepts/PhysicalSystem
saf://concepts/PhysicalUser
saf://concepts/PhysicalEnvironment
...
```

does **not** mean all of them should be loaded into the LLM context.

Instead, use MCP resources for **on-demand retrieval**.

Conceptually:

```text
MCP resource catalog
        │
        ├── viewpoints
        ├── concepts
        ├── stereotypes
        └── recipes
                │
                ▼
          LLM retrieves
          only what it needs
```

The MCP client/host decides when a resource is read and incorporated into the model context.

---

# 2. Make the SAF viewpoint the primary entry point

For a user request involving P1_PCXD, the agent should primarily retrieve:

```text
saf://viewpoints/P1_PCXD
```

This resource should contain enough information for the agent to normally implement the viewpoint without having to retrieve dozens of additional resources.

For example:

```yaml
id: P1_PCXD

description: >
  Physical Context Definition Viewpoint.

concepts:

  PhysicalSystemContext:
    stereotype: SAF_PhysicalContext

  PhysicalSOIRole:
    stereotype: SAF_PhysicalContextRole_SoI

  PhysicalUser:
    stereotype: SAF_PhysicalUser

  PhysicalExternalSystem:
    stereotype: SAF_PhysicalSystem

  PhysicalEnvironment:
    stereotype: SAF_PhysicalEnvironment

  PhysicalContextElementRole:
    stereotype: SAF_PhysicalContextRole

relationships:

  context_membership:
    kind: composition

presentation:
  diagram_type: BDD

validation:
  ...
```

This becomes the agent's **compact knowledge package for P1_PCXD**.

---

# 3. Use individual concept resources for progressive disclosure

Individual resources are still useful:

```text
saf://concepts/PhysicalSystem
saf://concepts/PhysicalUser
saf://concepts/PhysicalEnvironment
```

But they should be retrieved only when needed.

For example:

```text
Agent
  │
  ├── read saf://viewpoints/P1_PCXD
  │
  ├── understands PhysicalSystem sufficiently
  │
  └── no need to retrieve PhysicalSystem resource
```

If the viewpoint contains an ambiguous or complex reference:

```text
Agent
  │
  ├── read saf://viewpoints/P1_PCXD
  │
  ├── needs deeper PhysicalSystem semantics
  │
  └── read saf://concepts/PhysicalSystem
```

This is **progressive disclosure**.

---

# 4. Use resource templates where appropriate

Rather than explicitly implementing hundreds of unrelated resource handlers, use parameterized resource URIs conceptually like:

```text
saf://viewpoints/{viewpointId}
saf://concepts/{conceptId}
saf://stereotypes/{stereotypeId}
saf://recipes/{recipeId}
```

Examples:

```text
saf://viewpoints/P1_PCXD
saf://viewpoints/C2_SSTD

saf://concepts/PhysicalSystem
saf://concepts/PhysicalEnvironment

saf://recipes/P1_PCXD
```

This scales much better as SAF grows.

---

# 5. Separate SAF definition from Cameo implementation

There are two different questions:

### What does SAF define?

Example:

```text
P1_PCXD
Physical Context Definition Viewpoint
```

### How do we implement that definition in Cameo?

Those should not be mixed unnecessarily.

Recommended structure:

```text
saf://viewpoints/P1_PCXD
        │
        │ normative SAF definition
        ▼
saf://recipes/P1_PCXD
        │
        │ Cameo/MCP implementation
        ▼
MCP tools
```

The viewpoint resource describes **what P1_PCXD means**.

The recipe describes **how this MCP/Cameo implementation constructs it**.

This also keeps the architecture portable to other SysML tools or a future SysML2 backend.

---

# 6. `AGENTS.md` should contain operating rules, not the whole SAF framework

`AGENTS.md` should be relatively small.

Its job is to tell the agent:

```text
1. Identify the SAF viewpoint.
2. Read saf://viewpoints/{viewpoint}.
3. Treat that resource as authoritative.
4. Retrieve individual concepts only when needed.
5. Retrieve the implementation recipe when model construction is required.
6. Inspect the existing Cameo model before creating elements.
7. Prefer reusing existing elements over creating duplicates.
8. Use SAF-specific operations when available.
9. Use generic CRUD as low-level primitives.
10. Validate the result against the SAF viewpoint before declaring success.
```

It should **not** contain the entire SAF ontology.

---

# 7. Keep generic CRUD tools

The generic CRUD implementation in:

```text
scripts/element_crud.groovy
```

should remain.

However:

> **CRUD should be the low-level execution layer, not the primary abstraction exposed to the LLM for SAF modeling.**

Conceptually:

```text
SAF viewpoint
      │
      ▼
SAF recipe
      │
      ▼
SAF-specific operation
      │
      ▼
generic CRUD
      │
      ▼
Cameo API
```

For example:

```text
create P1_PCXD view
        │
        ├── create element
        ├── apply stereotype
        ├── create relationship
        ├── create diagram
        └── add element to diagram
```

The agent should normally reason in terms of:

```text
PhysicalSystem
PhysicalSOIRole
PhysicalContext
PhysicalUser
```

rather than:

```text
createElement(...)
applyStereotype(...)
createRelationship(...)
```

---

# 8. Prefer SAF-specific MCP operations when possible

Eventually the MCP server should expose higher-level operations such as:

```text
saf_create_view
saf_validate_view
```

For example:

```json
{
  "viewpoint": "P1_PCXD",
  "context": "Aircraft Operation",
  "system_of_interest": "Aircraft",
  "elements": [
    {
      "name": "Pilot",
      "kind": "PhysicalUser"
    },
    {
      "name": "Air Traffic Control",
      "kind": "PhysicalExternalSystem"
    },
    {
      "name": "Atmosphere",
      "kind": "PhysicalEnvironment"
    }
  ]
}
```

The server then translates those SAF concepts into the appropriate Cameo model elements, stereotypes, roles and relationships.

The LLM therefore operates at the **SAF semantic level**, while the MCP server handles Cameo-specific implementation details.

---

# 9. Recommended MCP architecture

```text
                       LLM / Agent
                            │
                            │
                      ┌─────┴─────┐
                      │ AGENTS.md │
                      └─────┬─────┘
                            │
                    identifies viewpoint
                            │
                            ▼
                saf://viewpoints/P1_PCXD
                            │
                    retrieves definition
                            │
                            ▼
                 saf://recipes/P1_PCXD
                            │
                 retrieves implementation
                            │
                            ▼
                    SAF MCP operations
                            │
              ┌─────────────┴─────────────┐
              │                           │
       model inspection              model mutation
              │                           │
              ▼                           ▼
        find/get/...                  CRUD tools
                                          │
                                          ▼
                                       Cameo
                                          │
                                          ▼
                                      validation
```

---

# 10. Resource hierarchy

A useful conceptual resource structure is:

```text
saf://
│
├── viewpoints/
│   ├── P1_PCXD
│   ├── C2_SSTD
│   └── ...
│
├── recipes/
│   ├── P1_PCXD
│   ├── C2_SSTD
│   └── ...
│
├── concepts/
│   ├── PhysicalSystem
│   ├── PhysicalUser
│   ├── PhysicalEnvironment
│   └── ...
│
└── stereotypes/
    ├── SAF_PhysicalContext
    ├── SAF_PhysicalSystem
    └── ...
```

The important distinction is:

```text
viewpoint → primary entry point

recipe → implementation instructions

concept → optional semantic drill-down

stereotype → optional implementation/detail drill-down
```

---

# 11. P1_PCXD is a particularly good first prototype

P1_PCXD is useful as a test case because it demonstrates the distinction between:

```text
Physical System
```

and:

```text
Physical SOI Role
Physical Context Element Role
```

The recipe therefore must capture not just the stereotype names, but also the **semantic role of each element**.

For example:

```text
Physical System
       │
       │ participates through
       ▼
Physical SOI Role
       │
       │ belongs to
       ▼
Physical System Context
```

This is precisely the type of semantic distinction that a simple "draw this diagram" prompt will often get wrong.

---

# 12. Validation should be a first-class operation

Do not consider:

```text
diagram created
```

to mean:

```text
viewpoint successfully implemented
```

Instead:

```text
CREATE
  │
  ▼
INSPECT
  │
  ▼
VALIDATE
  │
  ├── PASS ──► done
  │
  └── FAIL
       │
       ▼
      FIX
       │
       ▼
    VALIDATE
```

For P1_PCXD, validation could check:

```yaml
validation:

  required:
    - SAF_PhysicalContext
    - SAF_PhysicalContextRole_SoI

  allowed_context_elements:
    - SAF_PhysicalUser
    - SAF_PhysicalSystem
    - SAF_PhysicalEnvironment

  relationships:
    context_membership: composition

  diagram:
    type: BDD
```

This makes the recipe potentially **executable**, rather than merely documentation.

---

# 13. Recommended first implementation

For the first prototype, keep it deliberately small:

```text
AGENTS.md
    │
    └── tells agent how to work with SAF

MCP resource:
    saf://viewpoints/P1_PCXD
    │
    └── self-contained P1_PCXD definition

MCP resource:
    saf://recipes/P1_PCXD
    │
    └── Cameo-specific construction rules

MCP tools:
    find/get model elements
    create element
    apply stereotype
    create relationship
    create diagram
    add element to diagram

MCP tool:
    validate_saf_view
```

Do **not** start by implementing the entire SAF ontology.

Get one complete loop working:

```text
"Create a P1_PCXD view"
        ↓
retrieve viewpoint
        ↓
retrieve recipe
        ↓
inspect Cameo
        ↓
create/reuse semantic elements
        ↓
create BDD
        ↓
validate P1_PCXD
        ↓
PASS
```

Once this works reliably, the pattern can be generalized to the other SAF viewpoints.

---

## Core design principle

The resulting architecture should follow this rule:

> **Load the smallest amount of SAF knowledge necessary to perform the current task, but make that knowledge authoritative and semantically complete enough that the agent does not have to reconstruct SAF rules from generic SysML knowledge.**

In short:

```text
AGENTS.md
    = HOW to behave

SAF viewpoint resource
    = WHAT the viewpoint means

SAF recipe
    = HOW this implementation realizes it

Concept/stereotype resources
    = DEEPER DETAIL when needed

SAF MCP operations
    = SEMANTIC actions

CRUD tools
    = LOW-LEVEL primitives

Validator
    = AUTHORITATIVE correctness check
```
