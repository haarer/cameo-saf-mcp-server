import os
import httpx
import pytest
import json

SERVER_URL = os.environ.get("SERVER_URL", "http://localhost:18750")


@pytest.fixture(scope="session")
def client():
    with httpx.Client(base_url=SERVER_URL, timeout=30) as c:
        yield c


def _mcp_init(client):
    payload = {
        "jsonrpc": "2.0",
        "id": 1,
        "method": "initialize",
        "params": {
            "protocolVersion": "2024-11-05",
            "capabilities": {},
            "clientInfo": {"name": "structural-test-client", "version": "1.0.0"}
        }
    }
    r = client.post("/mcp", json=payload)
    assert r.status_code == 200
    session_id = r.headers.get("mcp-session-id")
    assert session_id
    body = r.json()
    assert body["result"]["serverInfo"]["name"] == "cameo-saf-mcp-server"

    r = client.post("/mcp", json={"jsonrpc": "2.0", "method": "notifications/initialized"},
                    headers={"Mcp-Session-Id": session_id})
    assert r.status_code == 200

    return session_id


@pytest.fixture(scope="session")
def tool_names(client):
    session_id = _mcp_init(client)
    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
                    headers={"Mcp-Session-Id": session_id})
    body = r.json()
    return [t["name"] for t in body["result"]["tools"]]


def _call_tool(client, session_id, tool_name, arguments=None):
    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 100, "method": "tools/call",
                                  "params": {"name": tool_name, "arguments": arguments or {}}},
                    headers={"Mcp-Session-Id": session_id})
    assert r.status_code == 200
    body = r.json()
    assert "result" in body, f"Tool {tool_name} returned error: {body}"
    assert not body["result"].get("isError", False), f"Tool {tool_name} error: {body['result']['content']}"
    content = body["result"]["content"]
    assert len(content) > 0
    return json.loads(content[0]["text"])


def _call_tool_raw(client, session_id, tool_name, arguments=None):
    """Call a tool and return (ok, result). ok False if the tool errored."""
    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 100, "method": "tools/call",
                                  "params": {"name": tool_name, "arguments": arguments or {}}},
                    headers={"Mcp-Session-Id": session_id})
    body = r.json()
    if not body.get("result") or body["result"].get("isError", False):
        content = (body.get("result") or {}).get("content") or []
        text = content[0]["text"] if content else "unknown error"
        return False, text
    parsed = json.loads(body["result"]["content"][0]["text"])
    if isinstance(parsed, dict) and "error" in parsed:
        return False, parsed["error"]
    return True, parsed


@pytest.fixture(scope="module")
def writable_root(client):
    """The writable primary model element to parent throwaway classes under."""
    session_id = _mcp_init(client)
    models = _call_tool(client, session_id, "find_elements_by_type", {"type": "Model"})
    root = next(m for m in models if not m.get("parentId"))
    return root["id"]


REQUIRED = ["create_part", "set_type", "get_block_structure", "set_multiplicity"]


def test_structural_tools_registered(client, tool_names):
    for name in REQUIRED:
        assert name in tool_names, f"{name} not registered"


@pytest.fixture(scope="module")
def scratch_blocks(client, writable_root):
    """Create + tear down a throwaway whole/part Class pair for structural tests."""
    session_id = _mcp_init(client)

    whole = _call_tool(client, session_id, "create_element",
                       {"type": "Class", "name": "ScratchWhole", "parentId": writable_root})
    part_type = _call_tool(client, session_id, "create_element",
                           {"type": "Class", "name": "ScratchPartType", "parentId": writable_root})

    yield {
        "session_id": session_id,
        "whole_id": whole["id"],
        "part_type_id": part_type["id"],
    }

    _call_tool(client, session_id, "delete_element", {"elementId": whole["id"]})
    _call_tool(client, session_id, "delete_element", {"elementId": part_type["id"]})


