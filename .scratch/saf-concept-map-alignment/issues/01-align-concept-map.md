Status: needs-triage

# Align SAF concept map with concepts.json and realizeconcept.json

## Summary

The `CONCEPT_MAP` in `scripts/saf_tools.groovy` defines which SAF concept kinds
`create_element` accepts. It currently has **22 entries**. The SAF specification
defines **~90+ Class-type concepts** with explicit SysML realizations in
`realizeconcept.json`. The map and the spec are misaligned.

## Current state

| Source | Entries | Location |
|---|---|---|
| README Concept Map table | 4 | `README.md:120-127` |
| `CONCEPT_MAP` in code | 22 | `scripts/saf_tools.groovy:9-30` |
| SAF spec `concepts.json` (Class type) | ~90+ | `/workspace/paper-tdse-ai-saf/sources/SAF-Specification/src/_data/concepts.json` |
| SAF spec `realizeconcept.json` | ~200+ mappings | `/workspace/paper-tdse-ai-saf/sources/SAF-Specification/src/_data/realizeconcept.json` |

## Gaps

1. **Missing concepts**: Many SAF concepts exist in the spec but aren't in
   `CONCEPT_MAP` — e.g. `system_capability`, `functional_requirement`,
   `stakeholder_requirement`, `claim`, `argument`, `security_context`, `asset`,
   `system_function`, `operational_state`, etc.

2. **Wrong SysML type**: Some entries map to `Class` but the spec may use a
   different type (e.g. `conceptual_function` -> `Activity` is already correct,
   but `operational_capability` -> `Activity` needs verification).

3. **Non-SAF-stereotype realizations**: Some concepts realize to
   SysML-native types without a SAF stereotype (`ItemFlow`, `ProxyPort`,
   `FlowProperty`, `ControlFlow`, `InputPin`, `OutputPin`, `Connector`,
   `Message`, `Lifeline`, `Event`, `Transition`). These need special handling
   — their "stereotype" field is null.

4. **Mixed stereotypes**: Some realize entries map to raw
   stereotypes (`SAF_PhysicalInternalRole`) used by multiple concepts
   (`General Physical Role`, `Physical Element Role`, `Physical Hardware Role`,
   `Physical Software Role`, `Hardware Element Role`, `Software Element Role`).
   The mapping must resolve which concept the user meant.

5. **README table**: Only 4 rows; needs full regeneration from spec.

## Definition of done

- [ ] Audit `CONCEPT_MAP` entries against `realizeconcept.json` — fix wrong
      SysML types and missing/incorrect stereotype names.
- [ ] Add all missing Class-type SAF concepts that have a realization in
      `realizeconcept.json`.
- [ ] Handle non-SAF-stereotype realizations (ItemFlow, ProxyPort, etc.)
      correctly — these should still be creatable but the stereotype field
      would be null.
- [ ] Add test cases verifying `saf_create_element` for each newly added kind.
- [ ] Update README.md SAF Concept Map table to match the canonical list.
- [ ] Regenerate test expected values (`test_saf_tools_registered` tool count)
      to account for any new items.

## Reference

- `concepts.json` — `/workspace/paper-tdse-ai-saf/sources/SAF-Specification/src/_data/concepts.json`
- `realizeconcept.json` — `/workspace/paper-tdse-ai-saf/sources/SAF-Specification/src/_data/realizeconcept.json`
- Current CONCEPT_MAP — `scripts/saf_tools.groovy` lines 9-30
- Current README table — `README.md` lines 120-127
