# Validation Test Suite Plan

## 1. Purpose

A validation test suite that measures how well an AI agent (OpenCode) combined with the SAF MCP server performs on real engineering tasks in Cameo Systems Modeler. The goal is to **optimize the MCP tool surface** — reduce wasted calls, improve accuracy, and ensure the agent + server combination is net-beneficial for a human user.

## 2. Dimensions

| Dimension | Definition | Measurement |
|---|---|---|
| **Accuracy** | Did the agent produce the correct model elements and relationships? | Outcome criteria judged by LLM, overridden by human |
| **Efficiency** | How many tool calls, token count, wall-clock time per task? | Automated from transcript |
| **Coverage** | What percentage of the available MCP tool surface gets exercised? | Tool names appearing in transcript vs enabled set |

## 3. Architecture

```
Container boundary
┌─────────────────────────────────────────────────────┐
│  validation/runner.py                               │
│    ↓ spawns (subprocess)                            │
│  opencode run --task "..."                          │
│    │ config via XDG_CONFIG_HOME (isolated)          │
│    │ MCP client → host.containers.internal:18750    │
│    ▼                                                │
│  Cameo (host)                                       │
│    └─ SAF MCP Server Plugin                         │
│       ├─ tools/ (SAF, CRUD, query, spec)            │
│       └─ admin_bridge.groovy (load/close/reset)     │
│                                                     │
│  Transcript captured → metrics computed → report    │
└─────────────────────────────────────────────────────┘
```

### Key Decisions

| Decision | Choice | Rationale |
|---|---|---|
| **Monorepo vs separate repo** | Monorepo (`validation/` dir) | Admin bridge lives in server; defers splitting until shape is stable |
| **Agent interface** | OpenCode CLI subprocess | Reuses existing agent loop; `--format json` for structured output |
| **Config isolation** | `XDG_CONFIG_HOME` per run | Full separation from the user's running opencode; no interference |
| **Model lifecycle** | Admin bridge Groovy scripts | `admin_load_model`, `admin_close_model`, `admin_reset_model`; hot-reloadable, easy to remove |
| **Correctness judgement** | LLM-assisted + human override | LLM evaluates outcome criteria; human ticks accept or overrides |
| **Efficiency metrics** | Fully automated | Parsed from transcript (tool calls, latency, errors) |
| **Repetitions** | Configurable per test case | Flattens LLM stochasticity; default 3 |

## 4. Test Case Format

```json
{
  "id": "ffds-surveillance-drone",
  "task": "Extend the existing FFDS Model by a Surveillance Drone capability on Operational Level and extend the FFDS use cases. In case of a suspected or confirmed fire the Drone is automatically launched from a launcher at a launch site, navigates to the area of interest and starts surveillance, providing life video feed and sensor data assisting in classifying the fire, and estimating fire propagation. During firefighting the Drone directly assists firefigthers with realtime surveillance data. When the batteries are low, the drone returns to the base and gets replenished. Multiple drones can be launched to ensure continous surveilance and coverage of larger areas. Multiple launch sites can be in operation",
  "model": "FFDS.mdzip",
  "enabled_tools": [
    "saf_create_element",
    "saf_create_relationship",
    "saf_set_requirement_tags",
    "saf_query_viewpoint",
    "saf_find_elements_by_type",
    "saf_get_element_details",
    "find_elements",
    "find_elements_by_type",
    "get_element_info",
    "get_element_details",
    "create_element",
    "create_relationship"
  ],
  "outcome_criteria": [
    "A Surveillance Drone capability exists in the appropriate package where the existig Operational Capabilities are located",
    "The drone capability has associated Operational Performers for the launch site and drone",
    "FFDS use cases are extended by new use cases related to surveillance, launch and recovery"
  ],
  "repetitions": 3
}
```

The `enabled_tools` array is the key lever: by restricting which tools are available, we measure how the agent copes with different tool-surface shapes. Comparing runs across variants tells us which tools are essential and which create noise.

## 5. Engineering Tasks

| # | Task | SAF Viewpoints Exercised |
|---|---|---|
| T1 | Extend FFDS with Surveillance Drone capability on Operational Level; extend use cases | O2_OCYD (Operational Capability Definition), O1_OSTY (Operational Story), O2_OPRF (Operational Performer Definition), C1_SUCD (System Use Case Definition) |
| T2 | Create new system functions; break down to drone and launch site | C2_SFBS (System Functional Breakdown Structure), C3_SFRE (System Functional Refinement), C3_SPRO (System Process Viewpoint), C2_SSTD (System Structure Definition) |
| T3 | What information is exchanged between Users of the FFDS and the FFDS? | C1_SCXD (System Context Definition), C1_SCXE (System Context Exchange), C2_SETD (System Exchange Type Definition) |
| T4 | Which interface partners does "Comms Node" have? What is exchanged? | C5_SIFD (System Interface Definition), C4_SIEX (System Internal Exchange), C4_SITI (System Internal Interaction) |

