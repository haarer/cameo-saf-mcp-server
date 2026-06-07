import os
import httpx
import pytest
import json

SERVER_URL = os.environ.get("SERVER_URL", "http://localhost:18750")

KNOWN_VIEWPOINT = "Operational Story Viewpoint"
KNOWN_VP_ID = "O1_OSTY"
KNOWN_CONCEPT = "System Requirement"
KNOWN_CONCERN = "For what purpose is the system developed or adapted?"
KNOWN_STAKEHOLDER = "System Architect"
KNOWN_STEREOTYPE = "SAF_SystemRequirement"


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
            "clientInfo": {"name": "spec-test-client", "version": "1.0.0"}
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


# -- Tool Registration --

def test_spec_tools_registered(client):
    """All 15 spec tools appear in tools/list."""
    session_id = _mcp_init(client)

    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
                    headers={"Mcp-Session-Id": session_id})
    body = r.json()
    tool_names = [t["name"] for t in body["result"]["tools"]]

    spec_tools = [
        "spec_list_viewpoints",
        "spec_list_concepts",
        "spec_list_concerns",
        "spec_list_stakeholders",
        "spec_list_stereotypes",
        "spec_search",
        "spec_get_viewpoint",
        "spec_get_concept",
        "spec_get_concern",
        "spec_get_stakeholder",
        "spec_get_stereotype",
        "spec_get_viewpoint_concepts",
        "spec_get_viewpoint_concerns",
        "spec_get_concept_stereotypes",
        "spec_get_special_implementations",
    ]
    for name in spec_tools:
        assert name in tool_names, f"Tool '{name}' not registered"


# -- spec_list_viewpoints --

def test_spec_list_viewpoints_returns_list(client):
    """Returns a non-empty list of viewpoints."""
    session_id = _mcp_init(client)

    data = _call_tool(client, session_id, "spec_list_viewpoints")

    assert isinstance(data, list)
    assert len(data) > 0

    vp = data[0]
    assert "id" in vp
    assert "name" in vp
    assert "vpId" in vp
    assert "domain" in vp
    assert "aspect" in vp
    assert "maturity" in vp


def test_spec_list_viewpoints_includes_known(client):
    """Known viewpoints appear in the list."""
    session_id = _mcp_init(client)

    data = _call_tool(client, session_id, "spec_list_viewpoints")

    names = [vp["name"] for vp in data]
    vp_ids = [vp["vpId"] for vp in data]
    assert KNOWN_VIEWPOINT in names
    assert KNOWN_VP_ID in vp_ids


# -- spec_list_concepts --

def test_spec_list_concepts_returns_list(client):
    """Returns a non-empty list of concepts."""
    session_id = _mcp_init(client)

    data = _call_tool(client, session_id, "spec_list_concepts")

    assert isinstance(data, list)
    assert len(data) > 0

    c = data[0]
    assert "id" in c
    assert "name" in c
    assert "type" in c


def test_spec_list_concepts_includes_known(client):
    """Known concepts appear in the list."""
    session_id = _mcp_init(client)

    data = _call_tool(client, session_id, "spec_list_concepts")

    names = [c["name"] for c in data]
    assert KNOWN_CONCEPT in names


# -- spec_list_concerns --

def test_spec_list_concerns_returns_list(client):
    """Returns a non-empty list of concerns."""
    session_id = _mcp_init(client)

    data = _call_tool(client, session_id, "spec_list_concerns")

    assert isinstance(data, list)
    assert len(data) > 0

    c = data[0]
    assert "id" in c
    assert "name" in c


def test_spec_list_concerns_includes_known(client):
    """Known concerns appear in the list."""
    session_id = _mcp_init(client)

    data = _call_tool(client, session_id, "spec_list_concerns")

    names = [c["name"] for c in data]
    assert KNOWN_CONCERN in names


# -- spec_list_stakeholders --

def test_spec_list_stakeholders_returns_list(client):
    """Returns a non-empty list of stakeholders."""
    session_id = _mcp_init(client)

    data = _call_tool(client, session_id, "spec_list_stakeholders")

    assert isinstance(data, list)
    assert len(data) > 0

    s = data[0]
    assert "id" in s
    assert "name" in s


