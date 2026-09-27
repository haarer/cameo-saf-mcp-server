"""Self-tests for the validation harness itself.

The harness is a measuring instrument. An instrument that silently changes its reading
is worse than no instrument, because every conclusion drawn from it inherits the error.

So these tests pin the things that were actually hard-won:

  * the annotation counts, which are ground truth for the whole exercise
  * the Groovy literal edge cases that took real debugging to get right
  * that the analyzer's severity logic cannot be talked into a wrong answer
  * that the gate fails on a deliberately worse surface, and for the stated reasons

Run: bin/vselftest
"""

from __future__ import annotations

import copy
import pathlib
import re
import subprocess
import sys
import tempfile
import traceback

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "lib"))

import analyze            # noqa: E402
import gate               # noqa: E402
import groovy_annotations  # noqa: E402
import surface            # noqa: E402

GROUND_TRUTH = {"tools": 75, "arguments": 174, "resources": 12, "prompts": 1}
RESULTS: list[tuple[str, str, str]] = []


def check(name: str):
    def deco(fn):
        try:
            fn()
            RESULTS.append(("pass", name, ""))
        except AssertionError as e:
            RESULTS.append(("fail", name, str(e) or "assertion failed"))
        except Exception:
            RESULTS.append(("error", name, traceback.format_exc(limit=3).strip()))
        return fn
    return deco


# --------------------------------------------------------------- parser

@check("parser: ground-truth counts 75/174/12/1")
def _():
    m = surface.from_scripts()
    assert m["counts"] == GROUND_TRUTH, f"got {m['counts']}"


@check("parser: four-apostrophe triple-quoted description -> 384 chars ending \"detector.'\"")
def _():
    anns = groovy_annotations.parse_file(ROOT.parent / "scripts" / "saf_tools.groovy")
    text = next(a for a in anns
                if a.identifier == "text" and a.group == "tool_argument")
    d = text.args["description"]
    assert isinstance(d, str), f"description did not resolve, got {type(d).__name__}"
    assert len(d) == 384, f"len={len(d)}"
    assert d.endswith("detector.'"), f"tail={d[-24:]!r}"


@check("parser: escape sequences decoded in double-quoted literals")
def _():
    anns = groovy_annotations.parse_file(ROOT.parent / "scripts" / "structural_tools.groovy")
    cp = next(a for a in anns if a.identifier == "create_part" and a.group == "tool")
    d = cp.args["description"]
    assert "\\n" not in d, "backslash-n left undecoded"
    assert "\n\nIMPORTANT" in d, "escaped newlines not decoded"
    assert len(d) == 1052, f"len={len(d)}"


@check("parser: no annotation span runs away and none is left dynamic")
def _():
    m = surface.from_scripts()
    assert not m["anomalies"], f"{len(m['anomalies'])} anomalies"
    dynamic = []
    for path in sorted((ROOT.parent / "scripts").glob("*.groovy")):
        for a in groovy_annotations.parse_file(path):
            if groovy_annotations.DYNAMIC in a.args.values():
                dynamic.append(f"{path.name}:{a.line} {a.identifier}")
    assert not dynamic, f"dynamic: {dynamic[:10]}"


@check("parser: no duplicate tool names")
def _():
    m = surface.from_scripts()
    assert not m["duplicate_tool_names"], m["duplicate_tool_names"]


# --------------------------------------------------------------- surface

@check("surface: manifest reproduces ground truth")
def _():
    m = surface.from_scripts()
    assert m["counts"] == GROUND_TRUTH, f"got {m['counts']}"
    assert sum(m["families"].values()) == GROUND_TRUTH["tools"], "family totals disagree"


@check("surface: identical manifest diffs to nothing")
def _():
    m = surface.from_scripts()
    d = surface.diff(m, copy.deepcopy(m))
    assert not (d["added_tools"] or d["removed_tools"] or d["changed_tools"]), d
    assert all(v == 0 for v in d["counts"]["delta"].values()), d["counts"]


@check("surface: diff detects add, remove, description change and argument change")
def _():
    m = surface.from_scripts()
    n = copy.deepcopy(m)
    by = {t["name"]: t for t in n["tools"]}
    n["tools"] = [t for t in n["tools"] if t["name"] != "echo"]
    by["create_element"]["description"] += " Extra words."
    by["create_element"]["arguments"] = [
        a for a in by["create_element"]["arguments"] if a["name"] != "name"]
    n["tools"].append({"name": "zz_new", "description": "d", "arguments": [],
                       "family": "core", "description_chars": 1, "required_arguments": 0,
                       "optional_arguments": 0, "arguments_total": 0, "decl": {}})
    d = surface.diff(m, n)
    assert d["removed_tools"] == ["echo"], d["removed_tools"]
    assert d["added_tools"] == ["zz_new"], d["added_tools"]
    changed = {c["name"]: c["fields"] for c in d["changed_tools"]}
    assert changed.get("create_element") == ["description", "args: -arg name"], changed


