# SAF MCP Agent Architecture — Revised Approach

## Core recommendation

Do **not** duplicate the existing SAF specification API with MCP resources.

The `cameo-saf-mcp-server` already has a strong semantic SAF surface through the `spec_*` tools in `saf_spec_tools.groovy`.

Use that as the authoritative SAF knowledge layer.

The initial architecture should therefore be:

```text
AGENTS.md
    │
    │ agent behavior / navigation rules
    ▼
SAF spec_* tools
    │
    │ SAF semantics
    ▼
LLM reasoning / planning
    │
    │ model operations
    ▼
Cameo model tools
    │
    ▼
generic CRUD
    │
    ▼
Cameo
```

MCP resources remain available as a future mechanism, but should **not** initially duplicate SAF concepts, viewpoints or stereotypes.

---

# 1. Existing SAF specification surface

The current MCP server already provides a structured SAF knowledge API.

### Discovery

```text
spec_list_viewpoints
spec_list_concepts
spec_list_concerns
spec_list_stakeholders
spec_list_stereotypes
spec_search
```

Use these when the required SAF entity is not already known.

### Viewpoint lookup

```text
spec_get_viewpoint
spec_get_viewpoint_concepts
spec_get_viewpoint_concerns
```

These should be the normal entry point when implementing a known SAF viewpoint.

### Concept lookup

```text
spec_get_concept
spec_get_concept_stereotypes
```

Use these for semantic or implementation details that are not sufficiently clear from the viewpoint.

### Stereotype lookup

```text
spec_get_stereotype
spec_get_special_implementations
```

Use these when the exact Cameo/SysML realization needs to be understood.

---

# 2. Use progressive disclosure

The LLM should **not** load the entire SAF specification.

For a known viewpoint such as P1_PCXD:

```text
spec_get_viewpoint("P1_PCXD")
        │
        ▼
spec_get_viewpoint_concepts("P1_PCXD")
        │
        ▼
Agent has P1_PCXD semantic scope
        │
        ├── sufficient?
        │       │
        │       └── YES → continue
        │
        └── NO
              │
              ▼
        spec_get_concept(...)
              │
              ▼
        spec_get_concept_stereotypes(...)
              │
              ▼
        spec_get_stereotype(...)
```

The agent should retrieve only the additional SAF information it actually needs.

---

# 3. Do not create duplicate SAF resources

Avoid initially creating resources such as:

```text
saf://concepts/PhysicalSystem
saf://concepts/PhysicalUser
saf://concepts/PhysicalEnvironment
```

when the same information is already available through:

```text
spec_get_concept("PhysicalSystem")
spec_get_concept("PhysicalUser")
spec_get_concept("PhysicalEnvironment")
```

Otherwise the system would have two representations of the same authoritative information:

```text
SAF specification
      │
      ├── spec_* tools
      │
      └── MCP resources
```

This creates unnecessary duplication and potential synchronization problems.

---

# 4. `AGENTS.md` should describe agent behavior

`AGENTS.md` should not contain the entire SAF ontology.

Its purpose is to teach the LLM **how to navigate and use the SAF MCP surface**.

Example:

```md
## SAF modeling

When implementing a SAF viewpoint:

1. Identify the SAF viewpoint.
2. Call `spec_get_viewpoint`.
3. Call `spec_get_viewpoint_concepts`.
4. Treat the returned concepts and relationships as authoritative.
5. Drill into individual concepts only when additional semantic
   or implementation detail is required.
6. Use `spec_get_concept_stereotypes` to determine model-level
   realizations.
7. Use `spec_get_stereotype` or
   `spec_get_special_implementations` when necessary.
8. Inspect the existing Cameo model before creating elements.
9. Prefer reusing existing model elements over creating duplicates.
10. Create or modify the semantic model before creating the diagram.
11. Validate the resulting model and view against the viewpoint.
```

The important principle is:

> **AGENTS.md tells the agent how to use the SAF knowledge API; the SAF API contains the actual SAF knowledge.**

---

# 5. Separate SAF semantics from model manipulation

The architecture should have a clear separation:

```text
SAF specification
    │
    │ What does P1_PCXD mean?
    ▼
spec_* tools
    │
    ▼
LLM reasoning
    │
    │ What should I create?
    ▼
model/Cameo tools
    │
    ▼
CRUD
    │
    │ How do I actually manipulate Cameo?
    ▼
Cameo
```

The CRUD layer should **not** know what P1_PCXD means.

The SAF specification layer should **not** modify the model.

---

# 6. Keep the existing CRUD tools

The generic CRUD implementation in:

```text
scripts/element_crud.groovy
```

should remain.

Treat it as the low-level execution layer.

Conceptually:

```text
SAF semantics
     │
     ▼
Agent plan
     │
     ▼
Cameo operation
     │
     ▼
CRUD
     │
     ▼
Cameo API
```

The LLM should ideally reason in terms of SAF concepts:

```text
PhysicalSystem
PhysicalSOIRole
PhysicalContext
PhysicalUser
PhysicalEnvironment
```

rather than low-level operations such as:

```text
createElement(...)
applyStereotype(...)
createRelationship(...)
```

unless it actually needs to perform those operations directly.

---

# 7. Do not introduce a recipe abstraction immediately

The earlier proposal introduced:

```text
saf://recipes/P1_PCXD
```

as a possible construction recipe.

This is still a useful **concept**, but it should not automatically become a new MCP resource.

First test whether:

```text
AGENTS.md
+
spec_* tools
+
model/Cameo tools
+
CRUD
```

