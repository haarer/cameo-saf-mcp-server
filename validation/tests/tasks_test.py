"""Self-tests for the task dataset and the oracle evaluators.

Same premise as harness_test.py: a scoring harness that cannot fail is not a harness.
So these tests deliberately feed it broken datasets and broken trajectories and assert
that it says so.

The model-backed clause evaluators are tested through a fake probe rather than a real
Cameo, which is the reason `oracle.evaluate` takes an injected probe at all.
"""

from __future__ import annotations

import copy
import json
import pathlib
import sys
import tempfile
from contextlib import contextmanager
import traceback

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "lib"))

import oracle            # noqa: E402
import tasks as T        # noqa: E402

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


def task_from(raw: dict) -> T.Task:
    with tempfile.TemporaryDirectory() as d:
        p = pathlib.Path(d) / "t.json"
        p.write_text(json.dumps(raw))
        return T.load(p)


BASE_TASK = {
    "id": "TX-test", "title": "t", "task": "do a thing", "readOnly": True,
    "expected_first_tool": "find_elements", "call_budget": 5, "repetitions": 1,
    "rationale": "r",
    "oracle": [{"kind": "model_unchanged"}],
}


# ------------------------------------------------------------------ dataset

@check("dataset: the committed tasks lint clean against surface-v1.json")
def _():
    manifest = json.load(open(ROOT / "surfaces" / "surface-v1.json"))
    tools = {t["name"] for t in manifest["tools"]}
    problems = T.lint_all(T.load_all(), tools)
    assert not problems, f"{json.dumps(problems, indent=2)}"


@check("dataset: every clause kind used has an evaluator")
def _():
    evaluable = set(oracle.TRAJECTORY_EVALUATORS) | set(oracle.MODEL_EVALUATORS)
    used = {c.kind for t in T.load_all() for c in t.clauses}
    assert used <= evaluable, f"unevaluable: {sorted(used - evaluable)}"


@check("dataset: mix of read-only and mutating tasks")
def _():
    ds = T.load_all()
    assert any(t.is_write for t in ds), "no mutating task"
    assert any(not t.is_write for t in ds), "no read-only task"


@check("dataset: read-only tasks all assert the model was left alone")
def _():
    for t in T.load_all():
        if not t.is_write:
            assert any(c.kind == "model_unchanged" for c in t.clauses), \
                f"{t.id} is read-only but has no model_unchanged clause"


# ------------------------------------------------------------------ linter

@check("linter: catches an unknown clause kind")
def _():
    raw = copy.deepcopy(BASE_TASK)
    raw["oracle"] = [{"kind": "vibes_are_good"}]
    probs = T.lint(task_from(raw))
    assert any("unknown kind" in p for p in probs), probs


@check("linter: catches a missing required field")
def _():
    raw = copy.deepcopy(BASE_TASK)
    del raw["expected_first_tool"]
    probs = T.lint(task_from(raw))
    assert any("expected_first_tool" in p for p in probs), probs


@check("linter: catches an unpassable answer_mentions_all (min > facts)")
def _():
    raw = copy.deepcopy(BASE_TASK)
    raw["oracle"] = [{"kind": "answer_mentions_all", "min": 3,
                      "facts": [{"id": "a", "any_of": ["x"]}]}]
    probs = T.lint(task_from(raw))
    assert any("unpassable" in p for p in probs), probs


@check("linter: catches a read-only task whose oracle demands a write")
def _():
    raw = copy.deepcopy(BASE_TASK)
    raw["oracle"] = [{"kind": "element_exists", "name": "New Thing"},
                     {"kind": "model_unchanged"}]
    probs = T.lint(task_from(raw))
    assert any("read-only task cannot create" in p for p in probs), probs


@check("linter: catches a read-only task with no model_unchanged clause")
def _():
    raw = copy.deepcopy(BASE_TASK)
    raw["oracle"] = [{"kind": "answer_mentions_all", "min": 1,
                      "facts": [{"id": "a", "any_of": ["x"]}]}]
    probs = T.lint(task_from(raw))
    assert any("model_unchanged" in p for p in probs), probs


@check("linter: catches a fact with empty any_of")
def _():
    raw = copy.deepcopy(BASE_TASK)
    raw["oracle"] = [{"kind": "answer_mentions_all", "min": 1,
                      "facts": [{"id": "a", "any_of": []}]}]
    probs = T.lint(task_from(raw))
    assert any("empty any_of" in p for p in probs), probs