@check("surface: live/offline verification detects an out-of-sync surface")
def _():
    m = surface.from_scripts()
    live = copy.deepcopy(m)
    live["tools"] = [t for t in live["tools"] if t["name"] != "diff"]
    live["tools"][0]["description"] += " changed"
    r = surface.verify_live(m, live)
    assert not r["in_sync"], "claimed in sync when it is not"
    assert r["only_in_live"] == [] and r["description_differs"], r


# --------------------------------------------------------------- analyze

@check("analyze: dispatches to a named sibling tool is detected, a bare mention is not")
def _():
    cases = [
        ("For SAF types, use saf_create_element instead.", "saf_create_element", "dispatches"),
        ("Prefer this over find_elements_by_type when querying SAF models.",
         "find_elements_by_type", "dispatches"),
        ("The example workflow calls create_element(type='Package') first.",
         "create_element", "mentions"),
        ("No reference here at all.", "create_element", None),
    ]
    for text, other, want in cases:
        got = analyze._cross_reference(text, other)
        assert got == want, f"{text[:40]!r} -> {got!r}, want {want!r}"


@check("analyze: a prefixed tool does not vouch for its own name")
def _():
    got = analyze._cross_reference("saf_create_element handles kinds.", "create_element")
    assert got is None, f"substring false positive: {got!r}"


@check("analyze: namespace twins with a documented boundary are info, not warn")
def _():
    rep = analyze.analyze(surface.from_scripts())
    twins = {tuple(t["tools"]): t for t in rep["metrics"]["overlap"]["namespace_twins"]}
    assert twins[("create_element", "saf_create_element")]["boundary_documented"], twins
    warns = {(f["rule"], f["subject"]) for f in rep["findings"] if f["severity"] == "warn"}
    assert ("8", "create_element") not in warns, "documented pair was reported as a warn"


@check("analyze: an undocumented twin pair IS a warn")
def _():
    m = surface.from_scripts()
    by = {t["name"]: t for t in m["tools"]}
    by["saf_create_element"]["description"] = re.sub(
        r"\bcreate_element\b", "the plain CRUD creator",
        by["saf_create_element"]["description"])
    by["saf_create_element"]["description_chars"] = len(by["saf_create_element"]["description"])
    rep = analyze.analyze(m)
    warns = {(f["rule"], f["subject"]) for f in rep["findings"] if f["severity"] == "warn"}
    assert ("8", "create_element") in warns, "undocumented pair was not reported"


@check("analyze: the known find_elements / find_elements_by_type overlap is found")
def _():
    rep = analyze.analyze(surface.from_scripts())
    pairs = {(p["a"], p["b"]) for p in rep["metrics"]["overlap"]["similar_pairs"]}
    assert ("find_elements", "find_elements_by_type") in pairs, sorted(pairs)


@check("analyze: rules 5-7 are disclosed as not covered, not silently scored")
def _():
    rep = analyze.analyze(surface.from_scripts())
    for rule in ("5", "6", "7"):
        assert rule in rep["not_covered"], f"rule {rule} missing from not_covered"


# --------------------------------------------------------------- gate

def _baseline_from(tmp: pathlib.Path, m: dict) -> dict:
    """Analyze `m`, commit it as the baseline, and hand back the report."""
    rep = analyze.analyze(m)
    gate.write_baseline(tmp / "static-v1.json", rep, {}, note="selftest")
    return rep


@check("gate: the unmodified surface passes")
def _():
    m = surface.from_scripts()
    with tempfile.TemporaryDirectory() as d:
        tmp = pathlib.Path(d)
        surface.save(m, tmp / "v1.json", "v1")
        base_rep = _baseline_from(tmp, m)
        results = gate.run(analyze.analyze(surface.load(tmp / "v1.json")), base_rep)
        assert gate.ok(results), gate.render(results)


@check("gate: a fatter surface fails, and for the right reasons")
def _():
    m = surface.from_scripts()
    with tempfile.TemporaryDirectory() as d:
        tmp = pathlib.Path(d)
        base_rep = _baseline_from(tmp, m)
        worse = copy.deepcopy(m)
        by = {t["name"]: t for t in worse["tools"]}
        by["create_element"]["description"] += " " * 400
        by["create_element"]["description_chars"] = len(by["create_element"]["description"])
        by["create_element"]["arguments"].append(
            {"name": "extra", "type": "string", "required": False,
             "description": "d", "decl": {}})
        by["create_element"]["optional_arguments"] += 1
        by["create_element"]["arguments_total"] += 1
        worse["counts"]["arguments"] = sum(t["arguments_total"] for t in worse["tools"])
        surface.save(worse, tmp / "v1.json", "v1")
        results = gate.run(analyze.analyze(surface.load(tmp / "v1.json")), base_rep)
        assert not gate.ok(results), "gate passed a worse surface"
        failed = {r.check for r in results if r.status == "fail"}
        assert "regress:arguments" in failed, failed
        assert "regress:approx_description_tokens" in failed, failed


