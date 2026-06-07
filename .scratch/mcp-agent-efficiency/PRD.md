# PRD: MCP Agent Efficiency Improvements

## Problem Statement

During a live session with the FFDS model, the agent made ~60+ tool calls to answer 4 questions. ~50% of those calls were redundant, returned empty results, or produced no useful data. The root causes fall into two categories:

1. **Agent guidance** (~60%): Tool descriptions don't convey expected value formats, common pitfalls, or when to use `cameo_*` vs `cameo_saf_*` variants. The agent doesn't learn from discovered patterns (e.g., `SAF_*` stereotype prefix).
2. **Tool design** (~40%): No batch operations, incomplete data exposure on certain element types, and cryptic error responses.

## Goals

- Reduce wasted tool calls by >50% in typical exploration sessions
- Enable the agent to operate at two levels:
  - **Abstract**: "In which contexts does FFDS appear?" → answered from cached metadata or a single query
  - **Concrete**: "Which classes stereotyped `SAF_ConceptualContext` have a role stereotyped `SAF_ConceptualContextRole_SoI` with type named FFDS?" → answered via precise model navigation
- Provide structured guidance so the agent self-corrects without trial-and-error

## Non-Goals

- Adding new Cameo API capabilities (this is about MCP tool surface, not model access)
- Modifying the MCP protocol implementation
- Changing hot-reload or transport behavior

---

## Phase 1: Tool Description Improvements (Agent Guidance)

**Effort**: Low — edit existing Groovy script annotations
**Impact**: High — addresses ~60% of wasted calls

### 1a. Stereotype naming convention

Every tool that accepts a `stereotype` parameter must include in its description:

- Expected format: `"SAF_ConceptualContext"` (full stereotype name with prefix), NOT `"conceptual_context"` (SAF concept kind)
- Case-insensitive: "This parameter is case-insensitive — don't retry with different casing"
- Discovery hint: "Use `spec_list_stereotypes` to see all available stereotype names"

**Affected tools** (in `scripts/model_find.groovy`, `scripts/element_crud.groovy`, `scripts/saf_tools.groovy`):
- `find_elements` (stereotype param)
- `find_elements_by_type` (stereotype param)
- `saf_find_elements_by_type` (stereotype param)
- `saf_query_viewpoint` (domain/aspect params)

### 1b. Variant disambiguation

Add a note to `cameo_*` vs `cameo_saf_*` tool descriptions:

- `cameo_*` tools: raw SysML operations, no SAF enrichment
- `cameo_saf_*` tools: SAF-enriched results (include `safKind`, `safDomain`, tagged values)
- When querying SAF models, prefer `cameo_saf_*` variants

### 1c. Parameter constraints

Tools with constrained parameters must list valid values in their description:

- `saf_get_viewpoint_views`: `viewpointCode` accepts only `"AM"`, `"OV"`, `"CV"`, `"PV"`. For sub-viewpoints (e.g., `C1_SCXD`), use `spec_get_viewpoint` instead.
- `saf_check_consistency`: `checks` accepts `["orphan_requirements", "broken_chains", "stereotype_compliance", "cross_domain_alignment"]`

### 1d. Error response enrichment

Tool handlers that return empty results or errors should include:

- A hint about what to try instead
- Example valid inputs

For example, if `saf_get_viewpoint_views` receives an unsupported viewpoint code, return:

```json
{
  "error": "Only domain codes AM/OV/CV/PV are supported. For specific viewpoints (e.g., C1_SCXD), use spec_get_viewpoint instead.",
  "suggestion": "cameo_spec_get_viewpoint(name='C1_SCXD')"
}
```

---

## Phase 2: Batch Operations (Tool Design)

**Effort**: Medium — new Groovy tools
**Impact**: High — eliminates N+1 drill-down

### 2a. `get_elements_details_batch`

New tool in `scripts/model_query.groovy`:

```groovy
@McpTool(name="get_elements_details_batch", description="""
Get detailed info for multiple elements in a single call.
Pass an array of element IDs. Returns a list of element details.
Use this instead of calling get_element_details multiple times.
""")
def getElementsDetailsBatch(List<String> elementIds) {
  // ... implementation
}
```

### 2b. `list_owned_elements`

New tool in `scripts/model_find.groovy`:

```groovy
@McpTool(name="list_owned_elements", description="""
List all direct children of a parent element with their names, types, and stereotypes.
Returns the full child list so you can decide which elements to drill into.
Use this before calling get_element_details on individual children.
""")
def listOwnedElements(String parentId, int depth = 1) {
  // ... implementation
}
```

### 2c. `get_port_type_info`

New tool in `scripts/model_query.groovy`:

```groovy
@McpTool(name="get_port_type_info", description="""
Get the interface definition that types a proxy port.
Returns the port name, the interface definition name and ID, and the connector it participates in.
Use this to discover which interface connects which context elements.
""")
def getPortTypeInfo(String portId) {
  // ... implementation
}
```

---

## Phase 3: Stereotype Catalog (Discovery)

**Effort**: Low — one new tool
**Impact**: Medium — eliminates trial-and-error stereotype discovery

