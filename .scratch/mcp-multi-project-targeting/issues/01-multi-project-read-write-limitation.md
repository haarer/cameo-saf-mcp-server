# Issue: Clarify/document the multi-project read/write limitation

Status: needs-triage

## Context

The MCP surface is **read-mostly-multi-project, write-single-project**. This
limitation was investigated while exporting the P4_PCXI viewpoint from the SAF
specification model into the file-based SAF_Profile project.

## Current state

- **Writes are strictly active-project-only.** Every write path resolves the
  project via `Application.getInstance().getProject()` (`getProject()` in
  `element_crud.groovy`, `saf_tools.groovy`, `structural_tools.groovy`,
  `modelcode.groovy`) and elements via `getProject().getElementByID(id)`.
  There is **no `projectId` parameter on any write tool**.
- `writableCheck` (element_crud.groovy L78) only **guards** against
  read-only/used-project targets — it never redirects the write to another
  project.
- **Reads are partially multi-project**: `cameo://projects`,
  `cameo://project/{id}`, `cameo://project/{id}/packages` address any loaded
  project; `find_elements_by_type` / `saf_find_elements_by_type` with
  `scope='all'` search primary + used projects; `cameo://element/{id}`
  (+`/children`, `/relationships`) resolves through the **active project only**.
- `diff_tool.groovy` is the only per-project element loop and is read-only.

## Consequence

To write into a project, that project must first be made the **active** project
(`admin_load_model` / `admin_reset_model` / user activation in the GUI). The
agent cannot target a specific non-active project for writes.

## Definition of done

- [ ] Document the write-single-project limitation in the repo README / tool
      docs so agents do not assume writes can target arbitrary loaded projects.
- [ ] Optional (future): consider adding an explicit `projectId` write-targeting
      mechanism; deliberately out of scope for now ("we don't go into that
      rabbit hole now").

## References

- `getProject()` definition — `element_crud.groovy` L15 (and equivalent in
  `saf_tools.groovy`, `structural_tools.groovy`, `modelcode.groovy`)
- `resolveElement(id)` — `element_crud.groovy` L117
- `writableCheck` — `element_crud.groovy` L78