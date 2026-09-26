"""Self-tests for model resolution, provenance and the live-model fact probes.

The fact probes are the part that can rot silently: a task clause resting on a number
that no longer matches the model would still lint clean. So these tests check the
filtering logic that produces those numbers against rows captured from a real model,
with no network involved.
"""

from __future__ import annotations

import json
import os
import pathlib
import subprocess
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


@check("baseline: the mutating task runs on a disposable copy, not a shared sample")
def _():
    cfg = json.loads((ROOT / "config.json").read_text())
    tm = cfg["cameo"]["test_models"]
    scratch = tm["T08-create-software-block"]
    shared = [v for k, v in tm.items() if k != "T08-create-software-block"]
    # Cameo may autosave whatever model is open, so the mutating cell must not be pointed at
    # a shared checkout or at the Cameo installation.
    for bad in ("/SAF-Cameo-Profile/", "/MSOSAref1/"):
        assert bad not in scratch, f"mutating task points into {bad}: {scratch}"
    assert scratch.startswith(cfg["cameo"]["host_workspace"]), scratch
    assert all(s.startswith(cfg["cameo"]["host_workspace"]) for s in shared), shared


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


def _dist_sample() -> pathlib.Path | None:
    p = pathlib.Path("/workspace/MSOSAref1/samples/SAF/SAF_FFDS.mdzip")
    return p if p.is_file() else None


def _search_roots() -> list[pathlib.Path]:
    return [pathlib.Path("/workspace/MSOSAref1"),
            pathlib.Path("/workspace/MSOSAref1/samples/SAF"),
            pathlib.Path("/workspace/MSOSAref1/profiles")]


@check("baseline: the scratch model keeps its original filename")
def _():
    # A model refers to itself by name ("href='SAF_FFDS.mdzip#...'"). Renaming the copy
    # would dangle every self-reference, so the isolation has to come from the directory.
    got = models.resolve("ffds-scratch", "/workspace/SAF-Cameo-Profile", None,
                         "/workspace/MSOSAref1", "/workspace/validation-scratch")
    assert got.name == "SAF_FFDS.mdzip", got
    assert str(got) == "/workspace/validation-scratch/samples/SAF/SAF_FFDS.mdzip", got


@check("deps: a model's .mdzip references are read out of the file, not assumed")
def _():
    src = _dist_sample()
    if src is None:
        return
    deps = models.model_dependencies(src)
    # An earlier version of this read only the archive's text parts, found nothing, and was
    # wrong: the references live in the compiled BINARY records.
    assert "SAF_FFDS.mdzip" not in deps, deps          # self-references excluded
    assert "SAF_Profile.mdzip" in deps, deps
    assert "SAF_Library.mdzip" in deps, deps


@check("deps: the closure is transitive, not one level deep")
def _():
    src = _dist_sample()
    if src is None:
        return
    closure = models.dependency_closure(src, _search_roots())
    # SAF_FFDS -> SAF_FFDS_NAF -> UAF Profile is two hops; one level would miss UAF Profile.
    assert "UAF Profile.mdzip" in closure, sorted(closure)
    unresolved = [n for n, e in closure.items() if not e.get("found")]
    assert not unresolved, unresolved


@check("deps: a model with no .mdzip references has an empty closure")
def _():
    import tempfile
    with tempfile.NamedTemporaryFile(suffix=".mdzip") as fh:
        import zipfile
        with zipfile.ZipFile(fh.name, "w") as z:
            z.writestr("a", "<elementID href='nothing'/>")
        assert models.model_dependencies(pathlib.Path(fh.name)) == []


@check("cli: vmodel provision materialises the model and its closure into scratch")
def _():
    import shutil, tempfile
    if _dist_sample() is None:
        return
    tmp = tempfile.mkdtemp(prefix="vscratch-")
    try:
        r = subprocess.run([str(ROOT / "bin" / "vmodel"), "provision", "SAF_FFDS"],
                           capture_output=True, text=True, timeout=900,
                           env=dict(os.environ, VALIDATION_SCRATCH_DIR=tmp))
        assert r.returncode == 0, r.stdout + r.stderr
        d = json.loads(r.stdout)
        assert d["unresolved"] == [], d["unresolved"]
        got = {f["relative"] for f in d["files"]}
        assert "samples/SAF/SAF_FFDS.mdzip" in got, got   # source layout is mirrored
        assert "profiles/SAF_Profile.mdzip" in got, got
        for f in d["files"]:                                # all it claims is really there
            assert pathlib.Path(f["scratch"]).is_file(), f
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


