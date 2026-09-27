"""Layer 0: static metrics over a surface manifest.

These numbers are cheap, deterministic and need no model and no server. They cannot
tell you whether the surface *works* -- that is what the task suite in 03 is for. What
they can do is explain a Task-Success-Rate change after the fact, and catch a surface
edit that made the surface worse before you spend an hour of model time finding out.

Each metric is tagged with the design rule from 01-designregeln-dos-donts.md that it
comes from, so a finding can be argued with on the record instead of on taste.

Rules 5 (return payloads), 6 (error shaping) and 7 (workflow collapsing) are not
decidable from annotations: they need a live call and a task trace. They are reported
as `not-covered` rather than silently scored, so the report never implies a rigour the
measurement does not have.
"""

from __future__ import annotations

import json
import math
import pathlib
import re
import sys
from collections import Counter, defaultdict

sys.path.insert(0, str(pathlib.Path(__file__).parent))

# Rule 2 names that name no intent. Offering one of these is a Don't per the doc.
VAGUE_NAMES = {
    "execute", "run", "perform", "operation", "do", "handle", "process",
    "call", "invoke", "action", "command", "request", "generic", "misc",
    "helper", "util", "utils", "stuff", "thing",
}
VAGUE_PREFIX = re.compile(r"^(execute|run|perform|handle|process|do|generic)_")

# Rule 3: a description that never says when to use the tool leaves the model to guess.
WHEN_HINTS = re.compile(
    r"\b(use this|use when|when you|when the|for when|if you need|"
    r"call this|returns?|use instead|instead of|not for|do not use|don't use|"
    r"only if|only when|required for|used to|when)\b",
    re.I,
)
NEGATION_HINTS = re.compile(
    r"(do not|don't|dont|not for|never use|avoid|instead of|rather than|"
    r"not use|use .* not|only when|only if|do NOT|IMPORTANT)", re.I,
)

# Rule 4: parameter names that carry no information about which element is meant.
GENERIC_PARAMS = {
    "id", "value", "values", "data", "input", "output", "result", "type",
    "name", "text", "content", "item", "obj", "object", "key", "path",
}

_WORD = re.compile(r"[a-z0-9]+")
_STOP = {
    "a", "an", "the", "of", "for", "to", "in", "on", "and", "or", "is", "are",
    "be", "by", "with", "that", "this", "it", "as", "at", "from", "returns",
    "return", "returns", "use", "used", "using", "when", "you", "your", "if",
    "not", "no", "can", "will", "should", "must", "may", "given", "one", "all",
    "any", "each", "other", "same", "such", "into", "via", "than", "then",
    "was", "were", "has", "have", "had", "but", "so", "do", "does", "did",
}

# Approximate. Only ever used for a relative "how big is this" number, never as a
# billing figure.
CHARS_PER_TOKEN = 3.6


def _tokens(text: str) -> set[str]:
    return {w for w in _WORD.findall((text or "").lower()) if w not in _STOP and len(w) > 2}


def _jaccard(a: set, b: set) -> float:
    if not a or not b:
        return 0.0
    return len(a & b) / len(a | b)


def _finding(rule, severity, message, subject=None, suggestion=None, evidence=None):
    return {"rule": rule, "severity": severity, "message": message,
            "subject": subject, "suggestion": suggestion, "evidence": evidence or {}}


# --------------------------------------------------------------------- metrics

def m_context_cost(manifest):
    """How much of the context window the surface description costs before any work.

    The single most useful static number for small models: a 9B model paying 6k tokens
    of tool descriptions up front has less room and more distraction than one paying 2k.
    """
    tool_desc = sum(t["description_chars"] for t in manifest["tools"])
    arg_desc = sum(len(a.get("description") or "")
                   for t in manifest["tools"] for a in t["arguments"])
    arg_names = sum(len(a["name"]) + 12 for t in manifest["tools"] for a in t["arguments"])
    total = tool_desc + arg_desc
    return {
        "tool_description_chars": tool_desc,
        "argument_description_chars": arg_desc,
        "argument_name_overhead_chars": arg_names,
        "total_description_chars": total,
        "approx_tokens": round(total / CHARS_PER_TOKEN),
        "tools": manifest["counts"]["tools"],
        "arguments": manifest["counts"]["arguments"],
        "approx_tokens_per_tool": round(total / max(1, manifest["counts"]["tools"])),
        "chars_per_tool": {t["name"]: t["description_chars"] for t in manifest["tools"]},
    }


