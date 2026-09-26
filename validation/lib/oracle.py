"""Evaluate a task's oracle clauses.

Three tiers, cheapest first, and a clause whose inputs are missing returns UNKNOWN
rather than PASS. That distinction is the whole point of this module: a task scored
because its evidence happened to be absent from the transcript looks like a success
that never happened, and that is how a benchmark starts lying.

    trajectory clauses  read trajectory.jsonl                      (offline)
    answer clauses      read the agent's final message            (offline)
    model clauses       query the running Cameo over MCP           (needs a probe)
    model_unchanged     compare a pre/post element count           (needs a probe)

The model-backed tiers take an injected `probe` (see `McpProbe`). Nothing here opens a
socket, so every clause in this file is unit-testable without a Cameo; step 4 of the
build order supplies the real probe and nothing in here changes.
"""

from __future__ import annotations

import json
import pathlib
import re
import sys
from dataclasses import dataclass, field

sys.path.insert(0, str(pathlib.Path(__file__).parent))

PASS, FAIL, UNKNOWN = "PASS", "FAIL", "UNKNOWN"

_VERDICT_ORDER = {PASS: 0, UNKNOWN: 1, FAIL: 2}


@dataclass
class ClauseResult:
    label: str
    kind: str
    verdict: str
    reason: str
    detail: dict = field(default_factory=dict)

    def __repr__(self):
        return f"<{self.verdict} {self.label}>"


@dataclass
class TaskResult:
    task_id: str
    clauses: list[ClauseResult] = field(default_factory=list)

    @property
    def verdict(self) -> str:
        if not self.clauses:
            return UNKNOWN
        return max((c.verdict for c in self.clauses), key=lambda v: _VERDICT_ORDER[v])

    @property
    def passed(self) -> int:
        return sum(1 for c in self.clauses if c.verdict == PASS)

    @property
    def unknown(self):
        return [c.label for c in self.clauses if c.verdict == UNKNOWN]

    @property
    def failed(self):
        return [c.label for c in self.clauses if c.verdict == FAIL]

    def as_dict(self) -> dict:
        return {"task_id": self.task_id, "verdict": self.verdict,
                "passed": self.passed, "clauses": len(self.clauses),
                "unknown": self.unknown, "failed": self.failed,
                "results": [c.__dict__ for c in self.clauses]}


# ------------------------------------------------------------------ trajectory

@dataclass
class Call:
    tool: str
    status: str
    input: dict
    output: str

    @property
    def errored(self) -> bool:
        return self.status == "error"


