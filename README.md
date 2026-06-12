# Cameo SAF MCP Server Plugin

A lightweight MCP (Model Context Protocol) server integrated as a plugin within CATIA Magic / Cameo Systems Modeler. Enables AI agents to interact with Cameo models via tools, resources, and prompts over HTTP.

The MCP protocol is implemented entirely in-house (~980 lines of hand-written Java) — no external MCP SDK dependency. This avoids Jackson classloader conflicts with Cameo's bundled Jackson 2.19.1.

```
You (human)
  │  natural language
  ▼
OpenCode agent (LLM)
  │  MCP over HTTP (JSON-RPC 2.0) :18750
  ▼
┌──────────────────────────────────────┐
│ CATIA Magic / Cameo Systems Modeler  │
│                                      │
│  ┌────────────────────────────────┐  │
│  │ Cameo SAF MCP Server Plugin    │  │
│  │ protocol / Groovy / hot-reload │  │
│  └──────────┬─────────────────────┘  │
│             │ Cameo Open API         │
│             ▼                        │
│  ┌────────────────────────────────┐  │
│  │ Cameo Model                    │  │
│  │ (SysML + SAF profile)          │  │
│  └────────────────────────────────┘  │
└──────────────────────────────────────┘
```

## User Guide

### Capabilities (v1.0.0)

- **MCP Protocol**: Full implementation of JSON-RPC 2.0 MCP methods — `initialize`, `tools/list`, `tools/call`, `resources/list`, `resources/read`, `prompts/list`, `prompts/get`, `ping`.
- **Dynamic Groovy Handlers**: Define tools, resources, and prompts in Groovy scripts using `@McpTool`, `@McpResource`, `@McpPrompt` annotations — no restart needed.
- **Hot Reloading**: Groovy scripts are monitored every 2 seconds and reloaded automatically on change.
- **Streamable HTTP Transport**: Single POST endpoint (`/mcp`) with `Mcp-Session-Id` header for session management.
- **Health Endpoint**: `GET /` returns server status and active session count.
- **Configurable Port**: Set via system property `cameo.mcp.server.port` (default `18750`).
- **Configurable Scripts Directory**: Set via system property `cameo.mcp.server.scripts.dir` (defaults to `scripts/` subdirectory of plugin installation).

### Example Scripts

The `scripts/` directory ships with several Groovy handlers:

#### `echo.groovy` — Echo tool
- `@McpTool(name="echo")` — Returns the input arguments as text.

#### `hello_prompt.groovy` — Hello prompt
- `@McpPrompt(name="hello")` — Returns a greeting message.

#### `logging_demo.groovy` — GUI Log integration
- `@McpTool(name="logging_demo")` — Writes messages to the Cameo notification window.

#### `model_info.groovy` — Model introspection
- `@McpTool(name="get_model_info")` — Returns the model name and a list of top-level packages and applied profiles.
- `@McpResource(uri="cameo://model/summary", mimeType="application/json")` — Returns a JSON summary of the open model (same payload as `get_model_info`).

#### `model_query.groovy` — Element querying
- `@McpTool(name="find_elements")` — Find elements by name pattern and/or stereotype name. Returns name, qualifiedName, element type, and applied stereotypes.
- `@McpTool(name="get_element_info")` — Get detailed info about an element by its qualifiedName. Returns name, type, stereotypes, owned elements, and relationships (dependencies, generalizations, typed properties).

#### `element_crud.groovy` — Generic SysML CRUD operations
- `@McpTool(name="create_element")` — Create any SysML element (Class, Package, Activity, Port, etc.) by type name. Optional stereotype and documentation.
- `@McpTool(name="create_relationship")` — Create relationships (dependency, association, composition, generalization, controlflow, objectflow, connector).
- `@McpTool(name="modify_element")` — Update element name and/or documentation by ID.
- `@McpTool(name="delete_element")` — Permanently delete an element and all owned sub-elements by ID.
- `@McpTool(name="set_tagged_values")` — Set tagged values on any stereotyped element.
- `@McpTool(name="find_elements_by_type")` — Find elements by SysML type and/or stereotype, returns results with element ID.
- `@McpTool(name="get_element_details")` — Get full element details by ID (name, type, stereotypes, owned elements, relationships).

#### `saf_tools.groovy` — Model Structure and SAF Viewpoints

SAF profile support for Cameo models. Provides tools for creating SAF-typed elements, querying viewpoints, building traceability chains, and exporting structured IR.

##### SAF Concept Map

