"""Build and version the MCP tool-surface manifest.

A manifest is the named, comparable snapshot that every measurement refers to. Without
one you cannot answer "is surface-v2 better than surface-v1?", because two arms that
were never described the same way are not comparable.

Two sources, same output shape:

  groovy-scripts  parse `@McpTool` / `@McpToolArgument` / `@McpResource` out of
                  `scripts/*.groovy`. Works with Cameo down, which is the normal case
                  while iterating on the surface. Parameter names, types, required
                  flags and descriptions all come from the source of truth.

  live-mcp        `tools/list` from a running server. The authoritative view of what a
                  model would actually be offered.

The two are meant to be cross-checked (`vsurface --verify-live`): a manifest built
offline that has drifted from the running surface is worse than no manifest, because
it looks authoritative.
"""

from __future__ import annotations

import datetime as _dt
import json
import os
import pathlib
import re
import subprocess
import sys

sys.path.insert(0, str(pathlib.Path(__file__).parent))

from groovy_annotations import DYNAMIC, parse_file  # noqa: E402

REPO = pathlib.Path(__file__).resolve().parents[2]
SCRIPTS = REPO / "scripts"

# Namespaces the suite treats as distinct families. Reported separately, because
# "18 saf_* vs 15 spec_* vs 12 CRUD" is a more actionable finding than "75 tools".
PREFIXES = ("saf_", "spec_", "admin_", "modelcode_", "plugincode_")


