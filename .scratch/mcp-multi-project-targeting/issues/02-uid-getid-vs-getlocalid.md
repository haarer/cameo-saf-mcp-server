# Issue: UIDs — MCP emits `getID()`, docgen keys on `getLocalID()`

Status: needs-triage

## Context

When exporting SAF spec concepts / stereotypes into the file-based SAF_Profile
project, the element UIDs reported by the MCP surface differ depending on
which project context reads them. The same logical element has **different
`getID()` values** depending on whether it is read through the
SAF_Specification module copy or through the file-based SAF_Profile project.

The docgen toolchain (SAF-Tools) was deliberately switched to `getLocalID()`
because only that API yields stable IDs regardless of whether an element lives
on TWC (Teamwork Cloud) or in a file-based model.

## Current state

- **MCP server emits `getID()` exclusively** — 95+ call sites across
  `element_crud.groovy`, `saf_tools.groovy`, `model_find.groovy`,
  `model_info.groovy`, `model_query.groovy`, `structural_tools.groovy`,
  `diff_tool.groovy`, `modelcode.groovy`, `export_diagram.groovy`,
  `saf_views.groovy`, `context_resources.groovy`, `validation_introspect.groovy`.
  **Zero** `getLocalID()` calls.
- **Resolution** uses `getProject().getElementByID(id)` — looks up by the
  session-level ID registry (`element_crud.groovy` L117, and per-file
  equivalents in `saf_tools.groovy`, `structural_tools.groovy`,
  `modelcode.groovy`).
- **Docgen (SAF-Tools) keys durable references on `getLocalID()`**:
  - `gen_spec.py` — stereotypes.csv `UID` column, concern anchors/links,
    example-name postfixes, "Bad Concern id" log.
  - `genstereotypedoc.py` — sets `helpID` tagged values and doc anchors from
    `cst.getLocalID()`.
  - `genjson.groovy` / `genselected_img.groovy` — example diagram filenames
    `"exa" + se.getLocalID() + ".svg"`.

## Observed divergence (concrete)

`SAF_C4_SCXI`:

- Read through **SAF_Specification module copy**: `2d558f1b-d385-4bff-a114-2c05e414c5e8`
- Read through **file-based SAF_Profile (active)**: `_19_0_4_26f0132_1626791693357_994866_3136`

Same stereotype, different `getID()` depending on context — exactly the
instability `getLocalID()` was chosen to avoid in the docgen.

Additional live confirmation (2026-09): with the file-based `SAF_Profile`
active, `cameo://element/_19_0_4_26f0132_1626791693357_994866_3136` returned
id `_19_0_4_26f0132_1626791693357_994866_3136` (the file ID). With
`SAF_Specification` active, resolving the same file ID `_19_0_4_...` returned
`SAF_C4_SCXI` with `id: 2d558f1b-42df-49f0-929e-034f21c86c4e` — **the same file
ID resolves under a different `getID()` depending solely on which project is
active.** `cameo://element/{id}` lookups go through the active project's element
registry only.

## Consequences

- Element UIDs captured by an agent in one session/project context may not
  resolve (or may resolve to a different element) in another context.
- Especially: **after the SAF_Profile module is refreshed/updated in the spec
  model, previously memorized UIDs must not be reused** — re-discover them in
  the active project.
- The MCP-reported `id` and the docgen-generated `UID`/`helpID`/anchor may not
  match for the same element, making cross-referencing between MCP sessions and
  generated documentation unreliable.

## Definition of done

- [ ] Decide on one canonical UID source (likely `getLocalID()` for stable,
      context-independent IDs) and align the MCP element-ID emission with it.
- [ ] Verify `getProject().getElementByID(id)` still resolves the canonical
      ID (or provide a lookup path that does).
- [ ] Document the rule in the repo README / agent docs: element UIDs are
      context-dependent; never carry memorized UIDs across project refreshes.

## References

- `getID()` emission sites — e.g. `element_crud.groovy` L327, `saf_tools.groovy` L315
- `getLocalID()` usage in docgen — `/workspace/SAF-Tools/gen_spec.py`,
  `genstereotypedoc.py`, `genjson.groovy`, `genselected_img.groovy`
- `Project` API — `getElementByID(String)` (live JVM introspection:
  `com.nomagic.magicdraw.core.Project`)