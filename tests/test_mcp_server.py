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
    assert body["result"]["serverInfo"]["name"] == "cameo-saf-mcp-server"

    # send initialized notification
    r = client.post("/mcp", json={"jsonrpc": "2.0", "method": "notifications/initialized"},
                    headers={"Mcp-Session-Id": session_id})
    assert r.status_code == 200

    return session_id


@pytest.fixture(scope="session")
def tool_names(client):
    """Discover available tool names from the server."""
    session_id = _mcp_init(client)
    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
                    headers={"Mcp-Session-Id": session_id})
    body = r.json()
    return [t["name"] for t in body["result"]["tools"]]


# -- Connectivity & Health --

def test_server_reachable(client):
    r = client.get("/")
    assert r.status_code == 200
    data = r.json()
    assert data["status"] == "ok"
    assert data["server"] == "cameo-saf-mcp-server"


def test_server_cors_headers(client):
    r = client.options("/")
    assert r.status_code == 204
    assert r.headers.get("access-control-allow-origin") == "*"


# -- MCP Session & JSON-RPC --

def test_mcp_session_and_list_tools(client, tool_names):
    session_id = _mcp_init(client)

    # tools/list
    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
                    headers={"Mcp-Session-Id": session_id})
    assert r.status_code == 200
    body = r.json()
    assert "result" in body
    tools = body["result"]["tools"]
    available = [t["name"] for t in tools]

    # Core tools that must always be present
    assert "echo" in available
    assert "logging_demo" in available
    assert "get_model_info" in available
    assert "delete_element" in available

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


def test_mcp_model_info(client):
    session_id = _mcp_init(client)

    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 2, "method": "tools/call",
                                  "params": {"name": "get_model_info", "arguments": {}}},
                    headers={"Mcp-Session-Id": session_id})
    assert r.status_code == 200
    body = r.json()
    assert "result" in body
    content = body["result"]["content"]
    assert len(content) > 0
    text = content[0]["text"]
    data = json.loads(text)
    assert "modelName" in data
    assert isinstance(data["modelName"], str) and len(data["modelName"]) > 0
    assert "packages" in data
    assert isinstance(data["packages"], list)
    assert "profiles" in data
    assert isinstance(data["profiles"], list)
    assert "usedProjects" in data
    assert isinstance(data["usedProjects"], list)


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
    data = json.loads(content[0]["text"])
    assert isinstance(data, list)
    if len(data) > 0:
        item = data[0]
        assert "name" in item
        assert "qualifiedName" in item
        assert "type" in item
        assert "stereotypes" in item


def test_mcp_get_element_info(client):
    session_id = _mcp_init(client)

    # First discover a valid qualifiedName from find_elements
    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 2, "method": "tools/call",
                                  "params": {"name": "find_elements", "arguments": {}}},
                    headers={"Mcp-Session-Id": session_id})
    body = r.json()
    content = body["result"]["content"]
    data = json.loads(content[0]["text"])
    assert len(data) > 0, "Need at least one element to test get_element_info"
    qn = data[0]["qualifiedName"]

    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 3, "method": "tools/call",
                                  "params": {"name": "get_element_info",
                                             "arguments": {"qualifiedName": qn}}},
                    headers={"Mcp-Session-Id": session_id})
    assert r.status_code == 200
    body = r.json()
    assert "result" in body
    content = body["result"]["content"]
    assert len(content) > 0
    info = json.loads(content[0]["text"])
    assert "name" in info
    assert "qualifiedName" in info
    assert "type" in info
    assert "stereotypes" in info
    assert "ownedElements" in info
    assert "relationships" in info


def test_mcp_resources(client):
    session_id = _mcp_init(client)

    # resources/list
    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 2, "method": "resources/list"},
                    headers={"Mcp-Session-Id": session_id})
    assert r.status_code == 200
    body = r.json()
    assert "result" in body
    resources = body["result"]["resources"]
    resource_uris = [r["uri"] for r in resources]
    assert "cameo://model/summary" in resource_uris

    # Read model summary resource
    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 3, "method": "resources/read",
                                  "params": {"uri": "cameo://model/summary"}},
                    headers={"Mcp-Session-Id": session_id})
    assert r.status_code == 200
    body = r.json()
    assert "result" in body
    text = body["result"]["contents"][0]["text"]
    data = json.loads(text)
    assert "modelName" in data


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


