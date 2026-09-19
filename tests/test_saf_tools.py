import os
import httpx
import pytest
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


@pytest.fixture(scope="session")
def tool_names(client):
    """Discover available tool names from the server."""
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


def _require_tools(tool_names, *names):
    """Skip if any of the given tools are missing."""
    missing = [n for n in names if n not in tool_names]
    if missing:
        pytest.skip(f"missing tools: {', '.join(missing)}")


# SAF tools defined in scripts/saf_tools.groovy
SAF_TOOLS = [
    "saf_create_element",
    "saf_set_requirement_tags",
    "saf_create_relationship",
    "saf_query_viewpoint",
    "saf_find_elements_by_type",
    "saf_get_element_semantics",
    "saf_build_traceability_chain",
    "saf_check_consistency",
    "saf_get_viewpoint_views",
    "saf_export_viewpoint",
]


# -- Tool Registration --

@pytest.mark.parametrize("tool_name", SAF_TOOLS)
def test_saf_tool_registered(tool_name, tool_names):
    """Each SAF tool from saf_tools.groovy appears in tools/list."""
    assert tool_name in tool_names, \
        f"Tool '{tool_name}' not registered (server has: {tool_names})"


# -- saf_find_elements_by_type --

def test_saf_find_elements_by_type_no_filter(client, tool_names):
    """Returns elements when called without filters."""
    _require_tools(tool_names, "saf_find_elements_by_type")
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


def test_saf_find_elements_by_type_with_type_filter(client, tool_names):
    """Filters by SysML type (e.g., Block)."""
    _require_tools(tool_names, "saf_find_elements_by_type")
    session_id = _mcp_init(client)
    data = _call_tool(client, session_id, "saf_find_elements_by_type", {"type": "Block"})

    assert isinstance(data, list)
    for elem in data:
        assert "Block" in elem["type"] or "block" in elem["type"].lower(), \
            f"Expected Block type, got {elem['type']}"


def test_saf_find_elements_by_type_with_stereotype_filter(client, tool_names):
    """Filters by stereotype name."""
    _require_tools(tool_names, "saf_find_elements_by_type")
    session_id = _mcp_init(client)
    data = _call_tool(client, session_id, "saf_find_elements_by_type", {"stereotype": "Requirement"})

    assert isinstance(data, list)
    for elem in data:
        has_req = any("Requirement" in s for s in elem["stereotypes"])
        assert has_req, f"Expected Requirement stereotype, got {elem['stereotypes']}"


# -- saf_get_element_semantics --

def test_saf_get_element_semantics_valid(client, tool_names):
    """Returns SAF interpretation for multiple elements (batch)."""
    _require_tools(tool_names, "saf_find_elements_by_type", "saf_get_element_semantics")
    session_id = _mcp_init(client)

    found = _call_tool(client, session_id, "saf_find_elements_by_type")
    ids = [e["id"] for e in found[:3]]

    data = _call_tool(client, session_id, "saf_get_element_semantics", {"elementIds": ids})

    assert isinstance(data, list)
    assert len(data) == len(ids)
    for row in data:
        if "error" in row:
            continue
        assert "id" in row
        assert "name" in row
        assert "type" in row
        assert "stereotypes" in row
        assert isinstance(row["stereotypes"], list)
        assert "safKind" in row
        assert "safDomain" in row
        assert "viewpoints" in row


def test_saf_get_element_semantics_invalid_id(client, tool_names):
    """Returns error entry for nonexistent element ID."""
    _require_tools(tool_names, "saf_get_element_semantics")
    session_id = _mcp_init(client)

    data = _call_tool(client, session_id, "saf_get_element_semantics",
                      {"elementIds": ["nonexistent-id"]})

    assert isinstance(data, list)
    assert len(data) == 1
    assert "error" in data[0]


def test_saf_get_element_semantics_ambiguity_contract(client, tool_names):
    """Every element must carry ambiguous, candidateKinds, safKind, safDomain.
    The ambiguous flag must be True iff candidateKinds has >1 entry, and safKind
    (when non-empty) must be one of the candidate kinds."""
    _require_tools(tool_names, "saf_find_elements_by_type", "saf_get_element_semantics")
    session_id = _mcp_init(client)

    # Sample a few elements of different types
    cap = _call_tool(client, session_id, "saf_find_elements_by_type",
                     {"stereotype": "SAF_OperationalCapability"})
    req = _call_tool(client, session_id, "saf_find_elements_by_type",
                     {"stereotype": "SAF_SystemRequirement"})
    flow = _call_tool(client, session_id, "saf_find_elements_by_type",
                      {"type": "Flow", "name": "flow for Visual Observation"})
    ids = [e["id"] for e in (cap[:1] + req[:1] + flow[:1])]
    assert len(ids) >= 1, "need at least one test element"

    data = _call_tool(client, session_id, "saf_get_element_semantics", {"elementIds": ids})
    assert len(data) == len(ids)

    for row in data:
        if "error" in row:
            continue
        assert isinstance(row.get("candidateKinds"), list), f"candidateKinds missing: {row}"
        assert isinstance(row.get("ambiguous"), bool), f"ambiguous missing: {row}"
        n_candidates = len(row["candidateKinds"])
        assert row["ambiguous"] == (n_candidates > 1), \
            f"ambiguous flag inconsistent with {n_candidates} candidates: {row}"
        if row["safKind"] and n_candidates > 1:
            kind_set = {c["kind"] for c in row["candidateKinds"]}
            assert row["safKind"] in kind_set, \
                f"safKind '{row['safKind']}' not in candidates {kind_set}: {row}"


