# Reverse Engineering: MCP Server → Partial SAF Model

This showcase reconstructs a partial SAF (Semantic Architect Framework) model of the MCP server's 74 Groovy tool scripts, by reverse-engineering the server surface into Cameo MagicDraw.

## Model

- **File:** `McpServerSAFA.mdzip`
- **Profile:** `SAF_Profile` (from `/workspace/MSOSA2024xRef3/profiles/SAF_Profile.mdzip`)

## What was built

A three-level partial architecture inside one model:

| Level | SAF kind | Elements |
|-------|----------|----------|
| Physical | `SAF_PhysicalSystem` (MCP Server) + `SAF_PhysicalSoftware` | composite with 19 software script blocks |
| Behavioral | Operation on each script block | 74 operations, 168 typed parameters (MCP data types) |
| Conceptual | `SAF_ConceptualSystem` (10 systems) + System Functions (`SAF_Function`, Activity) | 74 functions grouping the tools |
| Traceability | `Refine` (Abstraction) | 74 links: physical script block → conceptual function |

### Conceptual systems

1. Model Administration System
2. Model Navigation and Search System
3. Element Authoring System
4. SAF Architecture Modeling System
5. SAF Specification System
6. SAF Query and Traceability System
7. Diagram and View Management System
8. Code Authoring and Validation System
9. Model Diff System
10. Diagnostics and Utility System

### Physical software blocks (19)

`admin_bridge`, `element_crud`, `export_diagram`, `echo`, `diff_tool`, `model_find`, `model_info`, `model_query`, `modelcode`, `plugincode`, `saf_spec_tools`, `saf_tools`, `structural_tools`, `validation_introspect`, `logging_demo`, `context_resources`, `hello_prompt`, `saf_views`, `tool_contract_resources`.

18 of the 19 blocks carry operations; the four without operations (`context_resources`, `hello_prompt`, `saf_views`, `tool_contract_resources`) are structural only.

## Model decisions & deviations

- **System Functions are `SAF_Function` Activities.** The SAF profile has no `SAF_ConceptualFunction`, so `system_function` maps to Activity + `SAF_Function` — not a Class as originally planned.
- **Refine links use generic `Refine` Abstraction.** `saf_create_relationship(type:"refine")` crashes in the SAF tools (`No such property: stereotypeToApply for class: SafTools`); the fallback `create_relationship(type:"abstraction", stereotype:"Refine")` is used, source = script block, target = function.
- **`SAF_ConceptualSystem` is ambiguous** — it also realizes *Conceptual External System*; the 10 systems resolve uniquely in context.

## Navigation

- Physical: `Physical Domain` → MCP Server composite structure (19 parts) and blocks with operations/parameters.
- Conceptual: `Conceptual Domain` → 10 system classes, each owning up to 15 `SAF_Function` activities.
- Traceability: 74 `Refine` abstractions under `Physical Domain` linking script blocks to functions.

## Limitations

Observations from inspecting the model, with the modeler's reply.

### 1. Traceability chain is incomplete

**Observation:** The traceability chain from system functions down to physical elements is not complete.

**Reply:** Agreed — the biggest gap. Only block→function `Refine` links were drawn. The chain stops at the physical *block* level and never reaches the *operations*, which are what actually correspond to MCP endpoints. The precise trace would be: conceptual function → `Refine` → the specific operation, with the block as container. Additionally, the four structural-only blocks have no function links, and nothing distinguishes the surface kinds.

### 2. Conceptual functions are too granular

**Observation:** The conceptual functions should be merged into more abstract ones (partly the user's own fault for not stressing this point enough).

**Reply:** Agreed, partly earned. 74 functions mirroring 74 tools is transliteration, not abstraction; a conceptual layer should have ~10–15 abstract functions (e.g. "Manage model lifecycle", "Author elements", "Query SAF semantics"). The 1:1 mapping was the simpler faithful read; the script-level mapping is a useful audit trail but was treated as the architecture instead of the input to one. Clean fix: collapse functions to an abstract set and keep the precise per-tool trace in the operation→function `Refine` links.

### 3. Operations have no return / out parameters

**Observation:** The operations do not have return or out parameters, but the MCP surface has them.

**Reply:** Correct. The MCP surface is bidirectional: every `@McpTool` returns a structured `Map`, and resources yield typed content (JSON). The model has 168 typed in-parameters but no ownedParameter with `direction = return/out` — a real fidelity gap. Addable via a `McpResult` ValueType as each operation's return parameter.

### 4. Tool and resource operations are indistinguishable

**Observation:** It is not possible to distinguish resource operations from tool operations.

**Reply:** Correct. Confirmed in the scripts: `@McpResource` handlers (`cameo://element/{id}` in element_crud, five in model_info, plus saf_views, context_resources, tool_contract_resources) and the `@McpPrompt` handler (hello_prompt) are modeled as plain Operations identical to `@McpTool`s. Nothing distinguishes surface kind — no stereotype, tag, or naming convention.

### Root cause

Modeled the server's internal method surface faithfully but skipped the MCP protocol contract (result payloads, surface-kind taxonomy) and the conceptual abstraction step. The script-level mapping was necessary as an audit trail but was treated as the architecture instead of the input to it.