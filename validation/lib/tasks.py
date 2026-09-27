"""Load and validate the task dataset.

A dataset is only worth what its weakest task is worth. The failure mode that actually
bites is not "the task is hard", it is "the task is broken in a way that only shows up
three hours into a model run": an oracle clause naming a tool that was renamed, a
`readOnly: true` task whose oracle silently requires a write, a clause kind no evaluator
implements so the answer quietly passes.

So the loader is strict, and the linter (`bin/vtasks`) runs every check that can be
checked without a model. A dataset that lints is not necessarily good, but a dataset
that does not lint is definitely wasting someone's compute.
"""

from __future__ import annotations

import json
import pathlib
import re

TASKS_DIR = pathlib.Path(__file__).resolve().parents[1] / "tasks"

# Every clause kind, who evaluates it, and what the evaluator needs. `needs` is the set
# of artefacts that must exist for the clause to be decidable at all; a clause whose
# inputs are missing yields UNKNOWN, never PASS.
CLAUSE_KINDS: dict[str, dict] = {
    "element_exists":       {"needs": ("model",), "write": False,
                            "doc": "an element matching the name (and optional kind/type) exists"},
    "element_absent":       {"needs": ("model",), "write": False,
                            "doc": "no element matches the name"},
    "relationship_exists":  {"needs": ("model",), "write": False,
                            "doc": "a relationship of the given type links two named elements"},
    "count_at_least":       {"needs": ("model",), "write": False,
                            "doc": "at least N elements match the selector"},
    "count_at_most":        {"needs": ("model",), "write": False,
                            "doc": "at most N elements match the selector"},
    "model_unchanged":      {"needs": ("baseline",), "write": False,
                            "doc": "element count is identical to the pre-run baseline"},
    "trajectory_used":      {"needs": ("trajectory",), "write": False,
                            "doc": "the trajectory contains at least N calls of a tool"},
    "trajectory_avoided":   {"needs": ("trajectory",), "write": False,
                            "doc": "the trajectory contains at most N calls of a tool"},
    "trajectory_error_count": {"needs": ("trajectory",), "write": False,
                               "doc": "the trajectory contains at least N errored calls of a tool"},
    "answer_mentions_all":  {"needs": ("answer",), "write": False,
                             "doc": "the final answer contains at least `min` of the given facts"},
    "answer_forbids":       {"needs": ("answer",), "write": False,
                             "doc": "the final answer contains none of the forbidden strings"},
}

# Keys each clause kind understands. A key outside this set is ignored by the
# evaluator, so a typo silently turns a scored assertion into a weaker one.
CLAUSE_KEYS: dict[str, tuple[str, ...]] = {
    "element_exists":       ("kind", "name", "safKind", "type"),
    "element_absent":       ("kind", "name"),
    "relationship_exists":  ("kind", "from_name", "from_type", "from_safKind",
                             "to_name", "to_type", "to_safKind", "type"),
    "count_at_least":       ("kind", "name", "of_type", "of_safKind", "under", "min"),
    "count_at_most":        ("kind", "name", "of_type", "of_safKind", "under", "max"),
    "model_unchanged":      ("kind",),
    "trajectory_used":      ("kind", "tool", "min_calls"),
    "trajectory_avoided":   ("kind", "tool", "max_calls"),
    "trajectory_error_count": ("kind", "tool", "min"),
    "answer_mentions_all":  ("kind", "facts", "min"),
    "answer_forbids":       ("kind", "none_of"),
}

# A clause with write=True is only meaningful on a task that is allowed to write.
WRITE_CLAUSES = {"element_exists", "relationship_exists"}

REQUIRED_FIELDS = ("id", "title", "task", "readOnly", "expected_first_tool",
                   "call_budget", "repetitions", "oracle")


class TaskError(Exception):
    pass


class Clause:
    __slots__ = ("kind", "raw", "index", "task_id")

    def __init__(self, kind: str, raw: dict, index: int, task_id: str):
        self.kind, self.raw, self.index, self.task_id = kind, raw, index, task_id

    def get(self, key, default=None):
        return self.raw.get(key, default)

    def __getitem__(self, key):
        # Clause evaluators write `c["name"]` / `k in c`; without this every such
        # lookup raises TypeError and the clause degrades to UNKNOWN, which reads as
        # "harness could not tell" instead of "the agent got it wrong".
        return self.raw[key]

    def __contains__(self, key):
        return key in self.raw

    @property
    def label(self) -> str:
        return f"{self.task_id}#{self.index}:{self.kind}"

    def __repr__(self):
        return f"<Clause {self.label}>"