### 3a. `list_model_stereotypes`

New tool in `scripts/model_query.groovy`:

```groovy
@McpTool(name="list_model_stereotypes", description="""
Return all stereotype names currently applied in the open model,
grouped by prefix (SAF_, HyperlinkOwner, etc.).
Use this to discover valid stereotype names before searching.
""")
def listModelStereotypes() {
  // ... implementation
}
```

This gives the agent the complete stereotype namespace in one call, so it doesn't need to guess `SAF_*` prefix through trial and error.

---

## Phase 4: Query Router (Dual-Level Enabler)

**Effort**: High — new Groovy tool with semantic routing logic
**Impact**: Highest — bridges abstract and concrete queries

### 4a. `query_router`

New tool in `scripts/model_query.groovy`:

```groovy
@McpTool(name="query_router", description="""
Route a natural-language question about the model to the appropriate tools.
Pass your question in natural language. The router returns either:
  (a) a direct answer from cached metadata, or
  (b) a suggested sequence of tool calls with element IDs pre-filled.

Examples:
  'In which contexts does FFDS appear?' → returns list of contexts
  'Which classes have stereotype SAF_ConceptualContext?' → returns tool call sequence
""")
def queryRouter(String question) {
  // Parse question keywords
  // Match against known query patterns
  // Return structured response with either:
  //   - answer: direct text response, or
  //   - plan: list of {tool, arguments} objects
}
```

### 4b. Query pattern library

The router maintains a small pattern library that maps common question structures to tool call sequences:

| Pattern | Example | Tool Sequence |
|---|---|---|
| "in which contexts" | "In which contexts does FFDS appear?" | `find_elements(name='FFDS', stereotype='SAF_ConceptualContext')` |
| "which classes have stereotype" | "Which classes have stereotype SAF_ConceptualContext?" | `find_elements_by_type(stereotype='SAF_ConceptualContext')` |
| "what interfaces connect" | "What interfaces connect FFDS to Fire Department?" | `find_elements(name='FFDS', stereotype='SAF_ConceptualInterfaceDefinition')` → `get_elements_details_batch(...)` |
| "what flows on" | "What exchanges flow on EIF Fire?" | `find_elements(name='EIF Fire')` → `list_owned_elements(parentId)` |
| "list all" | "List all external systems" | `find_elements_by_type(stereotype='SAF_ConceptualSystem')` |

### 4c. Fallback behavior

If the router can't match a pattern, it returns a suggestion:

```json
{
  "matched": false,
  "suggestion": "Try rephrasing your question to match one of these patterns: 'in which contexts', 'which classes have stereotype', 'what interfaces connect', 'list all', 'what flows on'",
  "fallback": "Use find_elements or find_elements_by_type directly with the stereotype or name you're interested in."
}
```

---

## Phase 5: Structured Resources (Reference Data)

**Effort**: Low — new MCP resource
**Impact**: Medium — provides upfront context without tool calls

### 5a. `cameo://model/stereotype-guide`

New resource in `scripts/model_info.groovy`:

```groovy
@McpResource(
  uri = "cameo://model/stereotype-guide",
  name = "Stereotype Guide",
  description = "Guide to stereotype naming conventions and valid prefixes in the current model",
  mimeType = "application/json"
)
```

Returns a JSON document with:
- All stereotype prefixes found in the model
- Which tools accept which parameter types
- Common pitfalls and examples

### 5b. `cameo://model/tool-guide`

New resource in `scripts/model_info.groovy`:

```groovy
@McpResource(
  uri = "cameo://model/tool-guide",
  name = "Tool Usage Guide",
  description = "Guide to choosing the right tool for common model queries",
  mimeType = "application/json"
)
```

Returns a decision tree:
- "Query SAF metadata" → use `cameo_saf_*` tools
- "Query raw SysML" → use `cameo_*` tools
- "Explore structure" → start with `list_owned_elements`
- "Search by name" → use `find_elements`
- "Search by stereotype" → use `find_elements_by_type` or `saf_find_elements_by_type`

---

## Implementation Order

1. **Phase 1** (tool descriptions) — immediate impact, no new code
2. **Phase 3** (stereotype catalog) — one new tool, quick win
3. **Phase 2** (batch operations) — moderate effort, high ROI
4. **Phase 5** (structured resources) — low effort, good for persistent context
5. **Phase 4** (query router) — highest impact but most complex; build last

## Success Metrics

- Wasted tool calls reduced by >50% in a typical 4-question session
- Agent completes the same FFDS exploration in <30 tool calls (down from ~60)
- Agent self-corrects stereotype naming errors on first attempt (via tool description hints)
- Agent uses batch operations for multi-element queries

## Risks

- **Token budget**: Longer tool descriptions increase per-call token usage. Mitigation: keep descriptions concise, use examples sparingly.
- **Query router accuracy**: Pattern matching may miss edge cases. Mitigation: always provide a fallback path to direct tool usage.
- **Cameo API availability**: Batch operations depend on Cameo Open API supporting batch queries. Mitigation: implement as a loop in Groovy if needed (still saves MCP round-trips).
