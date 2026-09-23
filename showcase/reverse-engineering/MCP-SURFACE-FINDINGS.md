# MCP Surface Findings: Bugs, Shortcomings, Slow Turns

Analysis of the reverse-engineering session, based on both the earlier session (handoff) and the current one. Split into three parts: bugs in the MCP surface, design shortcomings, and turns that were unnecessary in hindsight.

## MCP surface bugs

1. **`saf_create_relationship(type:"refine")` is broken** — crashes with `No such property: stereotypeToApply for class: SafTools`. Every refinement link had to be made via the raw path `create_relationship(type:"abstraction", stereotype:"Refine")`, losing the SAF-specific refinement semantics (and any kind-specific stereotype auto-application). Highest-value fix candidate: `scripts/saf_tools.groovy`.

2. **Host path resolution is inconsistent, including its own hint.** Tools resolve paths on the host, not the container, but the `admin_reset_model` error hint said container `/workspace` maps to host `/home/mac/opencode/workspace`, while the actual host root is `/home/mac/projects/AI/opencode/workspace`. First reset attempt failed on that misleading hint. Nothing in the surface states which mapping is correct.

3. **No "save as" — `admin_save_model` silently writes to the working location.** The model ended up at `MSOSA2024xRef3/Untitled1/McpServerSAFA.` even though `admin_create_model` had reported a different savePath and the delivery target was the repo. Only detectable by cross-checking `get_model_status`, and rectifiable only by a manual file copy.

## MCP surface shortcomings

4. **`SAF_ConceptualSystem` is ambiguous** — it realizes both *Conceptual System* and *Conceptual External System*, so `safKind`/`safDomain` return empty and every query needs context disambiguation. Extra reasoning on each result row.

5. **`saf_create_element(kind:"system_function")` silently maps to Activity + `SAF_Function`**, while the SAF spec and the original plan imply a Class. A quiet deviation that surfaced only by inspecting the returned `sysmlType`; behavior semantics were off because of it.

6. **Long tool results are truncated with no count/summary**, so the full block-ID list (`…4320`, `…4322`, `…4324`, `…4326`, `…4328`, `…4334`, `…4336`) was unrecoverable and necessitated re-queries.

7. **No bulk creation/typing API** — 168 parameters each needed a separate `set_type` call (168 round trips), and operation creation was likewise per-element.

## Unnecessary turns

1. **Fabricating element IDs from the numeric-suffix pattern** ("…ends in 4740" ⇒ guess ID). All 18 param IDs (4685–4702) were wrong → one dead batch of `set_type`, then a re-listing of owned `Parameter`s and a full re-typing pass. Same thing happened once for the SetType function (4740): guessed ID → "Element not found" → `find_elements` → retry. The correct move (list owned elements first, or use `cameo://element/{id}/children`) would have saved both detours.

2. **Late file-location verification** — confirming where the model lives only at save time, then doing a `cp` + `reset_model` to validate the copied artifact. Verifying at `admin_create_model` (or having save-as) would have removed a whole delivery leg.

3. **Stale ID provenance** — the earlier session's ID list wasn't carried as real IDs into the handoff, so stale/guessed values were trusted instead of re-querying once up front.

## Recommended upstream fixes (priority order)

- The `refine` crash in `saf_tools.groovy`.
- Path-mapping consistency + a real save-as / path-aware save.
- Ambiguity surfacing for `SAF_ConceptualSystem` (hard error or clear disambiguation).
- Truncation with a stable count field and a way to page/filter by name — would have eliminated most re-query turns.