def test_create_part_types_and_stereotypes(client, tool_names, scratch_blocks):
    if not all(n in tool_names for n in ["create_part", "get_block_structure"]):
        pytest.skip("structural tools not registered")
    sid = scratch_blocks["session_id"]
    whole = scratch_blocks["whole_id"]
    ptype = scratch_blocks["part_type_id"]

    res = _call_tool(client, sid, "create_part", {
        "wholeBlockId": whole,
        "name": "myPart",
        "partTypeBlockId": ptype,
        "multiplicity": "0..*",
        "aggregation": "composite",
    })
    assert res["created"] is True
    assert res["typedBy"] == "ScratchPartType"
    assert res["multiplicity"] == "0..*"
    part_id = res["id"]

    struct = _call_tool(client, sid, "get_block_structure", {"blockId": whole})
    parts = [p for p in struct["parts"] if p["id"] == part_id]
    assert parts, "created part not found in block structure"
    p = parts[0]
    assert p["name"] == "myPart"
    assert p["typedBy"] == "ScratchPartType"
    assert p["multiplicity"] == "0..*"
    assert "PartProperty" in p["stereotypes"]

    _call_tool(client, sid, "delete_element", {"elementId": part_id})


def test_set_type_on_unttyped_part(client, tool_names, scratch_blocks):
    if not all(n in tool_names for n in ["create_part", "set_type", "get_block_structure"]):
        pytest.skip("structural tools not registered")
    sid = scratch_blocks["session_id"]
    whole = scratch_blocks["whole_id"]
    ptype = scratch_blocks["part_type_id"]

    # create_part with an empty name descendants, then untype via set_type
    res = _call_tool(client, sid, "create_part", {
        "wholeBlockId": whole,
        "name": "untypedPart",
        "partTypeBlockId": ptype,
        "multiplicity": "1",
        "aggregation": "composite",
    })
    part_id = res["id"]

    # Re-type to the same type via set_type and confirm it reports the change.
    st = _call_tool(client, sid, "set_type", {"elementId": part_id, "typeId": ptype})
    assert st["updated"] is True
    assert st["newType"] == "ScratchPartType"

    _call_tool(client, sid, "delete_element", {"elementId": part_id})


def test_set_multiplicity(client, tool_names, scratch_blocks):
    if not all(n in tool_names for n in ["create_part", "set_multiplicity"]):
        pytest.skip("structural tools not registered")
    sid = scratch_blocks["session_id"]
    whole = scratch_blocks["whole_id"]
    ptype = scratch_blocks["part_type_id"]

    res = _call_tool(client, sid, "create_part", {
        "wholeBlockId": whole,
        "name": "multPart",
        "partTypeBlockId": ptype,
        "multiplicity": "1",
    })
    part_id = res["id"]

    m = _call_tool(client, sid, "set_multiplicity", {"elementId": part_id, "multiplicity": "0..5"})
    assert m["multiplicity"] == "0..5"
    assert m["updated"] is True

    _call_tool(client, sid, "delete_element", {"elementId": part_id})


REQUIRED += ["create_connector"]


def test_create_connector_owned_by_whole(client, tool_names, scratch_blocks):
    if not all(n in tool_names for n in ["create_element", "create_part", "create_connector", "get_block_structure", "list_owned_elements"]):
        pytest.skip("structural/connector tools not registered")
    sid = scratch_blocks["session_id"]
    whole = scratch_blocks["whole_id"]
    ptype = scratch_blocks["part_type_id"]

    # Give the part type a shared port, then two parts typed by it.
    port = _call_tool(client, sid, "create_element", {"type": "Port", "name": "link", "parentId": ptype})

    p1 = _call_tool(client, sid, "create_part", {"wholeBlockId": whole, "name": "cA", "partTypeBlockId": ptype, "multiplicity": "1"})
    p2 = _call_tool(client, sid, "create_part", {"wholeBlockId": whole, "name": "cB", "partTypeBlockId": ptype, "multiplicity": "1"})

    conn = _call_tool(client, sid, "create_connector", {
        "wholeBlockId": whole,
        "end1PartId": p1["id"],
        "end1PortId": port["id"],
        "end2PartId": p2["id"],
        "end2PortId": port["id"],
    })
    assert conn["created"] is True
    assert conn["end1"] == "cA::link"
    assert conn["end2"] == "cB::link"

    # Proper wiring: connector is named from its ends and each end is fully
    # wired (role + partWithPort) with a default multiplicity of 1.
    assert conn["name"] == "cA::link <-> cB::link", conn
    assert conn["ends"] == [
        {"role": "link", "partWithPort": "cA", "multiplicity": "1"},
        {"role": "link", "partWithPort": "cB", "multiplicity": "1"},
    ], conn["ends"]

    struct = _call_tool(client, sid, "get_block_structure", {"blockId": whole})
    conn_ids = [c["id"] for c in struct["connectors"]]
    assert conn["id"] in conn_ids, "connector not found in whole block structure"

    # get_block_structure exposes per-end multiplicity too.
    found = next(c for c in struct["connectors"] if c["id"] == conn["id"])
    assert [e["multiplicity"] for e in found["ends"]] == ["1", "1"], found["ends"]

    # Proper nested-end treatment: each ConnectorEnd carries the SysML
    # "NestedConnectorEnd" stereotype (which records the part-within-whole path).
    ends = _call_tool(client, sid, "list_owned_elements", {"parentId": conn["id"]})
    types = [e["type"] for e in ends]
    assert types == ["Connector End", "Connector End"], ends
    for e in ends:
        assert "NestedConnectorEnd" in e["stereotypes"], e["stereotypes"]

    _call_tool(client, sid, "delete_element", {"elementId": conn["id"]})
    _call_tool(client, sid, "delete_element", {"elementId": p1["id"]})
    _call_tool(client, sid, "delete_element", {"elementId": p2["id"]})
    _call_tool(client, sid, "delete_element", {"elementId": port["id"]})