Tasks are drawn from the existing `_data/` SAF ontology and the FFDS model.

## 6. Harness Design (`validation/runner.py`)

### Flow per test case

```
for each test_case in suite:
  for repetition in range(test_case.repetitions):
    for tool_variant in tool_variants:
      1. Call admin_load_model(test_case.model) → reset to clean state
      2. Generate isolated opencode config with tool_variant.enabled_tools
      3. Spawn: XDG_CONFIG_HOME=<tmpdir> opencode run --format json --model <model> "<task>"
      4. Capture stdout/stderr → write transcript to output/<id>/<variant>/<rep>.jsonl
      5. Parse transcript: extract all MCP tool calls, responses, errors
      6. Compute efficiency metrics:
         - total_tool_calls
         - unique_tools_used
         - tool_call_sequence
         - errors / retries
         - wall_clock_time
         - estimated_token_count (from LLM I/O)
      7. Compute coverage:
         - tools_used / tools_enabled
         - tools_not_used (list)
      8. LLM evaluates outcome_criteria → structured judgement
      9. Present to human for accept/override
  Accumulate results → markdown report
```

### Config Template

The harness generates a temporary `opencode.json` per run:

```json
{
  "provider": { /* only model needed for this run */ },
  "mcp": {
    "cameo": {
      "type": "remote",
      "url": "http://host.containers.internal:18750/mcp",
      "enabled": true
    }
  },
  "disabled_providers": []
}
```

### Model Selection

Configurable via CLI flag or env var. The test suite should support any model the user has available:

```bash
python validation/runner.py --model lmstudio/qwen3.5-27b
```

## 7. Output

Per run:
```
validation/output/
  T1-ffds-surveillance-drone/
    full-toolset/
      00_transcript.jsonl
      00_metrics.json
      01_transcript.jsonl
      01_metrics.json
      02_transcript.jsonl
      02_metrics.json
    summary.json
  T2-ffds-function-breakdown/
    ...
  report.md              # Aggregate across all tasks
```

The aggregate report includes:
- Per-task accuracy (criteria pass/fail per repetition)
- Per-task efficiency (mean tool calls, latency, tokens)
- Per-task coverage (tool usage heatmap)
- Cross-task tool importance ranking

## 8. Tool-Set Variants (future)

Once the basic loop works, variants compare:

| Variant | Description |
|---|---|
| `full-toolset` | All tools enabled (baseline) |
| `no-saf-spec` | Exclude `spec_*` tools |
| `saf-only` | Only `saf_*` tools |
| `no-batch` | Exclude batch tools (`get_elements_details_batch`, `list_owned_elements`) |
| `minimal-core` | Only CRUD + find + echo |

Each variant answers: *does removing these tools help or hurt the agent?*

## 9. Iteration Plan

### Iteration 1 (MVP)

Single test case (T1), single tool-set variant (`full-toolset`), 1 repetition. Get the full loop working: admin bridge → spawn opencode → capture transcript → compute metrics → LLM judgement → human review.

### Iteration 2

All 4 tasks, 1 tool-set, 3 repetitions each. Automate report generation.

### Iteration 3

Multiple tool-set variants per task. Statistical comparison.

### Iteration 4

CI integration (requires Cameo running in CI, or a headless Cameo mode).

## 10. Container Setup

The harness runs inside the existing Podman container. Cameo runs on the host. Communication flows:

```
Container → host.containers.internal:18750 (MCP server)
Container → host.containers.internal:8600  (Javadoc MCP server, for research)
Container → host.containers.internal:1234  (LLM inference server)
```

No changes to the container setup. The harness only needs Python 3 + `httpx` (same deps as the existing test suite).

## 11. Files

```
validation/
  PLAN.md                          # This document
  runner.py                        # Test harness
  test_cases/
    ffds-surveillance-drone.json   # T1
    ffds-function-breakdown.json   # T2
    ffds-interface-exchange.json   # T3
    ffds-comms-node.json           # T4
scripts/
  admin_bridge.groovy              # Admin MCP tools (already created)
```
