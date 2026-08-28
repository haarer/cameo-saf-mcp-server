# Cameo SAF MCP Server Plugin Plan

## Overview
Implementation of an MCP (Model Context Protocol) server as a plugin within Cameo Systems Modeler, enabling AI agents to interact with Cameo models via tools, resources, and prompts over HTTP.

The MCP protocol is implemented in-house in plain Java (~410 lines) — no external MCP SDK dependency. This avoids Jackson classloader conflicts caused by the MCP SDK's Jackson 3 internals conflicting with Cameo's bundled Jackson 2.19.1.

## Origins

This project started as a fork of the `cameo-http-server` plugin (com.haarer.httpserver), an HTTP server plugin for Cameo with `@HttpEndpoint` annotation-based Groovy routing. That codebase had already gone through 3 iterations (basic HTTP infrastructure, Groovy script scanning with hot-reload, and Python integration tests). The existing plugin layer, Groovy classloader scanner, and HTTP transport were adapted and extended for the MCP protocol.

## Technical Approach
1. **Java Core**:
   - Plugin infrastructure for Cameo Systems Modeler.
   - In-house MCP JSON-RPC 2.0 protocol implementation using Cameo's Jackson.
   - Embedded HTTP server (`com.sun.net.httpserver.HttpServer`) on port 18750.
   - Groovy script engine integration for dynamic MCP feature registration.
2. **Transport**:
   - Streamable HTTP transport (single POST `/mcp` endpoint).
   - Session management via `Mcp-Session-Id` header.
   - `GET /` → health check endpoint.
3. **Dynamic MCP Features**:
   - Groovy scripts with `@McpTool`, `@McpResource`, `@McpPrompt` annotations.
   - `GroovyScriptScanner` compiles scripts and scans for annotations.
   - Hot-reload loop detects file changes and updates all sessions.

## Roadmap

### Iteration 1: Basic MCP Infrastructure (Completed)
- [x] Setup basic plugin structure (plugin.xml, build.gradle with MCP SDK).
- [x] Define `@McpTool`, `@McpResource`, `@McpPrompt` annotations.
- [x] Implement `GroovyScriptScanner` — compiles Groovy scripts, scans annotations, builds MCP feature specifications.
- [x] Implement HTTP transport — POST `/mcp` with session management.
- [x] Implement `CameoMcpServer` — manages session manager, protocol handler, transport, hot-reload.
- [x] Implement `CameoMcpServerPlugin` — plugin lifecycle (`init()`/`close()`).
- [x] Create Groovy scripts (echo tool, model info tool/resource, logging demo, hello prompt).
- [x] Build and deploy to Cameo.
- [x] Create Python integration test suite.

### Iteration 2: MCP SDK Removal and In-House Protocol (Completed)
- [x] Remove MCP SDK dependency (`io.modelcontextprotocol.sdk:mcp:1.1.3`) from build.
- [x] Create plain Java protocol records: `McpToolDefinition`, `McpResourceDefinition`, `McpPromptDefinition`.
- [x] Create `McpSession` with `Manager` for session lifecycle and tool/resource/prompt sync from scans.
- [x] Create `McpProtocolHandler` implementing JSON-RPC routing for all MCP methods.
- [x] Rewrite `GroovyScriptScanner` to return plain definition types.
- [x] Rewrite `CameoMcpServer` to manage `McpSession.Manager` and `McpProtocolHandler` directly.
- [x] Rewrite `StreamableMcpTransportProvider` to use `McpProtocolHandler` and shared `McpSession.Manager`.
- [x] Delete old transport classes that imported MCP SDK.
- [x] Fix Jackson 2 compatibility — use `mapper.getNodeFactory().numberNode()` instead of `JsonNodeFactory.instance.longNode()` (Jackson 3 API).
- [x] Fix `handlePromptsList` bug: `result.set("prompts", result)` → `result.set("prompts", promptsArray)`.
- [x] Fix session sync bug: new `initialize` sessions started with empty tool/resource/prompt lists because `reloadScripts()` ran in the constructor before any sessions existed. Fix: `McpSession.Manager` stores the latest `ScanResult` and auto-syncs on `create()`.
- [x] Build, deploy, verify compilation succeeds.
- [x] Integration test passes: tools/list returns echo and logging_demo, tools/call echo works.

### Iteration 3: MCP Schema Enhancement (Done — remaining items rejected)
- [x] Add `@McpToolArgument` annotation with typed JSON Schema generation (inputSchema with properties).
- [x] Add `@McpResourceTemplate` support for dynamic resource URIs — rejected. Tools cover all use cases; resource templates add no new capability.
- [x] Prompt argument completion — rejected. See `docs/adr/0006-skip-prompt-argument-completion.md`.