def test_spec_list_stakeholders_includes_known(client):
    """Known stakeholders appear in the list."""
    session_id = _mcp_init(client)

    data = _call_tool(client, session_id, "spec_list_stakeholders")

    names = [s["name"] for s in data]
    assert KNOWN_STAKEHOLDER in names


# -- spec_list_stereotypes --

def test_spec_list_stereotypes_returns_list(client):
    """Returns a non-empty list of stereotypes."""
    session_id = _mcp_init(client)

    data = _call_tool(client, session_id, "spec_list_stereotypes")

    assert isinstance(data, list)
    assert len(data) > 0

    s = data[0]
    assert "id" in s
    assert "name" in s


def test_spec_list_stereotypes_includes_known(client):
    """Known stereotypes appear in the list."""
    session_id = _mcp_init(client)

    data = _call_tool(client, session_id, "spec_list_stereotypes")

    names = [s["name"] for s in data]
    assert KNOWN_STEREOTYPE in names


# -- spec_search --

def test_spec_search_returns_results(client):
    """Returns results for a valid query."""
    session_id = _mcp_init(client)

    data = _call_tool(client, session_id, "spec_search", {"query": "system"})

    assert isinstance(data, list)
    assert len(data) > 0

    r = data[0]
    assert "type" in r
    assert "name" in r
    assert "id" in r


def test_spec_search_matches_by_name(client):
    """Search results contain the queried term in names."""
    session_id = _mcp_init(client)

    data = _call_tool(client, session_id, "spec_search", {"query": "requirement"})

    assert len(data) > 0
    # Concern search results use truncated question text as name (100 chars),
    # so check at least some results have the query in their name
    assert any("requirement" in r["name"].lower() for r in data)


def test_spec_search_returns_empty_for_gibberish(client):
    """Returns empty list for non-matching query."""
    session_id = _mcp_init(client)

    data = _call_tool(client, session_id, "spec_search", {"query": "xyznonexistent12345"})

    assert isinstance(data, list)
    assert len(data) == 0


def test_spec_search_includes_multiple_entity_types(client):
    """Search can return different entity types."""
    session_id = _mcp_init(client)

    data = _call_tool(client, session_id, "spec_search", {"query": "system"})

    types = {r["type"] for r in data}
    assert len(types) >= 2


# -- spec_get_viewpoint --

def test_spec_get_viewpoint_by_name(client):
    """Returns viewpoint details when looked up by name."""
    session_id = _mcp_init(client)

    data = _call_tool(client, session_id, "spec_get_viewpoint", {"name": KNOWN_VIEWPOINT})

    assert data["name"] == KNOWN_VIEWPOINT
    assert data["vpId"] == KNOWN_VP_ID
    assert data["domain"] == "operational"
    assert data["aspect"] == "Context & Exchange"
    assert "purpose" in data
    assert "concerns" in data
    assert "exposedConcepts" in data


def test_spec_get_viewpoint_by_vp_id(client):
    """Returns viewpoint details when looked up by VP_ID."""
    session_id = _mcp_init(client)

    data = _call_tool(client, session_id, "spec_get_viewpoint", {"name": KNOWN_VP_ID})

    assert data["name"] == KNOWN_VIEWPOINT
    assert data["vpId"] == KNOWN_VP_ID


def test_spec_get_viewpoint_not_found(client):
    """Returns error for unknown viewpoint name."""
    session_id = _mcp_init(client)

    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 100, "method": "tools/call",
                                  "params": {"name": "spec_get_viewpoint",
                                             "arguments": {"name": "Nonexistent Viewpoint"}}},
                    headers={"Mcp-Session-Id": session_id})
    body = r.json()
    assert "result" in body
    text = body["result"]["content"][0]["text"]
    assert "error" in text.lower() or "not found" in text.lower()


# -- spec_get_concept --

def test_spec_get_concept_by_name(client):
    """Returns concept details when looked up by name."""
    session_id = _mcp_init(client)

    data = _call_tool(client, session_id, "spec_get_concept", {"name": KNOWN_CONCEPT})

    assert data["name"] == KNOWN_CONCEPT
    assert "classType" in data
    assert data["classType"] == "Class"
    assert "documentation" in data
    assert "parents" in data
    assert "children" in data
    assert "associationEnds" in data


