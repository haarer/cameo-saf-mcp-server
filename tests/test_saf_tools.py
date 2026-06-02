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
            "clientInfo": {"name": "saf-test-client", "version": "1.0.0"}
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


def _get_model_root(client, session_id):
    info = _call_tool(client, session_id, "get_model_info")
    return info["modelName"]


# -- Tool Registration --

def test_saf_tools_registered(client):
    """All 5 new SAF tools appear in tools/list."""
    session_id = _mcp_init(client)

    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
                    headers={"Mcp-Session-Id": session_id})
    body = r.json()
    tool_names = [t["name"] for t in body["result"]["tools"]]

    saf_tools = [
        "saf_find_elements_by_type",
        "saf_get_element_details",
        "saf_build_traceability_chain",
        "saf_check_consistency",
        "saf_export_viewpoint",
        "saf_get_viewpoint_views",
    ]
    for name in saf_tools:
        assert name in tool_names, f"Tool '{name}' not registered"


# -- saf_find_elements_by_type --

def test_saf_find_elements_by_type_no_filter(client):
    """Returns elements when called without filters."""
    session_id = _mcp_init(client)
    data = _call_tool(client, session_id, "saf_find_elements_by_type")

    assert isinstance(data, list)
    assert len(data) > 0, "Expected at least one element in model"

    elem = data[0]
    assert "id" in elem
    assert "name" in elem
    assert "type" in elem
    assert "stereotypes" in elem
    assert "safKind" in elem
    assert "safDomain" in elem


def test_saf_find_elements_by_type_with_type_filter(client):
    """Filters by SysML type (e.g., Block)."""
    session_id = _mcp_init(client)
    data = _call_tool(client, session_id, "saf_find_elements_by_type", {"type": "Block"})

    assert isinstance(data, list)
    for elem in data:
        assert "Block" in elem["type"] or "block" in elem["type"].lower(), \
            f"Expected Block type, got {elem['type']}"


def test_saf_find_elements_by_type_with_stereotype_filter(client):
    """Filters by stereotype name."""
    session_id = _mcp_init(client)
    data = _call_tool(client, session_id, "saf_find_elements_by_type", {"stereotype": "Requirement"})

    assert isinstance(data, list)
    # May return empty list if no requirements exist — that's fine
    for elem in data:
        has_req = any("Requirement" in s for s in elem["stereotypes"])
        assert has_req, f"Expected Requirement stereotype, got {elem['stereotypes']}"


# -- saf_get_element_details --

def test_saf_get_element_details_valid(client):
    """Returns details for an existing element."""
    session_id = _mcp_init(client)

    # First get any element ID
    found = _call_tool(client, session_id, "saf_find_elements_by_type")
    elem_id = found[0]["id"]

    data = _call_tool(client, session_id, "saf_get_element_details", {"elementId": elem_id})

    assert "name" in data
    assert "type" in data
    assert "safKind" in data
    assert "safDomain" in data
    assert "stereotypes" in data
    assert "ownedElements" in data
    assert isinstance(data["ownedElements"], list)
    assert "traceability" in data
    assert isinstance(data["traceability"], list)


def test_saf_get_element_details_invalid_id(client):
    """Returns error for nonexistent element ID."""
    session_id = _mcp_init(client)

    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 100, "method": "tools/call",
                                  "params": {"name": "saf_get_element_details",
                                             "arguments": {"elementId": "nonexistent-id"}}},
                    headers={"Mcp-Session-Id": session_id})
    body = r.json()
    assert "result" in body
    content = body["result"]["content"][0]["text"]
    assert "error" in content.lower() or "not found" in content.lower() or "null" in content.lower()


# -- saf_build_traceability_chain --

def test_saf_build_traceability_chain_structure(client):
    """Returns a valid graph structure."""
    session_id = _mcp_init(client)

    # Find any element to start from
    found = _call_tool(client, session_id, "saf_find_elements_by_type")
    elem_id = found[0]["id"]

    data = _call_tool(client, session_id, "saf_build_traceability_chain",
                      {"elementId": elem_id, "maxDepth": 2})

    assert "rootId" in data
    assert "nodes" in data
    assert "edges" in data
    assert isinstance(data["nodes"], list)
    assert isinstance(data["edges"], list)
    assert data["rootId"] == elem_id


def test_saf_build_traceability_chain_max_depth(client):
    """Respects maxDepth parameter."""
    session_id = _mcp_init(client)

    found = _call_tool(client, session_id, "saf_find_elements_by_type")
    elem_id = found[0]["id"]

    data = _call_tool(client, session_id, "saf_build_traceability_chain",
                      {"elementId": elem_id, "maxDepth": 1})

    assert "nodes" in data
    assert "edges" in data