def m_description_length(manifest, warn_chars=800, error_chars=2000):
    long_tools = sorted(
        ({"name": t["name"], "chars": t["description_chars"],
          "file": t["decl"].get("file"), "line": t["decl"].get("line")}
         for t in manifest["tools"] if t["description_chars"] > warn_chars),
        key=lambda d: -d["chars"])
    return {
        "warn_above_chars": warn_chars,
        "error_above_chars": error_chars,
        "over_warn": long_tools,
        "over_error": [d for d in long_tools if d["chars"] > error_chars],
        "median_chars": _median([t["description_chars"] for t in manifest["tools"]]),
        "max_chars": max((t["description_chars"] for t in manifest["tools"]), default=0),
    }


def _median(xs):
    if not xs:
        return 0
    xs = sorted(xs)
    mid = len(xs) // 2
    return xs[mid] if len(xs) % 2 else round((xs[mid - 1] + xs[mid]) / 2)


def m_names(manifest):
    tools = manifest["tools"]
    vague, prefix_vague = [], []
    for t in tools:
        name = t["name"]
        if name.lower() in VAGUE_NAMES:
            vague.append(name)
        elif VAGUE_PREFIX.match(name):
            prefix_vague.append(name)
    lowercase_but_similar = []
    by_stem = defaultdict(list)
    for t in tools:
        by_stem[_stem(t["name"])].append(t["name"])
    for stem, names in by_stem.items():
        if len(names) > 1:
            lowercase_but_similar.append({"stem": stem, "tools": sorted(names)})
    return {
        "duplicate_names": manifest["duplicate_tool_names"],
        "vague_names": vague,
        "vague_prefixed": prefix_vague,
        "same_stem_different_namespace": lowercase_but_similar,
    }


def _stem(name: str) -> str:
    for p in ("saf_", "spec_", "admin_", "modelcode_", "plugincode_"):
        if name.startswith(p):
            return name[len(p):]
    return name


def m_parameters(manifest, warn_optional=5, error_optional=8):
    rows = []
    for t in manifest["tools"]:
        rows.append({"name": t["name"],
                     "total": t["arguments_total"],
                     "required": t["required_arguments"],
                     "optional": t["optional_arguments"]})
    optional = sorted((r for r in rows if r["optional"] > warn_optional),
                      key=lambda r: -r["optional"])
    generic = defaultdict(list)
    for t in manifest["tools"]:
        for a in t["arguments"]:
            if a["name"].lower() in GENERIC_PARAMS:
                generic[a["name"]].append(t["name"])
    identical_optional = []
    for t in manifest["tools"]:
        docs = Counter((a.get("description") or "").strip() for a in t["arguments"]
                       if not a["required"] and (a.get("description") or "").strip())
        for doc, n in docs.items():
            if n > 1:
                identical_optional.append(
                    {"tool": t["name"], "times": n, "description": doc[:120]})
    return {
        "warn_above_optional": warn_optional,
        "error_above_optional": error_optional,
        "over_optional_budget": optional,
        "over_error_budget": [r for r in optional if r["optional"] > error_optional],
        "tools_without_required_args": sorted(r["name"] for r in rows if r["required"] == 0),
        "generic_parameter_names": {k: sorted(v) for k, v in
                                    sorted(generic.items(), key=lambda kv: -len(kv[1]))},
        "optional_args_with_identical_help": identical_optional,
        "per_tool": sorted(rows, key=lambda r: -r["total"]),
    }