def test_saf_get_element_semantics_ambiguous(client, tool_names):
    """An ItemFlow element must report ambiguous=True and list all three item-exchange
    candidate kinds (conceptual/operational/physical) from the ItemFlow stereotype.
    When conveyed items resolve a domain, disambiguation should be present."""
    _require_tools(tool_names, "saf_find_elements_by_type", "saf_get_element_semantics")
    session_id = _mcp_init(client)

    found = _call_tool(client, session_id, "saf_find_elements_by_type",
                       {"type": "Flow", "name": "flow for Visual Observation"})
    assert len(found) >= 1, "need at least one ItemFlow 'flow for Visual Observation'"
    elem_id = found[0]["id"]

    data = _call_tool(client, session_id, "saf_get_element_semantics", {"elementIds": [elem_id]})
    row = data[0]

    assert row["ambiguous"] is True
    kind_names = {c["kind"] for c in row["candidateKinds"]}
    assert {"conceptual_item_exchange", "operational_item_exchange", "physical_item_exchange"} == kind_names
    for c in row["candidateKinds"]:
        assert "concept" in c
        assert "stereotype" in c
        assert "domain" in c
        assert c["domain"] in ("conceptual", "operational", "physical")
    # safKind is either a single disambiguated kind or "" if unresolved
    assert row["safKind"] in kind_names or row["safKind"] == ""
    assert row["safDomain"] in ("conceptual", "operational", "physical", "")


# -- saf_build_traceability_chain --

def test_saf_build_traceability_chain_structure(client, tool_names):
    """Returns a valid graph structure."""
    _require_tools(tool_names, "saf_find_elements_by_type", "saf_build_traceability_chain")
    session_id = _mcp_init(client)

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


def test_saf_build_traceability_chain_max_depth(client, tool_names):
    """Respects maxDepth parameter."""
    _require_tools(tool_names, "saf_find_elements_by_type", "saf_build_traceability_chain")
    session_id = _mcp_init(client)

    found = _call_tool(client, session_id, "saf_find_elements_by_type")
    elem_id = found[0]["id"]

    data = _call_tool(client, session_id, "saf_build_traceability_chain",
                      {"elementId": elem_id, "maxDepth": 1})

    assert "nodes" in data
    assert "edges" in data


def test_saf_build_traceability_chain_empty_result(client, tool_names):
    """Handles element with no traceability links gracefully."""
    _require_tools(tool_names, "saf_find_elements_by_type", "saf_build_traceability_chain")
    session_id = _mcp_init(client)

    found = _call_tool(client, session_id, "saf_find_elements_by_type")
    elem_id = found[0]["id"]

    data = _call_tool(client, session_id, "saf_build_traceability_chain",
                      {"elementId": elem_id, "maxDepth": 0})

    assert "nodes" in data
    assert "edges" in data


# -- saf_check_consistency --

def test_saf_check_consistency_basic(client, tool_names):
    """Returns a valid consistency report."""
    _require_tools(tool_names, "saf_check_consistency")
    session_id = _mcp_init(client)

    data = _call_tool(client, session_id, "saf_check_consistency", {})

    assert "summary" in data
    assert "issues" in data
    assert isinstance(data["issues"], list)

    summary = data["summary"]
    assert "totalElements" in summary
    assert "requirements" in summary


def test_saf_check_consistency_with_checks(client, tool_names):
    """Accepts explicit check list."""
    _require_tools(tool_names, "saf_check_consistency")
    session_id = _mcp_init(client)

    data = _call_tool(client, session_id, "saf_check_consistency",
                      {"checks": ["orphan_requirements", "stereotype_compliance"]})

    assert "summary" in data
    assert "issues" in data
    assert isinstance(data["issues"], list)


def test_saf_check_consistency_all_checks(client, tool_names):
    """Runs all consistency checks."""
    _require_tools(tool_names, "saf_check_consistency")
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

def test_saf_export_viewpoint_architecture_management(client, tool_names):
    """Exports architecture_management viewpoint."""
    _require_tools(tool_names, "saf_export_viewpoint")
    session_id = _mcp_init(client)

    data = _call_tool(client, session_id, "saf_export_viewpoint",
                      {"domain": "architecture_management"})

    assert "viewpoint" in data
    assert "nodes" in data
    assert "edges" in data
    assert "nodeCount" in data
    assert "edgeCount" in data
    assert data["viewpoint"]["domain"] == "architecture_management"