@check("linter: catches a fact with no id (failures would be unnameable)")
def _():
    raw = copy.deepcopy(BASE_TASK)
    raw["oracle"] = [{"kind": "answer_mentions_all", "min": 1,
                      "facts": [{"any_of": ["x"]}]}]
    probs = T.lint(task_from(raw))
    assert any("no id" in p for p in probs), probs


@check("linter: catches a count clause with no selector (it would count the whole model)")
def _():
    raw = copy.deepcopy(BASE_TASK)
    raw["readOnly"] = False
    raw["oracle"] = [{"kind": "count_at_least", "min": 1}, {"kind": "model_unchanged"}]
    probs = T.lint(task_from(raw))
    assert any("no selector" in p for p in probs), probs


@check("linter: catches a clause key the evaluator would silently ignore")
def _():
    raw = copy.deepcopy(BASE_TASK)
    raw["readOnly"] = False
    raw["oracle"] = [{"kind": "element_exists", "name": "x", "sagKind": "typo"},
                     {"kind": "model_unchanged"}]
    probs = T.lint(task_from(raw))
    assert any("sagKind" in p and "silently ignored" in p for p in probs), probs


@check("linter: a clause may carry a human-readable note")
def _():
    raw = copy.deepcopy(BASE_TASK)
    raw["readOnly"] = False
    raw["oracle"] = [{"kind": "element_exists", "name": "x", "note": "why this matters"},
                     {"kind": "model_unchanged"}]
    assert T.lint(task_from(raw)) == [], T.lint(task_from(raw))


@check("linter: catches a relationship clause missing an endpoint")
def _():
    raw = copy.deepcopy(BASE_TASK)
    raw["readOnly"] = False
    raw["oracle"] = [{"kind": "relationship_exists", "from_name": "a"},
                     {"kind": "model_unchanged"}]
    probs = T.lint(task_from(raw))
    assert any("to_name" in p for p in probs), probs


@check("linter: catches a task with no oracle at all")
def _():
    raw = copy.deepcopy(BASE_TASK)
    raw["oracle"] = []
    probs = T.lint(task_from(raw))
    assert any("unscored task" in p for p in probs), probs


@check("linter: catches a task with no rationale")
def _():
    raw = copy.deepcopy(BASE_TASK)
    del raw["rationale"]
    probs = T.lint(task_from(raw))
    assert any("rationale" in p for p in probs), probs


@check("linter: catches a tool that is not in the surface manifest")
def _():
    probs = T.lint(task_from(BASE_TASK), surface_tools={"echo"})
    assert any("not in the surface" in p for p in probs), probs


@check("linter: catches a fact id reused across two tasks")
def _():
    a, b = copy.deepcopy(BASE_TASK), copy.deepcopy(BASE_TASK)
    a["id"], b["id"] = "TA", "TB"
    clause = {"kind": "answer_mentions_all", "min": 1, "facts": [{"id": "shared", "any_of": ["x"]}]}
    a["oracle"] = [clause, {"kind": "model_unchanged"}]
    b["oracle"] = [copy.deepcopy(clause), {"kind": "model_unchanged"}]
    problems = T.lint_all([task_from(a), task_from(b)])
    assert "TB" in problems and any("shared" in p for p in problems["TB"]), problems


@check("linter: catches a dataset with no mutating task")
def _():
    t = task_from(BASE_TASK)
    problems = T.lint_all([t])
    assert "<dataset>" in problems and any("no mutating task" in p
                                          for p in problems["<dataset>"]), problems


# ------------------------------------------------------------------ fake probe

class FakeProbe:
    def __init__(self, elements, rels=None, count=None):
        self.elements = elements
        self.rels = rels or {}
        self._count = count

    def find(self, name=None, of_type=None, of_safKind=None, under=None, **kw):
        rows = self.elements
        if name is not None:
            rows = [r for r in rows if r.get("name") == name]
        if of_type is not None:
            rows = [r for r in rows if r.get("type") == of_type]
        if of_safKind is not None:
            rows = [r for r in rows if r.get("safKind") == of_safKind]
        if under is not None:
            rows = [r for r in rows if r.get("parent") == under]
        return list(rows)

    def relationships(self, a_id, b_id):
        return self.rels.get((a_id, b_id), [])

    def element_count(self):
        return self._count if self._count is not None else len(self.elements)


# ------------------------------------------------------------------ trajectory