# -- Element CRUD tools --

def test_mcp_delete_element_registered(client, tool_names):
    """delete_element tool appears in tools/list."""
    session_id = _mcp_init(client)
    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
                    headers={"Mcp-Session-Id": session_id})
    body = r.json()
    available = [t["name"] for t in body["result"]["tools"]]
    assert "delete_element" in available


def test_mcp_delete_element_invalid_id(client):
    """delete_element returns error for nonexistent ID."""
    session_id = _mcp_init(client)

    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 100, "method": "tools/call",
                                  "params": {"name": "delete_element",
                                             "arguments": {"elementId": "nonexistent-id"}}},
                    headers={"Mcp-Session-Id": session_id})
    body = r.json()
    content = json.loads(body["result"]["content"][0]["text"])
    assert "error" in content


def test_mcp_delete_element_missing_id(client):
    """delete_element returns error when elementId is missing."""
    session_id = _mcp_init(client)

    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 100, "method": "tools/call",
                                  "params": {"name": "delete_element", "arguments": {}}},
                    headers={"Mcp-Session-Id": session_id})
    body = r.json()
    content = json.loads(body["result"]["content"][0]["text"])
    assert "error" in content


def test_mcp_create_element_registered(client, tool_names):
    """create_element tool appears in tools/list."""
    assert "create_element" in tool_names, \
        f"create_element not in tool list: {tool_names}"


def test_mcp_find_elements_by_type_registered(client, tool_names):
    """find_elements_by_type tool appears in tools/list."""
    assert "find_elements_by_type" in tool_names, \
        f"find_elements_by_type not in tool list: {tool_names}"


def test_mcp_get_element_details_registered(client, tool_names):
    """get_element_details tool appears in tools/list."""
    assert "get_element_details" in tool_names, \
        f"get_element_details not in tool list: {tool_names}"


def test_mcp_get_elements_details_batch_registered(client, tool_names):
    """get_elements_details_batch tool appears in tools/list."""
    assert "get_elements_details_batch" in tool_names, \
        f"get_elements_details_batch not in tool list: {tool_names}"


def test_mcp_list_owned_elements_registered(client, tool_names):
    """list_owned_elements tool appears in tools/list."""
    assert "list_owned_elements" in tool_names, \
        f"list_owned_elements not in tool list: {tool_names}"


def test_mcp_get_port_type_info_registered(client, tool_names):
    """get_port_type_info tool appears in tools/list."""
    assert "get_port_type_info" in tool_names, \
        f"get_port_type_info not in tool list: {tool_names}"


def test_mcp_list_model_stereotypes_registered(client, tool_names):
    """list_model_stereotypes tool appears in tools/list."""
    assert "list_model_stereotypes" in tool_names, \
        f"list_model_stereotypes not in tool list: {tool_names}"


def test_mcp_get_elements_details_batch_functional(client, tool_names):
    """get_elements_details_batch returns details for multiple elements."""
    required = ["find_elements_by_type", "get_elements_details_batch"]
    missing = [n for n in required if n not in tool_names]
    if missing:
        pytest.skip(f"missing tools: {', '.join(missing)}")

    session_id = _mcp_init(client)

    # Grab up to 3 element IDs from find_elements_by_type
    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 20, "method": "tools/call",
                                  "params": {"name": "find_elements_by_type",
                                             "arguments": {"type": "Class"}}},
                    headers={"Mcp-Session-Id": session_id})
    body = r.json()
    assert not body["result"].get("isError", False)
    elements = json.loads(body["result"]["content"][0]["text"])
    if len(elements) < 2:
        pytest.skip("need at least 2 elements to test batch")
    ids = [e["id"] for e in elements[:3]]

    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 21, "method": "tools/call",
                                  "params": {"name": "get_elements_details_batch",
                                             "arguments": {"ids": ids}}},
                    headers={"Mcp-Session-Id": session_id})
    body = r.json()
    assert not body["result"].get("isError", False), f"batch failed: {body}"
    results = json.loads(body["result"]["content"][0]["text"])
    assert isinstance(results, list)
    assert len(results) == len(ids)
    for r in results:
        assert "id" in r
        assert "name" in r
        assert "type" in r
        assert "stereotypes" in r