class Task:
    __slots__ = ("path", "raw", "clauses")

    def __init__(self, path: pathlib.Path, raw: dict):
        self.path, self.raw = path, raw
        self.clauses = [Clause(c["kind"], c, i, raw["id"])
                        for i, c in enumerate(raw.get("oracle", []))]

    def __getattr__(self, item):
        try:
            return self.raw[item]
        except KeyError as e:
            raise AttributeError(item) from e

    @property
    def id(self):
        return self.raw["id"]

    @property
    def is_write(self):
        return any(c.kind in WRITE_CLAUSES for c in self.clauses)

    @property
    def expected_tools(self) -> set[str]:
        return {c.get("tool") for c in self.clauses if c.get("tool")}

    def facts(self) -> list[dict]:
        out = []
        for c in self.clauses:
            if c.kind == "answer_mentions_all":
                out += c.get("facts", [])
        return out

    def __repr__(self):
        return f"<Task {self.id} ({len(self.clauses)} clauses)>"


# ------------------------------------------------------------------ loading

def load(path: pathlib.Path) -> Task:
    with open(path, "r", encoding="utf-8") as fh:
        raw = json.load(fh)
    return Task(path, raw)


def load_all(tasks_dir: pathlib.Path | None = None) -> list[Task]:
    d = tasks_dir or TASKS_DIR
    return [load(p) for p in sorted(d.glob("*.json"))]


# ------------------------------------------------------------------ linting

def lint(task: Task, surface_tools: set[str] | None = None) -> list[str]:
    """Every check that does not need a live model. Returns a list of problems."""
    problems: list[str] = []
    raw = task.raw

    for field in REQUIRED_FIELDS:
        if field not in raw:
            problems.append(f"missing required field {field!r}")
    if "id" not in raw:
        return problems

    if not isinstance(raw.get("readOnly"), bool):
        problems.append("readOnly must be a boolean, not a truthy value")
    for numeric in ("call_budget", "repetitions"):
        v = raw.get(numeric)
        if not isinstance(v, int) or v < 1:
            problems.append(f"{numeric} must be a positive integer, got {v!r}")

    if not raw.get("oracle"):
        problems.append("no oracle clauses: an unscored task is not a measurement")
    if not raw.get("rationale"):
        problems.append("no rationale: nobody will remember why this task exists")

    for c in task.clauses:
        spec = CLAUSE_KINDS.get(c.kind)
        if spec is None:
            problems.append(f"clause {c.index}: unknown kind {c.kind!r}; "
                            f"known kinds: {', '.join(sorted(CLAUSE_KINDS))}")
            continue
        problems += _lint_clause(task, c, spec)

    if raw.get("readOnly") is True:
        for c in task.clauses:
            if c.kind in WRITE_CLAUSES:
                problems.append(
                    f"clause {c.index} ({c.kind}) asserts model state that a read-only "
                    f"task cannot create; either the task writes or the clause is wrong")
        if not any(c.kind in ("model_unchanged",) for c in task.clauses):
            problems.append("read-only task has no model_unchanged clause, so nothing "
                            "stops a runaway agent from quietly editing the model")

    if surface_tools is not None:
        for tool in sorted({task.raw.get("expected_first_tool", "")} | task.expected_tools):
            if tool and tool not in surface_tools:
                problems.append(f"references tool {tool!r}, which is not in the surface "
                                f"manifest -- rename the tool or the clause is unscoreable")

    if "needsCalibration" in raw and not raw["needsCalibration"]:
        problems.append("needsCalibration present but empty; drop the key instead")
    return problems