@check("cli: vmodel provision is idempotent and does not touch the source")
def _():
    import shutil, tempfile
    src = _dist_sample()
    if src is None:
        return
    before = (src.stat().st_size, src.stat().st_mtime_ns)
    tmp = tempfile.mkdtemp(prefix="vscratch-")
    try:
        env = dict(os.environ, VALIDATION_SCRATCH_DIR=tmp)
        for _ in range(2):
            r = subprocess.run([str(ROOT / "bin" / "vmodel"), "provision", "SAF_FFDS"],
                               capture_output=True, text=True, timeout=900, env=env)
            assert r.returncode == 0, r.stdout + r.stderr
        assert (src.stat().st_size, src.stat().st_mtime_ns) == before, "source was modified"
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


@check("cli: every vpath subcommand runs, rather than just the one under test")
def _():
    for args in (["--help"], ["check", "/workspace/validation-scratch"],
                 ["to-host", "/workspace/validation-scratch"],
                 ["to-harness", "/home/mac/oc3/workspace/validation-scratch"],
                 ["scratch"]):
        r = subprocess.run([str(ROOT / "bin" / "vpath")] + args, capture_output=True,
                           text=True, timeout=300)
        assert r.returncode == 0, f"{args}: {r.stdout}{r.stderr}"
        assert "Traceback" not in r.stderr, f"{args}: {r.stderr}"


@check("cli: vpath rejects an unknown subcommand with usage, not a traceback")
def _():
    r = subprocess.run([str(ROOT / "bin" / "vpath"), "nope"], capture_output=True,
                       text=True, timeout=300)
    assert r.returncode == 2, r.stdout + r.stderr
    assert "Traceback" not in r.stderr, r.stderr
    assert "usage" in (r.stderr + r.stdout).lower(), r.stderr


@check("cli: vmodel status reports the open model without needing a server argument")
def _():
    r = subprocess.run([str(ROOT / "bin" / "vmodel"), "status"], capture_output=True,
                       text=True, timeout=300)
    assert r.returncode in (0, 1), r.stdout + r.stderr
    assert "Traceback" not in r.stderr, r.stderr


@check("cli: vmodel locate maps a host path into the container workspace")
def _():
    r = subprocess.run([str(ROOT / "bin" / "vmodel"), "locate"], capture_output=True,
                       text=True, timeout=300)
    assert r.returncode in (0, 1), r.stdout + r.stderr
    assert "Traceback" not in r.stderr, r.stderr
    if r.returncode == 0 and r.stdout.strip():
        d = json.loads(r.stdout)
        # wherever it is open, the reported path has to be the host one, not a container one
        assert d.get("host_path", "").startswith("/home/"), d
        # and it must be translated back to a path this container can actually use
        if d.get("harness_path"):
            assert d["harness_path"].startswith("/workspace/"), d
            assert pathlib.Path(d["harness_path"]).parent.is_dir(), d


@check("cli: vpath verifies the real scratch model against the live server")
def _():
    model = pathlib.Path("/workspace/validation-scratch/samples/SAF/SAF_FFDS.mdzip")
    if not model.is_file():
        return                                  # not provisioned on this machine
    v = subprocess.run([str(ROOT / "bin" / "vpath"), "verify-model", str(model)],
                       capture_output=True, text=True, timeout=900)
    # Every exit code is a failure here. An earlier version of this test accepted "1", which
    # is exactly what a traceback in the CLI returns -- so it went on passing while
    # bin/vpath was completely broken.
    assert v.returncode == 0, v.stdout + v.stderr
    assert "Traceback" not in v.stderr, v.stderr
    d = json.loads(v.stdout)
    assert d["verified"] is True, d
    assert d["host_path"].endswith("samples/SAF/SAF_FFDS.mdzip"), d


@check("cli: vpath refuses a path outside the shared subtree instead of guessing")
def _():
    # The safety property: a path that is not in the shared subtree cannot be translated
    # without guessing, so it is refused. This is also the one legitimate exit code of 1.
    v = subprocess.run([str(ROOT / "bin" / "vpath"), "verify-model", "/tmp/not-shared.mdzip"],
                       capture_output=True, text=True, timeout=300)
    assert v.returncode == 1, v.stdout + v.stderr
    assert "Traceback" not in v.stderr, v.stderr
    d = json.loads(v.stdout)
    assert d["verified"] is False, d
    assert "shared" in d.get("reason", ""), d


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