def test_mcp_get_elements_details_batch_bad_id(client, tool_names):
    """get_elements_details_batch handles unknown IDs gracefully."""
    if "get_elements_details_batch" not in tool_names:
        pytest.skip("get_elements_details_batch not registered")
    session_id = _mcp_init(client)

    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 22, "method": "tools/call",
                                  "params": {"name": "get_elements_details_batch",
                                             "arguments": {"ids": ["_nonexistent_"]}}},
                    headers={"Mcp-Session-Id": session_id})
    body = r.json()
    assert not body["result"].get("isError", False)
    results = json.loads(body["result"]["content"][0]["text"])
    assert isinstance(results, list)
    assert len(results) == 1
    assert "error" in results[0]


def test_mcp_list_owned_elements_functional(client, tool_names):
    """list_owned_elements returns children of the primary model."""
    required = ["list_owned_elements", "find_elements_by_type"]
    missing = [n for n in required if n not in tool_names]
    if missing:
        pytest.skip(f"missing tools: {', '.join(missing)}")
    session_id = _mcp_init(client)

    # Find the root model element
    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 30, "method": "tools/call",
                                  "params": {"name": "find_elements_by_type",
                                             "arguments": {"type": "Model"}}},
                    headers={"Mcp-Session-Id": session_id})
    body = r.json()
    assert not body["result"].get("isError", False)
    models = json.loads(body["result"]["content"][0]["text"])
    root_model = next(m for m in models if not m.get("parentId"))
    parent_id = root_model["id"]

    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 31, "method": "tools/call",
                                  "params": {"name": "list_owned_elements",
                                             "arguments": {"parentId": parent_id}}},
                    headers={"Mcp-Session-Id": session_id})
    body = r.json()
    assert not body["result"].get("isError", False), f"list_owned_elements failed: {body}"
    results = json.loads(body["result"]["content"][0]["text"])
    assert isinstance(results, list)
    if len(results) > 0:
        for r in results:
            assert "id" in r
            assert "name" in r
            assert "type" in r
            assert "stereotypes" in r
            assert "parentId" in r


def test_mcp_list_owned_elements_bad_id(client, tool_names):
    """list_owned_elements handles unknown parentId gracefully."""
    if "list_owned_elements" not in tool_names:
        pytest.skip("list_owned_elements not registered")
    session_id = _mcp_init(client)

    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 32, "method": "tools/call",
                                  "params": {"name": "list_owned_elements",
                                             "arguments": {"parentId": "_bad_"}}},
                    headers={"Mcp-Session-Id": session_id})
    body = r.json()
    content = json.loads(body["result"]["content"][0]["text"])
    assert "error" in content or "not found" in str(content).lower()


def test_mcp_get_port_type_info_bad_id(client, tool_names):
    """get_port_type_info handles unknown portId gracefully."""
    if "get_port_type_info" not in tool_names:
        pytest.skip("get_port_type_info not registered")
    session_id = _mcp_init(client)

    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 40, "method": "tools/call",
                                  "params": {"name": "get_port_type_info",
                                             "arguments": {"portId": "_bad_"}}},
                    headers={"Mcp-Session-Id": session_id})
    body = r.json()
    content = json.loads(body["result"]["content"][0]["text"])
    assert "error" in content or "not found" in str(content).lower()


