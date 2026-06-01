# Cameo MCP Server Plugin

A lightweight MCP (Model Context Protocol) server integrated as a plugin within CATIA Magic / Cameo Systems Modeler. Enables AI agents to interact with Cameo models via tools, resources, and prompts over HTTP.

The MCP protocol is implemented entirely in-house (~980 lines of hand-written Java) — no external MCP SDK dependency. This avoids Jackson classloader conflicts with Cameo's bundled Jackson 2.19.1.

## Capabilities

### Current Version (v1.0.0)
- **MCP Protocol**: Full implementation of JSON-RPC 2.0 MCP methods — `initialize`, `tools/list`, `tools/call`, `resources/list`, `resources/read`, `prompts/list`, `prompts/get`, `ping`.
- **Dynamic Groovy Handlers**: Define tools, resources, and prompts in Groovy scripts using `@McpTool`, `@McpResource`, `@McpPrompt` annotations — no restart needed.
- **Hot Reloading**: Groovy scripts are monitored every 2 seconds and reloaded automatically on change.
- **Streamable HTTP Transport**: Single POST endpoint (`/mcp`) with `Mcp-Session-Id` header for session management.
- **Health Endpoint**: `GET /` returns server status and active session count.
- **Configurable Port**: Set via system property `cameo.mcp.server.port` (default `18750`).
- **Configurable Scripts Directory**: Set via system property `cameo.mcp.server.scripts.dir` (defaults to `scripts/` subdirectory of plugin installation).

## Internal Architecture

### Component Overview

1. **Plugin Layer (`CameoMcpServerPlugin`)**:
   - Extends Cameo's `Plugin` class.
   - Manages lifecycle (`init()` / `close()`).

2. **Server Layer (`CameoMcpServer`)**:
   - Owns `McpSession.Manager`, `McpProtocolHandler`, `StreamableMcpTransportProvider`, and the hot-reload loop.
   - `CameoMcpServer` -> `StreamableMcpTransportProvider` (HTTP on port 18750).
   - `CameoMcpServer` -> `GroovyScriptScanner` (hot-reload every 2s).

3. **Protocol Layer (`protocol/`)**:
   - **`McpProtocolHandler`**: Routes JSON-RPC methods. Builds responses using Cameo's `ObjectMapper` — no `convertValue()`, no serializer factory reflection.
   - **`McpSession`**: Per-session state (tools/resources/prompts) with `ConcurrentHashMap`-backed `Manager`.
   - **`McpToolDefinition`**: Record with `name`, `description`, `ToolHandler` functional interface.
   - **`McpResourceDefinition`**: Record with `uri`, `name`, `description`, `mimeType`, `ResourceHandler`.
   - **`McpPromptDefinition`**: Record with `name`, `description`, `PromptHandler`.

4. **Transport Layer (`StreamableMcpTransportProvider`)**:
   - HTTP server via `com.sun.net.httpserver.HttpServer`.
   - Thread pool via `Executors.newCachedThreadPool()`.
   - POST `/mcp` -> JSON-RPC handled by `McpProtocolHandler`.
   - GET `/` -> health check JSON.
   - CORS headers for cross-origin clients.
   - Session ID returned in `Mcp-Session-Id` response header on `initialize`.

5. **Handler Layer (`handlers/`)**:
   - **`GroovyScriptScanner`**: Compiles `*.groovy` files with `GroovyClassLoader`, scans `@McpTool`/`@McpResource`/`@McpPrompt` annotations, returns plain `McpToolDefinition`/`McpResourceDefinition`/`McpPromptDefinition` lists.
   - Annotations: `@McpTool(name, description)`, `@McpResource(uri, name, description, mimeType)`, `@McpPrompt(name, description)`.

### Data Flow
```
Client (AI Agent)
  │ POST /mcp {"jsonrpc":"2.0", "method":"tools/call", ...}
  │ Mcp-Session-Id: <uuid>
  ▼
StreamableMcpTransportProvider (HTTP)
  │
  ▼
McpProtocolHandler.handleRequest()
  │  routes by method name
  ▼
McpSession.getTools() / McpToolDefinition.ToolHandler.call()
  │
  ▼
GroovyScriptScanner handler lambda
  │  invokes Groovy method via reflection
  │  if return value is Map/List → serialize with Jackson ObjectMapper
  │  else → toString()
  ▼
Cameo API / Model
```

## Architecture Decisions