@contextmanager
def traj_file(events):
    """A trajectory file that outlives the block that writes it.

    Returning the path from inside a TemporaryDirectory deletes the file before it can
    be read, which is exactly the kind of harness bug that reads as "0 tool calls"
    and gets misread as an agent failure.
    """
    with tempfile.TemporaryDirectory() as d:
        p = pathlib.Path(d) / "trajectory.jsonl"
        with open(p, "w", encoding="utf-8") as fh:
            for e in events:
                fh.write(json.dumps(e) + "\n")
        yield p


def use(tool, status="completed", inp=None):
    return {"type": "tool_use", "timestamp": 1,
            "part": {"type": "tool", "tool": tool, "callID": "c1",
                     "state": {"status": status, "input": inp or {}, "output": "x"}}}


@check("oracle: reads tool_use events and their error status")
def _():
    with traj_file([use("echo"), use("find_elements", "error"), use("echo")]) as p:
        calls = oracle.read_trajectory(p)
        assert len(calls) == 3, f"got {len(calls)}"
        assert sum(1 for c in calls if c.errored) == 1
        assert calls[0].tool == "echo" and calls[0].status == "completed"


@check("oracle: a corrupt trajectory line yields fewer calls, not an exception")
def _():
    with tempfile.TemporaryDirectory() as d:
        p = pathlib.Path(d) / "t.jsonl"
        p.write_text(json.dumps(use("echo")) + "\nnot json at all\n{\"type\":\"other\"}\n")
        calls = oracle.read_trajectory(p)
        assert len(calls) == 1, f"got {len(calls)}"


@check("oracle: a missing trajectory yields no calls and UNKNOWN, never PASS")
def _():
    raw = copy.deepcopy(BASE_TASK)
    raw["oracle"] = [{"kind": "trajectory_used", "tool": "echo", "min_calls": 1}]
    r = oracle.evaluate(task_from(raw), calls=[], answer=None)
    assert r.verdict == oracle.FAIL, r.verdict


@check("oracle: trajectory_used passes on a matching call and fails on none")
def _():
    raw = copy.deepcopy(BASE_TASK)
    raw["oracle"] = [{"kind": "trajectory_used", "tool": "echo", "min_calls": 1},
                     {"kind": "model_unchanged"}]
    t = task_from(raw)
    assert oracle.evaluate(t, calls=[oracle.Call("echo", "completed", {}, "")],
                           probe=FakeProbe([]), baseline=0).verdict == oracle.PASS
    assert oracle.evaluate(t, calls=[oracle.Call("diff", "completed", {}, "")],
                           probe=FakeProbe([]), baseline=0).verdict == oracle.FAIL


@check("oracle: trajectory_avoided fails when the forbidden tool was used")
def _():
    raw = copy.deepcopy(BASE_TASK)
    raw["oracle"] = [{"kind": "trajectory_avoided", "tool": "find_elements_by_type",
                      "max_calls": 0}, {"kind": "model_unchanged"}]
    t = task_from(raw)
    bad = [oracle.Call("find_elements_by_type", "completed", {}, "")]
    assert oracle.evaluate(t, calls=bad, probe=FakeProbe([]),
                           baseline=0).verdict == oracle.FAIL
    assert oracle.evaluate(t, calls=[], probe=FakeProbe([]),
                           baseline=0).verdict == oracle.PASS


@check("oracle: trajectory_error_count counts only errored calls of that tool")
def _():
    raw = copy.deepcopy(BASE_TASK)
    raw["oracle"] = [{"kind": "trajectory_error_count", "tool": "echo", "min": 1},
                     {"kind": "model_unchanged"}]
    t = task_from(raw)
    good = [oracle.Call("echo", "error", {}, ""), oracle.Call("echo", "completed", {}, "")]
    bad = [oracle.Call("echo", "completed", {}, ""),
           oracle.Call("diff", "error", {}, "")]
    assert oracle.evaluate(t, calls=good, probe=FakeProbe([]),
                           baseline=0).verdict == oracle.PASS
    assert oracle.evaluate(t, calls=bad, probe=FakeProbe([]),
                           baseline=0).verdict == oracle.FAIL


# ------------------------------------------------------------------ answer

@check("oracle: answer_mentions_all honours min, not all-or-nothing")
def _():
    raw = copy.deepcopy(BASE_TASK)
    facts = [{"id": "a", "any_of": ["alpha"]}, {"id": "b", "any_of": ["beta"]},
             {"id": "c", "any_of": ["gamma"]}]
    raw["oracle"] = [{"kind": "answer_mentions_all", "min": 2, "facts": facts},
                     {"kind": "model_unchanged"}]
    t = task_from(raw)
    r = oracle.evaluate(t, answer="I found alpha and beta", probe=FakeProbe([]), baseline=0)
    assert r.verdict == oracle.PASS, r.verdict
    clause = r.clauses[0]
    assert clause.detail["missing"] == ["c"], clause.detail
    r2 = oracle.evaluate(t, answer="only alpha", probe=FakeProbe([]), baseline=0)
    assert r2.verdict == oracle.FAIL, r2.verdict


