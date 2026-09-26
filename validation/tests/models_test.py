"""Self-tests for model resolution, provenance and the live-model fact probes.

The fact probes are the part that can rot silently: a task clause resting on a number
that no longer matches the model would still lint clean. So these tests check the
filtering logic that produces those numbers against rows captured from a real model,
with no network involved.
"""

from __future__ import annotations

import json
import pathlib
import sys
import traceback

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "lib"))

import mcp_client      # noqa: E402
import models          # noqa: E402

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


# ------------------------------------------------------- rows captured from FFDS

# A SAF_Function search on the real FFDS model returns all of these shapes, which is
# the whole reason _stereotyped exists.
FN_ROWS = [
    {"id": "1", "name": "measure heat level", "type": "SAF_Function",
     "stereotypes": ["CustomSort", "SAF_Function"], "safKind": "system_function"},
    {"id": "2", "name": "Provide Sensor Data", "type": "SAF_Function",
     "stereotypes": ["SAF_Function"], "safKind": "system_function"},
    {"id": "3", "name": "", "type": "Dependency",
     "stereotypes": ["SAF_FunctionContribution"], "safKind": ""},
    {"id": "4", "name": "act", "type": "SAF_FunctionAction",
     "stereotypes": ["SAF_FunctionAction"], "safKind": ""},
    {"id": "5", "name": "Analyze FF data", "type": "SAF_FunctionAsset",
     "stereotypes": ["CustomSort", "SAF_FunctionAsset", "SAF_Function"], "safKind": ""},
]
EXCHANGE_ROWS = [
    {"id": "e1", "name": "Operational State", "type": "Class",
     "stereotypes": ["SAF_OperationalExchangeType"], "safKind": "operational_exchange_type"},
    {"id": "e2", "name": "Reported Condition", "type": "Class",
     "stereotypes": ["SAF_OperationalExchangeType"], "safKind": "operational_exchange_type"},
]


class FakeClient:
    """A stand-in that replays the row shapes above instead of calling Cameo."""

    def __init__(self, fn_rows=None, exchange_rows=None):
        self.fn_rows = FN_ROWS if fn_rows is None else fn_rows
        self.exchange_rows = EXCHANGE_ROWS if exchange_rows is None else exchange_rows

    def rows(self, tool, args=None):
        args = args or {}
        if args.get("stereotype") == "SAF_Function":
            return self.fn_rows
        if args.get("stereotype") == "SAF_OperationalExchangeType":
            return self.exchange_rows
        return []

    def call(self, tool, args=None):
        return {}

    def resource(self, uri):
        return json.dumps({"id": "x", "count": 0, "relationships": []})


# ------------------------------------------------------------------ resolution

@check("models: a short name resolves into the profile repo's samples dir")
def _():
    p = models.resolve("ffds", "/some/profile/repo")
    assert p == pathlib.Path("/some/profile/repo/SAF_Plugin/samples/SAF/SAF_FFDS.mdzip"), p


@check("models: an absolute path is used as given")
def _():
    p = models.resolve("/tmp/whatever.mdzip")
    assert p == pathlib.Path("/tmp/whatever.mdzip"), p


@check("models: provenance of a missing file reports exists=False, not a crash")
def _():
    prov = models.provenance(pathlib.Path("/nonexistent/model.mdzip"))
    assert prov["exists"] is False and "path" in prov


@check("models: provenance records a stable sha256 of real bytes")
def _():
    import hashlib
    import tempfile
    with tempfile.TemporaryDirectory() as d:
        p = pathlib.Path(d) / "m.mdzip"
        p.write_bytes(b"deterministic bytes")
        prov = models.provenance(p)
        assert prov["exists"] and prov["bytes"] == 19, prov
        assert prov["sha256"] == hashlib.sha256(b"deterministic bytes").hexdigest()


# ------------------------------------------------------------------- filtering

@check("models: exact stereotype membership ignores the substring siblings")
def _():
    got = models._stereotyped(FN_ROWS, "SAF_Function")
    ids = {r["id"] for r in got}
    assert ids == {"1", "2", "5"}, ids


@check("models: the function-count split is visible as a named element")
def _():
    by_type = {r["id"] for r in FN_ROWS if r.get("type") == "SAF_Function"}
    by_st = {r["id"] for r in models._stereotyped(FN_ROWS, "SAF_Function")}
    overlap = by_st - by_type
    assert overlap == {"5"}, overlap
    name = next(r["name"] for r in FN_ROWS if r["id"] in overlap)
    assert name == "Analyze FF data", name


