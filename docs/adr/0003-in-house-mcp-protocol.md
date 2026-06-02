# ADR-0003: In-House MCP Protocol (no MCP SDK)

## Status

Accepted

## Context

The plugin needs to speak the MCP JSON-RPC protocol. The standard
`io.modelcontextprotocol.sdk:mcp` library was initially used, but it
caused a runtime `NoSuchFieldError` because:

1. The MCP SDK uses `JacksonMcpJsonMapper.convertValue()` which triggers
   `BasicSerializerFactory.buildMapSerializer` to reflectively access
   `JsonFormat$Shape.POJO`.
2. This reflective access uses the `com.fasterxml.jackson.annotation`
   class from Cameo's bundled Jackson 2.19.1, where `JsonFormat$Shape`
   does not have a `POJO` field (added in Jackson 3).
3. The MCP SDK depends on Jackson 3, so its serialization internals
   expect Jackson 3 APIs that don't exist in Cameo's classpath.

This is a classloader conflict: the MCP SDK's Jackson 3 classes and
Cameo's bundled Jackson 2 cannot coexist on the same classpath.

## Decision

Implement the MCP JSON-RPC protocol in plain Java (~410 lines) using
only Cameo's bundled Jackson 2 `ObjectMapper` for basic JSON tree
operations (`readTree()`, `writeValueAsString()`), avoiding
`convertValue()` and serializer factory reflection entirely.

The in-house implementation covers:
- JSON-RPC 2.0 request/response/error framing.
- `tools/list`, `tools/call`, `resources/list`, `resources/read`,
  `prompts/list`, `prompts/get`, `initialize`.
- Session management with `Mcp-Session-Id` header.

## Consequences

1. **Zero additional dependencies**. The plugin bundles no Jackson 3
   jars, no MCP SDK jars, no fat JAR. Only Cameo's existing Jackson
   2.19.1 is used.

2. **Small surface area**. ~410 lines of protocol code vs pulling in
   the full MCP SDK + its transitive Jackson 3 dependencies. Easier
   to audit, debug, and maintain.

3. **No Jackson `convertValue()` in the codebase**. All JSON
   construction uses `ObjectNode`/`ArrayNode` factory methods from
   Cameo's `ObjectMapper`. Integer nodes use
   `mapper.getNodeFactory().numberNode()` (Jackson 2 compatible)
   instead of `JsonNodeFactory.instance.longNode()` (Jackson 3 API).

4. **Future MCP protocol revisions require manual updates**. There is
   no SDK to bump for new MCP spec features. Each new MCP method
   (e.g. `notifications/initialized`) must be implemented in
   `McpProtocolHandler` by hand.

## Related

- `src/com/haarer/saf/mcpserver/protocol/` — Java protocol implementation.
- `plan.md` § Jackson Classloader Conflict for the root-cause analysis.
- `plan.md` § Iteration 2 documents the MCP SDK removal effort.
