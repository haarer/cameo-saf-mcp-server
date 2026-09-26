"""Which model is under test, and what is actually in it.

Two jobs:

1. `resolve` turns a short model name into a path, with provenance. A measured number
   without the sha256 of the model it came from is not reproducible, and the whole
   reason this file exists is that the suite was originally pointed at a model too
   small to exercise the surface and carrying defects nobody had fixed.

2. `facts` extracts a small set of ground truths from a live model so the dataset's
   clauses can be grounded in something verified rather than assumed, and so a later
   change to the model shows up as a diff instead of silently invalidating a score.

The probes are written as explicit queries, and each records the query it used, so a
disagreement between the baseline and the model is diagnosable instead of mysterious.
"""

from __future__ import annotations

import hashlib
import json
import pathlib

# The SAF profile repo ships the sample models the SAF documentation is built on. The
# suite treats it as a *reference to a checkout*, not as a submodule: the models are
# test data that upstream changes on its own schedule, and pinning a commit of a 12 MB
# binary in this repo would buy reproducibility we get more cheaply from a sha256.
DEFAULT_PROFILE_REPO = "/workspace/SAF-Cameo-Profile"

MODELS = {
    "ffds": "SAF_FFDS.mdzip",
    "ffds-naf": "SAF_FFDS_NAF.mdzip",
    "library": "SAF_Library.mdzip",
    "library-full": "Library.mdzip",
}


def samples_dir(profile_repo: str = DEFAULT_PROFILE_REPO) -> pathlib.Path:
    return pathlib.Path(profile_repo) / "SAF_Plugin" / "samples" / "SAF"


def resolve(name: str, profile_repo: str | None = None,
            models_dir: str | None = None) -> pathlib.Path:
    """`ffds` -> the path to SAF_FFDS.mdzip inside the profile repo."""
    if name in MODELS:
        base = pathlib.Path(models_dir) if models_dir else samples_dir(
            profile_repo or DEFAULT_PROFILE_REPO)
        return base / MODELS[name]
    p = pathlib.Path(name)
    return p if p.is_absolute() else (models_dir and pathlib.Path(models_dir) / p or p)


def provenance(path: pathlib.Path) -> dict:
    if not path.is_file():
        return {"path": str(path), "exists": False}
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    st = path.stat()
    return {"path": str(path), "exists": True, "bytes": st.st_size,
            "sha256": h.hexdigest()}


# --------------------------------------------------------------------- probes

def _stereotyped(rows: list[dict], stereotype: str) -> list[dict]:
    """Rows that actually carry `stereotype`, by exact membership in `stereotypes`.

    Two traps make the obvious filters wrong, and both are silent:

    * The search's stereotype filter is a case-insensitive *substring*, so
      `SAF_Function` also returns `SAF_FunctionAction`, `SAF_FunctionAsset` and the
      `SAF_FunctionContribution` relationships: 105 hits for 39 functions.
    * The `type` field is not a reliable substitute. It echoes the stereotype for some
      elements (`type: SAF_Function`) but stays the bare metaclass for others
      (`SAF_OperationalExchangeType` elements report `type: Class`), so filtering on
      `type == stereotype` reports 0 where 9 exist.
    """
    return [r for r in rows if stereotype in (r.get("stereotypes") or [])]


