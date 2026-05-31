import os
import httpx
import pytest
import json

SERVER_URL = os.environ.get("SERVER_URL", "http://localhost:18750")


@pytest.fixture(scope="session")
def client():
    with httpx.Client(base_url=SERVER_URL, timeout=10) as c:
        yield c


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
    assert body["result"]["serverInfo"]["name"] == "cameo-mcp-server"

    # send initialized notification
    r = client.post("/mcp", json={"jsonrpc": "2.0", "method": "notifications/initialized"},
                    headers={"Mcp-Session-Id": session_id})
    assert r.status_code == 200

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

    # tools/list
    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
                    headers={"Mcp-Session-Id": session_id})
    assert r.status_code == 200
    body = r.json()
    assert "result" in body
    tools = body["result"]["tools"]
    tool_names = [t["name"] for t in tools]
    assert "echo" in tool_names
    assert "logging_demo" in tool_names

    # Call echo tool
    call_echo = {
        "jsonrpc": "2.0", "id": 3, "method": "tools/call",
        "params": {"name": "echo", "arguments": {"message": "hello world"}}
    }
    r = client.post("/mcp", json=call_echo, headers={"Mcp-Session-Id": session_id})
    assert r.status_code == 200
    body = r.json()
    assert "result" in body
    content = body["result"]["content"]
    assert len(content) > 0
    assert "Echo: hello world" in content[0]["text"]


def test_mcp_resources(client):
    session_id = _mcp_init(client)

    # resources/list
    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 2, "method": "resources/list"},
                    headers={"Mcp-Session-Id": session_id})
    assert r.status_code == 200
    body = r.json()
    assert "result" in body
    resources = body["result"]["resources"]

    # Read a resource if any exist; if none exist, skip the read test
    if resources:
        uri = resources[0]["uri"]
        r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 3, "method": "resources/read",
                                      "params": {"uri": uri}},
                        headers={"Mcp-Session-Id": session_id})
        assert r.status_code == 200
        body = r.json()
        assert "result" in body or "error" in body


def test_mcp_prompts(client):
    session_id = _mcp_init(client)

    # prompts/list
    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 2, "method": "prompts/list"},
                    headers={"Mcp-Session-Id": session_id})
    assert r.status_code == 200
    body = r.json()
    assert "result" in body
    prompts = body["result"]["prompts"]
    prompt_names = [p["name"] for p in prompts]
    assert "hello" in prompt_names

    # prompts/get
    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 3, "method": "prompts/get",
                                  "params": {"name": "hello"}},
                    headers={"Mcp-Session-Id": session_id})
    assert r.status_code == 200
    body = r.json()
    assert "result" in body
    assert "messages" in body["result"]


def test_mcp_ping(client):
    session_id = _mcp_init(client)

    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 2, "method": "ping"},
                    headers={"Mcp-Session-Id": session_id})
    assert r.status_code == 200
    body = r.json()
    assert "result" in body


def test_mcp_unknown_tool_returns_error(client):
    session_id = _mcp_init(client)

    call_unknown = {
        "jsonrpc": "2.0", "id": 3, "method": "tools/call",
        "params": {"name": "nonexistent_tool", "arguments": {}}
    }
    r = client.post("/mcp", json=call_unknown, headers={"Mcp-Session-Id": session_id})
    assert r.status_code == 200
    body = r.json()
    assert "error" in body
    assert body["error"]["code"] != 0


def test_mcp_no_session_returns_error(client):
    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
    assert r.status_code == 200
    body = r.json()
    assert "error" in body


def test_mcp_invalid_message_returns_error(client):
    r = client.post("/mcp", json={"invalid": "json"})
    assert r.status_code == 200
    body = r.json()
    assert "error" in body