def test_spec_get_concept_not_found(client):
    """Returns error for unknown concept name."""
    session_id = _mcp_init(client)

    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 100, "method": "tools/call",
                                  "params": {"name": "spec_get_concept",
                                             "arguments": {"name": "NonexistentConcept"}}},
                    headers={"Mcp-Session-Id": session_id})
    body = r.json()
    assert "result" in body
    text = body["result"]["content"][0]["text"]
    assert "error" in text.lower() or "not found" in text.lower()


# -- spec_get_concern --

def test_spec_get_concern_by_name(client):
    """Returns concern details when looked up by name."""
    session_id = _mcp_init(client)

    data = _call_tool(client, session_id, "spec_get_concern", {"name": KNOWN_CONCERN})

    assert "id" in data
    assert "question" in data
    assert "category" in data
    assert "viewpoints" in data
    assert isinstance(data["viewpoints"], list)


def test_spec_get_concern_not_found(client):
    """Returns error for unknown concern."""
    session_id = _mcp_init(client)

    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 100, "method": "tools/call",
                                  "params": {"name": "spec_get_concern",
                                             "arguments": {"name": "Nonexistent Concern"}}},
                    headers={"Mcp-Session-Id": session_id})
    body = r.json()
    assert "result" in body
    text = body["result"]["content"][0]["text"]
    assert "error" in text.lower() or "not found" in text.lower()


# -- spec_get_stakeholder --

def test_spec_get_stakeholder_by_name(client):
    """Returns stakeholder details when looked up by name."""
    session_id = _mcp_init(client)

    data = _call_tool(client, session_id, "spec_get_stakeholder", {"name": KNOWN_STAKEHOLDER})

    assert data["name"] == KNOWN_STAKEHOLDER
    assert "id" in data
    assert "documentation" in data
    assert "concerns" in data
    assert isinstance(data["concerns"], list)


def test_spec_get_stakeholder_not_found(client):
    """Returns error for unknown stakeholder."""
    session_id = _mcp_init(client)

    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 100, "method": "tools/call",
                                  "params": {"name": "spec_get_stakeholder",
                                             "arguments": {"name": "Nonexistent Stakeholder"}}},
                    headers={"Mcp-Session-Id": session_id})
    body = r.json()
    assert "result" in body
    text = body["result"]["content"][0]["text"]
    assert "error" in text.lower() or "not found" in text.lower()


# -- spec_get_stereotype --

def test_spec_get_stereotype_by_name(client):
    """Returns stereotype details when looked up by name."""
    session_id = _mcp_init(client)

    data = _call_tool(client, session_id, "spec_get_stereotype", {"name": KNOWN_STEREOTYPE})

    assert data["name"] == KNOWN_STEREOTYPE
    assert "id" in data
    assert "documentation" in data
    assert "realizedConcepts" in data
    assert isinstance(data["realizedConcepts"], list)
    assert "specialImplementations" in data


def test_spec_get_stereotype_not_found(client):
    """Returns error for unknown stereotype."""
    session_id = _mcp_init(client)

    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 100, "method": "tools/call",
                                  "params": {"name": "spec_get_stereotype",
                                             "arguments": {"name": "SAF_Nonexistent"}}},
                    headers={"Mcp-Session-Id": session_id})
    body = r.json()
    assert "result" in body
    text = body["result"]["content"][0]["text"]
    assert "error" in text.lower() or "not found" in text.lower()


# -- spec_get_viewpoint_concepts --

def test_spec_get_viewpoint_concepts_by_name(client):
    """Returns concepts exposed by a viewpoint."""
    session_id = _mcp_init(client)

    data = _call_tool(client, session_id, "spec_get_viewpoint_concepts",
                      {"viewpoint_name": KNOWN_VP_ID})

    assert "viewpoint" in data
    assert data["viewpoint"]["vpId"] == KNOWN_VP_ID
    assert "concepts" in data
    assert isinstance(data["concepts"], list)
    assert len(data["concepts"]) > 0

    c = data["concepts"][0]
    assert "name" in c
    assert "classType" in c
    assert "documentation" in c
    assert "parents" in c
    assert "children" in c
    assert "associationEnds" in c


def test_spec_get_viewpoint_concepts_not_found(client):
    """Returns error for unknown viewpoint."""
    session_id = _mcp_init(client)

    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 100, "method": "tools/call",
                                  "params": {"name": "spec_get_viewpoint_concepts",
                                             "arguments": {"viewpoint_name": "XX_XXXX"}}},
                    headers={"Mcp-Session-Id": session_id})
    body = r.json()
    assert "result" in body
    text = body["result"]["content"][0]["text"]
    assert "error" in text.lower() or "not found" in text.lower()