def test_mcp_list_model_stereotypes_functional(client, tool_names):
    """list_model_stereotypes returns grouped stereotype names."""
    if "list_model_stereotypes" not in tool_names:
        pytest.skip("list_model_stereotypes not registered")
    session_id = _mcp_init(client)

    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 50, "method": "tools/call",
                                  "params": {"name": "list_model_stereotypes",
                                             "arguments": {}}},
                    headers={"Mcp-Session-Id": session_id})
    body = r.json()
    assert not body["result"].get("isError", False), f"list_model_stereotypes failed: {body}"
    data = json.loads(body["result"]["content"][0]["text"])
    assert "total" in data
    assert isinstance(data["total"], int)
    assert "groups" in data
    assert isinstance(data["groups"], list)
    if len(data["groups"]) > 0:
        g = data["groups"][0]
        assert "prefix" in g
        assert "stereotypes" in g
        assert isinstance(g["stereotypes"], list)
        if len(g["stereotypes"]) > 0:
            st = g["stereotypes"][0]
            assert "name" in st
            assert "count" in st


def test_mcp_set_tagged_values_registered(client, tool_names):
    """set_tagged_values tool appears in tools/list."""
    assert "set_tagged_values" in tool_names, \
        f"set_tagged_values not in tool list: {tool_names}"


def test_mcp_create_relationship_registered(client, tool_names):
    """create_relationship tool appears in tools/list."""
    assert "create_relationship" in tool_names, \
        f"create_relationship not in tool list: {tool_names}"


def test_mcp_modify_element_registered(client, tool_names):
    """modify_element tool appears in tools/list."""
    assert "modify_element" in tool_names, \
        f"modify_element not in tool list: {tool_names}"


def test_mcp_delete_element_create_then_delete(client, tool_names):
    """Create an element, verify it exists, delete it, verify it's gone."""
    required = ["create_element", "find_elements_by_type", "get_element_details", "delete_element"]
    missing = [n for n in required if n not in tool_names]
    if missing:
        pytest.skip(f"missing tools: {', '.join(missing)}")

    session_id = _mcp_init(client)

    # 1. Find the root model element to use as writable parent
    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 10, "method": "tools/call",
                                  "params": {"name": "find_elements_by_type",
                                             "arguments": {"type": "Model"}}},
                    headers={"Mcp-Session-Id": session_id})
    body = r.json()
    assert not body["result"].get("isError", False), f"find_elements_by_type failed: {body}"
    models = json.loads(body["result"]["content"][0]["text"])
    # The primary model is the one with no parent
    root_model = next(m for m in models if not m.get("parentId"))
    parent_id = root_model["id"]

    # 2. Create a test element
    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 11, "method": "tools/call",
                                  "params": {"name": "create_element",
                                             "arguments": {"type": "Class",
                                                           "name": "TempDeleteTest",
                                                           "parentId": parent_id}}},
                    headers={"Mcp-Session-Id": session_id})
    body = r.json()
    assert not body["result"].get("isError", False), f"Create failed: {body}"
    created = json.loads(body["result"]["content"][0]["text"])
    elem_id = created["id"]
    assert elem_id

    # 3. Verify element exists
    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 12, "method": "tools/call",
                                  "params": {"name": "get_element_details",
                                             "arguments": {"elementId": elem_id}}},
                    headers={"Mcp-Session-Id": session_id})
    body = r.json()
    assert not body["result"].get("isError", False)
    details = json.loads(body["result"]["content"][0]["text"])
    assert details["name"] == "TempDeleteTest"

    # 4. Delete the element
    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 13, "method": "tools/call",
                                  "params": {"name": "delete_element",
                                             "arguments": {"elementId": elem_id}}},
                    headers={"Mcp-Session-Id": session_id})
    body = r.json()
    assert not body["result"].get("isError", False), f"Delete failed: {body}"
    result = json.loads(body["result"]["content"][0]["text"])
    assert result["deleted"] is True
    assert result["elementId"] == elem_id
    assert result["name"] == "TempDeleteTest"
    if "type" in result:
        assert result["type"] is not None

    # 5. Verify element no longer exists (get_element_details should return error text)
    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 14, "method": "tools/call",
                                  "params": {"name": "get_element_details",
                                             "arguments": {"elementId": elem_id}}},
                    headers={"Mcp-Session-Id": session_id})
    body = r.json()
    content = json.loads(body["result"]["content"][0]["text"])
    # The tool should return an error map (not found)
    assert "error" in content or "not found" in str(content).lower()