@check("models: a stereotype whose rows report type='Class' is still found")
def _():
    got = models._stereotyped(EXCHANGE_ROWS, "SAF_OperationalExchangeType")
    assert len(got) == 2, got
    # and the type field really would have found none, which is the trap
    assert [r for r in EXCHANGE_ROWS if r["type"] == "SAF_OperationalExchangeType"] == []


@check("models: collect_facts records the substring and stereotype counts separately")
def _():
    f = models.collect_facts(FakeClient())
    assert f["saf_function_substring_hits"]["value"] == 5, f["saf_function_substring_hits"]
    assert f["saf_function_count_by_type"]["value"] == 2, f["saf_function_count_by_type"]
    assert f["saf_function_count_by_stereotype"]["value"] == 3
    assert f["saf_function_overlap"]["value"] == ["Analyze FF data"]
    assert f["saf_operational_exchange_type_count"]["value"] == 2


@check("models: every recorded fact carries the query that produced it")
def _():
    f = models.collect_facts(FakeClient())
    for k, v in f.items():
        if isinstance(v, dict) and "value" in v:
            assert v.get("query"), f"{k} has no query recorded"


# ------------------------------------------------------------------------ diff

@check("models: diff reports a moved count")
def _():
    base = {"a": {"value": 39}}
    now = {"a": {"value": 40}}
    d = models.diff_facts(base, now)
    assert d and "39" in d[0] and "40" in d[0], d


@check("models: diff reports a changed nested field such as ambiguity")
def _():
    base = {"p": {"ambiguous": True, "candidateKinds": ["x", "y"]}}
    now = {"p": {"ambiguous": False, "candidateKinds": ["x"]}}
    d = models.diff_facts(base, now)
    assert len(d) == 2, d


@check("models: an unchanged model diffs clean")
def _():
    base = {"a": {"value": 1}, "b": {"name": "x"}}
    assert models.diff_facts(base, dict(base)) == []


# ----------------------------------------------------------------- token/auth

@check("mcp: a token is discovered from the environment without printing it")
def _():
    import os
    os.environ["CAMEO_MCP_TOKEN"] = "  secret-value  "
    try:
        assert mcp_client.bearer_token() == "secret-value"
    finally:
        os.environ.pop("CAMEO_MCP_TOKEN", None)


@check("mcp: a missing token yields None rather than an empty header")
def _():
    import os
    saved_tok = os.environ.pop("CAMEO_MCP_TOKEN", None)
    old = os.environ.get("OPENCODE_CONFIG")
    os.environ["OPENCODE_CONFIG"] = "/nonexistent/opencode.json"
    try:
        assert mcp_client.bearer_token() is None
    finally:
        # Unset it again: leaving a bogus OPENCODE_CONFIG behind starves every later
        # subprocess of a token, and the failures then look like server problems.
        os.environ.pop("OPENCODE_CONFIG", None)
        if old is not None:
            os.environ["OPENCODE_CONFIG"] = old
        if saved_tok is not None:
            os.environ["CAMEO_MCP_TOKEN"] = saved_tok


@check("mcp: a 401 is reported as an auth problem, not as 'Cameo is down'")
def _():
    import urllib.error
    import urllib.request

    def boom(req, timeout=None):
        raise urllib.error.HTTPError("u", 401, "Unauthorized", {},
                                     __import__("io").BytesIO(b"Invalid or missing token"))

    real = urllib.request.urlopen
    urllib.request.urlopen = boom
    try:
        c = mcp_client.Client("http://x/mcp", token="t")
        try:
            c._post({})
            raise AssertionError("expected McpError")
        except mcp_client.McpError as e:
            assert "401" in str(e) and "CAMEO_MCP_TOKEN" in str(e), str(e)
            assert "Cameo looks down" not in str(e), str(e)
    finally:
        urllib.request.urlopen = real


@check("mcp: a refused connection is reported as Cameo being down")
def _():
    import urllib.error
    import urllib.request

    def boom(req, timeout=None):
        raise urllib.error.URLError("connection refused")

    real = urllib.request.urlopen
    urllib.request.urlopen = boom
    try:
        c = mcp_client.Client("http://x/mcp", token="t")
        try:
            c._post({})
            raise AssertionError("expected McpError")
        except mcp_client.McpError as e:
            assert "cannot reach" in str(e) and "Cameo looks down" in str(e), str(e)
    finally:
        urllib.request.urlopen = real


