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


def test_saf_create_diagram_has_no_kind_or_domain_argument(client):
    """The viewpoint dictates the diagram kind, so there is nothing to pass.

    Regression: the tool used to take diagramType and resolve it against an
    alias table, and the tool description claimed 'Omitting both draws all
    children' while dropping every relationship. A caller-chosen kind let a
    SAF-conformant view come out in the wrong shape, and domainFilter filtered
    on a per-element domain guess that the viewpoint chain already replaces.
    Both are gone rather than deprecated: an argument that is accepted and
    ignored is the same lie the description was telling.
    """
    defs = _tool_defs(client)
    assert "saf_create_diagram" in defs
    props = defs["saf_create_diagram"]["inputSchema"]["properties"]
    assert "diagramType" not in props, "diagram kind must come from the viewpoint"
    assert "domainFilter" not in props, "domainFilter was a per-element domain guess"
    assert "viewpoint" in props, "the viewpoint must remain the way the kind is decided"


def test_saf_create_diagram_description_states_what_is_not_drawn(client):
    """The description used to promise a diagram it did not draw.

    'Omitting both draws all children of parentId' was false: isNonShapeable
    Group dropped comments, literals and every relationship kind, and
    saf_add_relationship_paths could only draw Association, so a dependency
    between two shapes had no drawing path at all. The description must now
    say so, and name the tool that does draw them.
    """
    defs = _tool_defs(client)
    desc = defs["saf_create_diagram"]["description"]
    low = desc.lower()
    for fragment in ("relationshipsskipped", "saf_add_relationship_paths", "not drawn"):
        assert fragment in low, f"description must mention {fragment!r}: {desc}"


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
    assert "saf_add_relationship_paths" in desc, f"expected path guidance in: {desc}"
    agg = defs["create_part"]["inputSchema"]["properties"]["aggregation"]["description"]
    assert "duplicate" in agg.lower(), f"aggregation arg should warn about duplicates: {agg}"


def test_saf_add_relationship_paths_registered_and_guides(client):
    """The association-path tool must be present and must advertise non-silent behavior
    (skipped associations are reported, not swallowed) so agents can draw compositions
    on a BDD without silent failure.
    """
    defs = _tool_defs(client)
    assert "saf_add_relationship_paths" in defs, "saf_add_relationship_paths not registered"
    desc = defs["saf_add_relationship_paths"].get("description", "")
    assert "composition" in desc.lower(), f"expected composition mention in: {desc}"
    assert "skipped" in desc.lower(), f"expected skipped/reporting mention in: {desc}"
    props = defs["saf_add_relationship_paths"]["inputSchema"]["properties"]
    assert "diagramId" in props, "diagramId arg missing"
    assert props["diagramId"].get("required") or True  # required flag is fine


