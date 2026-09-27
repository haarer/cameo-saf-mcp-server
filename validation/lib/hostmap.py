"""Container <-> host path mapping.

The harness runs in a container; Cameo runs on the host. They name the same shared subtree
differently -- the container sees ``/workspace``, the host sees ``/home/mac/oc3/workspace`` --
so every path that crosses the boundary has to be translated, and a translation built on a
guess is invisible until something fails to load much later.

Two rules this module exists to enforce:

1. The two prefixes are **configuration**, never constants in code. They come from
   ``config.json`` (``cameo.host_workspace`` / ``cameo.local_workspace``) and can be
   overridden per-process. A path outside the shared subtree has no honest translation and
   is reported as untranslatable rather than rewritten into something that looks plausible
   and will not open.
2. The mapping is **verified against the server**, not asserted. ``verify_model`` translates
   a harness path and asks the running Cameo to open the result. A translation that no
   server has ever confirmed is a hypothesis.
"""
from __future__ import annotations

import json
import os
import pathlib
import sys
import time

# Overridable per process; bin/vpath seeds these from config.json.
HOST_WORKSPACE = os.environ.get("CAMEO_HOST_WORKSPACE", "/home/mac/oc3/workspace")
HARNESS_WORKSPACE = os.environ.get("CAMEO_LOCAL_WORKSPACE", "/workspace")

# Where disposable model copies live. Deliberately NOT inside the Cameo installation: a
# plugin install is deployed, versioned state, and the wrong place to accumulate the scratch
# files of an experiment. It is also not inside any git tree, so nothing it holds can dirty
# a repository.
SCRATCH_DIR = os.environ.get("VALIDATION_SCRATCH_DIR", "/workspace/validation-scratch")


def configure(host_workspace: str | None = None, harness_workspace: str | None = None,
              scratch_dir: str | None = None) -> None:
    global HOST_WORKSPACE, HARNESS_WORKSPACE, SCRATCH_DIR
    if host_workspace:
        HOST_WORKSPACE = host_workspace.rstrip("/")
    if harness_workspace:
        HARNESS_WORKSPACE = harness_workspace.rstrip("/")
    if scratch_dir:
        SCRATCH_DIR = scratch_dir


def _norm(p: str) -> str:
    return str(pathlib.PurePosixPath(str(p).strip()))


def is_shared(path: str) -> bool:
    """True if `path` lies inside the subtree both sides can see."""
    p = _norm(path)
    for root in (HOST_WORKSPACE, HARNESS_WORKSPACE):
        if p == root or p.startswith(root + "/"):
            return True
    return False


def to_host(harness_path: str) -> str:
    """Harness-side path -> the path the host Cameo must be given."""
    p = _norm(harness_path)
    if p == HARNESS_WORKSPACE:
        return HOST_WORKSPACE
    if p.startswith(HARNESS_WORKSPACE + "/"):
        return HOST_WORKSPACE + p[len(HARNESS_WORKSPACE):]
    if p == HOST_WORKSPACE or p.startswith(HOST_WORKSPACE + "/"):
        return p                                    # already a host path; be forgiving
    return p                                        # untranslatable; classify() says so


def to_harness(host_path: str) -> str:
    """Host-side path -> the path this process can open."""
    p = _norm(host_path)
    if p == HOST_WORKSPACE:
        return HARNESS_WORKSPACE
    if p.startswith(HOST_WORKSPACE + "/"):
        return HARNESS_WORKSPACE + p[len(HOST_WORKSPACE):]
    if p == HARNESS_WORKSPACE or p.startswith(HARNESS_WORKSPACE + "/"):
        return p
    return p


def classify(path: str) -> dict:
    """Describe a path relative to the boundary, and say what can be done with it."""
    p = _norm(path)
    shared = is_shared(p)
    out = {
        "input": p,
        "shared": shared,
        "host_path": to_host(p),
        "harness_path": to_harness(p),
        "host_workspace": HOST_WORKSPACE,
        "harness_workspace": HARNESS_WORKSPACE,
    }
    out["which_side"] = (
        "harness" if p == HARNESS_WORKSPACE or p.startswith(HARNESS_WORKSPACE + "/")
        else "host" if p == HOST_WORKSPACE or p.startswith(HOST_WORKSPACE + "/")
        else "outside"
    )
    hp = out["harness_path"]
    out["exists_harness"] = pathlib.Path(hp).exists() if shared else None
    if not shared:
        out["_note"] = (
            f"{p!r} is outside the shared subtree, so neither side can hand it to the other. "
            f"Bind-mount it, or set CAMEO_HOST_WORKSPACE / CAMEO_LOCAL_WORKSPACE to the pair "
            f"of roots that actually correspond."
        )
    return out


def scratch_path(filename: str | None = None) -> pathlib.Path:
    """The disposable-model directory, or a path inside it.

    Provision it rather than assume it exists: the directory is what keeps a mutating
    validation run off the shared samples and off the Cameo install.
    """
    d = pathlib.Path(SCRATCH_DIR)
    d.mkdir(parents=True, exist_ok=True)
    return d if filename is None else d / filename


