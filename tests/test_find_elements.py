import os
import httpx
import json

SERVER_URL = os.environ.get("SERVER_URL", "http://localhost:18750")

def _mcp_init(client):

    payload = {
        "jsonrpc": "2.0",
        "id": 1,
        "method": "initialize",
        "params": {
            "protocolVersion": "2024-11-05",
            "capabilities": {},
            "clientInfo": {"name": "test-client", "version": "1.0.0"}
        }
    }
    r = client.post("/mcp", json=payload)
    assert r.status_code == 200
    session_id = r.headers.get("mcp-session-id")
    assert session_id
    body = r.json()
    assert body["result"]["serverInfo"]["name"] == "cameo-saf-mcp-server"

    # send initialized notification
    r = client.post("/mcp", json={"jsonrpc": "2.0", "method": "notifications/initialized"},
                    headers={"Mcp-Session-Id": session_id})
    assert r.status_code == 200

    return session_id

def test_mcp_find_elements(client):
    session_id = _mcp_init(client)

    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 2, "method": "tools/call",
                                  "params": {"name": "find_elements", "arguments": {}}},
                    headers={"Mcp-Session-Id": session_id})
    assert r.status_code == 200
    body = r.json()
    assert "result" in body
    assert not body["result"].get("isError", False)
    content = body["result"]["content"]
    assert len(content) > 0
    import json as _json
    data = _json.loads(content[0]["text"])
    print(data)


with httpx.Client(base_url=SERVER_URL, timeout=10) as cl:
    test_mcp_find_elements(cl)