are already sufficient.

For example:

```text
User:
Create a P1_PCXD view for the Aircraft.

        ↓

Agent:
spec_get_viewpoint(P1_PCXD)

        ↓

spec_get_viewpoint_concepts(P1_PCXD)

        ↓

additional spec_* calls if required

        ↓

reason about required model structure

        ↓

inspect Cameo model

        ↓

create/reuse model elements

        ↓

create BDD

        ↓

validate
```

Only if the agent repeatedly struggles with the same construction procedure should a dedicated recipe abstraction be introduced.

---

# 8. Possible future recipe layer

If experimentation shows that the LLM needs procedural guidance beyond the SAF specification, introduce a higher-level construction layer.

Possible implementations, in increasing complexity:

### Option A — `AGENTS.md`

Add viewpoint-specific construction guidance.

### Option B — MCP tool

```text
saf_get_viewpoint_recipe("P1_PCXD")
```

### Option C — MCP resource

```text
saf://recipes/P1_PCXD
```

The recipe should **not duplicate SAF semantics**.

Instead, it should describe how the MCP/Cameo implementation realizes the semantics obtained from the `spec_*` API.

For example:

```yaml
viewpoint: P1_PCXD

workflow:
  - inspect_system_of_interest
  - identify_or_create_physical_context
  - identify_or_create_physical_soi_role
  - identify_context_elements
  - create_context_element_roles
  - establish_relationships
  - create_bdd
  - populate_bdd
  - validate
```

---

# 9. Why P1_PCXD is a good prototype

P1_PCXD is particularly useful because it requires the agent to understand the distinction between:

```text
Physical System
```

and:

```text
Physical SOI Role
Physical Context Element Role
```

The agent therefore cannot simply generate a visually plausible generic SysML diagram.

It needs to understand the SAF semantics and their Cameo realization.

The prototype should test whether the existing:

```text
spec_get_viewpoint
spec_get_viewpoint_concepts
spec_get_concept
spec_get_concept_stereotypes
spec_get_stereotype
```

surface provides enough information for the LLM to make those distinctions correctly.

---

# 10. Validation should be part of the workflow

Creating the diagram is not enough.

The workflow should be:

```text
INSPECT
   │
   ▼
UNDERSTAND SAF VIEWPOINT
   │
   ▼
PLAN
   │
   ▼
CREATE / REUSE MODEL ELEMENTS
   │
   ▼
CREATE RELATIONSHIPS
   │
   ▼
CREATE / UPDATE DIAGRAM
   │
   ▼
VALIDATE
   │
   ├── PASS → done
   │
   └── FAIL
        │
        ▼
       FIX
        │
        ▼
     VALIDATE
```

A future `validate_saf_view` tool would therefore be highly valuable.

---

# 11. Role of MCP resources

Resources are **not abandoned**.

They are simply not the first mechanism to use for SAF specification data.

Potential future uses include:

```text
saf://docs/...
saf://examples/...
saf://agent-guidance/...
saf://recipes/...
```

They become useful when there is information that:

* is large;
* is relatively static;
* does not fit naturally into the existing structured tools;
* should be retrieved on demand;
* should not be duplicated in `AGENTS.md`.

For the initial P1_PCXD prototype, however:

> **Use zero new resources.**

---

# 12. Recommended first experiment

Implement only the P1_PCXD agent guidance in `AGENTS.md`.

Then test a realistic request such as:

```text
Create a P1_PCXD Physical Context Definition View for the Aircraft
system.

The aircraft is operated by a Pilot, interacts with an Airport Ground
System, and operates within the physical environment of the Atmosphere.
```

Observe the agent's behavior.

Specifically evaluate whether it can:

1. Identify P1_PCXD.
2. Retrieve the viewpoint definition.
3. Retrieve the viewpoint concepts.
4. Understand the difference between classifiers and context roles.
5. Determine the required SAF stereotypes.
6. Inspect the existing Cameo model.
7. Reuse existing elements.
8. Create missing elements.
9. Create the correct relationships.
10. Create the BDD.
11. Validate the result.

If it succeeds, the existing MCP surface may already be sufficient.

If it fails repeatedly at a particular step, that failure tells us exactly what abstraction is missing.

---

# Final architecture

The recommended initial architecture is:

```text
                         AGENTS.md
                             │
                             │
                             ▼
                     ┌──────────────┐
                     │     LLM      │
                     │    Agent     │
                     └──────┬───────┘
                            │
                    SAF knowledge
                            │
                            ▼
                 ┌────────────────────┐
                 │    spec_* tools    │
                 │                    │
                 │ viewpoint          │
                 │ concepts           │
                 │ stereotypes        │
                 │ relationships      │
                 └─────────┬──────────┘
                           │
                    SAF understanding
                           │
                           ▼
                    Agent reasoning
                           │
                     model operations
                           │
                           ▼
                 ┌────────────────────┐
                 │ Cameo/model tools  │
                 └─────────┬──────────┘
                           │
                           ▼
                    Generic CRUD
                           │
                           ▼
                         Cameo
                           │
                           ▼
                      Validation
```

## Guiding principle

> **Do not duplicate knowledge that the MCP server already exposes well.**

Use:

```text
AGENTS.md
    = how the agent should behave

spec_* tools
    = authoritative SAF semantics

LLM
    = reasoning and planning

Cameo/model tools
    = model-level operations

CRUD
    = low-level implementation

Validation
    = correctness
```

Add recipes, higher-level SAF tools, or MCP resources **only when the P1_PCXD experiment demonstrates a concrete need for them**.