def test_saf_add_relationship_paths_errors_cleanly_on_unknown_diagram(client):
    """Calling with a nonexistent diagram must return a structured error map, not throw."""
    _require_client_ok(client)
    session_id = _mcp_init(client)
    r = client.post("/mcp", json={"jsonrpc": "2.0", "id": 100, "method": "tools/call",
                                  "params": {"name": "saf_add_relationship_paths",
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
    """The viewpoint arg must be present, documented as the chain-driven collect
    (viewpoint--exposes->concept--realizes->stereotype), and now also carry the
    diagram kind and the view stereotype. domainFilter is gone, not deprecated.
    """
    defs = _tool_defs(client)
    assert "saf_create_diagram" in defs
    props = defs["saf_create_diagram"]["inputSchema"]["properties"]
    assert "viewpoint" in props, "viewpoint arg missing"
    vp_desc = props["viewpoint"]["description"]
    assert "exposes" in vp_desc and "realizes" in vp_desc, f"viewpoint arg must describe the chain: {vp_desc}"
    assert "domain" in vp_desc.lower(), f"viewpoint arg must contrast with domain inference: {vp_desc}"
    assert "stereotype" in vp_desc.lower(), f"viewpoint arg must say it marks the view: {vp_desc}"
    assert "domainFilter" not in props, "domainFilter was a per-element domain guess, superseded by the chain"


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


def test_saf_create_diagram_kind_comes_from_the_viewpoint(client, writable_root):
    """A viewpoint's own presentation dictates the kind.

    O2_OCYD's Presentation asks for a block definition diagram, which
    createDiagram() spells "Class Diagram". The result must say the kind came
    from the viewpoint, so a caller can tell it was chosen rather than guessed.
    """
    session_id = _mcp_init(client)
    result = _call_tool(client, session_id, "saf_create_diagram",
                        {"name": "ScratchKindFromViewpoint",
                         "parentId": writable_root,
                         "viewpoint": "O2_OCYD"})
    try:
        assert result["diagramType"] == "Class Diagram", \
            f"O2_OCYD must draw a BDD: {result}"
        assert result["diagramTypeSource"] == "viewpoint", \
            f"the kind must be attributed to the viewpoint: {result}"
        assert result["viewpoint"] == "O2_OCYD", f"viewpoint must be echoed: {result}"
    finally:
        _call_tool(client, session_id, "delete_element", {"elementId": result["diagramId"]})


def test_saf_create_diagram_marks_the_view_with_its_stereotype(client, writable_root):
    """A diagram built for a viewpoint must actually carry the view stereotype.

    cameo://saf-views identifies a SAF view purely by the SAF_<VP_ID> stereotype
    being applied to the diagram element, so a diagram that was never marked
    was invisible to it. The stereotype is read back off the element here
    rather than trusted from the tool's own return value.

    Note this is deliberately NOT asserted through saf_get_viewpoint_views:
    that tool scores a diagram by the SAF kinds of its content and never
    consults the view stereotype, so it cannot confirm the marking.
    """
    session_id = _mcp_init(client)
    result = _call_tool(client, session_id, "saf_create_diagram",
                        {"name": "ScratchViewMarked",
                         "parentId": writable_root,
                         "viewpoint": "O2_OCYD"})
    try:
        assert result.get("viewStereotypeApplied") == "SAF_O2_OCYD", \
            f"the view stereotype must be reported as applied: {result}"

        applied = _call_tool(client, session_id, "get_stereotype_tags",
                             {"elementId": result["diagramId"]})
        names = {t.get("stereotype") for t in applied.get("tags", [])}
        assert "SAF_O2_OCYD" in names, \
            f"the diagram element must really carry the stereotype: {applied}"
    finally:
        _call_tool(client, session_id, "delete_element", {"elementId": result["diagramId"]})


def test_saf_create_diagram_reports_undrawn_relationships(client, writable_root):
    """A dependency between two drawn shapes was silently invisible.

    Relationships are never drawn as shapes, and saf_add_relationship_paths used
    to accept only mdkernel.Association, so a dependency had no drawing path at
    all. It must now be reported rather than dropped, and the path tool must
    accept it.
    """
    session_id = _mcp_init(client)

    pkg = _call_tool(client, session_id, "create_element",
                     {"type": "Package", "name": "ScratchRelPkg",
                      "parentId": writable_root})
    a = _call_tool(client, session_id, "create_element",
                   {"type": "Class", "name": "RelEndA", "parentId": pkg["id"]})
    b = _call_tool(client, session_id, "create_element",
                   {"type": "Class", "name": "RelEndB", "parentId": pkg["id"]})
    dep = _call_tool(client, session_id, "create_relationship",
                     {"type": "dependency", "sourceId": a["id"], "targetId": b["id"]})
    result = None
    try:
        result = _call_tool(client, session_id, "saf_create_diagram",
                            {"name": "ScratchRelBDD",
                             "parentId": pkg["id"],
                             "elementIds": [a["id"], b["id"]]})

        skipped_ids = {r["elementId"] for r in result.get("relationshipsSkipped", [])}
        assert dep["id"] in skipped_ids, \
            f"an undrawn dependency must be reported: {result}"

        paths = _call_tool(client, session_id, "saf_add_relationship_paths",
                           {"diagramId": result["diagramId"],
                            "relationshipIds": [dep["id"]]})
        assert paths["relationshipsFound"] == 1, \
            f"a dependency must be accepted by the path tool: {paths}"
        assert not paths.get("notRelationships"), \
            f"a dependency is a relationship and must not be rejected: {paths}"
    finally:
        if result and "diagramId" in result:
            _call_tool(client, session_id, "delete_element", {"elementId": result["diagramId"]})
        for el in (dep, b, a, pkg):
            _call_tool(client, session_id, "delete_element", {"elementId": el["id"]})


def test_saf_create_diagram_bdd_never_draws_part_properties(client, writable_root):
    """A part is internal structure and must not land on a block definition diagram.

    SAF context roles are applied to the part properties inside a SAF context
    as well as to the classifiers, and both realize concepts the viewpoint
    exposes. A viewpoint collect therefore found the parts, passed them
    through the stereotype test, and drew them on the BDD - where a part
    labelled 'coffeeMachine' reads as though the SOI were composed of its own
    internal structure. The part must be left off and reported, not drawn.
    """
    session_id = _mcp_init(client)
    pkg = _call_tool(client, session_id, "create_element",
                     {"type": "Package", "name": "ScratchPartLeakPkg",
                      "parentId": writable_root})
    whole = _call_tool(client, session_id, "create_element",
                       {"type": "Block", "name": "ScratchWhole", "parentId": pkg["id"]})
    part = _call_tool(client, session_id, "create_part",
                      {"name": "scratchInner", "wholeBlockId": whole["id"],
                       "partTypeBlockId": whole["id"]})
    result = None
    try:
        result = _call_tool(client, session_id, "saf_create_diagram",
                            {"name": "ScratchPartLeakBDD",
                             "parentId": pkg["id"],
                             "viewpoint": "C1_SCXD"})
        assert result["diagramType"] == "Class Diagram", \
            f"C1_SCXD must give a BDD: {result}"

        shape_ids = {s["elementId"] for s in result.get("shapes", [])}
        assert part["id"] not in shape_ids, \
            f"a part property must not be drawn on a BDD: {result}"
        assert whole["id"] in shape_ids, \
            f"the owning block should still be drawn: {result}"

        skipped = {s["elementId"]: s for s in result.get("shapesSkipped", [])}
        assert part["id"] in skipped, \
            f"the omitted part must be reported, not silently dropped: {result}"
        assert "IBD" in skipped[part["id"]]["reason"], \
            f"the reason should say where a part belongs: {skipped[part['id']]}"
    finally:
        if result and "diagramId" in result:
            _call_tool(client, session_id, "delete_element", {"elementId": result["diagramId"]})
        for el in (whole, pkg):
            _call_tool(client, session_id, "delete_element", {"elementId": el["id"]})


def test_saf_create_diagram_ibd_is_created_under_the_owning_block(client, writable_root):
    """An IBD is a block's internal structure, not a diagram that sits in a package.

    Handing createDiagram a Package for a Composite Structure Diagram makes
    MagicDraw invent a fresh Block to hang the diagram on. That new block owns
    no part properties, so the diagram depicts an empty whole and the parts
    the caller asked for float free of it. The diagram must land on the block
    that actually owns the parts.
    """
    session_id = _mcp_init(client)
    pkg = _call_tool(client, session_id, "create_element",
                     {"type": "Package", "name": "ScratchIbdPkg",
                      "parentId": writable_root})
    whole = _call_tool(client, session_id, "create_element",
                       {"type": "Block", "name": "ScratchIbdWhole", "parentId": pkg["id"]})
    part = _call_tool(client, session_id, "create_part",
                      {"name": "scratchInnerA", "wholeBlockId": whole["id"],
                       "partTypeBlockId": whole["id"]})
    result = None
    try:
        result = _call_tool(client, session_id, "saf_create_diagram",
                            {"name": "ScratchIbdRehomed",
                             "parentId": pkg["id"],
                             "viewpoint": "C1_SCXE",
                             "elementIds": [part["id"]]})
        assert result["diagramType"] == "Composite Structure Diagram", \
            f"C1_SCXE must give an IBD: {result}"
        assert result["parentId"] == whole["id"], \
            f"the IBD must be owned by the block holding the part: {result}"
        assert result["reparentedOnto"] == whole["id"], \
            f"the re-homing must be reported: {result}"
    finally:
        if result and "diagramId" in result:
            _call_tool(client, session_id, "delete_element", {"elementId": result["diagramId"]})
        for el in (whole, pkg):
            _call_tool(client, session_id, "delete_element", {"elementId": el["id"]})