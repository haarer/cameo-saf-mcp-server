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

import os
import hashlib
import json
import pathlib

# The SAF profile repo ships the sample models the SAF documentation is built on. The
# suite treats it as a *reference to a checkout*, not as a submodule: the models are
# test data that upstream changes on its own schedule, and pinning a commit of a 12 MB
# binary in this repo would buy reproducibility we get more cheaply from a sha256.
DEFAULT_PROFILE_REPO = "/workspace/SAF-Cameo-Profile"
# The Cameo distribution ships the same samples. It is not a git tree, which makes it the
# right place for a disposable copy: a mutating cell then never dirties a repository.
# Fallback only. The real location is asked of Cameo at runtime (see locate()), because the
# distribution can be installed anywhere and the harness has no business assuming a path.
DEFAULT_CAMEO_DIST = "/workspace/MSOSAref1"

# Cameo runs on the host and reports host paths; the harness runs in a container and reads
# container paths. These two prefixes are the translation, and they are configuration, not
# knowledge: config.json records how the running server's own report resolved them.
HOST_WORKSPACE = os.environ.get("CAMEO_HOST_WORKSPACE", "/home/mac/oc3/workspace")
HARNESS_WORKSPACE = os.environ.get("CAMEO_LOCAL_WORKSPACE", "/workspace")

# Bare filename -> the SAF profile repo's samples dir. "dist:NAME" -> the Cameo
# distribution's samples dir.
MODELS = {
    "ffds": "SAF_FFDS.mdzip",
    "ffds-naf": "SAF_FFDS_NAF.mdzip",
    "library": "SAF_Library.mdzip",
    "library-full": "Library.mdzip",
    # The disposable copy used by mutating cells. It keeps the ORIGINAL FILENAME: an .mdzip
    # refers to itself by name ("href='SAF_FFDS.mdzip#...'"), so renaming the file would
    # dangle every self-reference in it. Isolation comes from the directory, not the name.
    "ffds-scratch": "scratch:samples/SAF/SAF_FFDS.mdzip",
}


def samples_dir(profile_repo: str = DEFAULT_PROFILE_REPO) -> pathlib.Path:
    return pathlib.Path(profile_repo) / "SAF_Plugin" / "samples" / "SAF"


def dist_samples_dir(cameo_dist: str = DEFAULT_CAMEO_DIST) -> pathlib.Path:
    return pathlib.Path(cameo_dist) / "samples" / "SAF"


def resolve(name: str, profile_repo: str | None = None,
            models_dir: str | None = None,
            cameo_dist: str | None = None,
            scratch_dir: str | None = None) -> pathlib.Path:
    """`ffds` -> a concrete .mdzip path on the harness filesystem."""
    if name in MODELS:
        spec = MODELS[name]
        if models_dir:                       # explicit override wins over both defaults
            return pathlib.Path(models_dir) / spec.removeprefix("dist:")
        if spec.startswith("dist:"):
            return dist_samples_dir(cameo_dist or DEFAULT_CAMEO_DIST) / spec[5:]
        if spec.startswith("scratch:"):
            return pathlib.Path(scratch_dir or SCRATCH_DIR) / spec[8:]
        return samples_dir(profile_repo or DEFAULT_PROFILE_REPO) / spec
    p = pathlib.Path(name)
    return p if p.is_absolute() else (models_dir and pathlib.Path(models_dir) / p or p)


def host_to_harness(host_path: str) -> pathlib.Path:
    """Translate a path Cameo reported into one this process can open.

    Cameo tells us where its model lives in host terms. The harness cannot read that string,
    but the two trees are the same subtree under different roots, so the prefix swap is a
    lookup rather than a guess. Anything outside the shared subtree is returned unchanged and
    flagged by the caller, because there is no honest translation for it.
    """
    if host_path.startswith(HOST_WORKSPACE + "/"):
        return pathlib.Path(HARNESS_WORKSPACE + host_path[len(HOST_WORKSPACE):])
    return pathlib.Path(host_path)


def locate(c) -> dict:
    """Ask Cameo where its open model actually is, and map that onto this filesystem.

    Nothing here is assumed: the model name, the host path, whether Cameo considers it
    writable, and the distribution's samples directory (the open model's own directory when
    it is a distribution sample) all come from the server. Hardcoding an install path is
    what made the earlier scratch-copy setup wrong on any machine but this one.
    """
    host_path, name, writable, source = None, None, None, None
    try:
        proj = c.resource("cameo://project")
        if isinstance(proj, str):
            proj = json.loads(proj)
        if isinstance(proj, dict):
            loc = proj.get("location") or ""
            host_path = loc[5:] if loc.startswith("file:") else (loc or None)
            name = proj.get("name")
            writable = proj.get("writable")
            source = "cameo://project"
    except Exception as exc:                      # noqa: BLE001 - fall through to admin
        err = str(exc)
    if host_path is None:
        r = loaded_identity(c).get("reported") or {}
        host_path = r.get("filePath")
        name = name or r.get("modelName")
        source = source or "admin_get_model_status"
    out = {"source": source, "model_name": name, "host_path": host_path,
           "writable": writable}
    if host_path:
        hp = pathlib.PurePosixPath(host_path)
        out["harness_path"] = str(host_to_harness(host_path))
        out["samples_dir_host"] = str(hp.parent)
        out["samples_dir_harness"] = str(host_to_harness(str(hp.parent)))
        # If Cameo reports a location outside the shared subtree, the harness has no
        # honest way to read it. Say so instead of returning a path that will not open.
        out["under_shared_workspace"] = host_path.startswith(HOST_WORKSPACE + "/")
        if not out["under_shared_workspace"]:
            out["_note"] = (f"not under the shared workspace {HOST_WORKSPACE!r}; the "
                            f"harness cannot open this path. Bind-mount it or set "
                            f"CAMEO_HOST_WORKSPACE/CAMEO_LOCAL_WORKSPACE.")
    return out


