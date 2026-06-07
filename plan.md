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

### Iteration 5: Transport Expansion (Pending)
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