### Iteration 3b: JSON Output from Groovy Handlers (Completed)
- [x] In `GroovyScriptScanner`'s `ToolHandler`/`ResourceHandler`/`PromptHandler`, detect `Map`/`List`/`Collection` return values from the Groovy method and serialize them with the Jackson `ObjectMapper` instead of calling `.toString()`. This eliminates the need for Groovy scripts to bundle their own JSON serialization (and avoids the missing `groovy-json` module problem in Cameo's bundled Groovy).
- [x] Updated `model_info.groovy` and `model_query.groovy` to return native `Map`/`List` objects instead of manually concatenated JSON strings.


### Iteration 3c: SAF Spec Ontology Tools (Completed)
- [x] Build Java `SafDataStore` with typed records, cross-reference indexes, and hot-reload.
  - Load 9+ JSON files (viewpoints, concepts, concerns, stakeholders, rationales, exposes, stereotypes, realizeconcept, special-implementations, domains, aspects).
  - Bidirectional cross-reference resolution at load time.
  - Hot-reload via polling `file.lastModified()` every 2s, atomic swap of index.
  - `SafDataStore.getInstance()` static accessor visible to all Groovy classloaders.
  - `getCameoElement(guid)` for SAF spec-model development.
- [x] Add `scripts/saf_spec_tools.groovy` with 15 `spec_*` tools:
  - `spec_list_viewpoints`, `spec_list_concepts`, `spec_list_concerns`, `spec_list_stakeholders`, `spec_list_stereotypes`
  - `spec_search` (fuzzy cross-entity search)
  - `spec_get_viewpoint`, `spec_get_viewpoint_concepts`, `spec_get_viewpoint_concerns`
  - `spec_get_concept`, `spec_get_concept_stereotypes`
  - `spec_get_concern`, `spec_get_stakeholder`, `spec_get_stereotype`
  - `spec_get_special_implementations`
- [x] Migrate `SafTools.groovy` static maps to derive from `SafDataStore`.
- [x] See `docs/adr/0008-java-saf-data-store-with-hot-reload.md`.

### Iteration 4: SAF Viewpoint Awareness (Completed — fixed in follow-up per ADR-0010)
- [x] Migrate `SafTools.groovy` static maps to derive from `SafDataStore`.
- [ ] **Follow-up:** Fix `saf_get_viewpoint_views`:
  - Replace hardcoded `getKindsForViewpoint()` with `SafDataStore`-derived mapping.
  - Fix diagram content traversal: use `PresentationElement.getModelElement()` instead of `getOwnedElement()`.
  - See `docs/adr/0010-revise-viewpoint-tools-approach.md`.

### Iteration 5: MCP Agent Efficiency Improvements

Reduce wasted tool calls by >50% in typical exploration sessions. See `.scratch/mcp-agent-efficiency/PRD.md`.

- [x] **Phase 1: Tool Description Improvements** — Rewrite `@McpTool` descriptions with expected value formats (`SAF_*` stereotype prefix, not concept-kind names), case-insensitivity note, variant disambiguation (`cameo_*` vs `cameo_saf_*`), and constrained parameter valid values. Affected: `find_elements`, `find_elements_by_type`, `saf_find_elements_by_type`, `saf_query_viewpoint`, `saf_get_viewpoint_views`, `saf_check_consistency`.
- [x] **Phase 1b: Structured Error Responses** — Tools returning empty results or errors include a hint about what to try instead and example valid inputs (e.g., `saf_get_viewpoint_views` unsupported code → suggest `spec_get_viewpoint`).
- [x] **Phase 2: Batch Operations** — Add `get_elements_details_batch(ids[])`, `list_owned_elements(parentId, depth?)`, and `get_port_type_info(portId)` to eliminate N+1 drill-down.
- [x] **Phase 3: Stereotype Catalog** — Add `list_model_stereotypes()` tool returning all stereotype names applied in the open model, grouped by prefix.
- [ ] **Phase 4: Query Router** — Add `query_router(question)` tool that maps natural-language questions to concrete tool call sequences with pre-filled element IDs, with fallback to direct tool usage.
- [ ] **Phase 5: Structured Resources** — Add `cameo://model/stereotype-guide` and `cameo://model/tool-guide` MCP resources for upfront reference data without tool calls.

### Iteration 6: Structural Modeling — Typed Parts, Type-Setting, Structure Read (Completed)

Goal: close the structural-modeling gap surfaced while modeling the Lawnbot physical hardware. The interface could create Classes/Ports/Parts and SAF decomposition relationships, but had **no way to set a Property's type**, **no way to create a block-owned typed part property**, and **no way to read a block's internal structure** (parts, ports, connectors) needed to reconstruct an IBD. SAF `create_relationship(type=composition)` produced package-level association ends, not block-owned internal parts.

Derived from `docs/mcp-surface-review.md` (Tier 1 of the capability proposal).

