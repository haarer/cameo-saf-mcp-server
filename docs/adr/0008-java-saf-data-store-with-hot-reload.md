# ADR-0008: Java SafDataStore with Hot-Reload for Shared SAF Spec Data

## Status

Accepted

## Context

The MCP server needs to expose tools that explain the SAF specification to LLMs — concepts, viewpoints, concerns, stakeholders, stereotypes, and their relationships. These tools require loading 9+ JSON files from the SAF spec (`_data/`).

The server will eventually expose three groups of tools. A clear naming convention is needed so LLMs can immediately distinguish them by name alone:

### Tool naming convention

| Group | Prefix | Domain | Examples |
|---|---|---|---|
| **Generic UML/SysML** | *(no prefix)* | Plain Cameo model operations, no SAF involvement | `get_model_info`, `find_elements`, `create_element`, `delete_element` |
| **SAF Spec** | `spec_` | Explains the SAF ontology — what concepts, viewpoints, stereotypes mean; guides the LLM on how to model | `spec_get_viewpoint`, `spec_list_concepts`, `spec_get_stakeholder` |
| **SAF Model** | `saf_` | Operates on the loaded SysML model with an applied SAF profile — what is modeled, create/query/check in the model | `saf_find_elements_by_type`, `saf_create_element`, `saf_check_consistency` |

Every `@McpTool(description=...)` starts with the group tag (e.g. `"SAF Spec: Get detailed info about a viewpoint..."`) so `tools/list` responses self-classify for the LLM.

### Classloader isolation problem

Each `.groovy` script file is compiled by its own `GroovyClassLoader` (child of the Java application classloader). Classes defined in one Groovy file are invisible to all others. Currently `SafTools` loads JSON data (`concepts.json`, `realizeconcept.json`, `viewpoints.json`) into its own static maps, but those maps are unreachable from any other Groovy script. This forces every script that needs spec data to load it independently.

### Tool architecture pattern

The Python MCP server (`saf-specification/tools/`) implements a `list_*` + `search` + `get_*` pattern with 15 tools. This is the recommended pattern for agentic work:

- `list_*` — broad exploration (returns summaries, the LLM picks what to drill into)
- `search` — fuzzy cross-entity discovery (one call finds matches across all types)
- `get_*` — focused drill-down (returns full detail for one entity)

Separate tools are more token-efficient than combined tools: the LLM pays for only what it needs. A combined viewpoint tool would return concepts + concerns + stakeholders + metadata on every call. Three separate tools (`get_viewpoint`, `get_viewpoint_concepts`, `get_viewpoint_concerns`) let the agent chain only as far as needed. This is consistent with ADR-0007's composable tools philosophy.

### SAF spec model query

The SAF specification itself is maintained as a Cameo model. The JSON files in `_data/` are generated from that model. A SAF spec developer working on this model needs to jump from a JSON record to its source Cameo element by GUID. This is a separate concern from querying user models.

Key requirements:

1. **Shared access** — all Groovy scripts (existing and new) must read from the same in-memory data store.
2. **Hot-reloadable content** — the SAF spec is actively developed. JSON file content changes frequently. A full Cameo restart for every spec update is unacceptable.
3. **Stable JSON schema** — the structure of the JSON files is stable; schema changes are rare and a restart is acceptable when they occur.
4. **Minimal duplication** — `SafTools` should derive its maps from the shared store, not load JSON independently.

## Decision

### 1. Java SafDataStore with hot-reload

Implement a Java `SafDataStore` class with:

1. **Typed records** matching the SAF spec data model (Viewpoint, Concept, Concern, Stakeholder, Rationale, Expose, Stereotype, RealizeConcept, SpecialImplementation, Domain, Aspect).
2. **Cold load at startup** — all JSON files parsed into records, cross-reference indexes built.
3. **Hot-reload thread** — polls `file.lastModified()` on all JSON files every 2 seconds. On change: re-read, re-parse, rebuild indexes.
4. **Atomic swap** — an `AtomicReference<DataIndex>` holds the current index. Hot-reload writes a new index and swaps. Groovy reads are always consistent.
5. **`SafDataStore.getInstance()` static accessor** — Java statics are visible across all `GroovyClassLoader` instances, so any Groovy script can reach the store.
6. **`SafTools` derives from it** — the static maps (CONCEPT_MAP, STEREO_TO_KIND, KIND_TO_DOMAIN) are populated from `SafDataStore` instead of loading JSON directly.