def _lint_clause(task: Task, c: Clause, spec: dict) -> list[str]:
    p: list[str] = []
    kind = c.kind

    def need(field):
        if field not in c.raw:
            p.append(f"clause {c.index} ({kind}): missing {field!r}")

    allowed = set(CLAUSE_KEYS.get(kind, ())) | {"kind", "note"}
    for key in c.raw:
        if key not in allowed:
            p.append(f"clause {c.index} ({kind}): key {key!r} is not understood by "
                     f"this clause kind and would be silently ignored; "
                     f"known keys: {', '.join(sorted(allowed - {'kind'}))}")

    if kind in ("trajectory_used", "trajectory_avoided", "trajectory_error_count"):
        need("tool")
        # error_count is a count assertion, so it takes `min` like count_at_least; the
        # other two read a call threshold and take an explicit *_calls bound.
        bound = {"trajectory_used": "min_calls",
                 "trajectory_avoided": "max_calls",
                 "trajectory_error_count": "min"}[kind]
        need(bound)
        v = c.get(bound)
        if not isinstance(v, int) or v < 0:
            p.append(f"clause {c.index} ({kind}): {bound} must be a non-negative integer")
    elif kind in ("count_at_least", "count_at_most"):
        bound = "min" if kind == "count_at_least" else "max"
        need(bound)
        v = c.get(bound)
        if not isinstance(v, int) or v < 0:
            p.append(f"clause {c.index} ({kind}): {bound} must be a non-negative integer")
        if not any(k in c.raw for k in ("name", "of_type", "of_safKind", "under")):
            p.append(f"clause {c.index} ({kind}): no selector, so it counts the "
                    f"whole model and cannot fail")
    elif kind in ("element_exists", "element_absent"):
        need("name")
    elif kind == "relationship_exists":
        need("from_name")
        need("to_name")
    elif kind == "answer_mentions_all":
        need("facts")
        facts = c.get("facts") or []
        if not facts:
            p.append(f"clause {c.index} ({kind}): facts is empty, so it can never fail")
        for i, f in enumerate(facts):
            if not isinstance(f, dict) or "any_of" not in f:
                p.append(f"clause {c.index} fact {i}: needs an 'any_of' list of "
                         f"accepted surface forms")
            elif not f["any_of"]:
                p.append(f"clause {c.index} fact {i} ({f.get('id')}): empty any_of")
            elif not f.get("id"):
                p.append(f"clause {c.index} fact {i}: has any_of but no id, so a failure "
                         f"cannot be reported usefully")
        mn = c.get("min", len(facts))
        if not isinstance(mn, int) or mn < 1:
            p.append(f"clause {c.index} ({kind}): min must be >= 1, got {mn!r}")
        elif mn > len(facts):
            p.append(f"clause {c.index} ({kind}): min={mn} exceeds the {len(facts)} "
                     f"facts given, so the clause is unpassable")
    elif kind == "answer_forbids":
        need("none_of")
        if not c.get("none_of"):
            p.append(f"clause {c.index} ({kind}): empty none_of")
    return p


def lint_all(tasks: list[Task], surface_tools: set[str] | None = None) -> dict:
    """Dataset-wide checks in addition to the per-task ones."""
    problems: dict[str, list[str]] = {}
    for t in tasks:
        probs = lint(t, surface_tools)
        if probs:
            problems[t.id] = probs

    ids = [t.id for t in tasks]
    for i, a in enumerate(ids):
        for b in ids[i + 1:]:
            if a == b:
                problems.setdefault(a, []).append(f"duplicate task id {a!r}")

    seen_facts: dict[str, str] = {}
    for t in tasks:
        for f in t.facts():
            fid = f.get("id")
            if fid in seen_facts and seen_facts[fid] != t.id:
                problems.setdefault(t.id, []).append(
                    f"fact id {fid!r} is also used by {seen_facts[fid]}; fact ids should "
                    f"be unique so a report can name the failing fact")
            seen_facts[fid] = t.id

    n_write = sum(1 for t in tasks if t.is_write)
    if tasks and n_write == 0:
        problems.setdefault("<dataset>", []).append(
            "no mutating task: the suite would never exercise create/relate, and the "
            "reset-between-repetitions path would be untested")
    return problems


def summary(tasks: list[Task]) -> dict:
    return {
        "tasks": len(tasks),
        "read_only": sum(1 for t in tasks if not t.is_write),
        "mutating": sum(1 for t in tasks if t.is_write),
        "clauses": sum(len(t.clauses) for t in tasks),
        "clause_kinds": sorted({c.kind for t in tasks for c in t.clauses}),
        "uncalibrated": [t.id for t in tasks if t.raw.get("needsCalibration")],
        "total_call_budget": sum(t.raw.get("call_budget", 0) for t in tasks),
    }
