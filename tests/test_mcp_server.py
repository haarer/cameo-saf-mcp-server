import os
import httpx
import pytest
import json

SERVER_URL = os.environ.get("SERVER_URL", "http://localhost:18741")


@pytest.fixture(scope="session")
def client():
    with httpx.Client(base_url=SERVER_URL, timeout=10) as c:
        yield c


def _read_sse_events(body: str):
    events = []
    for block in body.split("\n\n"):
        if not block.strip():
            continue
        event = {}
        for line in block.strip().split("\n"):
            if line.startswith("event:"):
                event["event"] = line[6:].strip()
            elif line.startswith("data:"):
                event["data"] = line[5:].strip()
        if event:
            events.append(event)
    return events


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
    result = r.json()
    assert result["result"]["serverInfo"]["name"] == "cameo-mcp-server"

    # send initialized notification
    r = client.post("/mcp", json={"jsonrpc": "2.0", "method": "notifications/initialized"},
                    headers={"Mcp-Session-Id": session_id})
    assert r.status_code == 202

    return session_id


# -- Connectivity & Health --

def test_server_reachable(client):
    r = client.get("/")
    assert r.status_code == 200
    data = r.json()
    assert data["status"] == "ok"
    assert data["server"] == "cameo-mcp-server"


def test_server_cors_headers(client):
    r = client.options("/")
    assert r.status_code == 204
    assert r.headers.get("access-control-allow-origin") == "*"


# -- MCP Session & JSON-RPC --

def test_mcp_session_and_list_tools(client):
    session_id = _mcp_init(client)

    # tools/list - response comes as SSE on the POST response
    tools_list = {"jsonrpc": "2.0", "id": 2, "method": "tools/list"}
    r = client.post("/mcp", json=tools_list, headers={"Mcp-Session-Id": session_id})
    assert r.status_code == 200
    assert r.headers.get("content-type", "").startswith("text/event-stream")
    events = _read_sse_events(r.text)
    assert len(events) >= 1
    data = json.loads(events[0]["data"])
    assert "result" in data
    tools = data["result"]["tools"]
    tool_names = [t["name"] for t in tools]
    assert "echo" in tool_names
    assert "get_model_name" in tool_names

    # Call echo tool
    call_echo = {
        "jsonrpc": "2.0", "id": 3, "method": "tools/call",
        "params": {"name": "echo", "arguments": {"message": "hello world"}}
    }
    r = client.post("/mcp", json=call_echo, headers={"Mcp-Session-Id": session_id})
    assert r.status_code == 200
    events = _read_sse_events(r.text)
    assert len(events) >= 1
    data = json.loads(events[0]["data"])
    assert "result" in data
    content = data["result"]["content"]
    assert len(content) > 0
    assert "Echo: hello world" in content[0]["text"]


def test_mcp_resources(client):
    session_id = _mcp_init(client)

    # resources/list
    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 2, "method": "resources/list"},
                    headers={"Mcp-Session-Id": session_id})
    assert r.status_code == 200
    events = _read_sse_events(r.text)
    assert len(events) >= 1
    data = json.loads(events[0]["data"])
    assert "result" in data
    resources = data["result"]["resources"]
    resource_uris = [r["uri"] for r in resources]
    assert "cameo://model/summary" in resource_uris

    # Read model summary resource
    read_resource = {
        "jsonrpc": "2.0", "id": 3, "method": "resources/read",
        "params": {"uri": "cameo://model/summary"}
    }
    r = client.post("/mcp", json=read_resource, headers={"Mcp-Session-Id": session_id})
    assert r.status_code == 200
    events = _read_sse_events(r.text)
    assert len(events) >= 1
    data = json.loads(events[0]["data"])
    assert "result" in data or "error" in data


def test_mcp_prompts(client):
    session_id = _mcp_init(client)

    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 2, "method": "prompts/list"},
                    headers={"Mcp-Session-Id": session_id})
    assert r.status_code == 200


def test_mcp_unknown_tool_returns_error(client):
    session_id = _mcp_init(client)

    call_unknown = {
        "jsonrpc": "2.0", "id": 3, "method": "tools/call",
        "params": {"name": "nonexistent_tool", "arguments": {}}
    }
    r = client.post("/mcp", json=call_unknown, headers={"Mcp-Session-Id": session_id})
    assert r.status_code == 200
    events = _read_sse_events(r.text)
    assert len(events) >= 1
    data = json.loads(events[0]["data"])
    assert "error" in data
    assert data["error"]["code"] != 0


def test_mcp_invalid_message_returns_400(client):
    r = client.post("/mcp", json={"invalid": "json"})
    assert r.status_code in (400, 500)


def test_mcp_missing_session_id_returns_400(client):
    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
    assert r.status_code == 400