def m_overlap(manifest, threshold=0.55):
    """Rule 8: pairs of tools a model could reasonably confuse.

    Deliberately conservative -- it reports *candidates* to argue about, not verdicts.
    A high score on a pair like saf_create_element/create_element is information; a
    failing gate on it would be a design decision, not a measurement.
    """
    items = []
    for t in manifest["tools"]:
        items.append((t["name"], _tokens(t["name"]) | _tokens(t.get("description", "")),
                      t["family"]))
    pairs = []
    for i in range(len(items)):
        ni, ti, fi = items[i]
        for j in range(i + 1, len(items)):
            nj, tj, fj = items[j]
            if fi != fj and _stem(ni) == _stem(nj):
                continue                       # deliberate namespace split
            s = _jaccard(ti, tj)
            if s >= threshold:
                shared = sorted(ti & tj)
                pairs.append({"a": ni, "b": nj, "score": round(s, 3),
                              "shared_terms": shared[:8]})
    pairs.sort(key=lambda p: -p["score"])

    # Cross-namespace twins are the most suspicious class: same stem, two spellings.
    # But a stem collision is only a defect when the boundary is unstated. If each card
    # names its counterpart ("use saf_create_element instead", "prefer this over
    # find_elements_by_type"), the split is a documented dispatch pair and the correct
    # reading is "the model must read both cards", not "these two are the same tool".
    twins = []
    by_stem = defaultdict(list)
    for t in manifest["tools"]:
        by_stem[_stem(t["name"])].append(t)
    for stem, ts in by_stem.items():
        if len(ts) > 1:
            base = max(ts, key=lambda x: len(x.get("description", "")))
            for other in ts:
                if other is base:
                    continue
                pair, crossrefs = [], {}
                for a, b in ((base, other), (other, base)):
                    txt = a.get("description", "")
                    hit = _cross_reference(txt, b["name"])
                    crossrefs[a["name"]] = hit
                    pair.append(a["name"])
                twins.append({
                    "stem": stem,
                    "tools": sorted(x["name"] for x in ts),
                    "most_documented": base["name"],
                    "self_similarity": round(
                        _jaccard(_tokens(base.get("description", "")),
                                 _tokens(other.get("description", ""))), 3),
                    "boundary_documented": all(crossrefs.values()),
                    "cross_references": crossrefs,
                })
    return {"threshold": threshold, "similar_pairs": pairs, "namespace_twins": twins}


# A "dispatch" points the model at one tool in preference to the other. The target name
# sits between the verb and the trailing "instead", so lead and trail are tested
# separately rather than as one phrase.
#
# "first" is deliberately not a trail marker: "call create_element first" is a sentence
# about ordering, not a claim that the other tool is wrong, and reading it as a dispatch
# makes every walkthrough example vouch for a boundary it never states.
_DISPATCH_LEAD = re.compile(
    r"\b(use|using|prefer|prefers|preferring|choose|choosing|try|reach for|"
    r"instead of|rather than|over)\b(?:\s+\S+){0,4}\s*$", re.I)
_DISPATCH_TRAIL = re.compile(r"\b(instead|rather than)\b", re.I)


def _cross_reference(text: str, other: str) -> str | None:
    """How `text` points the model at `other`, if it does.

    Word-boundary match, not substring: "saf_create_element" contains "create_element",
    so a naive `in` test makes every prefixed twin vouch for its own unprefixed sibling
    and the check never fires. Every occurrence is examined -- a card that dispatches
    once and merely mentions later still dispatches.
    """
    if not text:
        return None
    best = None
    for hit in re.finditer(rf"\b{re.escape(other)}\b", text):
        lead = text[max(0, hit.start() - 160): hit.start()]
        trail = text[hit.end(): hit.end() + 60]
        if _DISPATCH_LEAD.search(lead) or _DISPATCH_TRAIL.search(trail):
            return "dispatches"
        best = best or "mentions"
    return best


def m_guidance(manifest):
    """Rule 3: does each description actually help the model choose?"""
    no_when, no_negation, no_desc, no_arg_desc = [], [], [], []
    for t in manifest["tools"]:
        d = t.get("description") or ""
        if not d.strip():
            no_desc.append(t["name"])
            continue
        if not WHEN_HINTS.search(d):
            no_when.append(t["name"])
        if not NEGATION_HINTS.search(d):
            no_negation.append(t["name"])
    for t in manifest["tools"]:
        for a in t["arguments"]:
            if not (a.get("description") or "").strip():
                no_arg_desc.append(f"{t['name']}.{a['name']}")
    return {
        "tools_without_description": no_desc,
        "tools_without_usage_guidance": no_when,
        "tools_without_boundary_guidance": no_negation,
        "arguments_without_description": no_arg_desc,
        "counts": {
            "tools": len(manifest["tools"]),
            "without_usage_guidance": len(no_when),
            "without_boundary_guidance": len(no_negation),
            "arguments_without_description": len(no_arg_desc),
        },
    }