This is the same hot-reload pattern used by `GroovyScriptScanner` (polling interval, file timestamp tracking).

### 2. Bidirectional cross-reference resolution

`SafDataStore` resolves all foreign-key relationships in both directions at load time, even when the JSON files only link in one direction. For example:

- A Concept references Viewpoints via `InViewpoint[]` → the store also indexes "which concepts does this viewpoint expose"
- A Viewpoint references Concerns via `Concern[]` (GUIDs) → the store also indexes "which viewpoints frame this concern"
- A RealizeConcept maps Concept → Stereotype → the store indexes both directions

This means any Groovy tool can traverse the graph in either direction with a single lookup.

### 3. SAF spec model query (`getCameoElement`)

`SafDataStore` exposes a method to resolve a SAF spec GUID to its source Cameo element in the live model:

```java
Element getCameoElement(String safGuid)
```

This is only useful when the SAF spec model itself is open in Cameo (the model from which the JSON files were generated). It returns `null` otherwise. This is a spec-development aid — not for user-model queries.

### 4. Tool set: 15 `spec_*` tools

All SAF spec explanation tools use the `spec_` prefix and live in a single file `scripts/saf_spec_tools.groovy`. Full tool list:

| Tool | Returns |
|---|---|
| `spec_list_viewpoints` | All viewpoints, optional filter by domain/aspect/maturity |
| `spec_list_concepts` | All concepts (name, type, ID) |
| `spec_list_concerns` | All concerns (name, category, ID) |
| `spec_list_stakeholders` | All stakeholders (name, ID) |
| `spec_list_stereotypes` | All stereotypes (name, ID, realized concepts) |
| `spec_search` | Fuzzy name search across all entity types |
| `spec_get_viewpoint` | Viewpoint metadata + exposed concept names + concern questions + dependencies |
| `spec_get_viewpoint_concepts` | Full resolved concepts for a viewpoint (inheritance, relationships) |
| `spec_get_viewpoint_concerns` | Concerns with stakeholder rationales for a viewpoint |
| `spec_get_concept` | Concept with inheritance hierarchy, relationships with multiplicities, viewpoint exposure |
| `spec_get_concept_stereotypes` | All stereotypes that realize a concept (direct + indirect UML metaclass mappings) |
| `spec_get_concern` | Concern question + owner + which viewpoints address it |
| `spec_get_stakeholder` | Stakeholder profile + all concerns with rationales |
| `spec_get_stereotype` | Stereotype details + realized concepts + special implementations |
| `spec_get_special_implementations` | UML metaclass → SAF-stereotype mapping, optional stereotype filter |

### 5. Sequencing (Option A)

Three steps in this iteration:

1. **Build `SafDataStore`** (Java + hot-reload) — the foundational layer
2. **Add `scripts/saf_spec_tools.groovy`** with the 15 `spec_*` tools
3. *(later iteration)* Migrate `SafTools.groovy` static maps to derive from `SafDataStore`

This avoids a transitional period where both are loading the same JSON files.

### 6. Tool naming convention

Three tool groups with distinct prefixes as documented in the Context section above. All new SAF spec tools use the `spec_` prefix and are placed in `scripts/saf_spec_tools.groovy`. The `@McpTool(description=...)` of every tool starts with the group tag (`"SAF Spec:"`, `"SAF Model:"`, or omitted for generic SysML).

## Consequences

1. **Added:** `src/com/haarer/saf/mcpserver/data/SafDataStore.java` with typed records and hot-reload.
2. **Added:** Hot-reload thread managed by `CameoMcpServer` (parallel to the Groovy script hot-reload).
3. **Changed:** `SafTools.groovy` static initializer reads from `SafDataStore` instead of loading JSON with its own `ObjectMapper`.
4. **JSON file changes** → `SafDataStore` picks them up, swaps data → all Groovy tools see new data without any Groovy recompile.
5. **Groovy script changes** → `GroovyScriptScanner` recompiles → `SafTools` static init re-runs against latest `SafDataStore`.
6. A restart is only needed if the Java `SafDataStore` class itself changes (rare — only with JSON schema changes).