| SAF Kind | SysML Type | SAF Stereotype |
|---|---|---|
| [System Requirement](https://saf.gfse.org/devdoc/concepts.html#_19_0_2_8710274_1558520012975_812587_44177) | Class | SAF_SystemRequirement |
| [Conceptual System](https://saf.gfse.org/devdoc/concepts.html#_19_0_2_26f0132_1562303524176_845719_91080) | Class | SAF_ConceptualSystem |
| [System Function](https://saf.gfse.org/devdoc/concepts.html#_19_0_2_26f0132_1562303524161_394471_91009) | Activity | SAF_Function |
| [Physical System](https://saf.gfse.org/devdoc/concepts.html#_19_0_2_26f0132_1562303524176_824595_91079) | Class | SAF_PhysicalSystem |
| [Operational Performer](https://saf.gfse.org/devdoc/concepts.html#_19_0_2_26f0132_1562316412621_925297_43615) | Class | SAF_OperationalPerformer |
| [Operational Capability](https://saf.gfse.org/devdoc/concepts.html#_19_0_3_8710274_1581665911562_310714_48925) | Class | SAF_OperationalCapability |
| [System Capability](https://saf.gfse.org/devdoc/concepts.html#_19_0_4_6d8019d_1617463709646_290604_336) | Class | SAF_SystemCapability |
| [Operational Story](https://saf.gfse.org/devdoc/concepts.html#_19_0_2_26f0132_1561750989144_963009_48675) | Class | SAF_OperationalStory |

> **Note**: This table will be expanded to match the full SAF specification — see `.scratch/saf-concept-map-alignment/issues/01-align-concept-map.md`.

##### Tools
- `@McpTool(name="saf_create_element")` — Create an element by SAF concept kind, name, and parent ID. Applies the correct stereotype.
- `@McpTool(name="saf_set_requirement_tags")` — Set requirement ID and text tagged values on a SAF_SystemRequirement element.
- `@McpTool(name="saf_create_relationship")` — Create a relationship between two elements using SAF types (satisfy, derive, trace, refine, verify, allocate, etc.).
- `@McpTool(name="saf_query_viewpoint")` — Query elements filtered by SAF domain and aspect. Returns filtered elements with their SAF kinds.
- `@McpTool(name="saf_find_elements_by_type")` — Find elements by SysML type and/or stereotype. Returns matching elements with ID, name, type, stereotypes, SAF kind, and SAF domain.
- `@McpTool(name="saf_get_element_details")` — Get detailed SAF information about an element by ID. Returns name, type, SAF kind, domain, tagged values, owned elements, and traceability relationships.
- `@McpTool(name="saf_build_traceability_chain")` — Build a traceability chain from an element following satisfy, derive, trace, refine, and verify relationships. Returns a graph of connected elements with relationship types.
- `@McpTool(name="saf_check_consistency")` — Check SAF model consistency: verify requirement satisfaction chains, cross-domain alignment, and stereotype compliance. Returns list of issues and summary.
- `@McpTool(name="saf_get_viewpoint_views")` — Find diagrams that conform to a SAF viewpoint. Search by short code (AM, OV, CV, PV) or name. Returns diagrams ranked by conformance score.
- `@McpTool(name="saf_export_viewpoint")` — Export a single SAF viewpoint as structured IR. Returns all elements in the viewpoint with their SAF metadata, relationships, and tagged values.

### Python Client Example

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

## Developer Guide

### Internal Architecture

Java core + Groovy plugins ([ADR-0002](docs/adr/0002-java-core-with-groovy-plugins.md)).

#### Component Overview

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

#### Data Flow

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

### Architecture Decisions

- In-house MCP protocol, no MCP SDK ([ADR-0003](docs/adr/0003-in-house-mcp-protocol.md)).
- `com.sun.net.httpserver.HttpServer` ([ADR-0004](docs/adr/0004-builtin-http-server.md)).
- Port 18750 ([ADR-0005](docs/adr/0005-port-selection.md)).

### Build Concepts

#### Dependency Model

The plugin runs inside Cameo Systems Modeler and uses Cameo's own classpath at runtime. The build requires Cameo SDK jars (`compileOnly`) for two purposes:

| Dependency | Category | Build Requirement |
|---|---|---|
| `com.nomagic.magicdraw.plugins.Plugin` | **Proprietary** (Cameo SDK) | Stub or real Cameo SDK jar |
| `com.nomagic.magicdraw.core.Application` | **Proprietary** (Cameo SDK) | Stub or real Cameo SDK jar |
| `com.fasterxml.jackson.databind.ObjectMapper` | **Open source** (Jackson) | Real jar from Maven Central |
| `groovy.lang.GroovyClassLoader` | **Open source** (Apache Groovy) | Real jar from Maven Central |

#### Local Build (with real Cameo SDK)

Build and deploy directly:

```bash
# Build + deploy to Cameo (set plugin version explicitly)
gradle deploy -PcameoHome="/path/to/Cameo" -PpluginVersion=0.1.0

# Build only (JAR + filtered plugin.xml in build/plugin-dist/)
gradle assemblePlugin -PcameoHome="/path/to/Cameo" -PpluginVersion=0.1.0
```

Full Resource Manager zip (recommended for distribution):

```bash
python ci/build-plugin.py --cameo-home /path/to/Cameo --version 0.1.0
```

When `--version` is omitted, the script auto-detects the current git tag or falls back to `0.1.0-dev`.

The `cameoHome` property must point to a directory containing `lib/` with Cameo SDK jars matching the glob patterns in `build.gradle`:
- `com.nomagic.magicdraw.foundation-*.jar`
- `com.nomagic.magicdraw.core.diagram-*.jar`
- `com.nomagic.magicdraw.modeling-*.jar`
- `com.nomagic.utils-*.jar`
- `core-*.jar`
- `jackson-*.jar`

Alternatively, run `install.sh` which does the same (JAR + scripts copy) with configurable `CAMEO_HOME`:

```bash
CAMEO_HOME=/path/to/Cameo bash install.sh
```

The plugin JAR is a thin JAR — all dependencies (Jackson, Groovy) come from Cameo's classpath at runtime.

#### CI Build (without real Cameo SDK)

GitHub CI cannot access proprietary Cameo SDK jars. Instead, `ci/prepare-ci-libs.sh` creates a `ci-libs/` directory with:

1. **Real open-source jars** — Jackson (`jackson-core`, `jackson-databind`, `jackson-annotations`) and Apache Groovy downloaded from Maven Central.
2. **Cameo SDK stubs** — Minimal Java classes (`Plugin`, `Application`, `GUILog`) compiled into `core-stubs.jar`. These satisfy the compiler with empty method bodies — no proprietary Cameo code is included.

The CI workflow (`.github/workflows/ci.yml`) has two jobs:

1. **`compile`** (on tag push `v*`): Runs `prepare-ci-libs.sh` then `gradle compileJava -PcameoHome=ci-libs` to verify Java compilation with stubs.
2. **`release`** (on tag push `v*`, after `compile`): Runs `build-plugin.py --cameo-home ci-libs --version ${{ github.ref_name }}` which builds the JAR, injects the git tag into `plugin.xml` and the resource descriptor, then packages a Resource Manager zip attached to the GitHub release.

**Why stubs are sufficient**: Only 2 Cameo classes appear in the Java source, with trivial overrides (`init()`, `close()`, `isSupported()`) and one static call (`Application.getInstance().getGUILog().log()`). The compiler only needs the method signatures, not the runtime implementation.

**What CI does NOT verify**: The Groovy scripts are not compiled in CI (they load dynamically at runtime inside Cameo). Integration tests require a live Cameo instance and are not run in public CI.

### Testing

Integration tests are in `tests/`. They exercise the MCP protocol against a running Cameo instance.

#### Prerequisites

- Cameo Systems Modeler must be running with the plugin loaded.
- A model must be open in Cameo (model-querying tools return `No model open` otherwise).
- Python 3 with `httpx` (`pip install httpx` or use `uv`).

#### Running

Run the step-by-step diagnostic:
```bash
cd tests
python diag_mcp_step.py
```

Run the full pytest suite:
```bash
cd tests
bash run_tests.sh
# or
cd tests && python -m pytest -v
```

When running from the Podman container, point `SERVER_URL` to the host:
```bash
SERVER_URL=http://host.containers.internal:18750 python diag_mcp_step.py
SERVER_URL=http://host.containers.internal:18750 python -m pytest tests/ -v
```

**Important**: The test suite runs from inside a Podman container. Cameo runs on the **host machine**, not inside the container. The test scripts only communicate via HTTP(S). Always start Cameo on the host first, then run tests from the container. Use `host.containers.internal` to reach the host (resolves to `169.254.1.2`).

The `/workspace` directory is **shared** between the container and the host via a bind mount. Changes made in the container to files under `/workspace` (including build output, scripts, and plugin JARs) are immediately visible on the host filesystem, and vice versa.

#### Topology

```
Host machine
┌──────────────────────────────────────────┐
│  Cameo + MCP plugin                     │
│  0.0.0.0:18750                          │
└──────────────────────┬───────────────────┘
                       │ host.containers.internal:18750
                       ▼
            ┌─────────────────────────┐
            │ Podman container        │
            │ test scripts            │
            │ SERVER_URL=             │
            │ host.containers.internal│
            │ :18750                  │
            └─────────────────────────┘
```

#### Coverage

##### Server & Protocol (12 test rows)

| Endpoint / Method | Test | Status |
|---|---|---|
| `GET /` health | `test_server_reachable` | ✅ |
| CORS preflight | `test_server_cors_headers` | ✅ |
| `initialize` | `_mcp_init` helper | ✅ |
| `tools/list` | `test_mcp_session_and_list_tools` | ✅ |
| `tools/call` echo | `test_mcp_session_and_list_tools` | ✅ |
| `tools/call` get_model_info | `test_mcp_model_info` | ✅ |
| `tools/call` find_elements | `test_mcp_find_elements` | ✅ |
| `tools/call` get_element_info | `test_mcp_get_element_info` | ✅ |
| `resources/list` + `read` | `test_mcp_resources` | ✅ |
| `prompts/list` + `get` | `test_mcp_prompts` | ✅ |
| `ping` | `test_mcp_ping` | ✅ |

##### Error Handling (3 tests)

| Scenario | Test | Status |
|---|---|---|
| Unknown tool | `test_mcp_unknown_tool_returns_error` | ✅ |
| Missing session | `test_mcp_no_session_returns_error` | ✅ |
| Invalid message | `test_mcp_invalid_message_returns_error` | ✅ |

##### CRUD Operations (4 tests)

| Scenario | Test | Status |
|---|---|---|
| `delete_element` registered | `test_mcp_delete_element_registered` | ✅ |
| create + delete flow | `test_mcp_delete_element_create_then_delete` | ✅ |
| Invalid element ID | `test_mcp_delete_element_invalid_id` | ✅ |
| Missing element ID | `test_mcp_delete_element_missing_id` | ✅ |

##### SAF Tools (26 tests)

| Scenario | Test | Status |
|---|---|---|
| All SAF tools registered | `test_saf_tools_registered` | ✅ |
| Find by type (no filter) | `test_saf_find_elements_by_type_no_filter` | ✅ |
| Find by type (type filter) | `test_saf_find_elements_by_type_with_type_filter` | ✅ |
| Find by type (stereotype filter) | `test_saf_find_elements_by_type_with_stereotype_filter` | ✅ |
| Get element details (valid) | `test_saf_get_element_details_valid` | ✅ |
| Get element details (invalid) | `test_saf_get_element_details_invalid_id` | ✅ |
| Traceability chain (structure) | `test_saf_build_traceability_chain_structure` | ✅ |
| Traceability chain (max depth) | `test_saf_build_traceability_chain_max_depth` | ✅ |
| Traceability chain (empty) | `test_saf_build_traceability_chain_empty_result` | ✅ |
| Consistency check (basic) | `test_saf_check_consistency_basic` | ✅ |
| Consistency check (with checks) | `test_saf_check_consistency_with_checks` | ✅ |
| Consistency check (all checks) | `test_saf_check_consistency_all_checks` | ✅ |
| Export viewpoint (AM) | `test_saf_export_viewpoint_architecture_management` | ✅ |
| Export viewpoint (with aspect) | `test_saf_export_viewpoint_with_aspect` | ✅ |
| Export viewpoint (physical) | `test_saf_export_viewpoint_physical` | ✅ |
| Export viewpoint (operational) | `test_saf_export_viewpoint_operational` | ✅ |
| Export viewpoint (metadata) | `test_saf_export_viewpoint_nodes_have_metadata` | ✅ |
| Viewpoint views (registered) | `test_saf_get_viewpoint_views_registered` | ✅ |
| Viewpoint views (by code AM) | `test_saf_get_viewpoint_views_by_code_am` | ✅ |
| Viewpoint views (by code CV) | `test_saf_get_viewpoint_views_by_code_cv` | ✅ |
| Viewpoint views (by name) | `test_saf_get_viewpoint_views_by_name` | ✅ |
| Viewpoint views (with content) | `test_saf_get_viewpoint_views_with_content` | ✅ |
| Viewpoint views (unknown) | `test_saf_get_viewpoint_views_unknown_viewpoint` | ✅ |
| Viewpoint views (structure) | `test_saf_get_viewpoint_views_view_structure` | ✅ |
| Viewpoint views (conformance sort) | `test_saf_get_viewpoint_views_sorted_by_conformance` | ✅ |
| Full workflow | `test_saf_full_workflow` | ✅ |

Tests are not part of CI — they require a live Cameo instance.

### License

Apache License 2.0 — Copyright © Alexander Haarer