### 1. In-House MCP Protocol (no MCP SDK)
**Decision**: Implement MCP JSON-RPC protocol in plain Java instead of using `io.modelcontextprotocol.sdk:mcp`.
**Reasoning**:
- **Classloader Conflict**: The MCP SDK internally uses `JacksonMcpJsonMapper.convertValue()` which triggers `BasicSerializerFactory.buildMapSerializer` to reflectively access `JsonFormat$Shape.POJO` on Cameo's bundled Jackson 2.19.1 class, which doesn't have that field (added in Jackson 3). This causes `NoSuchFieldError` at runtime.
- **Zero Dependencies**: No bundled Jackson, no fat JAR, no classpath pollution.
- **Minimal Surface**: ~550 lines of protocol code vs pulling in a full SDK + its transitive dependencies.

### 2. Use of `com.sun.net.httpserver.HttpServer`
**Decision**: Use the built-in JDK HTTP server instead of a heavyweight framework.
**Reasoning**: Minimal dependencies, sufficient performance, simple deployment.

### 3. Dynamic Groovy Endpoints via Annotations
**Decision**: Load endpoint logic from external `.groovy` files with annotation scanning.
**Reasoning**:
- Fast iteration without restarting Cameo.
- Keeps Java core stable and minimal.

### 4. Port Selection
**Decision**: Default port `18750`.
**Reasoning**: Avoids conflict with other Cameo plugins and bridges.

## Example Scripts

The `scripts/` directory ships with several Groovy handlers:

### `echo.groovy` — Echo tool
- `@McpTool(name="echo")` — Returns the input arguments as text.

### `hello_prompt.groovy` — Hello prompt
- `@McpPrompt(name="hello")` — Returns a greeting message.

### `logging_demo.groovy` — GUI Log integration
- `@McpTool(name="logging_demo")` — Writes messages to the Cameo notification window.

### `model_info.groovy` — Model introspection
- `@McpTool(name="get_model_info")` — Returns the model name and a list of top-level packages and applied profiles.
- `@McpResource(uri="cameo://model/summary", mimeType="application/json")` — Returns a JSON summary of the open model (same payload as `get_model_info`).

### `model_query.groovy` — Element querying
- `@McpTool(name="find_elements")` — Find elements by name pattern and/or stereotype name. Returns name, qualifiedName, element type, and applied stereotypes.
- `@McpTool(name="get_element_info")` — Get detailed info about an element by its qualifiedName. Returns name, type, stereotypes, owned elements, and relationships (dependencies, generalizations, typed properties).

### `saf_tools.groovy` — Model Structure and SAF Viewpoints (v1.0.0)
SAF profile support for Cameo models. Provides tools for creating SAF-typed elements, querying viewpoints, building traceability chains, and exporting structured IR.

#### SAF Concept Map
| SAF Kind | SysML Type | SAF Stereotype |
|---|---|---|
| `system_requirement` | Class | SAF_SystemRequirement |
| `conceptual_system` | Block | SAF_ConceptualSystem |
| `physical_system` | Block | SAF_PhysicalSystem |
| `operational_performer` | Block | SAF_OperationalPerformer |

#### Tools
- `@McpTool(name="saf_create_element")` — Create an element by SAF concept kind, name, and parent ID. Applies the correct stereotype.
- `@McpTool(name="saf_set_requirement_tags")` — Set requirement ID and text tagged values on a SAF_SystemRequirement element.
- `@McpTool(name="saf_create_relationship")` — Create a relationship between two elements using SAF types (satisfy, derive, trace, refine, verify, allocate, etc.).
- `@McpTool(name="saf_query_viewpoint")` — Query elements filtered by SAF domain and aspect. Returns filtered elements with their SAF kinds.
- `@McpTool(name="saf_find_elements_by_type")` — Find elements by SysML type and/or stereotype. Returns matching elements with ID, name, type, stereotypes, SAF kind, and SAF domain.
- `@McpTool(name="saf_get_element_details")` — Get detailed SAF information about an element by ID. Returns name, type, SAF kind, domain, tagged values, owned elements, and traceability relationships.
- `@McpTool(name="saf_build_traceability_chain")` — Build a traceability chain from an element following satisfy, derive, trace, refine, and verify relationships. Returns a graph of connected elements with relationship types.
- `@McpTool(name="saf_check_consistency")` — Check SAF model consistency: verify requirement satisfaction chains, cross-domain alignment, and stereotype compliance. Returns list of issues and summary.
- `@McpTool(name="saf_export_viewpoint")` — Export a single SAF viewpoint as structured IR. Returns all elements in the viewpoint with their SAF metadata, relationships, and tagged values.