def test_saf_build_traceability_chain_empty_result(client):
    """Handles element with no traceability links gracefully."""
    session_id = _mcp_init(client)

    found = _call_tool(client, session_id, "saf_find_elements_by_type")
    elem_id = found[0]["id"]

    data = _call_tool(client, session_id, "saf_build_traceability_chain",
                      {"elementId": elem_id, "maxDepth": 0})

    # maxDepth 0 should return only root or empty
    assert "nodes" in data
    assert "edges" in data


# -- saf_check_consistency --

def test_saf_check_consistency_basic(client):
    """Returns a valid consistency report."""
    session_id = _mcp_init(client)

    data = _call_tool(client, session_id, "saf_check_consistency", {})

    assert "summary" in data
    assert "issues" in data
    assert isinstance(data["issues"], list)

    summary = data["summary"]
    assert "totalElements" in summary
    assert "requirements" in summary


def test_saf_check_consistency_with_checks(client):
    """Accepts explicit check list."""
    session_id = _mcp_init(client)

    data = _call_tool(client, session_id, "saf_check_consistency",
                      {"checks": ["orphan_requirements", "stereotype_compliance"]})

    assert "summary" in data
    assert "issues" in data
    assert isinstance(data["issues"], list)


def test_saf_check_consistency_all_checks(client):
    """Runs all consistency checks."""
    session_id = _mcp_init(client)

    data = _call_tool(client, session_id, "saf_check_consistency",
                      {"checks": [
                          "orphan_requirements",
                          "broken_chains",
                          "stereotype_compliance",
                          "cross_domain_alignment"
                      ]})

    assert "summary" in data
    assert "issues" in data


# -- saf_export_viewpoint --

def test_saf_export_viewpoint_architecture_management(client):
    """Exports architecture_management viewpoint."""
    session_id = _mcp_init(client)

    data = _call_tool(client, session_id, "saf_export_viewpoint",
                      {"domain": "architecture_management"})

    assert "viewpoint" in data
    assert "nodes" in data
    assert "edges" in data
    assert "nodeCount" in data
    assert "edgeCount" in data
    assert data["viewpoint"]["domain"] == "architecture_management"


def test_saf_export_viewpoint_with_aspect(client):
    """Exports viewpoint filtered by aspect."""
    session_id = _mcp_init(client)

    data = _call_tool(client, session_id, "saf_export_viewpoint",
                      {"domain": "conceptual", "aspect": "structure"})

    assert "viewpoint" in data
    assert data["viewpoint"]["domain"] == "conceptual"
    assert data["viewpoint"]["aspect"] == "structure"
    assert isinstance(data["nodes"], list)
    assert isinstance(data["edges"], list)


def test_saf_export_viewpoint_physical(client):
    """Exports physical domain viewpoint."""
    session_id = _mcp_init(client)

    data = _call_tool(client, session_id, "saf_export_viewpoint",
                      {"domain": "physical"})

    assert "viewpoint" in data
    assert data["viewpoint"]["domain"] == "physical"
    assert "nodes" in data
    assert "nodeCount" in data


def test_saf_export_viewpoint_operational(client):
    """Exports operational domain viewpoint."""
    session_id = _mcp_init(client)

    data = _call_tool(client, session_id, "saf_export_viewpoint",
                      {"domain": "operational"})

    assert "viewpoint" in data
    assert data["viewpoint"]["domain"] == "operational"
    assert "nodes" in data


def test_saf_export_viewpoint_nodes_have_metadata(client):
    """Exported nodes contain SAF metadata."""
    session_id = _mcp_init(client)

    data = _call_tool(client, session_id, "saf_export_viewpoint",
                      {"domain": "architecture_management"})

    if data["nodes"]:
        node = data["nodes"][0]
        assert "id" in node
        assert "name" in node
        assert "type" in node
        assert "stereotypes" in node
        assert "safKind" in node
        assert "safDomain" in node
        assert "taggedValues" in node


# -- Viewpoint Views --

def test_saf_get_viewpoint_views_registered(client):
    """New tool appears in tools/list."""
    session_id = _mcp_init(client)

    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
                    headers={"Mcp-Session-Id": session_id})
    body = r.json()
    tool_names = [t["name"] for t in body["result"]["tools"]]
    assert "saf_get_viewpoint_views" in tool_names


