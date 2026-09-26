"""Static regression gate.

The gate exists because a surface edit is cheap to make and expensive to evaluate. If
you need an hour of small-model runs to find out that adding one convenience argument
made selection worse, the feedback loop is too slow to keep improving anything.

So: static metrics are a tripwire, not a score. Fail here means "this change is very
likely worse and needs a real task run to justify", never "this change is bad".

Two kinds of check, deliberately separated:

  budgets   absolute limits you set on purpose (max tools, max description tokens).
  baseline  whatever the previous known-good surface measured. Catches the change you
            did not notice making.

Both are escape-hatchable with --update-baseline, but the point of `--explain` is that
you have to say out loud which check you are overriding.
"""

from __future__ import annotations

import json
import pathlib
import sys
from dataclasses import dataclass, asdict

sys.path.insert(0, str(pathlib.Path(__file__).parent))

# Absolute budgets. These are the current surface, not a target. They are set at the
# observed value (or a hair above) on purpose: a tripwire that starts red gets ignored,
# which destroys the only thing it was for. A change that wants to go past one has to
# either shrink something else or say so via --update-baseline.
#
# The current worst cases (9 optional arguments on create_information_flow, 2715-char
# description on saf_get_element_semantics) are recorded as findings, not as budget
# violations. Fixing them is a task-suite decision, not a gate decision.
DEFAULT_BUDGETS = {
    "tools": 80,
    "arguments": 200,
    "approx_description_tokens": 17000,
    "warn_findings": 10,
    "max_optional_arguments": 9,
    "max_description_chars": 2800,
}

# Metrics where an increase is a regression. Direction matters: description length and
# warning count can only go down, tool counts can only go down.
LOWER_IS_BETTER = {
    "tools": "headline.tools",
    "arguments": "headline.arguments",
    "approx_description_tokens": "headline.approx_description_tokens",
    "warn": "headline.warn",
    "info": "headline.info",
}


@dataclass
class Result:
    check: str
    status: str            # pass | fail | skip
    detail: str
    before: object = None
    after: object = None

    def line(self) -> str:
        mark = {"pass": "ok  ", "fail": "FAIL", "skip": "skip"}[self.status]
        return f"  {mark} {self.check}: {self.detail}"


def _dig(report: dict, path: str):
    for part in path.split("."):
        if not isinstance(report, dict):
            return None
        report = report.get(part)
    return report


def _finding_key(f: dict) -> str:
    """Identity of a finding, stable enough to tell 'new' from 'pre-existing'.

    Message text is deliberately excluded: re-wording a finding should not make it look
    like the old one was fixed and a new one appeared.
    """
    return f"rule{f['rule']}|{f.get('subject') or ''}"


def run(report: dict, baseline: dict | None,
        budgets: dict | None = None, tol: float = 0.0) -> list[Result]:
    budgets = {**DEFAULT_BUDGETS, **(budgets or {})}
    results: list[Result] = []

    if baseline is None:
        return [Result("baseline", "skip",
                       "no baseline supplied -- budgets only, no regression check")]

    # --- budgets -------------------------------------------------------------
    for key, limit in budgets.items():
        if key == "max_optional_arguments":
            worst = _dig(report, "metrics.parameters.over_optional_budget") or []
            actual = max((r["optional"] for r in worst), default=0)
            results.append(Result(
                f"budget:{key}", "pass" if actual <= limit else "fail",
                f"widest optional-argument count {actual} <= {limit}"
                if actual <= limit else
                f"widest tool has {actual} optional arguments > {limit}", limit, actual))
        elif key == "max_description_chars":
            actual = _dig(report, "metrics.description_length.max_chars") or 0
            results.append(Result(
                f"budget:{key}", "pass" if actual <= limit else "fail",
                f"longest description {actual} <= {limit}" if actual <= limit else
                f"longest description {actual} > {limit}", limit, actual))
        else:
            actual = _dig(report, LOWER_IS_BETTER.get(key, f"headline.{key}"))
            if actual is None:
                actual = _dig(report, f"headline.{key}")
            if not isinstance(actual, (int, float)):
                continue
            results.append(Result(
                f"budget:{key}", "pass" if actual <= limit else "fail",
                f"{key}={actual} <= {limit}" if actual <= limit
                else f"{key}={actual} > {limit}", limit, actual))

    # --- regressions vs baseline --------------------------------------------
    for key, path in LOWER_IS_BETTER.items():
        before, after = _dig(baseline, path), _dig(report, path)
        if before is None or after is None:
            continue
        allowed = before * (1 + tol) if isinstance(before, float) else before + tol
        results.append(Result(
            f"regress:{key}", "pass" if after <= allowed else "fail",
            f"{key} {before} -> {after}" if after <= allowed
            else f"{key} {before} -> {after} (worse by {after - before})",
            before, after))

    # --- newly introduced findings ------------------------------------------
    # Matched on rule+subject, not on message text, so rewording a finding does not
    # make it look like the old one was fixed. Severity is compared separately: the
    # same finding getting *worse* is a regression too, and would otherwise hide
    # behind an unchanged key.
    old = {_finding_key(f): f for f in baseline.get("findings", [])}
    new = {_finding_key(f): f for f in report.get("findings", [])}
    introduced = [f for k, f in new.items()
                  if k not in old and f["severity"] == "warn"]
    escalated = [f for k, f in new.items()
                 if k in old and f["severity"] == "warn"
                 and old[k]["severity"] != "warn"]
    resolved = [f for k, f in old.items() if k not in new]
    results.append(Result(
        "regress:new_warns", "pass" if not introduced else "fail",
        "no new warn findings" if not introduced
        else f"{len(introduced)} new warn finding(s): "
             + ", ".join(f.get("subject") or f["message"] for f in introduced[:5]),
        None, [f.get("subject") for f in introduced]))
    results.append(Result(
        "regress:escalated", "pass" if not escalated else "fail",
        "no finding got worse" if not escalated
        else f"{len(escalated)} finding(s) escalated to warn: "
             + ", ".join(f.get("subject") or f["message"] for f in escalated[:5]),
        None, [f.get("subject") for f in escalated]))
    results.append(Result(
        "info:resolved", "pass" if not resolved else "skip",
        "no findings disappeared" if not resolved
        else f"{len(resolved)} finding(s) no longer reported: "
             + ", ".join(f.get("subject") or f["message"] for f in resolved[:5])))

    return results


def ok(results: list[Result]) -> bool:
    return not any(r.status == "fail" for r in results)


def render(results: list[Result]) -> str:
    fails = [r for r in results if r.status == "fail"]
    head = "GATE PASS" if not fails else f"GATE FAIL ({len(fails)} check(s))"
    return "\n".join([head] + [r.line() for r in results]) + "\n"


def write_baseline(path: pathlib.Path, report: dict, budgets: dict,
                   note: str = "") -> pathlib.Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {"note": note, "budgets": budgets, "report": report}
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, indent=2, ensure_ascii=False)
        fh.write("\n")
    return path
