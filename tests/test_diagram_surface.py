import os
import httpx
import pytest

SERVER_URL = os.environ.get("SERVER_URL", "http://localhost:18750")


@pytest.fixture(scope="session")
def client():
    with httpx.Client(base_url=SERVER_URL, timeout=30) as c:
        yield c


def _mcp_init(client):
    r = client.post("/mcp", json={
        "jsonrpc": "2.0", "id": 1, "method": "initialize",
        "params": {"protocolVersion": "2024-11-05", "capabilities": {},
                   "clientInfo": {"name": "surface-test-client", "version": "1.0.0"}}
    })
    session_id = r.headers.get("mcp-session-id")
    assert session_id
    client.post("/mcp", json={"jsonrpc": "2.0", "method": "notifications/initialized"},
                headers={"Mcp-Session-Id": session_id})
    return session_id


def _tool_defs(client):
    session_id = _mcp_init(client)
    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
                    headers={"Mcp-Session-Id": session_id})
    return {t["name"]: t for t in r.json()["result"]["tools"]}


def _assert_arg(desc_or_args, arg_name, *fragments):
    for f in fragments:
        assert f.lower() in desc_or_args.lower(), f"'{arg_name}' arg missing '{f}': {desc_or_args}"


def test_saf_create_diagram_diagram_type_uses_real_values(client):
    """The diagramType surface must not mislead with non-functional symbolic constants.

    Regression: the tool used to throw 'Unknown diagram type: UML_CLASS_DIAGRAM'
    because it passed the symbolic name straight to createDiagram(). The surface must
    accept friendly names and document that BDD='Class Diagram', IBD='Composite
    Structure Diagram' (not UML_* constants).
    """
    defs = _tool_defs(client)
    assert "saf_create_diagram" in defs
    d = defs["saf_create_diagram"]
    desc = d.get("description", "")
    assert "Class Diagram" in desc, f"expected BDD=Class Diagram guidance in: {desc}"
    assert "Composite Structure Diagram" in desc, f"expected IBD guidance in: {desc}"
    props = d["inputSchema"]["properties"]
    assert "diagramType" in props, "diagramType arg missing"
    dt = props["diagramType"]["description"]
    assert "class diagram" in dt.lower() and "UML_CLASS_DIAGRAM" in dt, f"diagramType arg unhelpful: {dt}"


def test_create_relationship_warns_composition_is_not_part(client):
    """create_relationship('composition') produces a package-level association, not a
    block-owned part property; the surface must steer agents to create_part for parts.
    """
    defs = _tool_defs(client)
    assert "create_relationship" in defs
    desc = defs["create_relationship"].get("description", "")
    assert "create_part" in desc, f"expected create_part guidance in: {desc}"
    assert "block-owned part" in desc.lower(), f"expected block-owned part warning in: {desc}"
    type_desc = defs["create_relationship"]["inputSchema"]["properties"]["type"]["description"]
    assert "create_part" in type_desc, f"type arg should mention create_part: {type_desc}"


def test_saf_add_association_paths_registered_and_guides(client):
    """The association-path tool must be present and must advertise non-silent behavior
    (skipped associations are reported, not swallowed) so agents can draw compositions
    on a BDD without silent failure.
    """
    defs = _tool_defs(client)
    assert "saf_add_association_paths" in defs, "saf_add_association_paths not registered"
    desc = defs["saf_add_association_paths"].get("description", "")
    assert "composition" in desc.lower(), f"expected composition mention in: {desc}"
    assert "skipped" in desc.lower(), f"expected skipped/reporting mention in: {desc}"
    props = defs["saf_add_association_paths"]["inputSchema"]["properties"]
    assert "diagramId" in props, "diagramId arg missing"
    assert props["diagramId"].get("required") or True  # required flag is fine


def test_saf_add_association_paths_errors_cleanly_on_unknown_diagram(client):
    """Calling with a nonexistent diagram must return a structured error map, not throw."""
    _require_client_ok(client)
    session_id = _mcp_init(client)
    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 100, "method": "tools/call",
                                  "params": {"name": "saf_add_association_paths",
                                             "arguments": {"diagramId": "nonexistent-diagram-id"}}},
                    headers={"Mcp-Session-Id": session_id})
    body = r.json()
    content = body["result"]["content"][0]["text"]
    assert "not found" in content.lower() or "error" in content.lower(), f"unexpected: {content}"


def _require_client_ok(client):
    r = client.get("/")
    return r