@check("gate: a new warn finding is caught")
def _():
    m = surface.from_scripts()
    with tempfile.TemporaryDirectory() as d:
        tmp = pathlib.Path(d)
        base_rep = _baseline_from(tmp, m)
        worse = copy.deepcopy(m)
        worse["tools"].append({"name": "execute", "description": "Runs it.",
                               "arguments": [], "family": "core", "description_chars": 8,
                               "required_arguments": 0, "optional_arguments": 0,
                               "arguments_total": 0, "decl": {}})
        worse["counts"]["tools"] += 1
        surface.save(worse, tmp / "v1.json", "v1")
        results = gate.run(analyze.analyze(surface.load(tmp / "v1.json")), base_rep)
        new_warns = next(r for r in results if r.check == "regress:new_warns")
        assert new_warns.status == "fail", new_warns.detail


@check("gate: a finding escalating info -> warn is caught")
def _():
    m = surface.from_scripts()
    with tempfile.TemporaryDirectory() as d:
        tmp = pathlib.Path(d)
        base_rep = _baseline_from(tmp, m)
        worse = copy.deepcopy(m)
        by = {t["name"]: t for t in worse["tools"]}
        by["saf_create_element"]["description"] = re.sub(
            r"\bcreate_element\b", "the plain CRUD creator",
            by["saf_create_element"]["description"])
        by["saf_create_element"]["description_chars"] = len(
            by["saf_create_element"]["description"])
        surface.save(worse, tmp / "v1.json", "v1")
        results = gate.run(analyze.analyze(surface.load(tmp / "v1.json")), base_rep)
        esc = next(r for r in results if r.check == "regress:escalated")
        assert esc.status == "fail", esc.detail


@check("gate: re-wording a finding is not reported as a new one")
def _():
    m = surface.from_scripts()
    with tempfile.TemporaryDirectory() as d:
        tmp = pathlib.Path(d)
        base_rep = _baseline_from(tmp, m)
        surface.save(m, tmp / "v1.json", "v1")
        same = analyze.analyze(surface.load(tmp / "v1.json"))
        for f in same["findings"]:
            f["message"] = "totally reworded: " + f["message"]
        results = gate.run(same, base_rep)
        assert gate.ok(results), gate.render(results)


@check("gate: no baseline means budgets only, and says so")
def _():
    results = gate.run(analyze.analyze(surface.from_scripts()), None)
    assert len(results) == 1 and results[0].status == "skip", results
    assert "no baseline" in results[0].detail, results[0].detail


# ---------------------------------------------------------------- main

# ------------------------------------------------------------------ CLI behaviour

def cli(*args) -> tuple[int, str]:
    r = subprocess.run([str(ROOT / "bin" / args[0]), *args[1:]],
                       capture_output=True, text=True, timeout=120)
    return r.returncode, r.stdout + r.stderr


@check("cli: vsurface show accepts a bare version and an explicit path alike")
def _():
    rc_a, out_a = cli("vsurface", "show", "v1")
    rc_b, out_b = cli("vsurface", "show", "surfaces/surface-v1.json")
    assert rc_a == 0 and rc_b == 0, (rc_a, rc_b)
    assert out_a.splitlines()[0] == out_b.splitlines()[0], (out_a, out_b)


@check("cli: vsurface rejects a missing manifest with a message, not a traceback")
def _():
    rc, out = cli("vsurface", "show", "v9")
    assert rc != 0, out
    assert "Traceback" not in out, out
    assert "no such surface manifest" in out, out


@check("cli: vsurface documents verify the way it actually parses it")
def _():
    doc = (ROOT / "bin" / "vsurface").read_text()
    assert "vsurface verify v1" in doc, "usage still says 'verify live v1', which parses the mode as the version"
    assert "vsurface verify live" not in doc, doc


@check("cli: vsurface live default is the MCP endpoint, not the server root")
def _():
    doc = (ROOT / "bin" / "vsurface").read_text()
    assert 'DEFAULT_URL = "http://host.containers.internal:18750/mcp"' in doc, \
        "live mode POSTs to DEFAULT_URL; without /mcp it queries the wrong endpoint"


@check("cli: vrun refuses an uncalibrated task cleanly instead of crashing")
def _():
    rc, out = cli("vrun", "--task", "T01-mcp-server-blocks")
    assert rc != 0, out
    assert "Traceback" not in out, out


def main() -> int:
    width = max(len(n) for _, n, _ in RESULTS)
    for status, name, detail in RESULTS:
        mark = {"pass": "ok  ", "fail": "FAIL", "error": "ERR "}[status]
        print(f"  {mark} {name.ljust(width)}")
        if detail and status != "pass":
            for line in detail.splitlines():
                print(f"        {line}")
    failed = [r for r in RESULTS if r[0] != "pass"]
    print(f"\n{len(RESULTS) - len(failed)}/{len(RESULTS)} harness self-tests passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