def collect_facts(client) -> dict:
    """Ground truths, each with the query that produced it."""
    f: dict = {}

    def search(**args) -> list[dict]:
        return client.rows("saf_find_elements_by_type", args)

    # --- counts, and the substring trap that makes them wrong
    fn_rows = search(stereotype="SAF_Function")
    f["saf_function_substring_hits"] = {
        "value": len(fn_rows), "query": "saf_find_elements_by_type(stereotype=SAF_Function)"}
    by_type = [r for r in fn_rows if r.get("type") == "SAF_Function"]
    by_st = _stereotyped(fn_rows, "SAF_Function")
    f["saf_function_count_by_type"] = {
        "value": len(by_type),
        "query": "...restricted to type == SAF_Function"}
    f["saf_function_count_by_stereotype"] = {
        "value": len(by_st),
        "query": "...restricted to 'SAF_Function' in the stereotypes list",
        "note": "39 or 40 is a genuine definitional split in the model, not a bug: one "
                "element carries both SAF_Function and SAF_FunctionAsset. A task that "
                "asks for 'the number of functions' must accept both and reward the "
                "agent for naming the overlap."}
    f["saf_function_overlap"] = {
        "value": sorted(r.get("name") for r in by_st
                        if r["id"] not in {x["id"] for x in by_type}),
        "query": "stereotyped as SAF_Function but not of type SAF_Function",
        "note": "the element that makes the two counts differ"}
    f["saf_physical_hardware_count"] = {
        "value": len(_stereotyped(search(stereotype="SAF_PhysicalHardware"), "SAF_PhysicalHardware")),
        "query": "saf_find_elements_by_type(stereotype=SAF_PhysicalHardware)"}
    f["saf_conceptual_system_count"] = {
        "value": len(_stereotyped(search(stereotype="SAF_ConceptualSystem"), "SAF_ConceptualSystem")),
        "query": "saf_find_elements_by_type(stereotype=SAF_ConceptualSystem)"}
    f["saf_physical_system_count"] = {
        "value": len(_stereotyped(search(stereotype="SAF_PhysicalSystem"), "SAF_PhysicalSystem")),
        "query": "saf_find_elements_by_type(stereotype=SAF_PhysicalSystem)"}
    opx = _stereotyped(search(stereotype="SAF_OperationalExchangeType"),
                       "SAF_OperationalExchangeType")
    f["saf_operational_exchange_type_count"] = {
        "value": len(opx),
        "query": "saf_find_elements_by_type(stereotype=SAF_OperationalExchangeType), "
                 "filtered on the stereotypes list",
        "note": "these rows report type='Class', not the stereotype, so filtering on the "
                "type field finds none of them"}
    f["saf_operational_exchange_type_names"] = {
        "value": sorted(r["name"] for r in opx if r.get("name")),
        "query": "names of the SAF_OperationalExchangeType elements"}

    # --- a named function: its SAF kind, and the fact that it is unlinked
    fns = {r.get("name"): r for r in _stereotyped(fn_rows, "SAF_Function")}
    probe = next((r for r in fns.values() if r.get("name")), None)
    if probe:
        f["probe_function"] = {"name": probe["name"], "id": probe["id"],
                               "safKind": probe.get("safKind"),
                               "query": "first element with type SAF_Function and a name"}
        rels = json.loads(client.resource(f"cameo://element/{probe['id']}/relationships"))
        f["probe_function_relationship_count"] = {
            "value": rels.get("count", len(rels.get("relationships", []))),
            "query": f"resources/read cameo://element/<{probe['name']}>/relationships",
            "note": "0 means the function->hardware link is not exposed on the function; "
                    "a task that needs it is unanswerable through this surface"}

    # --- a conceptual system: SAF reports it as ambiguous between two concepts
    cs = _stereotyped(search(stereotype="SAF_ConceptualSystem"), "SAF_ConceptualSystem")
    named = next((r for r in cs if r.get("name")), None)
    if named:
        sem = client.call("saf_get_element_semantics", {"elementIds": [named["id"]]})
        sem = sem[0] if isinstance(sem, list) and sem else {}
        f["probe_conceptual_system"] = {
            "name": named["name"], "id": named["id"],
            "ambiguous": sem.get("ambiguous"),
            "candidateKinds": [c["kind"] for c in (sem.get("candidateKinds") or [])],
            "safKind": sem.get("safKind"),
            "query": "saf_get_element_semantics on a named SAF_ConceptualSystem"}

    # --- a block's internal structure
    ps = _stereotyped(search(stereotype="SAF_PhysicalSystem"), "SAF_PhysicalSystem")
    target = next((r for r in ps if r.get("name") == "Commercial LORAWAN Gateway"),
                  next((r for r in ps if r.get("name")), None))
    if target:
        bs = client.call("get_block_structure", {"blockId": target["id"]})
        f["probe_block_structure"] = {
            "name": target["name"], "id": target["id"],
            "parts": len(bs.get("parts", [])), "ports": len(bs.get("ports", [])),
            "connectors": len(bs.get("connectors", [])),
            "port_names": [p.get("name") for p in bs.get("ports", [])],
            "query": "get_block_structure"}

    # --- a requirement's id and text, which only the semantics tool carries
    reqs = _stereotyped(search(stereotype="SAF_StakeholderRequirement"), "SAF_StakeholderRequirement")
    if reqs:
        sem = client.call("saf_get_element_semantics",
                          {"elementIds": [r["id"] for r in reqs[:8]]})
        got = [s for s in (sem or []) if s.get("requirement")]
        if got:
            s = got[0]
            rq = s["requirement"]
            f["probe_requirement"] = {
                "name": s.get("name"), "id": rq.get("Id") or rq.get("id"),
                "text": rq.get("Text") or rq.get("text"),
                "count": len(reqs),
                "query": "saf_get_element_semantics -> requirement field"}
    return f


def facts_to_rows(facts: dict) -> list[tuple[str, object]]:
    out = []
    for k, v in facts.items():
        if isinstance(v, dict) and "value" in v:
            out.append((k, v["value"]))
    return out


def diff_facts(base: dict, now: dict) -> list[str]:
    """Human-readable differences between two fact sets."""
    problems: list[str] = []
    for key, cur in now.items():
        old = base.get(key)
        if old is None:
            continue
        if isinstance(old, dict) and isinstance(cur, dict):
            for field in ("value", "ambiguous", "safKind", "parts", "ports", "connectors",
                          "candidateKinds", "port_names", "text", "name"):
                if field in old and field in cur and old[field] != cur[field]:
                    problems.append(
                        f"{key}.{field}: baseline {old[field]!r} -> now {cur[field]!r}")
        elif old != cur:
            problems.append(f"{key}: baseline {old!r} -> now {cur!r}")
    return problems