NOT_COVERED = {
    "5": "Return payload size and task-specificity need live calls; annotations declare "
         "inputs only.",
    "6": "Error-message shaping is server runtime behaviour; measure it with a task "
         "that provokes failures.",
    "7": "Workflow collapsing needs task traces to show which chains are actually "
         "walked. Candidate chains can be guessed from tool names, not measured.",
}


# ---------------------------------------------------------------------- report

def analyze(manifest: dict) -> dict:
    metrics = {
        "context_cost": m_context_cost(manifest),
        "description_length": m_description_length(manifest),
        "names": m_names(manifest),
        "parameters": m_parameters(manifest),
        "overlap": m_overlap(manifest),
        "guidance": m_guidance(manifest),
    }

    findings: list[dict] = []
    cc, dl, nm, pa, gl = (metrics["context_cost"], metrics["description_length"],
                          metrics["names"], metrics["parameters"], metrics["guidance"])

    # Rule 1 -- tool count small.
    findings.append(_finding(
        "1", "info",
        f"{cc['tools']} tools, {cc['arguments']} arguments, "
        f"~{cc['approx_tokens']} description tokens "
        f"({cc['approx_tokens_per_tool']}/tool)",
        suggestion="Tool count alone is not a target. Use it to compare surfaces, then "
                   "let task success decide."))

    # Rule 2 -- unambiguous names.
    if nm["vague_names"] or nm["vague_prefixed"]:
        findings.append(_finding(
            "2", "warn",
            f"{len(nm['vague_names']) + len(nm['vague_prefixed'])} tool(s) use a "
            f"non-committal name",
            subject=(nm["vague_names"] + nm["vague_prefixed"])[0],
            suggestion="Name the intent: `find_elements`, not `execute`.",
            evidence={"names": nm["vague_names"] + nm["vague_prefixed"]}))
    for group in nm["same_stem_different_namespace"]:
        findings.append(_finding(
            "2", "info",
            f"{len(group['tools'])} tools share the stem `{group['stem']}`: "
            + ", ".join(group["tools"]),
            subject=group["tools"][0],
            suggestion="Same stem in two namespaces is an intentional split; it is "
                       "still two tools the model must choose between."))

    # Rule 3 -- short, action-oriented descriptions.
    for d in dl["over_error"]:
        findings.append(_finding(
            "3", "warn",
            f"description is {d['chars']} chars (budget {dl['error_above_chars']})",
            subject=d["name"],
            suggestion="Split the reference material into a deep-contract resource "
                       "(`cameo://tool/*`) and keep the card to intent + when-to-use.",
            evidence={"decl": f"{d['file']}:{d['line']}"}))
    for d in dl["over_warn"]:
        if d["chars"] <= dl["error_above_chars"]:
            findings.append(_finding(
                "3", "info", f"description is {d['chars']} chars", subject=d["name"]))
    if gl["counts"]["without_usage_guidance"]:
        findings.append(_finding(
            "3", "warn",
            f"{gl['counts']['without_usage_guidance']}/{gl['counts']['tools']} tools "
            f"never say when to use them",
            suggestion="Add one 'Use this when ...' line; tool selection is where a "
                       "small model actually fails.",
            evidence={"tools": gl["tools_without_usage_guidance"]}))
    if gl["counts"]["without_boundary_guidance"]:
        findings.append(_finding(
            "3", "info",
            f"{gl['counts']['without_boundary_guidance']} tools give no 'do not use "
            f"this when' guidance",
            subject=gl["tools_without_boundary_guidance"][0] if
                    gl["tools_without_boundary_guidance"] else None,
            evidence={"tools": gl["tools_without_boundary_guidance"]}))
    if gl["arguments_without_description"]:
        findings.append(_finding(
            "4", "warn",
            f"{len(gl['arguments_without_description'])} arguments have no description",
            subject=gl["arguments_without_description"][0],
            evidence={"args": gl["arguments_without_description"][:20]}))

    # Rule 4 -- parameters reduced.
    for r in pa["over_error_budget"]:
        findings.append(_finding(
            "4", "warn",
            f"{r['optional']} optional arguments ({r['required']} required)",
            subject=r["name"],
            suggestion=f"Above the {pa['error_above_optional']}-optional budget: split "
                       "the tool or drop the rarely used switches."))
    for r in pa["over_optional_budget"]:
        if r["optional"] <= pa["error_above_optional"]:
            findings.append(_finding(
                "4", "info", f"{r['optional']} optional arguments", subject=r["name"]))
    if pa["tools_without_required_args"]:
        findings.append(_finding(
            "4", "info",
            f"{len(pa['tools_without_required_args'])} tools have no required argument",
            subject=pa["tools_without_required_args"][0],
            evidence={"tools": pa["tools_without_required_args"]}))
    for name, users in list(pa["generic_parameter_names"].items())[:5]:
        findings.append(_finding(
            "4", "info", f"generic parameter name `{name}` on {len(users)} tools",
            subject=name, evidence={"tools": users[:10]}))

    # Rule 8 -- semantic overlap.
    for p in overlap_significant(metrics["overlap"]):
        findings.append(_finding(
            "8", "warn",
            f"`{p['a']}` and `{p['b']}` overlap at {p['score']} "
            f"({', '.join(p['shared_terms'][:4])})",
            subject=p["a"],
            suggestion="Merge them, or name the boundary explicitly in both cards.",
            evidence=p))
    for tw in metrics["overlap"]["namespace_twins"]:
        pair = " / ".join(f"`{x}`" for x in tw["tools"])
        if tw["boundary_documented"]:
            findings.append(_finding(
                "8", "info",
                f"{pair} share the stem `{tw['stem']}` but document the boundary "
                f"(self-similarity {tw['self_similarity']})",
                subject=tw["tools"][0],
                suggestion="Documented dispatch pair, not a duplicate. The cost is that "
                           "the model must read both cards before it can pick one -- "
                           "keep that in mind before merging."))
        else:
            findings.append(_finding(
                "8", "warn",
                f"{pair} share the stem `{tw['stem']}` with no stated boundary "
                f"(self-similarity {tw['self_similarity']}, "
                f"refs {tw['cross_references']})",
                subject=tw["tools"][0],
                suggestion="Same stem in two prefixes forces the model to compare them "
                           "blind. State which one to use, or merge them."))

    order = {"warn": 0, "info": 1}
    findings.sort(key=lambda f: (order.get(f["severity"], 2), f["rule"], f["message"]))

    return {
        "surface": manifest["version"],
        "generated": manifest["generated"],
        "source": manifest["source"]["kind"],
        "headline": {
            "tools": manifest["counts"]["tools"],
            "arguments": manifest["counts"]["arguments"],
            "resources": manifest["counts"]["resources"],
            "prompts": manifest["counts"]["prompts"],
            "approx_description_tokens": cc["approx_tokens"],
            "warn": sum(1 for f in findings if f["severity"] == "warn"),
            "info": sum(1 for f in findings if f["severity"] == "info"),
        },
        "metrics": metrics,
        "findings": findings,
        "not_covered": NOT_COVERED,
    }