def read_trajectory(path: pathlib.Path | str) -> list[Call]:
    """Read the `tool_use` events out of an opencode --format json stream.

    Tolerant on purpose: a trajectory that fails to parse must yield fewer calls, not an
    exception, because a parse error in the harness would be scored as an agent failure.
    """
    calls: list[Call] = []
    p = pathlib.Path(path)
    if not p.exists():
        return calls
    with open(p, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            try:
                ev = json.loads(line)
            except json.JSONDecodeError:
                continue
            if not isinstance(ev, dict) or ev.get("type") != "tool_use":
                continue
            part = ev.get("part") or {}
            if part.get("type") != "tool":
                continue
            state = part.get("state") or {}
            calls.append(Call(
                tool=part.get("tool") or "",
                status=state.get("status") or "unknown",
                input=state.get("input") or {},
                output=state.get("output") if isinstance(state.get("output"), str) else "",
            ))
    return calls


# ------------------------------------------------------------------ text match

_WS = re.compile(r"\s+")


def _norm(text: str) -> str:
    return _WS.sub(" ", (text or "").lower()).strip()


def fact_present(answer: str, surface_form: str) -> bool:
    """Case- and whitespace-insensitive containment.

    Deliberately not word-boundary matching: answers contain IDs, names and code
    fragments, and a model that writes "Model Navigation and Search  System" with a
    stray space has not got the fact wrong. Over-eager matching is caught by
    `answer_forbids` and by the fact's own any_of, not by tightening this.
    """
    a, s = _norm(answer), _norm(surface_form)
    if not s:
        return False
    return s in a


# ------------------------------------------------------------------ evaluators

def _eval_trajectory_used(c, calls, answer):
    n = sum(1 for x in calls if x.tool == c.get("tool"))
    need = c.get("min_calls", 1)
    return (PASS if n >= need else FAIL,
            f"{c.get('tool')} called {n}x, needed >= {need}", {"calls": n})


def _eval_trajectory_avoided(c, calls, answer):
    n = sum(1 for x in calls if x.tool == c.get("tool"))
    cap = c.get("max_calls", 0)
    return (PASS if n <= cap else FAIL,
            f"{c.get('tool')} called {n}x, allowed <= {cap}", {"calls": n})


def _eval_trajectory_error_count(c, calls, answer):
    n = sum(1 for x in calls if x.tool == c.get("tool") and x.errored)
    need = c.get("min", 1)
    return (PASS if n >= need else FAIL,
            f"{c.get('tool')} errored {n}x, needed >= {need}", {"errors": n})


def _eval_answer_mentions_all(c, calls, answer):
    facts = c.get("facts") or []
    if answer is None:
        return UNKNOWN, "no final answer available", {}
    hit, miss = [], []
    for f in facts:
        forms = f.get("any_of") or []
        (hit if any(fact_present(answer, s) for s in forms) else miss).append(f.get("id"))
    need = c.get("min", len(facts))
    ok = len(hit) >= need
    return (PASS if ok else FAIL,
            f"{len(hit)}/{len(facts)} facts present, needed {need}",
            {"present": hit, "missing": miss, "min": need})


def _eval_answer_forbids(c, calls, answer):
    if answer is None:
        return UNKNOWN, "no final answer available", {}
    found = [s for s in (c.get("none_of") or []) if fact_present(answer, s)]
    return (PASS if not found else FAIL,
            "no forbidden string present" if not found
            else f"forbidden string(s) present: {found}", {"found": found})


def _eval_model_unchanged(c, calls, answer, probe=None, baseline=None):
    if probe is None or baseline is None:
        return UNKNOWN, "needs a model probe and a pre-run element count", {}
    now = probe.element_count()
    return (PASS if now == baseline else FAIL,
            f"element count {baseline} -> {now}", {"before": baseline, "after": now})


def _eval_element_exists(c, calls, answer, probe=None, **_):
    if probe is None:
        return UNKNOWN, "needs a model probe", {}
    rows = probe.find(name=c.get("name"))
    if c.get("safKind"):
        rows = [r for r in rows if r.get("safKind") == c["safKind"]]
    if c.get("type"):
        rows = [r for r in rows if r.get("type") == c["type"]]
    return (PASS if rows else FAIL,
            f"{len(rows)} element(s) named {c.get('name')!r}"
            + (f" with safKind={c['safKind']}" if c.get("safKind") else ""),
            {"names": [r.get("name") for r in rows][:5]})


def _eval_element_absent(c, calls, answer, probe=None, **_):
    if probe is None:
        return UNKNOWN, "needs a model probe", {}
    rows = probe.find(name=c.get("name"))
    return (PASS if not rows else FAIL,
            f"{len(rows)} element(s) named {c.get('name')!r}", {})


def _endpoints(probe, name_key, type_key, kind_key, c):
    """Resolve a relationship endpoint by name, optionally narrowed by SysML type or SAF kind.

    `type` is the SysML type the finder reports ("Activity", "Class", ...); the SAF
    concept kind is the separate `safKind` field. Putting a SAF kind such as
    "function" into a `type` filter matches nothing and silently turns a correct
    answer into a FAIL, so the two filters stay distinct here.
    """
    rows = probe.find(name=c.get(name_key))
    if c.get(type_key):
        rows = [r for r in rows if r.get("type") == c[type_key]]
    if c.get(kind_key):
        rows = [r for r in rows if r.get("safKind") == c[kind_key]]
    return rows


def _eval_relationship_exists(c, calls, answer, probe=None, **_):
    if probe is None:
        return UNKNOWN, "needs a model probe", {}
    src = _endpoints(probe, "from_name", "from_type", "from_safKind", c)
    dst = _endpoints(probe, "to_name", "to_type", "to_safKind", c)
    if not src or not dst:
        missing = [n for n, rows in ((c.get("from_name"), src), (c.get("to_name"), dst))
                   if not rows]
        return FAIL, f"endpoint(s) not found after filtering: {', '.join(missing)}", {}
    want = c.get("type")
    for a in src:
        for b in dst:
            for rel in probe.relationships(a["id"], b["id"]):
                if want is None or (rel.get("type") or "").lower() == want.lower():
                    return PASS, f"{want or 'any'} link {a['name']} -> {b['name']}", \
                           {"type": rel.get("type")}
    return FAIL, f"no {want or ''} relationship between the named elements", {}


def _eval_count(c, calls, answer, probe=None, **_):
    if probe is None:
        return UNKNOWN, "needs a model probe", {}
    rows = probe.find(**{k: c[k] for k in ("name", "of_type", "of_safKind", "under")
                         if k in c})
    n = len(rows)
    if c.get("min") is not None:
        return (PASS if n >= c["min"] else FAIL, f"count {n}, needed >= {c['min']}",
                {"count": n})
    return (PASS if n <= c["max"] else FAIL, f"count {n}, allowed <= {c['max']}",
            {"count": n})


TRAJECTORY_EVALUATORS = {
    "trajectory_used": _eval_trajectory_used,
    "trajectory_avoided": _eval_trajectory_avoided,
    "trajectory_error_count": _eval_trajectory_error_count,
    "answer_mentions_all": _eval_answer_mentions_all,
    "answer_forbids": _eval_answer_forbids,
    "model_unchanged": _eval_model_unchanged,
}
MODEL_EVALUATORS = {
    "element_exists": _eval_element_exists,
    "element_absent": _eval_element_absent,
    "relationship_exists": _eval_relationship_exists,
    "count_at_least": _eval_count,
    "count_at_most": _eval_count,
}


# ------------------------------------------------------------------ entry point

def evaluate(task, calls=None, answer=None, probe=None, baseline=None) -> TaskResult:
    calls = calls if calls is not None else []
    result = TaskResult(task.id)
    for c in task.clauses:
        if c.kind in TRAJECTORY_EVALUATORS:
            fn = TRAJECTORY_EVALUATORS[c.kind]
        elif c.kind in MODEL_EVALUATORS:
            fn = MODEL_EVALUATORS[c.kind]
        else:
            result.clauses.append(ClauseResult(
                c.label, c.kind, UNKNOWN, "no evaluator implements this clause kind"))
            continue
        try:
            # Dispatch on the clause *kind*, never on the resolved function: `fn in
            # MAP` tests a dict's keys, which silently sends every clause down the
            # wrong branch and turns a wrong answer into an UNKNOWN.
            if c.kind in MODEL_EVALUATORS:
                verdict, reason, detail = fn(c, calls, answer, probe=probe)
            elif c.kind == "model_unchanged":
                verdict, reason, detail = fn(c, calls, answer, probe, baseline)
            else:
                verdict, reason, detail = fn(c, calls, answer)
        except Exception as e:                       # never let a clause crash a run
            verdict, reason, detail = UNKNOWN, f"evaluator raised {type(e).__name__}: {e}", {}
        result.clauses.append(ClauseResult(c.label, c.kind, verdict, reason, detail))
    return result


def render(result: TaskResult) -> str:
    out = [f"{result.task_id}: {result.verdict}  "
           f"({result.passed}/{len(result.clauses)} clauses passed)"]
    for c in result.clauses:
        mark = {PASS: "ok  ", FAIL: "FAIL", UNKNOWN: "unk "}[c.verdict]
        out.append(f"  {mark} {c.kind}: {c.reason}")
    return "\n".join(out)


class McpProbe:
    """The model-backed tier's view of a live Cameo, over MCP.

    Kept separate from the clause evaluators on purpose: everything above this line scores
    an answer or a trajectory, and this is the only part that talks to a server. The interface
    is deliberately tiny -- ``find`` and ``element_count`` -- because a clause that cannot be
    expressed in those two calls has to justify going lower.

    The SAF concept kind is carried through as ``safKind`` and kept separate from the SysML
    ``type``. They are different vocabularies, and conflating them turns a correct answer into
    a silent FAIL (see ``_endpoints``).
    """

    def __init__(self, client, scope: str = "all"):
        self.c = client
        self.scope = scope

    # ------------------------------------------------------------- reads

    def find(self, name: str | None = None, type: str | None = None,
             stereotype: str | None = None, parentId: str | None = None) -> list[dict]:
        args: dict = {}
        if name:
            args["name"] = name
        if type:
            args["type"] = type
        if stereotype:
            args["stereotype"] = stereotype
        if parentId:
            args["parentId"] = parentId
        if self.scope and self.scope != "all":
            args["scope"] = self.scope
        return self.c.rows("saf_find_elements_by_type", args)

    def element_count(self) -> int:
        """A count that actually moves.

        get_model_info's modelRoots[].elementCount reads 0 for a model with thousands of
        elements, so taking it at face value makes the model_unchanged clause compare 0 to 0
        and pass no matter what the run did. Fall back to admin_get_model_status, and refuse
        to report a count of 0 as if it were real.
        """
        info = self.c.call("get_model_info", {})
        roots = info.get("modelRoots") or []
        primary = next((r for r in roots if r.get("primary")), None)
        if primary is not None and int(primary.get("elementCount") or 0) > 0:
            return int(primary["elementCount"])
        st = self.c.call("admin_get_model_status", {})
        n = int(st.get("elementCount") or 0)
        if n <= 0:
            raise RuntimeError(
                "no usable element count from get_model_info or admin_get_model_status; "
                "reporting 0 would make every model_unchanged clause pass vacuously")
        return n

    def get(self, element_id: str) -> dict:
        return self.c.call("get_element_info", {"qualifiedName": element_id})

    def close(self) -> None:
        self.c.close()

    def __enter__(self) -> "McpProbe":
        return self

    def __exit__(self, *exc) -> None:
        self.close()
