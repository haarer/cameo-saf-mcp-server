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

### Iteration 3: MCP Schema Enhancement (Done, except prompt completion)
- [x] Add `@McpToolArgument` annotation with typed JSON Schema generation (inputSchema with properties).
- [ ] Add `@McpResourceTemplate` support for dynamic resource URIs.
- [x] Prompt argument completion — rejected. See `docs/adr/0006-skip-prompt-argument-completion.md`.

### Iteration 3b: JSON Output from Groovy Handlers (Completed)
- [x] In `GroovyScriptScanner`'s `ToolHandler`/`ResourceHandler`/`PromptHandler`, detect `Map`/`List`/`Collection` return values from the Groovy method and serialize them with the Jackson `ObjectMapper` instead of calling `.toString()`. This eliminates the need for Groovy scripts to bundle their own JSON serialization (and avoids the missing `groovy-json` module problem in Cameo's bundled Groovy).
- [x] Updated `model_info.groovy` and `model_query.groovy` to return native `Map`/`List` objects instead of manually concatenated JSON strings.


### Iteration 4: Transport Expansion (Pending)
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
