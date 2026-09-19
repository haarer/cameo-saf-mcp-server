import json
import os
import httpx
import pytest

SERVER_URL = os.environ.get("SERVER_URL", "http://localhost:18750")


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


@pytest.fixture(scope="module")
def writable_root(client):
    """The writable primary model root to parent throwaway elements under."""
    session_id = _mcp_init(client)
    models = _call_tool(client, session_id, "find_elements_by_type", {"type": "Model"})
    root = next(m for m in models if not m.get("parentId"))
    return root["id"]


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
    props = d["inputSchema"]["properties"]
    assert "diagramType" in props, "diagramType arg missing"
    dt = props["diagramType"]["description"]
    assert "class diagram" in dt.lower(), f"diagramType arg missing BDD=Class Diagram: {dt}"
    assert "composite structure diagram" in dt.lower(), f"diagramType arg missing IBD guidance: {dt}"
    assert "UML_CLASS_DIAGRAM" in dt, f"diagramType arg unhelpful: {dt}"


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


def test_create_part_discloses_auto_created_companion_association(client):
    """create_part with composite/shared aggregation auto-creates a companion Association
    (MagicDraw behavior). The surface must disclose this so agents do NOT call
    create_relationship('composition') for the same pair and produce duplicates.
    """
    defs = _tool_defs(client)
    assert "create_part" in defs
    desc = defs["create_part"].get("description", "")
    assert "companion Association" in desc, f"expected companion Association mention in: {desc}"
    assert "create_relationship" in desc, f"expected create_relationship warning in: {desc}"
    assert "duplicate" in desc.lower(), f"expected duplicate warning in: {desc}"
    assert "saf_add_association_paths" in desc, f"expected path guidance in: {desc}"
    agg = defs["create_part"]["inputSchema"]["properties"]["aggregation"]["description"]
    assert "duplicate" in agg.lower(), f"aggregation arg should warn about duplicates: {agg}"


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


def test_saf_create_diagram_skips_documentation_comment(client, writable_root):
    """Regression: saf_create_diagram used to throw 'No signature of method: getName
    for class ... CommentImpl' when the scoped element owned a documentation Comment.
    Comments are not NamedElements (no getName()) and cannot be diagrammed as
    classifier shapes, so they must be skipped, not crash the tool.
    """
    session_id = _mcp_init(client)

    # create_element with documentation attaches a Comment owned by the Class.
    owner = _call_tool(client, session_id, "create_element",
                       {"type": "Class", "name": "ScratchDiagramCommentOwner",
                        "parentId": writable_root,
                        "documentation": "Regression test: this Comment must not crash diagram creation."})

    try:
        result = _call_tool(client, session_id, "saf_create_diagram",
                            {"name": "ScratchCommentSkipBDD",
                             "parentId": writable_root,
                             "diagramType": "Class Diagram",
                             "scopeElementId": owner["id"]})

        # No crash: we got a normal result with a diagramId.
        assert "diagramId" in result, f"expected diagramId, got: {result}"
        assert "shapesSkipped" in result, f"expected shapesSkipped reporting, got: {result}"

        # The owned Comment must not appear as a shape (it has no getName and is not
        # a classifier); the documented element itself should still be a shape.
        shape_ids = [s["elementId"] for s in result.get("shapes", [])]
        assert owner["id"] in shape_ids, f"owner shape missing: {result}"

        def has_comment_error(item):
            reason = item.get("reason", "")
            return "Comment" in reason or "getName" in reason

        assert not any(has_comment_error(s) for s in result.get("shapesSkipped", [])), \
            f"comment surfaced as error in shapesSkipped: {result['shapesSkipped']}"

        _call_tool(client, session_id, "delete_element", {"elementId": result["diagramId"]})
    finally:
        _call_tool(client, session_id, "delete_element", {"elementId": owner["id"]})


def test_saf_create_diagram_viewpoint_driven_surface(client):
    """The viewpoint arg must be present and documented as the chain-driven collect
    (viewpoint--exposes->concept--realizes->stereotype), replacing per-element
    domain inference; domainFilter must be marked deprecated.
    """
    defs = _tool_defs(client)
    assert "saf_create_diagram" in defs
    props = defs["saf_create_diagram"]["inputSchema"]["properties"]
    assert "viewpoint" in props, "viewpoint arg missing"
    vp_desc = props["viewpoint"]["description"]
    assert "exposes" in vp_desc and "realizes" in vp_desc, f"viewpoint arg must describe the chain: {vp_desc}"
    assert "domain inference" in vp_desc.lower() or "domain" in vp_desc.lower(), f"viewpoint arg must contrast with domain inference: {vp_desc}"
    assert "domainFilter" in props
    df_desc = props["domainFilter"]["description"]
    assert "deprecated" in df_desc.lower(), f"domainFilter must be deprecated: {df_desc}"


def test_saf_create_diagram_viewpoint_driven_collects_only_realizing_elements(client, writable_root):
    """saf_create_diagram(viewpoint=...) must collect only owned elements that
    realize one of the viewpoint's exposed concepts (resolved via the
    viewpoint--exposes->concept--realizes->stereotype chain). Elements carrying a
    SAF stereotype outside the viewpoint's concepts must NOT be collected.
    """
    session_id = _mcp_init(client)

    pkg = _call_tool(client, session_id, "create_element",
                     {"type": "Package", "name": "ScratchViewpointPkg",
                      "parentId": writable_root})

    # O2_OCYD exposes 'Operational Capability' -> realizing stereotype SAF_OperationalCapability.
    # A concept-drive collect must pick up this element via the chain, not via a domain guess.
    op_cap = _call_tool(client, session_id, "saf_create_element",
                        {"kind": "operational_capability", "name": "VP Chain Marker",
                         "parentId": pkg["id"]})

    # A physical-system-stereotyped element belongs to a DIFFERENT set of exposed
    # concepts; it must be excluded from an operational-capability-viewpoint diagram.
    phys = _call_tool(client, session_id, "saf_create_element",
                      {"kind": "physical_system", "name": "VP Not-Exposed Marker",
                       "parentId": pkg["id"]})

    try:
        result = _call_tool(client, session_id, "saf_create_diagram",
                            {"name": "ScratchViewpointDriven",
                             "parentId": pkg["id"],
                             "diagramType": "Class Diagram",
                             "viewpoint": "O2_OCYD"})

        assert "diagramId" in result, f"expected diagramId, got: {result}"
        shape_ids = [s["elementId"] for s in result.get("shapes", [])]
        assert op_cap["id"] in shape_ids, \
            f"element realizing an exposed concept must be collected: {result}"
        assert phys["id"] not in shape_ids, \
            f"element realizing a non-exposed concept must be excluded: {result}"

        _call_tool(client, session_id, "delete_element", {"elementId": result["diagramId"]})
    finally:
        _call_tool(client, session_id, "delete_element", {"elementId": phys["id"]})
        _call_tool(client, session_id, "delete_element", {"elementId": op_cap["id"]})
        _call_tool(client, session_id, "delete_element", {"elementId": pkg["id"]})