## Python Client Example

```python
import httpx

# Create session
r = httpx.post("http://localhost:18750/mcp", json={
    "jsonrpc": "2.0", "method": "initialize", "id": 1
})
session_id = r.headers.get("Mcp-Session-Id")

# List tools
r = httpx.post("http://localhost:18750/mcp", json={
    "jsonrpc": "2.0", "method": "tools/list", "id": 2
}, headers={"Mcp-Session-Id": session_id})
print(r.json())

# Call a tool
r = httpx.post("http://localhost:18750/mcp", json={
    "jsonrpc": "2.0", "method": "tools/call", "id": 3,
    "params": {"name": "echo", "arguments": {"message": "hello"}}
}, headers={"Mcp-Session-Id": session_id})
print(r.json())
```

## Test Suite

Integration tests are in `tests/`. They exercise the MCP protocol against a running Cameo instance.

**Important**: The test suite runs from inside a Podman container. Cameo runs on the **host machine**, not inside the container. The test scripts cannot check for Cameo processes, listening ports, or filesystem state on the host — they only communicate via HTTP(S). Always start Cameo on the host first, then run tests from the container.

Use `host.containers.internal` to reach the host from inside the container. It resolves to `169.254.1.2`.

The `/workspace` directory is **shared** between the container and the host via a bind mount. Changes made in the container to files under `/workspace` (including build output, scripts, and plugin JARs) are immediately visible on the host filesystem, and vice versa.

### Coverage

| Endpoint / Method | Test | Status |
|---|---|---|
| `GET /` health | `test_server_reachable` | ✅ |
| CORS preflight | `test_server_cors_headers` | ✅ |
| `initialize` | `_mcp_init` helper | ✅ |
| `notifications/initialized` | `_mcp_init` helper | ✅ |
| `tools/list` | `test_mcp_session_and_list_tools` | ✅ |
| `tools/call` echo | `test_mcp_session_and_list_tools` | ✅ |
| `tools/call` get_model_info | `test_mcp_model_info` | ✅ |
| `tools/call` find_elements | `test_mcp_find_elements` | ✅ |
| `tools/call` get_element_info | `test_mcp_get_element_info` | ✅ |
| `resources/list` | `test_mcp_resources` | ✅ |
| `resources/read` | `test_mcp_resources` | ✅ |
| `prompts/list` | `test_mcp_prompts` | ✅ |
| `prompts/get` hello | `test_mcp_prompts` | ✅ |
| `ping` | `test_mcp_ping` | ✅ |
| Unknown tool | `test_mcp_unknown_tool_returns_error` | ✅ |
| Missing session | `test_mcp_no_session_returns_error` | ✅ |
| Invalid message | `test_mcp_invalid_message_returns_error` | ✅ |

### Prerequisites
- Python 3 with `httpx` (`pip install httpx` or use `uv`)
- A running Cameo instance on the host with the plugin loaded

### Running

Run the step-by-step diagnostic:
```bash
cd tests
python diag_mcp_step.py
```

Run the full pytest suite:
```bash
cd tests
bash run_tests.sh
```

Override the server URL:
```bash
SERVER_URL=http://host.containers.internal:18750 python diag_mcp_step.py
SERVER_URL=http://host.containers.internal:18750 bash run_tests.sh
```

## Build and Deployment

The project uses Gradle. Built and tested with OpenJDK 21.

```bash
# Build and deploy
gradle deploy -PcameoHome="/path/to/Cameo"

# Build only (JAR + plugin.xml in build/plugin-dist/)
gradle assemblePlugin
```

Alternatively, run `install.sh` which does the same (JAR + scripts copy) with configurable `CAMEO_HOME`:

```bash
CAMEO_HOME=/path/to/Cameo bash install.sh
```

The plugin JAR is a thin JAR — all dependencies (Jackson) come from Cameo's classpath at runtime.

## Deployment Topology

The HTTP server binds to `0.0.0.0:18750`, so it is reachable from both the host loopback and external IPs (including the Podman container's `host.containers.internal`).

```
Host machine
┌──────────────────────────────────────────┐
│  Cameo + MCP plugin                     │
│  0.0.0.0:18750                          │
└──────────────────────┬───────────────────┘
                       │ host.containers.internal:18750
                       ▼
            ┌─────────────────────┐
            │ OpenCode container  │
            │ test scripts        │
            │ SERVER_URL=         │
            │ host.containers     │
            │ .internal:18750     │
            └─────────────────────┘
```

## License
Apache License 2.0 — Copyright © Alexander Haarer
