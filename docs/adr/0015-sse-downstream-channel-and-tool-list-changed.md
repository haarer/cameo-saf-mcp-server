# ADR-0015: SSE Downstream Channel and Tool-List-Changed Notification

## Status

Accepted

## Context

The plugin hot-reloads its Groovy scripts every ~2s (`CameoMcpServer`
`hotReloadLoop` → `GroovyScriptScanner`), so the tool set can change at
runtime while clients are connected. The MCP spec provides
`notifications/tools/list_changed` for exactly this, and clients that honor
it (e.g. opencode) re-list their tools on receipt — but only when the server
declares the `tools.listChanged` capability in `initialize`.

Before this change the server had neither:

1. `initialize` did not declare `capabilities.tools.listChanged`, and
2. the transport was POST-only (plus `GET /` health and `DELETE /mcp`), so
   there was no downstream channel at all to deliver a server-initiated
   notification.

Consequence in practice (observed in the 2026-08-23 surface-review session):
after a hot reload, clients kept a stale tool list and the agent had to build
its own curl helper against `/mcp` to discover and call newly enabled
`admin_*` tools. The retrospective classified this as a server-side
weakness, not a harness property.

## Decision

1. `McpProtocolHandler.handleInitialize` declares
   `capabilities.tools.listChanged = true` (resources and prompts remain
   `false`).

2. `StreamableMcpTransportProvider` gains a per-session SSE downstream
   channel on the existing `/mcp` endpoint: `GET /mcp` with a valid
   `Mcp-Session-Id` opens a `text/event-stream` (unknown session → 404).
   Each stream is an `SseClient` (key, sessionId, exchange, output stream,
   closed latch) tracked in a `ConcurrentHashMap`. A single scheduled
   executor writes a `: ka` comment-line keepalive every 15s; a failed write
   removes the client. `broadcastNotification(method)` writes a JSON-RPC
   notification frame to every open stream and returns the delivery count.

3. Stream lifetime is latch-based: the handler thread blocks on the
   client's `CountDownLatch` until another thread removes the client
   (keepalive write failure, `DELETE /mcp` for that session, or server
   stop). See sub-bug (a) below for why.

4. `CameoMcpServer.reloadScripts()` computes a tool-set signature (sorted
   `name::description`) before and after each scan, syncs every session from
   the scan, and — when the signature changed — broadcasts
   `notifications/tools/list_changed` to all open SSE streams.

Sub-bugs found and fixed during implementation/verification:

(a) **GET body EOF is not a disconnect signal.** GET request bodies reach
EOF immediately, so any disconnect detection based on reading the GET body
would have closed the stream on the first read. Lifetime is therefore
latch-based (point 3), and the handler thread never reads the GET body.

(b) **Deletion detection was missing.** `GroovyScriptScanner.hasChanges()`
previously reported only added/changed scripts (mtime comparison); deleted
scripts stayed in the served tool set until a server restart, and no
list-changed notification for a removal could ever fire. `hasChanges()` now
also reports any cached path that no longer exists on disk.

## Consequences

1. Clients that honor the capability re-list automatically after a hot
   reload that changes the tool set. No restart, no manual re-list, and no
   agent-side HTTP helper is needed anymore.

2. `GET /mcp` is dual-purpose: it previously returned 405 (POST-only) and
   now opens the per-session SSE stream. The `GET /` health probe is
   unchanged and additionally reports the number of open SSE streams. The
   SSE stream is optional for clients — POST remains the primary
   request/response channel.

3. **Stale entries are best-effort, server is authoritative.** The
   notification only signals that the list changed; it does not converge any
   client. Sessions created before a change, or clients that ignore or miss
   the notification (no SSE stream, non-conforming client), keep stale tool
   entries in their cache until the next `tools/list`. That degrades
   gracefully: `tools/list` always returns the current server-side set, and
   calling a removed tool fails with "Tool not found" instead of executing
   an outdated definition. Deletion detection (sub-bug b) is what makes
   removals visible in the first place.

4. **Stale bookkeeping entries are corrected by this change set.** `plan.md`
   iteration 8 ("tool list change notifications") and the `CHANGELOG.md`
   `[Unreleased]` pending line both listed this notification as pending;
   both are updated to reflect the implementation.

5. The signature comparison runs on every 2s scan and is cheap (a sorted
   list of name+description strings); no notification is broadcast when the
   tool set is unchanged.

## Related

- ADR-0003 (in-house MCP protocol, no SDK) — the channel and notification
  are hand-rolled on the same protocol code, per that decision.
- `src/com/haarer/saf/mcpserver/StreamableMcpTransportProvider.java` — SSE
  channel, keepalive, broadcast.
- `src/com/haarer/saf/mcpserver/protocol/McpProtocolHandler.java` —
  `tools.listChanged` capability declaration.
- `src/com/haarer/saf/mcpserver/CameoMcpServer.java` — reload loop,
  signature comparison, broadcast.
- `src/com/haarer/saf/mcpserver/handlers/GroovyScriptScanner.java` —
  deletion-detection fix.
- `plan.md` iteration 8 — the standalone *SSE transport option* (SSE as an
  alternative to the POST transport) remains an open human decision; this
  ADR covers only the downstream notification channel on the existing
  transport.