def test_create_connector_explicit_end_multiplicities(client, tool_names, scratch_blocks):
    if not all(n in tool_names for n in ["create_element", "create_part", "create_connector"]):
        pytest.skip("structural/connector tools not registered")
    sid = scratch_blocks["session_id"]
    whole = scratch_blocks["whole_id"]
    ptype = scratch_blocks["part_type_id"]

    port = _call_tool(client, sid, "create_element", {"type": "Port", "name": "sock", "parentId": ptype})
    p1 = _call_tool(client, sid, "create_part", {"wholeBlockId": whole, "name": "mA", "partTypeBlockId": ptype, "multiplicity": "1"})
    p2 = _call_tool(client, sid, "create_part", {"wholeBlockId": whole, "name": "mB", "partTypeBlockId": ptype, "multiplicity": "1"})

    conn = _call_tool(client, sid, "create_connector", {
        "wholeBlockId": whole,
        "end1PartId": p1["id"], "end1PortId": port["id"],
        "end2PartId": p2["id"], "end2PortId": port["id"],
        "end1Multiplicity": "0..*", "end2Multiplicity": "2",
    })
    assert conn["created"] is True
    assert [e["multiplicity"] for e in conn["ends"]] == ["0..*", "2"], conn["ends"]

    _call_tool(client, sid, "delete_element", {"elementId": conn["id"]})
    _call_tool(client, sid, "delete_element", {"elementId": p1["id"]})
    _call_tool(client, sid, "delete_element", {"elementId": p2["id"]})
    _call_tool(client, sid, "delete_element", {"elementId": port["id"]})


def test_create_connector_rejects_port_not_owned_by_part_type(client, tool_names, scratch_blocks, writable_root):
    """A connector end whose port is not owned by the part's type must be rejected."""
    if not all(n in tool_names for n in ["create_element", "create_part", "create_connector"]):
        pytest.skip("structural/connector tools not registered")
    sid = scratch_blocks["session_id"]
    whole = scratch_blocks["whole_id"]
    ptype = scratch_blocks["part_type_id"]

    # A port on a *different* type than the part's type.
    other_type = _call_tool(client, sid, "create_element", {"type": "Class", "name": "ScratchOtherPortOwner", "parentId": writable_root})
    fp_ok, foreign_port = _call_tool_raw(client, sid, "create_element", {"type": "Port", "name": "foreign", "parentId": other_type["id"]})
    assert fp_ok, f"could not create foreign port: {foreign_port}"
    assert "id" in foreign_port, foreign_port

    p1 = _call_tool(client, sid, "create_part", {"wholeBlockId": whole, "name": "zA", "partTypeBlockId": ptype, "multiplicity": "1"})
    p2 = _call_tool(client, sid, "create_part", {"wholeBlockId": whole, "name": "zB", "partTypeBlockId": ptype, "multiplicity": "1"})

    ok, err = _call_tool_raw(client, sid, "create_connector", {
        "wholeBlockId": whole,
        "end1PartId": p1["id"], "end1PortId": foreign_port["id"],
        "end2PartId": p2["id"], "end2PortId": foreign_port["id"],
    })
    assert not ok, "connector with foreign port should have been rejected"
    assert "not owned by part" in err, err

    _call_tool(client, sid, "delete_element", {"elementId": p1["id"]})
    _call_tool(client, sid, "delete_element", {"elementId": p2["id"]})
    _call_tool(client, sid, "delete_element", {"elementId": foreign_port["id"]})
    _call_tool(client, sid, "delete_element", {"elementId": other_type["id"]})

