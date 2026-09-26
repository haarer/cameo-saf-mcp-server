# Validation Test Suite Plan

Consolidated 2026-09-26. Supersedes the earlier draft of this file.

## 1. Purpose

A reproducible test suite that measures **how well an LLM agent drives the SAF MCP
tool surface on real SysML/SAF engineering tasks in Cameo**, so that changes to the
surface can be judged as improvements or regressions instead of by feel.

The unit of optimisation is **not** the tool count and **not** the token count. It is
the pair

```
Task Success Rate  (primary)          <- 02-optimierungsziele §1
Cognitive Surface Complexity (cause)  <- 02-optimierungsziele, heuristic only
```

Everything else in this suite exists to explain a movement in the primary metric.

Central hypothesis (03-teststrategie, closing section):

> A clearer MCP surface can reduce the required model size, the number of tool calls
> and the token consumption without degrading success on real tasks.

## 2. Sources of truth

| Source | Role | Where it lands in this plan |
|---|---|---|
| `01-designregeln-dos-donts.md` | Design rules the surface must satisfy; the *levers* a change may pull | §5 surface variants, §8 Layer 0 |
| `02-optimierungsziele.md` | Prioritised metric set; explicit non-goals | §6 metrics, §7 experiment protocol |
| `03-teststrategie.md` | Task dataset, A/B split, ablations, error taxonomy, deterministic evaluation, resource measurement, regression | §4, §7, §9, §10, §11 |
| `scripts/admin_bridge.groovy` | Model lifecycle + tool-filter levers (already implemented) | §5 |
| `src/.../McpSession.java`, `StreamableMcpTransportProvider.java` | The filter mechanism and the unfiltered admin HTTP escape hatch | §5 |
| `./deploy-scripts.sh` | How a surface edit reaches the running Cameo | §5, §10 |

The three German documents are the methodology. This file is the implementation plan
for them. Where the earlier draft of this plan disagreed with them, they win.

### What changed versus the earlier draft

1. **The suite is self-contained.** Everything it needs lives in `validation/`. It
   shells out to `opencode` and talks HTTP to the MCP server, and to nothing else.
   No external orchestrator, no sibling project, no shared runtime directory. This
   repo and any tooling around it have independent lifecycles and are expected to
   diverge; a validation harness that breaks when an unrelated tool is reorganised is
   not a measuring instrument.
2. **The from-scratch `validation/runner.py` is replaced by `validation/bin/vrun`**,
   a shell runner that owns one cell's whole lifecycle. Written for this suite, using
   the `opencode run --format json` event stream as its only telemetry source.
3. **The unit of versioning moved from "enabled_tools per test case" to a versioned
   surface manifest** (§5). A per-case tool list cannot answer "is surface-v2 better
   than surface-v1?", because the two arms were never comparable.
4. **Benchmark A and Benchmark B are separate harnesses** (§8), because they isolate
   different failure causes and differ in cost by an order of magnitude.
5. **Deterministic outcome checks were promoted ahead of the LLM judge** (§9), per
   03-teststrategie §8.
6. **The agent under test never gets the admin tools.** See §5 — this is a correctness
   property of the measurement, not a surface opinion.

