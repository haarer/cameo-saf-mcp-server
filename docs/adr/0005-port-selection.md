# ADR-0005: Default Port 18750

## Status

Accepted

## Context

The MCP HTTP server needs a TCP port. Cameo Systems Modeler and its
ecosystem of plugins already occupy several ports. The chosen port
must not collide with:

- Cameo's own services.
- Other Cameo plugins on the same host.
- Common developer tool ports (e.g. 8080, 3000, 5432).

## Decision

Default to port **18750**.

## Consequences

1. **No known conflicts**. Port 18750 falls outside the range of common
   Cameo plugin ports and standard developer tool ports.

2. **Configurable at startup**. If the port is occupied, the user can
   change it via plugin configuration. The port is only used for
   localhost or container-to-host communication; no external-facing
   firewall rules are needed.

3. **Referenced throughout the codebase**. Tests use
   `SERVER_URL=http://host.containers.internal:18750`, the build
   configures it, and the health endpoint is at `GET /` on this port.
   Changing the port requires updates in `StreamableMcpTransportProvider`,
   tests, and documentation.

## Related

- `src/com/haarer/saf/mcpserver/transport/StreamableMcpTransportProvider.java`
  — default port constant.
- `tests/test_mcp_server.py`, `tests/test_saf_tools.py` — test URLs.
- `README.md` — documented default port.