def overlap_significant(overlap: dict, min_terms: int = 3):
    return [p for p in overlap["similar_pairs"] if len(p["shared_terms"]) >= min_terms]


# ----------------------------------------------------------------------- output

def render(report: dict) -> str:
    h = report["headline"]
    out = [f"surface-{report['surface']}  (source: {report['source']})",
           f"  {h['tools']} tools / {h['arguments']} arguments / "
           f"{h['resources']} resources / {h['prompts']} prompt"
           f"   ~{h['approx_description_tokens']} description tokens",
           f"  findings: {h['warn']} warn, {h['info']} info", ""]

    by_rule = defaultdict(list)
    for f in report["findings"]:
        by_rule[f["rule"]].append(f)
    titles = {
        "1": "tool count", "2": "tool names unambiguous", "3": "descriptions short and action-oriented",
        "4": "parameters reduced", "8": "semantic overlap",
    }
    for rule in sorted(by_rule):
        group = by_rule[rule]
        out.append(f"[rule {rule}] {titles.get(rule, '')} -- {len(group)} finding(s)")
        for f in group:
            mark = "!" if f["severity"] == "warn" else "-"
            out.append(f"  {mark} {f['message']}")
            if f.get("subject"):
                out.append(f"      subject: {f['subject']}")
            if f.get("suggestion"):
                out.append(f"      try: {f['suggestion']}")
        out.append("")

    out.append("[not covered by static analysis]")
    for rule, why in report["not_covered"].items():
        out.append(f"  rule {rule}: {why}")
    out.append("")
    return "\n".join(out)