@check("oracle: fact matching is case- and whitespace-insensitive")
def _():
    raw = copy.deepcopy(BASE_TASK)
    raw["oracle"] = [{"kind": "answer_mentions_all", "min": 1,
                      "facts": [{"id": "n", "any_of": ["Model Navigation and Search System"]}]},
                     {"kind": "model_unchanged"}]
    t = task_from(raw)
    for answer in ("it is model navigation and search system",
                   "MODEL NAVIGATION AND   SEARCH SYSTEM"):
        r = oracle.evaluate(t, answer=answer, probe=FakeProbe([]), baseline=0)
        assert r.verdict == oracle.PASS, f"{answer!r} -> {r.verdict}"


@check("oracle: a missing answer is UNKNOWN, never a silent PASS")
def _():
    raw = copy.deepcopy(BASE_TASK)
    raw["oracle"] = [{"kind": "answer_mentions_all", "min": 1,
                      "facts": [{"id": "a", "any_of": ["alpha"]}]},
                     {"kind": "model_unchanged"}]
    r = oracle.evaluate(task_from(raw), answer=None, probe=FakeProbe([]), baseline=0)
    verdicts = {c.kind: c.verdict for c in r.clauses}
    assert verdicts["answer_mentions_all"] == oracle.UNKNOWN, verdicts
    assert verdicts["model_unchanged"] == oracle.PASS, verdicts
    assert r.verdict == oracle.UNKNOWN, r.verdict
    assert r.unknown, "expected an UNKNOWN clause, got none"


@check("oracle: answer_forbids fails on a forbidden string")
def _():
    raw = copy.deepcopy(BASE_TASK)
    raw["oracle"] = [{"kind": "answer_forbids", "none_of": ["4 blocks"]},
                     {"kind": "model_unchanged"}]
    t = task_from(raw)
    assert oracle.evaluate(t, answer="there are 19 blocks", probe=FakeProbe([]),
                           baseline=0).verdict == oracle.PASS
    assert oracle.evaluate(t, answer="there are 4 blocks", probe=FakeProbe([]),
                           baseline=0).verdict == oracle.FAIL


# ------------------------------------------------------------------ model state

@check("oracle: model clauses are UNKNOWN without a probe, never PASS")
def _():
    raw = copy.deepcopy(BASE_TASK)
    raw["readOnly"] = False
    raw["oracle"] = [{"kind": "element_exists", "name": "New Thing"},
                     {"kind": "model_unchanged"}]
    r = oracle.evaluate(task_from(raw), calls=[], answer="done", probe=None)
    assert r.verdict == oracle.UNKNOWN, r.verdict
    assert len(r.unknown) == 2, r.unknown


@check("oracle: element_exists checks the safKind as well as the name")
def _():
    raw = copy.deepcopy(BASE_TASK)
    raw["readOnly"] = False
    raw["oracle"] = [{"kind": "element_exists", "name": "logging_probe",
                      "safKind": "physical_software"}]
    t = task_from(raw)
    wrong = FakeProbe([{"id": "1", "name": "logging_probe", "type": "Block",
                        "safKind": "conceptual_system"}])
    right = FakeProbe([{"id": "1", "name": "logging_probe", "type": "Block",
                        "safKind": "physical_software"}])
    assert oracle.evaluate(t, probe=wrong).verdict == oracle.FAIL
    assert oracle.evaluate(t, probe=right).verdict == oracle.PASS


@check("oracle: relationship_exists resolves endpoints by name, not by ID")
def _():
    raw = copy.deepcopy(BASE_TASK)
    raw["readOnly"] = False
    raw["oracle"] = [{"kind": "relationship_exists", "from_name": "diagnostics",
                      "from_safKind": "conceptual_function", "to_name": "logging_probe",
                      "type": "Refine"}]
    t = task_from(raw)
    els = [{"id": "f1", "name": "diagnostics", "type": "Activity",
            "safKind": "conceptual_function"},
           {"id": "b1", "name": "logging_probe", "type": "Block",
            "safKind": "physical_software"}]
    no = FakeProbe(els, {("f1", "b1"): [{"type": "Satisfy"}]})
    yes = FakeProbe(els, {("f1", "b1"): [{"type": "Refine"}]})
    assert oracle.evaluate(t, probe=no).verdict == oracle.FAIL
    assert oracle.evaluate(t, probe=yes).verdict == oracle.PASS