- [x] #1 `set_type(elementId, typeId)` — set the `type` of any TypedElement (Property, Port, ProxyPort, etc.). The missing primitive that unblocks port and part typing.
- [x] #2 `create_part(wholeBlockId, name, partTypeBlockId, multiplicity)` — one-call "add typed internal part": create a Property owned by the block, set its type, apply `PartProperty` + optional SAF role stereotype, optional multiplicity.
- [x] #3 `get_block_structure(blockId)` — read the internal structure an IBD needs: owned parts (name, type, multiplicity, stereotype), owned ports (name, type/interface), and connectors with end roles. Single-call replacement for N+1 drill-down.
- [x] #4 Composition fix — `create_part` (above) is the canonical path for block-owned decomposition. It creates a typed part property directly on the whole's internal structure (vs. SAF `create_relationship(type=composition)`, which only produces package-level association ends). Note: for SysML decomposition the part should use `aggregation=composite` so Cameo applies `PartProperty`; non-composite parts are typed as `ReferenceProperty`. Documented in the `create_part` tool description.
- [x] #5 `create_connector(wholeBlockId, end1PartId, end1PortId, end2PartId, end2PortId, name?, end1Multiplicity?, end2Multiplicity?)` — create a properly-wired internal-structure Connector owned by a whole Block, connecting two part ports (the representation an IBD renders). Each ConnectorEnd is explicitly created with its `role` (the port on the part's type) and `partWithPort` (the part property of the whole), its multiplicity is set (default `1`), and the connector is validated (each part's type must own its port, the part must be owned by the whole) and added to the whole block's `ownedConnectors`.
  - **Nested-end semantics (verified against a hand-made Cameo reference)**: each ConnectorEnd is tagged with the SysML **`NestedConnectorEnd`** stereotype, which records the path to the end's part within the whole in its **`propertyPath`** tagged value — set to `[part]` (the part property of the whole). This is what makes the connector appear when the IBD does "Display All Paths" (the canonical nested-end representation the Cameo GUI produces).
  - **Read-side fix (empirically corrected)**: a connector's ends were previously hidden because `list_owned_elements`/`collectOwned` filtered owned children by name — it only admitted `NamedElement`s plus a hardcoded `Comment`/`ConnectorEnd` allow-list. But ConnectorEnds (like Comments and many value-specification kinds) are **unnamed** elements that have no `getName()` and are not `NamedElement`. `getOwnedElement()` *does* return them (verified: a connector's `getOwnedElement()` returns its 2 `ConnectorEndImpl`s); they were dropped by the name filter, not by the traversal. The traversal no longer filters by name — **every owned child is surfaced** (name is `""` when an element has none), and an optional `filterType` argument lets callers restrict by type/kind. Connector ends now report `["NestedConnectorEnd"]` like the reference.
- [x] #6 Applied to the live Lawnbot model: replaced the 6 placeholder connectors (5 unnamed + 1 stray "test") with 5 named, properly-wired connectors (`csi`, `i2c`, `motor`, `power`, `power5v`), each end multiplicity `1`; verification confirmed every end carries `NestedConnectorEnd` with `propertyPath=[part]`, matching the reference. Model persisted via `admin_save_model`.

Follow-ups (Tier 2+ in `docs/mcp-surface-review.md`): diagrams, port/part detail enrichment (incl. exposing `propertyPath`/interface on the read side), multiplicity/aggregation/navigability setters, offset/limit batching, `get_connectors(blockId)` (currently folded into `get_block_structure`), explicit `NestedConnectorEnd`/`propertyPath` surfaced by `get_port_type_info` / `get_block_structure`.

### Iteration 7: Transport Expansion (Pending)
- [ ] Add SSE transport option for server-initiated notifications.
- [ ] Add WebSocket transport option.
- [ ] Add `notifications/initialized` and tool list change notifications.

## Lessons Learned

### Jackson Classloader Conflict
The root cause of the MCP SDK conflict:
- `McpAsyncServer.toolsCallRequestHandler` calls `JacksonMcpJsonMapper.convertValue()`.
- This triggers `BasicSerializerFactory.buildMapSerializer` to reflectively access `JsonFormat$Shape.POJO`.
- The reflective access uses the `com.fasterxml.jackson.annotation` class from Cameo's bundled Jackson 2.19.1.
- Jackson 2.19.1's `JsonFormat$Shape` enum does NOT have the `POJO` field — it was added in Jackson 3.
- Result: `java.lang.NoSuchFieldError`.

Fix: Rip out MCP SDK entirely. Use Cameo's `ObjectMapper` for simple `readTree()`/`writeValueAsString()` operations that don't trigger serializer factory reflection on annotations. Use `mapper.getNodeFactory().numberNode()` for numeric JSON nodes.