def verify_model(harness_path: str, mcp_url: str) -> dict:
    """Prove the mapping by asking the server to open a model through it.

    This is the only real test of a path translation. Everything else is arithmetic, and
    arithmetic over the wrong pair of roots is confidently wrong.
    """
    import mcp_client

    info = classify(harness_path)
    out = dict(info)
    if not info["shared"]:
        out["verified"] = False
        out["reason"] = "path is not in the shared subtree; refusing to guess"
        return out
    if not pathlib.Path(info["harness_path"]).is_file():
        out["verified"] = False
        out["reason"] = f"no file at {info['harness_path']}"
        return out

    c = mcp_client.Client(mcp_url, timeout=300).connect()
    try:
        # Already open at the right path? Then there is nothing to prove by reloading, and
        # reloading is actively harmful: admin_load_model on the open model can hang past the
        # client timeout, which reads as a broken mapping when the mapping is fine.
        already = _open_location(c)
        if already and _same_file(already, info["host_path"]):
            out["server_says"] = {"status": "already open", "path": already}
            out["open_model"] = c.call("admin_get_model_status", {})
            out["verified"] = True
            out["reloaded"] = False
            return out
        # Project loading is asynchronous: a load that succeeded can still report no active
        # project for a moment, which is not the same as a load that failed. So retry on the
        # specific transient shape instead of reporting a failure that is really a race.
        attempts, delay = 4, 1.5
        loaded = status = None
        for i in range(attempts):
            loaded = c.call("admin_load_model", {"path": info["host_path"]})
            status = c.call("admin_get_model_status", {})
            if not _is_load_error(loaded) and _open_location(c):
                break
            if i < attempts - 1:
                time.sleep(delay)
                delay *= 2

        out["server_says"] = loaded
        out["open_model"] = status
        # The authoritative answer is cameo://project. admin_get_model_status is not
        # self-consistent: on a fresh load its "fileName" holds a full path, and once the
        # model has been open a while the same key holds a bare basename. A field that
        # changes meaning depending on timing is not a field to verify against.
        host = _open_location(c)
        if not _is_load_error(loaded) and host and host == info["host_path"]:
            out["verified"] = True
        else:
            out["verified"] = False
            out["reason"] = (
                f"the server opened {host!r} rather than {info['host_path']!r}"
                if host else
                "the server never reported an open model, after "
                f"{attempts} attempts: {loaded!r}")
    finally:
        c.close()
    return out


def _is_load_error(payload) -> bool:
    if isinstance(payload, dict):
        return bool(payload.get("error"))
    return "error" in str(payload).lower()


def _same_file(a: str, b: str) -> bool:
    """Compare two paths for the same file, tolerating a "file:" prefix and trailing slashes.

    The server is not consistent about the scheme: cameo://project has been seen reporting both
    "/home/..." and "file:/home/...". Treating those as different files makes an open model look
    unverified.
    """
    def norm(p: str) -> str:
        p = str(p)
        if p.startswith("file:"):
            p = p[5:]
        return p.rstrip("/")
    return norm(a) == norm(b)


def _open_location(c) -> str | None:
    """The open model's own reported location, from cameo://project.

    Falls back to admin_get_model_status, and only trusts a value that is an absolute path.
    """
    import json as _json
    try:
        proj = _json.loads(c.resource("cameo://project"))
    except Exception:
        proj = None
    for blob in (proj, None):
        if not isinstance(blob, dict):
            continue
        for k in ("location", "filePath", "file_path", "path", "fileName"):
            v = blob.get(k)
            if v and (str(v).startswith("/") or str(v).startswith("file:/")):
                return str(v)
    try:
        blob = c.call("admin_get_model_status", {})
    except Exception:
        return None
    for k in ("filePath", "file_path", "path", "location", "fileName"):
        v = blob.get(k) if isinstance(blob, dict) else None
        if v and str(v).startswith("/"):
            return str(v)
    return None


MCP_DEFAULT = "http://host.containers.internal:18750/mcp"

USAGE = """usage: vpath <command> [path]

  check <path>        classify a path and show both sides
  to-host <path>      the string to hand the host Cameo
  to-harness <path>   the string this process can open
  scratch [name]      the scratch dir (optionally for one file)
  verify-model <path> prove the mapping by having Cameo open a model through it
"""


def main(argv: list[str] | None = None) -> int:
    import json
    import os

    argv = list(sys.argv[1:] if argv is None else argv)
    if not argv or argv[0] in ("-h", "--help", "help"):
        print(USAGE.rstrip())
        return 0
    cmd, rest = argv[0], argv[1:]

    if cmd == "check":
        if not rest:
            print(USAGE.rstrip())
            return 2
        print(json.dumps(classify(rest[0]), indent=2, ensure_ascii=False))
        return 0

    if cmd == "to-host":
        if not rest:
            print(USAGE.rstrip())
            return 2
        print(to_host(rest[0]))
        return 0

    if cmd == "to-harness":
        if not rest:
            print(USAGE.rstrip())
            return 2
        print(to_harness(rest[0]))
        return 0

    if cmd == "scratch":
        print(scratch_path(*rest))
        return 0

    if cmd == "verify-model":
        if not rest:
            print(USAGE.rstrip())
            return 2
        url = os.environ.get("CAMEO_MCP_URL", MCP_DEFAULT)
        out = verify_model(rest[0], url)
        print(json.dumps(out, indent=2, ensure_ascii=False))
        return 0 if out.get("verified") else 1

    print(f"vpath: unknown command {cmd!r}", file=sys.stderr)
    print(USAGE.rstrip(), file=sys.stderr)
    return 2