def _now() -> str:
    return _dt.datetime.now(_dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _git_commit() -> str:
    try:
        out = subprocess.run(
            ["git", "-C", str(REPO), "rev-parse", "--short", "HEAD"],
            capture_output=True, text=True, timeout=10,
        )
        return out.stdout.strip() or "unknown"
    except Exception:
        return "unknown"


def family(name: str) -> str:
    for p in PREFIXES:
        if name.startswith(p):
            return p.rstrip("_")
    return "core"


def _blank_tool() -> dict:
    return {"name": "", "description": "", "arguments": [], "decl": {}}


# ---------------------------------------------------------------- groovy source

def from_scripts(script_dir: pathlib.Path | None = None) -> dict:
    script_dir = script_dir or SCRIPTS
    files = sorted(script_dir.glob("*.groovy"))
    if not files:
        raise SystemExit(f"no .groovy scripts found in {script_dir}")

    tools: list[dict] = []
    resources: list[dict] = []
    prompts: list[dict] = []
    anomalies: list[dict] = []

    for path in files:
        current: dict | None = None
        for ann in parse_file(path):
            rel = str(pathlib.Path(path).relative_to(REPO))
            decl = {"file": rel, "line": ann.line}
            if ann.malformed_literals:
                anomalies.append({"file": rel, "line": ann.line, "kind": ann.kind,
                                  "identifier": ann.identifier,
                                  "detail": "unterminated string literal near this annotation"})
            if ann.group == "tool":
                current = _blank_tool()
                current["name"] = ann.identifier
                current["description"] = ann.args.get("description") or ""
                current["decl"] = decl
                if ann.interpolated:
                    current["interpolated_description"] = True
                tools.append(current)
            elif ann.group == "tool_argument" and current is not None:
                current["arguments"].append({
                    "name": ann.args.get("name") or "",
                    "type": ann.args.get("type") or "string",
                    "required": ann.args.get("required") is True,
                    "description": ann.args.get("description") or "",
                    "decl": decl,
                })
            elif ann.group == "resource":
                resources.append({
                    "uri": ann.identifier,
                    "name": ann.args.get("name") or "",
                    "description": ann.args.get("description") or "",
                    "mimeType": ann.args.get("mimeType") or "text/plain",
                    "decl": decl,
                })
            elif ann.group == "prompt":
                prompts.append({
                    "name": ann.identifier,
                    "description": ann.args.get("description") or "",
                    "decl": decl,
                })

    return _assemble("groovy-scripts", tools, resources, prompts,
                     anomalies=anomalies,
                     source={"scripts_dir": str(script_dir.relative_to(REPO)),
                             "script_files": [p.name for p in files]})


# ---------------------------------------------------------------- live server

def from_live(mcp_url: str, timeout: int = 30) -> dict:
    """Read tools/list, resources/list and prompts/list from a running server.

    Goes through mcp_client so it carries the bearer token. The previous private
    urlopen loop omitted it, so every live call against the real server came back 401
    -- which reads as "Cameo is down" and sends you looking in the wrong place.
    """
    import mcp_client

    c = mcp_client.Client(mcp_url, timeout=timeout).connect()

    def listed(method: str, key: str) -> list:
        try:
            return c.rpc(method).get(key, []) or []
        except mcp_client.McpError:
            return []

    tools = []
    for t in listed("tools/list", "tools"):
        schema = t.get("inputSchema") or {}
        props = schema.get("properties") or {}
        required = set(schema.get("required") or [])
        args = []
        for aname, spec in props.items():
            spec = spec if isinstance(spec, dict) else {}
            args.append({
                "name": aname,
                "type": spec.get("type", "string"),
                "required": aname in required,
                "description": spec.get("description", ""),
            })
        tools.append({"name": t.get("name", ""), "description": t.get("description", ""),
                      "arguments": args, "decl": {}})

    resources = [{"uri": r.get("uri", ""), "name": r.get("name", ""),
                  "description": r.get("description", ""),
                  "mimeType": r.get("mimeType", "")}
                 for r in listed("resources/list", "resources")]

    prompts = []
    for pr in listed("prompts/list", "prompts"):
        prompts.append({
            "name": pr.get("name", ""),
            "description": pr.get("description", ""),
            "arguments": [{"name": a.get("name", ""),
                           "description": a.get("description", ""),
                           "required": bool(a.get("required"))}
                          for a in (pr.get("arguments") or [])],
        })

    c.close()
    return _assemble("live", tools, resources, prompts,
                     source={"mcp_url": mcp_url})



def _assemble(source_kind: str, tools, resources, prompts,
              anomalies=None, source=None) -> dict:
    for t in tools:
        t["family"] = family(t["name"])
        t["description_chars"] = len(t.get("description") or "")
        req = [a for a in t["arguments"] if a.get("required")]
        opt = [a for a in t["arguments"] if not a.get("required")]
        t["required_arguments"] = len(req)
        t["optional_arguments"] = len(opt)
        t["arguments_total"] = len(t["arguments"])

    tools.sort(key=lambda t: t["name"])
    resources.sort(key=lambda r: r["uri"])
    prompts.sort(key=lambda p: p["name"])

    counts = {"tools": len(tools), "resources": len(resources), "prompts": len(prompts),
              "arguments": sum(t["arguments_total"] for t in tools)}
    fam: dict[str, int] = {}
    for t in tools:
        fam[t["family"]] = fam.get(t["family"], 0) + 1

    names = [t["name"] for t in tools]
    dupes = sorted({n for n in names if names.count(n) > 1})

    manifest = {
        "version": None,
        "generated": _now(),
        "source": {"kind": source_kind, "plugin_commit": _git_commit(), **(source or {})},
        "counts": counts,
        "families": dict(sorted(fam.items())),
        "duplicate_tool_names": dupes,
        "anomalies": anomalies or [],
        "tools": tools,
        "resources": resources,
        "prompts": prompts,
    }
    return manifest


def save(manifest: dict, path: pathlib.Path, version: str) -> pathlib.Path:
    manifest["version"] = version
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(manifest, fh, indent=2, ensure_ascii=False, sort_keys=False)
        fh.write("\n")
    return path


def load(path: pathlib.Path) -> dict:
    with open(path, "r", encoding="utf-8") as fh:
        return json.load(fh)


# ---------------------------------------------------------------- comparison

_TOOL_CRITICAL = ("name", "description", "arguments")


def diff(before: dict, after: dict) -> dict:
    """Structural difference between two manifests, ignoring the volatile fields."""
    def index(m):
        return {t["name"]: t for t in m["tools"]}

    a, b = index(before), index(after)
    added = sorted(set(b) - set(a))
    removed = sorted(set(a) - set(b))
    changed = []
    for name in sorted(set(a) & set(b)):
        fields = []
        for key in _TOOL_CRITICAL:
            if a[name].get(key) != b[name].get(key):
                if key == "arguments":
                    pa = {x["name"]: (x["type"], x["required"]) for x in a[name]["arguments"]}
                    pb = {x["name"]: (x["type"], x["required"]) for x in b[name]["arguments"]}
                    detail = []
                    for k in sorted(set(pa) - set(pb)):
                        detail.append(f"-arg {k}")
                    for k in sorted(set(pb) - set(pa)):
                        detail.append(f"+arg {k}")
                    for k in sorted(set(pa) & set(pb)):
                        if pa[k] != pb[k]:
                            detail.append(f"~arg {k} {pa[k]}->{pb[k]}")
                    if detail:
                        fields.append("args: " + ", ".join(detail))
                else:
                    fields.append(key)
        if fields:
            changed.append({"name": name, "fields": fields})

    return {
        "added_tools": added,
        "removed_tools": removed,
        "changed_tools": changed,
        "counts": {
            "before": before["counts"],
            "after": after["counts"],
            "delta": {k: after["counts"].get(k, 0) - before["counts"].get(k, 0)
                      for k in after["counts"]},
        },
    }


def verify_live(offline: dict, live: dict) -> dict:
    """Report where the offline manifest and the running surface disagree."""
    a = {t["name"] for t in offline["tools"]}
    b = {t["name"] for t in live["tools"]}
    da = {t["name"]: t.get("description", "") for t in offline["tools"]}
    db = {t["name"]: t.get("description", "") for t in live["tools"]}
    return {
        "only_in_scripts": sorted(a - b),
        "only_in_live": sorted(b - a),
        "description_differs": sorted(n for n in (a & b) if da[n] != db[n]),
        "in_sync": a == b and all(da[n] == db[n] for n in a & b),
    }
