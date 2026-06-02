Status: needs-triage

# Fix `notifications/initialized` handling per JSON-RPC 2.0 spec

## Summary

`McpProtocolHandler.handleRequest()` does not detect JSON-RPC **notifications**
(requests without an `id` field). When the client sends
`notifications/initialized`, the server returns a `"Method not found"` error
response — but per JSON-RPC 2.0 spec, notifications **must not** receive any
response.

## Current behavior

```
Client sends:  {"jsonrpc":"2.0", "method":"notifications/initialized"}
                 (no "id" field → notification)

Server returns: {"jsonrpc":"2.0", "error":{"code":-32601,"message":"Method not found"}, "id":null}
                 (violates spec — should send nothing)
```

## Expected behavior

1. Detect that the request has no `id` field → it's a notification.
2. If method is recognized (`notifications/initialized`), handle silently and
   send **no response**.
3. If method is unknown, **still send no response** (spec: notifications
   never get responses, even on error).
4. The test `test_mcp_session_and_list_tools` sends this notification but
   doesn't assert no-response. Update test to verify no response body.

## What to change

- `src/com/haarer/saf/mcpserver/protocol/McpProtocolHandler.java`:
  - Check `request.has("id")` at top of `handleRequest()`.
  - If no `id`, route to notification handler instead of normal flow.
  - Return `HandleResult.notification()` (new variant) that causes the
    transport layer to send no body.
- `src/com/haarer/saf/mcpserver/StreamableMcpTransportProvider.java`:
  - In `handleRequest()`, if result is a notification, don't write response.
- `tests/test_mcp_server.py`:
  - Update notification test to assert no response sent.

## Reference

- JSON-RPC 2.0 spec: <https://www.jsonrpc.org/specification#notification>
- Current handler: `McpProtocolHandler.java:27-49`