## 3. Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│  validation/bin/vrun          one cell = one (task, variant, model, rep)│
│    preflight -> reset model -> apply filter -> run -> restore -> record│
│                                                                      │
│  validation/lib/                                                        │
│    surface.py    build + version the tool manifest            (§5)     │
│    analyze.py    Layer 0 design-rule metrics, no LLM          (§8)     │
│    bench_a.py    Layer 1 tool-selection probe                 (§8)     │
│    trajectory.py tool calls, errors, timings from trajectory   (§6)     │
│    evaluate.py   oracle -> judge -> error taxonomy            (§9)     │
│    report.py     aggregate report.md / report.json            (§11)    │
│    gate.py       regression gate                              (§11)    │
│    propose.py    ranked fix proposals, never auto-applied     (§11)    │
│                                                                      │
│  validation/tasks/<id>.json      dataset                           (§4)  │
│  validation/surfaces/*.json      surface manifests                (§5)  │
│  validation/variants/*.json      tool-set variants                (§5)  │
│  validation/baselines/*.json     recorded baselines              (§11)  │
│  validation/output/              per-cell artifacts, ledger, reports    │
└──────────────────────────────────────────────────────────────────────┘
        │ MCP (streamable HTTP)                │ HTTP
        ▼                                      ▼
   opencode run --format json          Cameo on the host
   (one model per cell)                host.containers.internal:18750
```

The runner, the analysis and the verdict are deliberately three separate things.
`vrun` produces evidence and knows nothing about success; `evaluate.py` decides
success and never runs an agent. A measurement whose harness and whose judge are the
same code path cannot be checked.

## 4. Task dataset

`validation/tasks/<id>.json`, one file per task. Start with 6–8 and grow toward the
100–500 that 03-teststrategie §1 suggests; that number is a ceiling, not a starting
point, because every repetition is a real local-model mission against a live Cameo.

```json
{
  "id": "T02-drone-function-breakdown",
  "task": "Create the system functions for a surveillance drone and break them down ...",
  "readOnly": false,
  "preconditions": ["SAF profile applied"],
  "oracle": [
    {"kind": "element_exists", "name": "Surveillance Drone", "type": "SystemFunction"},
    {"kind": "relationship_exists", "from": "Launch Drone", "to": "Surveillance Drone", "type": "Satisfy"},
    {"kind": "count_at_least", "under": "Operational Capabilities", "ofType": "OperationalCapability", "min": 1}
  ],
  "expected_first_tool": "saf_find_elements_by_type",
  "call_budget": 40,
  "outcomes": [
    "A SurveillanceDrone system function exists as a child of the FFDS system function package",
    "It is broken down into a Drone and a Launch Site function"
  ],
  "repetitions": 3
}
```

- `oracle` is the **deterministic** success signal (03-teststrategie §8), checked by a
  client the agent never sees, after the run.
- `expected_first_tool` feeds Tool Selection Accuracy; `call_budget` feeds the
  `TOO_MANY_TOOL_CALLS` class.
- `outcomes` are only for clauses the oracle cannot express, and only then does the
  judge run (§9).
- The test model per task is configured in `config.json` under
  `cameo.test_models.<task-id>`, as a **host** path. It is deliberately not in the
  task file, so a task file stays portable between machines.

Seed tasks, carried over from the earlier draft:

| # | Task | Read-only | SAF viewpoints exercised |
|---|---|---|---|
| T1 | Extend FFDS with a Surveillance Drone capability on Operational Level; extend use cases | no | O2_OCYD, O1_OSTY, O2_OPRF, C1_SUCD |
| T2 | Create new system functions; break down to drone and launch site | no | C2_SFBS, C3_SFRE, C3_SPRO, C2_SSTD |
| T3 | What information is exchanged between Users of the FFDS and the FFDS? | yes | C1_SCXD, C1_SCXE, C2_SETD |
| T4 | Which interface partners does "Comms Node" have? What is exchanged? | yes | C5_SIFD, C4_SIEX, C4_SITI |

Start with T3 and T4. Read-only tasks need no model reset between repetitions, they
exercise the navigation and interpretation path where surface quality shows up first,
and they are the cheapest cells in the suite.

## 5. Surface manifest, variants, and who owns the environment

The surface is **75 tools** defined by `@McpTool` annotations across `scripts/*.groovy`
(18 `saf_*`, 15 `spec_*`, 12 CRUD, 9 `admin_*`, 21 other). To compare surfaces they
must be named and versioned.

```
validation/surfaces/surface-v1.json    the manifest: every tool, its description, its params
validation/surfaces/surface-v2.json    after a surface change, committed alongside the diff
validation/variants/full.json          { surface, mode, tools }
validation/variants/saf-only.json
validation/variants/minimal-core.json
```

- A manifest is generated from the live `tools/list` when Cameo is up, and parsed from
  `scripts/*.groovy` when it is not, so the offline layers still work. The generated
  file is committed; the generator never silently overwrites a committed version.
- A **variant** selects a subset of a manifest: `mode: all | only | except`.

| Variant | Shape | Question it answers |
|---|---|---|
| `full` | every non-admin tool | baseline |
| `saf-only` | `saf_*` + `spec_*` | does the generic CRUD layer help or distract? |
| `minimal-core` | CRUD + find only | how far can the surface shrink before success collapses? |
| `no-batch` | drop the batch tools | is batching worth its context cost? |
| `single-tool` | one tool | is a given tool *findable*? (Benchmark A) |

### Who owns the model and the filter

`vrun` owns both, and the agent under test gets neither. Order inside a cell:

```
1. clear any pre-existing filter            POST /admin/api/disable
2. reload the task's test model             admin_reset_model   (unfiltered)
3. apply the variant                        POST /admin/api/enable
4. run the agent                            (sees exactly the variant, no admin_*)
5. clear the filter                         POST /admin/api/disable   (in a trap)
```

Two properties of the current implementation make this work, and both are load-bearing:

1. **`/admin/api/*` is plain HTTP and is not filtered.** The runner applies and clears
   the filter over HTTP, never through `admin_set_enabled_tools`, so a variant can
   never lock the harness out of its own lever. A consequence worth stating: `admin_*`
   in a manifest describes what the *agent* sees, and withholding it genuinely hides
   those tools from the agent while the harness keeps full control.
2. **The filter is `static` and process-wide.** `McpSession.enabledTools`
   (`McpSession.java:18`) is checked in both `tools/list` (`McpProtocolHandler.java:86`)
   and `tools/call` (`McpProtocolHandler.java:110`). A variant therefore affects *every*
   connected session, including a human's interactive one. `vrun` takes an exclusive
   lock and restores the filter from an `EXIT` trap, but it cannot stop a human from
   attaching mid-cell. If the suite graduates to continuous use, the fix is to scope
   the filter per session rather than statically.

### Why the agent never gets `admin_*`

If the agent can call `admin_reset_model`, a cell can discard everything it built and
start over, which inflates the call count, and the post-run oracle then scores a model
the agent never actually produced. Model lifecycle is therefore not part of the
surface being measured — it is part of the apparatus. `vrun` strips `admin_*` from every
variant unless `--allow-admin` is passed, and that flag exists so the *cost of having
them* can be measured deliberately, not by accident.

### Surface changes are edits, not filters

A variant that renames a tool, rewrites a description or drops a parameter is a change
to `scripts/*.groovy`. It is made on a branch, deployed with `./deploy-scripts.sh`
(Groovy hot-reloads, ~2s settle; no Cameo restart unless `src/` changed), recorded as a
new `surfaces/surface-vN.json`, and only then measured. Never ablate against a dirty
live surface.

## 6. Metrics

Priority order is fixed by 02-optimierungsziele §1–§8. The report leads with row 1.

| # | Metric | Source | Definition |
|---|---|---|---|
| 1 | **Task Success Rate** | 02 §1 | oracle passed, or judge accepted and not human-overridden |
| 2 | Tool Selection Accuracy | 02 §2 | first tool decision matches `expected_first_tool` |
| 3 | Argument Accuracy | 02 §3 | per call: required params present, no unknown params, enums legal, types right |
| 4 | Invalid Tool Call Rate | 02 §4 | `status: error`, per call |
| 5 | Tool Calls / Task | 02 §5 | count of `tool_use` events per repetition; mean, median and spread |
| 6 | Tokens / Successful Task | 02 §6 | `usage.json` totals ÷ successes — the efficiency headline |
| 7 | Latency / Successful Task | 02 §7 | summed `state.time.end − start`, plus wall clock |
| 8 | Smallest viable model size | 02 §8 | lowest catalogue model clearing the success threshold |
| 9 | Coverage | earlier PLAN §2 | tools used ÷ tools enabled, plus the never-used list |

Metrics 1, 3, 4, 5, 7 and 9 are **fully deterministic** and derived from the
trajectory. Metric 2 needs one declared field per task. Metrics 6 and 8 come from
`usage.json` and need no judge at all. Only metric 1's fallback path needs a model.

### Where the numbers come from

`vrun` records `trajectory.jsonl`, a copy of opencode's `--format json` NDJOIN event
stream. Its schema is the suite's telemetry contract:

```jsonc
{"type":"tool_use","timestamp":1790401548386,
 "part":{"type":"tool","tool":"<name>","callID":"...",
         "state":{"status":"completed|error","input":{...},"output":"...",
                  "error":"...","time":{"start":...,"end":...}}}}
{"type":"step_finish","part":{"tokens":{"total":…,"input":…,"output":…,
                                       "reasoning":…,"cache":{"read":…,"write":…}},
                              "cost":…}}
{"type":"text","part":{"type":"text","text":"…"}}
```

Two properties to preserve when touching the runner:

- The stream **mixes object and string lines**. Always `jq -s` slurp and
  `select(type == "object" and .type == …)`. A `jq` filter that assumes objects dies
  on the first string line.
- Token counts are **streamed per step and summed**, never estimated. `usage.json` is
  the authoritative source; a cell whose `usage.json` has `unparsed: true` contributes
  to success rate but is excluded from every token metric, and says so.

### Error taxonomy

Every failed task gets exactly one class (03-teststrategie §7), derived mechanically
from the trajectory so the classification is reproducible.

| Class | Detection |
|---|---|
| `TOOL_SELECTION_ERROR` | first decision was a plausible-but-wrong tool; the run recovered |
| `ARGUMENT_ERROR` | `status:error` whose message names an argument, required or enum problem |
| `HALLUCINATED_TOOL` | `tools/call` for a name absent from the enabled set |
| `HALLUCINATED_ARGUMENT` | an argument key absent from the tool's schema |
| `MISSING_CONTEXT` | repeated identical read calls, or reads that keep returning empty |
| `TOO_MANY_TOOL_CALLS` | success, but calls/task exceeded `call_budget` |
| `WRONG_INTERPRETATION` | oracle failed although every call succeeded |
| `TOOL_EXECUTION_ERROR` | `status:error` originating in Cameo, not in argument validation |
| `INFRA_FAILURE` | Cameo unreachable, model load failed, timeout |

`INFRA_FAILURE` is load-bearing: an unreachable host must never be scored as an agent
failure. `vrun --preflight-only` is the gate; a suite that cannot reach Cameo refuses
to produce a success rate rather than reporting 0 %.

### Run health is not a verdict

`vrun` records `health: ok | degraded` (timeout, non-zero exit, empty trajectory, no
usage accounting) and stops there. It deliberately does **not** ask the agent to grade
itself: a self-reported verdict is not evidence, and asking for one invites a model to
assert success it did not achieve. Success is decided afterwards, by checking the
model.

## 7. Experiment protocol

Control variables held constant (03-teststrategie §3) and written into every cell's
`meta.json`, so a comparison can be *refused* when they drifted:

```
model id, base_url, context limit, max_output   (the catalogue entry)
system prompt                                   (vrun's preamble, versioned with the suite)
task dataset revision
Cameo plugin commit + surface version
enabled tool set (the resolved list, not just its size)
test model path + sha256
```

Two cells whose `meta.json` disagree on any control field are printed side by side but
flagged `NOT-COMPARABLE`. This is the mechanism that makes 03-teststrategie §10
actionable: `Success +5 %` with `Tokens +80 %` is not an improvement, and
`4B +10 %` with `14B −5 %` is a regression — but only if the arms really were equal.

**Serialisation is forced, not chosen.** Cameo holds one open model and one
process-wide tool filter, so two cells can never run concurrently. `vrun` takes an
exclusive `flock` and exits rather than queueing.

**Repetitions.** 3 per cell by default. With a local model this is the dominant cost,
so start at 3 on read-only tasks and 1–2 on mutating ones until the variance is known.

**Order.** Interleave arms per repetition — one repetition of v1, then one of v2 —
rather than finishing all of v1 first, so drift in the host or the model server cannot
masquerade as a surface effect.

## 8. The layers

03-teststrategie §4 keeps these apart because they localise the failure: when B is good
and A is bad, the problem is tool discovery and surface design, not tool execution.

### Layer 0 — static analyser (`analyze.py`): free, no LLM, no Cameo

From the manifest, the design-rule metrics of 01-designregeln that need no judgement:

```
tool count
parameters per tool: total, required, optional
optional-parameter ratio
description length distribution (chars) and outliers
name ambiguity: generic verbs (execute, run, get, query, list) per 01 §2
description overlap: pairwise similarity above a threshold -> suspected semantic
                    duplicates per 01 §8
```

These become **regression thresholds**, not scores. A change that adds a tool or pushes
overlap up is flagged before any LLM run happens. This is the only layer runnable while
Cameo is down, so it is wired first.

### Layer 1 — Benchmark A, tool selection (`bench_a.py`): cheap, no Cameo

Present the agent one task and the tool cards of one variant; it must answer with a
single tool choice. Yields Tool Selection Accuracy and a direct read on whether names
and descriptions are discriminable (01 §2, §3, §8). No model mutation, no reset, no
oracle — so it is cheap enough to run across the full model-size ladder (03 §6) and to
A/B every single rename or description edit.

### Layer 2 — Benchmark B, end-to-end cells (`bin/vrun`): expensive, full stack

One cell per (task, variant, model, repetition), as in §5. Yields everything in §6
except the pure selection metric, plus the error taxonomy.

## 9. Outcome evaluation

Three tiers, cheapest first (03-teststrategie §8):

1. **Deterministic oracle** — post-run MCP queries asserting the `oracle` clauses,
   made from a client whose tool set the agent never saw. Default. Examples: an
   element of type T named N exists under package P; a relationship of type R from A
   to B exists; a package contains at least k children of type T.
2. **LLM judge** — only for `outcomes` clauses the oracle cannot express. The judge
   sees the task, the clause, the tool-call sequence and the relevant tool outputs. It
   does **not** see the raw transcript, and it never sees the agent's own summary as
   evidence of anything.
3. **Human override** — the judge records `accept` or `override` per clause; every
   override is written to `validation/reviews/<cell>.json` with a reason, so judge
   drift stays visible.

Judge model and prompt are pinned in each run's `meta.json`. An unparsable verdict is
`UNKNOWN`, never `FAIL`. A cell whose health is `degraded` is reported as unresolved
and routed to the human, never scored as a failure.

## 10. Ablations

Per 03-teststrategie §5, one tool at a time, four description arms:

| Arm | Description |
|---|---|
| A | current (full) |
| B | short — one line: action plus when-to-use |
| C | current plus a worked example |
| D | current plus an explicit decision rule ("use this *instead of* X when …") |

Arm D is the interesting one for this surface. The repo's `AGENTS.md` already carries
decision rules — "reach for `cameo://*` resources first" — which suggests those rules
belong in the tool descriptions, where a *small* model will actually encounter them,
rather than in a file it may never read.

Each arm is a branch off the surface branch, deployed with `./deploy-scripts.sh`,
measured, and merged only if it wins. The layer to run this on is Layer 1, where an arm
costs one probe instead of a full mission.

## 11. Outputs and regression gate

Per cell:

```
validation/output/<surface>/<task>/<model-key>/<rep>/
  meta.json          controls, surface shape, health, usage
  brief.md           the task text as given
  prompt.txt         the full prompt including the preamble
  session.jsonl      raw opencode event stream
  trajectory.jsonl   the copy the metrics are computed from
  session.err        stderr
  usage.json         authoritative token accounting
  report.md          what the agent says it did (evidence, not a verdict)
  reset_result.json  what admin_reset_model returned
validation/output/ledger.jsonl     one row per cell
```

Aggregate per surface version: `report.md` and `report.json`, leading with the 02 §12
table, then the model-size scaling curve, then the coverage heatmap, then the ranked
list of tools never used and tools used but never succeeding.

### Regression gate

`gate.py` exits non-zero, and is CI-usable, when any of these hold:

```
Task Success Rate          drops > 2 pp vs the recorded baseline
Tokens / Successful Task   rises > 20 %
Invalid Tool Call Rate     rises
Cognitive Surface Complexity rises   (tool count, optional params, overlap clusters)
a control field drifted              (=> NOT-COMPARABLE, never a pass)
INFRA_FAILURE present in the arm     (=> the arm is void, not a failure)
```

Thresholds live in `validation/baselines/<surface-version>.json` and are recalibrated
after the first real baseline exists. The 2 pp / 20 % numbers are the
03-teststrategie §10 examples, not measured constants.

### Ranked fix proposals

Not applied automatically. After each report, `propose.py` emits a ranked list of
concrete, checkable surface changes against the 01 rules — merge these two tools,
shorten this description, drop this optional parameter, add this decision rule — each
with the metric it should move and the evidence from the run. A human decides. An
automated apply-and-re-measure loop is deliberately out of scope until the measurement
is trusted: an optimiser that rewrites the surface against a noisy 3-repetition
estimate optimises noise.

## 12. Hazards

| Hazard | Mitigation |
|---|---|
| Tool filter is process-wide `static` (`McpSession.java:18`) — a human's interactive session loses tools mid-cell | exclusive lock, filter restored from an `EXIT` trap, preflight refuses to start dirty; per-session scoping is the long-term fix |
| A variant hides `admin_*` from the agent *and* from a naive harness | the runner uses unfiltered `/admin/api/*`; `vrun` also strips `admin_*` itself so the default arm is clean |
| Host unreachable → 0 % success looks like a model failure | `INFRA_FAILURE`; `--preflight-only` gates the suite |
| Host path mapping is not a safe default | `cameo.host_workspace` is **required**, with no fallback. This repo's own tool descriptions disagree about it — `scripts/admin_bridge.groovy:14` says `/home/mac/opencode/workspace`, `scripts/tool_contract_resources.groovy:58` says `/home/mac/oc3/workspace`, and `showcase/reverse-engineering/MCP-SURFACE-FINDINGS.md:9` records the real root as `/home/mac/projects/AI/opencode/workspace`. A wrong guess does not fail loudly, so `vrun` refuses to start without it |
| MCP tool names are server-prefixed and the prefix has changed between versions | the preamble describes admin tools by suffix and tells the agent not to seek them; no literal prefixed name is hardcoded |
| Cameo is a single live instance with one model | strict serialisation via `flock`; model reset per cell; test models only |
| 3 repetitions is noisy for a small local model | report min/median/max and spread; never gate on a delta smaller than the observed spread |
| Judge drift silently changes the metric | pin judge model and prompt, log every verdict and every human override |
| `opencode run` intermittently exits non-zero on clean completions | `vrun` classifies this as `health: degraded`, never as a task failure |
| Editing a script in this repo does not affect the running surface (AGENTS.md) | `vrun` records `plugin_commit`; surface edits go through `./deploy-scripts.sh` and are recorded as a new `surface-vN.json` |

## 13. Build order

| # | Step | Needs Cameo | Needs LLM |
|---|---|---|---|
| 0 | `analyze.py` + `surface-v1.json` from `scripts/*.groovy`; design-rule metrics; `gate.py` on those metrics | no | no |
| 1 | Dataset: 6–8 tasks with oracle clauses, read-only first | no | no |
| 2 | `bench_a.py` — selection probe over the manifest, full model ladder | no | yes |
| 3 | `trajectory.py` + `evaluate.py` + `report.py`; oracle and judge | no | yes |
| 4 | `vrun` against the live surface: filter → reset → run → restore | yes | yes |
| 5 | Baseline recorded; gate thresholds calibrated | yes | yes |
| 6 | First real surface change as surface-v2, measured against the baseline | yes | yes |
| 7 | Ablations (§10), starting on Layer 1 | yes | yes |

Steps 0–3 need neither Cameo nor a model server and can be built and run now. Step 4
is the first thing blocked while the host is down.

## 14. Configuration

`validation/config.json`, overridable per key by the same name in UPPER_SNAKE. Two
fields must be filled in before `vrun` will run:

- `cameo.host_workspace` (or `CAMEO_HOST_WORKSPACE`) — no default, by design; see §12.
- `cameo.test_models.<task-id>` — a **host** path to a test `.mdzip`. Test models
  only.

`models.entries` is the catalogue for the model-size curve (02 §6). Each entry needs
`provider`, `base_url`, `model`, `context` and `max_output`, and is written verbatim
into the per-run `opencode.json`, so adding a model is a config edit and nothing else:

```json
"models": { "default": "qwen-9b",
  "entries": {
    "qwen-9b":  { "provider": "local", "base_url": "http://<host>:1234/v1",
                  "model": "<gguf-id>", "context": 262144, "max_output": 8192 },
    "qwen-27b": { "provider": "local", "base_url": "http://<host>:1234/v1",
                  "model": "<gguf-id>", "context": 70000,  "max_output": 8192 }
  }
}
```

## 15. Files

```
validation/
  PLAN.md                       this file
  01-designregeln-dos-donts.md
  02-optimierungsziele.md
  03-teststrategie.md
  config.json                   suite config: endpoints, model catalogue, host paths
  bin/vrun                      one cell: preflight -> reset -> filter -> run -> restore
  lib/
    surface.py                  build + version the tool manifest
    analyze.py                  Layer 0 design-rule metrics
    bench_a.py                  Layer 1 tool-selection probe
    trajectory.py               trajectory.jsonl -> calls, errors, timings
    evaluate.py                 oracle, judge, error taxonomy
    report.py                   aggregate report.md / report.json
    gate.py                     regression gate
    propose.py                  ranked fix proposals (never auto-applied)
  surfaces/surface-vN.json      committed surface manifests
  variants/<name>.json          tool-set variants
  tasks/<id>.json               task dataset
  baselines/<surface>.json      recorded baselines
  reviews/<cell>.json           human overrides of judge verdicts
  output/                       per-cell artifacts, ledger.jsonl, report.md, report.json
```

Everything the suite needs is in this repository plus the `opencode` binary, `jq`,
`curl`, and a running Cameo. Nothing else.