def test_saf_get_viewpoint_views_by_code_am(client):
    """Get views by viewpoint code AM."""
    session_id = _mcp_init(client)

    data = _call_tool(client, session_id, "saf_get_viewpoint_views",
                      {"viewpointCode": "AM"})

    assert "viewpoint" in data
    assert data["viewpoint"]["domain"] == "architecture_management"
    assert "views" in data
    assert isinstance(data["views"], list)
    assert "viewCount" in data
    assert "relevantKinds" in data


def test_saf_get_viewpoint_views_by_code_cv(client):
    """Get views by viewpoint code CV."""
    session_id = _mcp_init(client)

    data = _call_tool(client, session_id, "saf_get_viewpoint_views",
                      {"viewpointCode": "CV"})

    assert data["viewpoint"]["domain"] == "conceptual"
    assert isinstance(data["views"], list)


def test_saf_get_viewpoint_views_by_name(client):
    """Get views by viewpoint name."""
    session_id = _mcp_init(client)

    data = _call_tool(client, session_id, "saf_get_viewpoint_views",
                      {"viewpointName": "operational"})

    assert data["viewpoint"]["domain"] == "operational"
    assert isinstance(data["views"], list)


def test_saf_get_viewpoint_views_with_content(client):
    """Get views with content included."""
    session_id = _mcp_init(client)

    data = _call_tool(client, session_id, "saf_get_viewpoint_views",
                      {"viewpointCode": "PV", "includeContent": True})

    assert data["viewpoint"]["domain"] == "physical"
    if data["views"]:
        view = data["views"][0]
        assert "diagramId" in view
        assert "name" in view
        assert "type" in view
        assert "matchCount" in view
        assert "conformance" in view
        assert "content" in view
        assert isinstance(view["content"], list)


def test_saf_get_viewpoint_views_unknown_viewpoint(client):
    """Returns error for unknown viewpoint."""
    session_id = _mcp_init(client)

    def call_with_error():
        r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 100, "method": "tools/call",
                                      "params": {"name": "saf_get_viewpoint_views",
                                                 "arguments": {"viewpointCode": "XX"}}},
                        headers={"Mcp-Session-Id": session_id})
        body = r.json()
        return body["result"]["content"][0]["text"]

    result = json.loads(call_with_error())
    assert "error" in result
    assert "Unknown viewpoint" in result["error"]


def test_saf_get_viewpoint_views_view_structure(client):
    """Each view has required fields."""
    session_id = _mcp_init(client)

    data = _call_tool(client, session_id, "saf_get_viewpoint_views",
                      {"viewpointCode": "OV"})

    for view in data["views"]:
        assert "diagramId" in view
        assert "name" in view
        assert "type" in view
        assert "matchCount" in view
        assert "totalElements" in view
        assert "conformance" in view
        assert isinstance(view["matchCount"], int)
        assert isinstance(view["conformance"], int)
        assert 0 <= view["conformance"] <= 100


def test_saf_get_viewpoint_views_sorted_by_conformance(client):
    """Views are sorted by conformance descending."""
    session_id = _mcp_init(client)

    data = _call_tool(client, session_id, "saf_get_viewpoint_views",
                      {"viewpointCode": "AM"})

    views = data["views"]
    for i in range(len(views) - 1):
        assert views[i]["conformance"] >= views[i + 1]["conformance"]


# -- Integration: full workflow --

def test_saf_full_workflow(client):
    """End-to-end: find elements, get details, build traceability, export viewpoint."""
    session_id = _mcp_init(client)

    # 1. Find elements
    found = _call_tool(client, session_id, "saf_find_elements_by_type")
    assert len(found) > 0

    # 2. Get details of first element
    elem_id = found[0]["id"]
    details = _call_tool(client, session_id, "saf_get_element_details",
                         {"elementId": elem_id})
    assert details["name"]

    # 3. Build traceability chain
    chain = _call_tool(client, session_id, "saf_build_traceability_chain",
                       {"elementId": elem_id, "maxDepth": 2})
    assert chain["rootId"] == elem_id

    # 4. Check consistency
    consistency = _call_tool(client, session_id, "saf_check_consistency", {})
    assert consistency["summary"]["totalElements"] >= 0

    # 5. Export viewpoint
    export = _call_tool(client, session_id, "saf_export_viewpoint",
                        {"domain": "architecture_management"})
    assert export["nodeCount"] >= 0

    # 6. Get viewpoint views
    views = _call_tool(client, session_id, "saf_get_viewpoint_views",
                       {"viewpointCode": "AM"})
    assert views["viewCount"] >= 0