@check("oracle: a SAF kind in a SysML `type` filter matches nothing (documented trap)")
def _():
    raw = copy.deepcopy(BASE_TASK)
    raw["readOnly"] = False
    raw["oracle"] = [{"kind": "relationship_exists", "from_name": "diagnostics",
                      "from_type": "function", "to_name": "logging_probe",
                      "type": "Refine"}]
    els = [{"id": "f1", "name": "diagnostics", "type": "Activity",
            "safKind": "conceptual_function"},
           {"id": "b1", "name": "logging_probe", "type": "Block"}]
    r = oracle.evaluate(task_from(raw), probe=FakeProbe(els, {("f1", "b1"): [{"type": "Refine"}]}))
    assert r.verdict == oracle.FAIL
    assert "not found after filtering" in r.clauses[0].reason, r.clauses[0].reason


@check("oracle: every shipped clause passes the linter and has an evaluator")
def _():
    used = {c.kind for t in T.load_all() for c in t.clauses}
    assert used, "no clauses in the shipped dataset"
    for kind in used:
        assert kind in oracle.TRAJECTORY_EVALUATORS or kind in oracle.MODEL_EVALUATORS, kind


@check("oracle: count_at_most catches a duplicate created by a retry")
def _():
    raw = copy.deepcopy(BASE_TASK)
    raw["readOnly"] = False
    raw["oracle"] = [{"kind": "count_at_most", "name": "logging_probe", "max": 1}]
    t = task_from(raw)
    one = FakeProbe([{"id": "1", "name": "logging_probe"}])
    two = FakeProbe([{"id": "1", "name": "logging_probe"},
                     {"id": "2", "name": "logging_probe"}])
    assert oracle.evaluate(t, probe=one).verdict == oracle.PASS
    assert oracle.evaluate(t, probe=two).verdict == oracle.FAIL


@check("oracle: model_unchanged fails when the element count moved")
def _():
    raw = copy.deepcopy(BASE_TASK)
    t = task_from(raw)
    assert oracle.evaluate(t, probe=FakeProbe([], count=10),
                           baseline=10).verdict == oracle.PASS
    assert oracle.evaluate(t, probe=FakeProbe([], count=11),
                           baseline=10).verdict == oracle.FAIL


@check("oracle: a clause whose evaluator raises is UNKNOWN, never a crash")
def _():
    class Exploding:
        def find(self, **kw):
            raise RuntimeError("Cameo went away mid-check")

    raw = copy.deepcopy(BASE_TASK)
    raw["readOnly"] = False
    raw["oracle"] = [{"kind": "element_exists", "name": "x"}]
    r = oracle.evaluate(task_from(raw), probe=Exploding())
    assert r.verdict == oracle.UNKNOWN, r.verdict


@check("oracle: verdict is FAIL only if a clause failed, UNKNOWN otherwise")
def _():
    raw = copy.deepcopy(BASE_TASK)
    raw["readOnly"] = False
    raw["oracle"] = [{"kind": "element_exists", "name": "x"},
                     {"kind": "trajectory_used", "tool": "echo", "min_calls": 1}]
    t = task_from(raw)
    calls = [oracle.Call("echo", "completed", {}, "")]
    partial = oracle.evaluate(t, calls=calls, probe=None)          # model unknown
    assert partial.verdict == oracle.UNKNOWN, partial.verdict
    assert not partial.failed, partial.failed
    full = oracle.evaluate(t, calls=calls, probe=FakeProbe([{"id": "1", "name": "x"}]))
    assert full.verdict == oracle.PASS, full.verdict


# ------------------------------------------------------------------ main

def main() -> int:
    width = max(len(n) for _, n, _ in RESULTS)
    for status, name, detail in RESULTS:
        mark = {"pass": "ok  ", "fail": "FAIL", "error": "ERR "}[status]
        print(f"  {mark} {name.ljust(width)}")
        if detail and status != "pass":
            for line in detail.splitlines():
                print(f"        {line}")
    failed = [r for r in RESULTS if r[0] != "pass"]
    print(f"\n{len(RESULTS) - len(failed)}/{len(RESULTS)} dataset/oracle self-tests passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
