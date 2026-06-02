# ADR-0004: Use of `com.sun.net.httpserver.HttpServer`

## Status

Accepted

## Context

The MCP protocol requires an HTTP transport (POST `/mcp`) to receive
JSON-RPC requests from AI agents. The plugin runs inside Cameo Systems
Modeler, which already loads a large number of jars. Adding a
heavyweight HTTP framework (Jetty, Netty, Undertow) would increase
startup time, classpath complexity, and the risk of classloader
conflicts.

## Decision

Use the JDK built-in `com.sun.net.httpserver.HttpServer` with
`Executors.newCachedThreadPool()` for the thread pool.

## Consequences

1. **No additional runtime dependencies**. The JDK's built-in HTTP
   server is always available and requires no extra jars.

2. **Sufficient for the workload**. The MCP protocol is request-response
   with session affinity. Each request is lightweight (JSON-RPC
   dispatch to Groovy script handlers). The built-in server handles
   this comfortably.

3. **Simple API**. `HttpServer.create()` + `createContext()` +
   `setExecutor()` is ~10 lines of setup. No configuration files,
   no servlet API, no XML.

4. **Limitations**: No built-in TLS, no HTTP/2, no WebSocket upgrade.
   These are not needed for the current use case (localhost or
   container-to-host communication). TLS can be added via a reverse
   proxy if needed later.

## Related

- `src/com/haarer/saf/mcpserver/transport/StreamableMcpTransportProvider.java`
  — HTTP server setup and request handling.