# -- spec_get_viewpoint_concerns --

def test_spec_get_viewpoint_concerns_by_name(client):
    """Returns concerns framed by a viewpoint."""
    session_id = _mcp_init(client)

    data = _call_tool(client, session_id, "spec_get_viewpoint_concerns",
                      {"viewpoint_name": KNOWN_VP_ID})

    assert "viewpoint" in data
    assert data["viewpoint"]["vpId"] == KNOWN_VP_ID
    assert "concerns" in data
    assert isinstance(data["concerns"], list)
    assert len(data["concerns"]) > 0

    c = data["concerns"][0]
    assert "question" in c
    assert "category" in c
    assert "stakeholders" in c
    assert isinstance(c["stakeholders"], list)


def test_spec_get_viewpoint_concerns_not_found(client):
    """Returns error for unknown viewpoint."""
    session_id = _mcp_init(client)

    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 100, "method": "tools/call",
                                  "params": {"name": "spec_get_viewpoint_concerns",
                                             "arguments": {"viewpoint_name": "XX_XXXX"}}},
                    headers={"Mcp-Session-Id": session_id})
    body = r.json()
    assert "result" in body
    text = body["result"]["content"][0]["text"]
    assert "error" in text.lower() or "not found" in text.lower()


# -- spec_get_concept_stereotypes --

def test_spec_get_concept_stereotypes_by_name(client):
    """Returns stereotypes that realize a concept."""
    session_id = _mcp_init(client)

    data = _call_tool(client, session_id, "spec_get_concept_stereotypes",
                      {"name": KNOWN_CONCEPT})

    assert "concept" in data
    assert data["concept"]["name"] == KNOWN_CONCEPT
    assert "stereotypes" in data
    assert isinstance(data["stereotypes"], list)
    assert len(data["stereotypes"]) > 0

    s = data["stereotypes"][0]
    assert "stereotypeName" in s
    assert "stereotypeId" in s


def test_spec_get_concept_stereotypes_not_found(client):
    """Returns error for unknown concept."""
    session_id = _mcp_init(client)

    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 100, "method": "tools/call",
                                  "params": {"name": "spec_get_concept_stereotypes",
                                             "arguments": {"name": "NonexistentConcept"}}},
                    headers={"Mcp-Session-Id": session_id})
    body = r.json()
    assert "result" in body
    text = body["result"]["content"][0]["text"]
    assert "error" in text.lower() or "not found" in text.lower()


# -- spec_get_special_implementations --

def test_spec_get_special_implementations_returns_list(client):
    """Returns a list of special implementations."""
    session_id = _mcp_init(client)

    data = _call_tool(client, session_id, "spec_get_special_implementations")

    assert isinstance(data, list)
    assert len(data) > 0

    impl = data[0]
    assert "relationType" in impl
    assert "typedElement" in impl or "typeDefinition" in impl
    assert "id" in impl


# -- Integration: spec knowledge base query workflow --

def test_spec_full_workflow(client):
    """End-to-end: list viewpoints, drill into one, get its concepts and concerns."""
    session_id = _mcp_init(client)

    # 1. List viewpoints
    viewpoints = _call_tool(client, session_id, "spec_list_viewpoints")
    assert len(viewpoints) > 0

    # 2. Get details of a specific viewpoint
    vp = _call_tool(client, session_id, "spec_get_viewpoint", {"name": KNOWN_VIEWPOINT})
    assert vp["vpId"] == KNOWN_VP_ID

    # 3. Get concepts exposed by that viewpoint
    concepts = _call_tool(client, session_id, "spec_get_viewpoint_concepts",
                          {"viewpoint_name": KNOWN_VP_ID})
    assert len(concepts["concepts"]) > 0

    # 4. Get concerns framed by that viewpoint
    concerns = _call_tool(client, session_id, "spec_get_viewpoint_concerns",
                          {"viewpoint_name": KNOWN_VP_ID})
    assert len(concerns["concerns"]) > 0

    # 5. Search across all entity types
    results = _call_tool(client, session_id, "spec_search",
                         {"query": KNOWN_CONCEPT.split()[0]})
    assert len(results) > 0