# ------------------------------------------------------------- shipped baseline

@check("baseline: every shipped task's grounded_in ids exist in models/ffds.json")
def _():
    import tasks as T
    known = set(json.loads((ROOT / "models" / "ffds.json").read_text())["facts"])
    for t in T.load_all():
        for fid in t.raw.get("grounded_in", []):
            assert fid in known, f"{t.id}: {fid}"


@check("baseline: no task is left pointing at the showcase model")
def _():
    for f in sorted((ROOT / "tasks").glob("*.json")):
        d = json.loads(f.read_text())
        assert "McpServerSAFA" not in json.dumps(d), f"{f.name} still references the showcase model"


@check("baseline: config maps every task id to an FFDS model on the host path")
def _():
    cfg = json.loads((ROOT / "config.json").read_text())
    tm = cfg["cameo"]["test_models"]
    import tasks as T
    ids = {t.id for t in T.load_all()}
    assert set(tm) == ids, set(tm) ^ ids
    for tid, path in tm.items():
        assert "SAF_FFDS" in path, f"{tid} -> {path}"
        assert path.startswith(cfg["cameo"]["host_workspace"]), f"{tid} is not a host path"


@check("baseline: the mutating task targets the scratch copy, not the pristine sample")
def _():
    d = json.loads((ROOT / "tasks" / "T08-create-software-block.json").read_text())
    assert d["readOnly"] is False
    assert d["model"].endswith("scratch.mdzip"), d["model"]
    cfg = json.loads((ROOT / "config.json").read_text())
    assert cfg["cameo"]["test_models"][d["id"]].endswith("scratch.mdzip")


# ------------------------------------------------------------------- CLI wiring

def cli(*args):
    import subprocess
    r = subprocess.run([str(ROOT / "bin" / args[0]), *args[1:]],
                       capture_output=True, text=True, timeout=600)
    return r.returncode, r.stdout + r.stderr


@check("cli: vmodel resolve needs no server")
def _():
    rc, out = cli("vmodel", "resolve", "ffds")
    assert rc == 0, out
    d = json.loads(out)
    assert d["exists"] and d["sha256"], d
    assert "SAF_FFDS.mdzip" in d["path"], d


@check("cli: vmodel resolve names the profile repo when the model is missing")
def _():
    rc, out = cli("vmodel", "resolve", "/nope/absent.mdzip")
    assert rc != 0, out
    assert "SAF_Plugin/samples/SAF" in out, out
    assert "Traceback" not in out, out


@check("cli: vtasks lint passes offline with no server")
def _():
    rc, out = cli("vtasks", "lint")
    assert rc == 0, out
    assert "grounding: every clause traces to" in out, out


@check("cli: the shipped dataset never names a tool absent from the live server")
def _():
    rc, out = cli("vtasks", "lint", "--live")
    assert rc == 0, out
    # drift is allowed and must be reported, but nothing the dataset depends on
    for line in out.splitlines():
        if "NAMED BY A TASK" in line:
            raise AssertionError(f"a task depends on a missing tool: {line.strip()}")


@check("cli: vsurface build live can read the authenticated surface")
def _():
    rc, out = cli("vsurface", "build", "live", "selftest-live",
                  "--url", "http://host.containers.internal:18750/mcp")
    try:
        assert rc == 0, out
        assert "tools=" in out, out
        assert "Unauthorized" not in out, "the live client did not send the bearer token"
    finally:
        (ROOT / "surfaces" / "surface-selftest-live.json").unlink(missing_ok=True)


def main() -> int:
    width = max(len(n) for _, n, _ in RESULTS)
    for status, name, detail in RESULTS:
        mark = {"pass": "ok  ", "fail": "FAIL", "error": "ERR "}[status]
        print(f"  {mark} {name.ljust(width)}")
        if detail and status != "pass":
            for line in detail.splitlines():
                print(f"        {line}")
    failed = [r for r in RESULTS if r[0] != "pass"]
    print(f"\n{len(RESULTS) - len(failed)}/{len(RESULTS)} model/client self-tests passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