def test_saf_export_viewpoint_with_aspect(client, tool_names):
    """Exports viewpoint filtered by aspect."""
    _require_tools(tool_names, "saf_export_viewpoint")
    session_id = _mcp_init(client)

    data = _call_tool(client, session_id, "saf_export_viewpoint",
                      {"domain": "conceptual", "aspect": "structure"})

    assert "viewpoint" in data
    assert data["viewpoint"]["domain"] == "conceptual"
    assert data["viewpoint"]["aspect"] == "structure"
    assert isinstance(data["nodes"], list)
    assert isinstance(data["edges"], list)


def test_saf_export_viewpoint_physical(client, tool_names):
    """Exports physical domain viewpoint."""
    _require_tools(tool_names, "saf_export_viewpoint")
    session_id = _mcp_init(client)

    data = _call_tool(client, session_id, "saf_export_viewpoint",
                      {"domain": "physical"})

    assert "viewpoint" in data
    assert data["viewpoint"]["domain"] == "physical"
    assert "nodes" in data
    assert "nodeCount" in data


def test_saf_export_viewpoint_operational(client, tool_names):
    """Exports operational domain viewpoint."""
    _require_tools(tool_names, "saf_export_viewpoint")
    session_id = _mcp_init(client)

    data = _call_tool(client, session_id, "saf_export_viewpoint",
                      {"domain": "operational"})

    assert "viewpoint" in data
    assert data["viewpoint"]["domain"] == "operational"
    assert "nodes" in data


def test_saf_export_viewpoint_nodes_have_metadata(client, tool_names):
    """Exported nodes contain SAF metadata."""
    _require_tools(tool_names, "saf_export_viewpoint")
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

def test_saf_get_viewpoint_views_by_code_am(client, tool_names):
    """Get views by viewpoint code AM."""
    _require_tools(tool_names, "saf_get_viewpoint_views")
    session_id = _mcp_init(client)

    data = _call_tool(client, session_id, "saf_get_viewpoint_views",
                      {"viewpointCode": "AM"})

    assert "viewpoint" in data
    assert data["viewpoint"]["domain"] == "architecture_management"
    assert "views" in data
    assert isinstance(data["views"], list)
    assert "viewCount" in data
    assert "relevantKinds" in data


def test_saf_get_viewpoint_views_by_code_cv(client, tool_names):
    """Get views by viewpoint code CV."""
    _require_tools(tool_names, "saf_get_viewpoint_views")
    session_id = _mcp_init(client)

    data = _call_tool(client, session_id, "saf_get_viewpoint_views",
                      {"viewpointCode": "CV"})

    assert data["viewpoint"]["domain"] == "conceptual"
    assert isinstance(data["views"], list)


def test_saf_get_viewpoint_views_by_name(client, tool_names):
    """Get views by viewpoint name."""
    _require_tools(tool_names, "saf_get_viewpoint_views")
    session_id = _mcp_init(client)

    data = _call_tool(client, session_id, "saf_get_viewpoint_views",
                      {"viewpointName": "operational"})

    assert data["viewpoint"]["domain"] == "operational"
    assert isinstance(data["views"], list)


def test_saf_get_viewpoint_views_with_content(client, tool_names):
    """Get views with content included."""
    _require_tools(tool_names, "saf_get_viewpoint_views")
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


def test_saf_get_viewpoint_views_unknown_viewpoint(client, tool_names):
    """Returns error for unknown viewpoint."""
    _require_tools(tool_names, "saf_get_viewpoint_views")
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


def test_saf_get_viewpoint_views_view_structure(client, tool_names):
    """Each view has required fields."""
    _require_tools(tool_names, "saf_get_viewpoint_views")
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


def test_saf_get_viewpoint_views_sorted_by_conformance(client, tool_names):
    """Views are sorted by conformance descending."""
    _require_tools(tool_names, "saf_get_viewpoint_views")
    session_id = _mcp_init(client)

    data = _call_tool(client, session_id, "saf_get_viewpoint_views",
                      {"viewpointCode": "AM"})

    views = data["views"]
    for i in range(len(views) - 1):
        assert views[i]["conformance"] >= views[i + 1]["conformance"]


# -- Integration: full workflow --

def test_saf_full_workflow(client, tool_names):
    """End-to-end: find elements, get details, build traceability, export viewpoint."""
    _require_tools(tool_names,
        "saf_find_elements_by_type",
        "saf_get_element_semantics",
        "saf_build_traceability_chain",
        "saf_check_consistency",
        "saf_export_viewpoint",
        "saf_get_viewpoint_views",
    )
    session_id = _mcp_init(client)

    # 1. Find elements
    found = _call_tool(client, session_id, "saf_find_elements_by_type")
    assert len(found) > 0

    # 2. Get semantics of first named element
    #    (unfiltered results may legitimately start with unnamed elements,
    #    e.g. unnamed activity actions in the loaded model)
    elem_id = next(e["id"] for e in found if e.get("name"))
    semantics = _call_tool(client, session_id, "saf_get_element_semantics",
                           {"elementIds": [elem_id]})
    assert isinstance(semantics, list)
    assert semantics[0]["name"]

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