def model_dependencies(mdzip: pathlib.Path) -> list[str]:
    """Every other .mdzip this model points at, read out of the file itself.

    An .mdzip holds proxy references (``<elementID href='SAF_Library.mdzip'/>``) by bare
    filename. They resolve relative to the referencing model, so a copy that is moved on its
    own carries references to files that are not there -- and nothing complains, the
    references simply resolve to nothing. The dependency set is therefore derived here
    rather than written down, because a hand-maintained list is a list that rots when
    upstream adds a reference.

    The text parts of the archive are not where this lives; it is in the compiled BINARY
    records, which is why grepping only the XML says "no dependencies" and is wrong.
    """
    import re
    import zipfile

    self_name = mdzip.name
    found: set[str] = set()
    try:
        with zipfile.ZipFile(mdzip) as z:
            for info in z.infolist():
                if info.is_dir():
                    continue
                with z.open(info) as fh:
                    blob = fh.read()
                # The href is 'NAME.mdzip#fragment': the fragment is the element id inside
                # the other model, and the part before it is the file to resolve.
                for m in re.finditer(rb"href=['\"]([^'\"]+?\.mdzip)(#[^'\"]*)?['\"]", blob):
                    name = m.group(1).decode("utf-8", "replace").rsplit("/", 1)[-1]
                    if name and name != self_name:
                        found.add(name)
    except zipfile.BadZipFile:
        return []
    return sorted(found)


def find_dependency(name: str, near: pathlib.Path, search: list[pathlib.Path]) -> pathlib.Path | None:
    """Locate a dependency by name: beside the model first, then the known roots."""
    for cand in (near / name, near.parent / name, near.parent.parent / name):
        if cand.is_file():
            return cand
    for root in search:
        for cand in root.rglob(name):
            if cand.is_file():
                return cand
    return None


def dependency_closure(root: pathlib.Path, search: list[pathlib.Path]) -> dict:
    """Transitive closure of a model's .mdzip dependencies, and where each was found.

    Walks the graph rather than trusting one level: SAF_FFDS -> SAF_FFDS_NAF -> UAF Profile,
    and SAF_FFDS -> Library -> SAF_Library -> SAF_Profile. Copying one file level deep would
    leave dangling references that resolve to nothing and fail silently.
    """
    seen: dict[str, dict] = {}
    queue = [(root.name, root)]
    while queue:
        name, path = queue.pop(0)
        if name in seen:
            continue
        entry = {"name": name, "source": str(path), "found": path.is_file()}
        if path.is_file():
            entry["bytes"] = path.stat().st_size
            for dep in model_dependencies(path):
                if dep not in seen:
                    loc = find_dependency(dep, path, search)
                    if loc:
                        queue.append((dep, loc))
        seen[name] = entry
    for name, entry in seen.items():
        entry.setdefault("resolved_from", None)
    return seen


def provenance(path: pathlib.Path) -> dict:
    """Identity of the sample file on THIS filesystem.

    Advisory only, and deliberately not the suite's correctness input. Cameo runs on the
    host and loads models by host path; the harness sees a container path. The two are
    different strings for the same bytes, so a hash here says "these are the bytes of the
    FFDS sample", never "this is what Cameo has open". What Cameo actually has open is
    asked of Cameo, in loaded_identity() -- a file the harness cannot read tells it
    nothing about a model it did not load.
    """
    if not path.is_file():
        return {"path": str(path), "exists": False, "scope": "harness-filesystem"}
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    st = path.stat()
    return {"path": str(path), "exists": True, "bytes": st.st_size,
            "sha256": h.hexdigest(), "scope": "harness-filesystem"}


def loaded_identity(c) -> dict:
    """Ask the running Cameo which model is open, and report it as-is.

    This is the provenance that matters. It comes from the process that owns the model, so
    it cannot drift from reality the way a locally hashed path can, and it is the same
    fact a run should be attributed to.
    """
    try:
        r = c.call("admin_get_model_status", {})
    except Exception as exc:                      # noqa: BLE001 - reported, not raised
        return {"available": False, "error": str(exc)}
    if not isinstance(r, dict):
        return {"available": False, "error": "unexpected response", "raw": r}
    return {"available": True, "reported": r}


# --------------------------------------------------------------------- probes

def _pick(rows: list[dict], want: str | None = None) -> dict | None:
    """Choose one element deterministically from an unordered result set.

    Search result order is not stable: the same model, loaded from a byte-identical copy,
    returns its rows in a different order and the element IDs differ, because they are
    minted per project instance. A fact baseline built on "whatever came first" therefore
    drifts on an unchanged model, which trains you to ignore the alarm. Sort by name and
    fall back to the id, so the same content always selects the same element.
    """
    named = [r for r in rows if r.get("name")]
    if want:
        return next((r for r in named if r.get("name") == want), None)
    if not named:
        return None
    return sorted(named, key=lambda r: (r.get("name") or "", r.get("id") or ""))[0]


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
    probe = _pick(list(fns.values()))
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
    named = _pick(cs, want="Camera") or _pick(cs)
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
    target = _pick(ps, want="Commercial LORAWAN Gateway") or _pick(ps)
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
        # Sort before slicing: this probe only inspects the first handful, so on an
        # unordered list the handful itself changes between loads of the same bytes.
        reqs = sorted(reqs, key=lambda r: (r.get("name") or "", r.get("id") or ""))
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
