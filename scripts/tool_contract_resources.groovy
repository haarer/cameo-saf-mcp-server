import com.haarer.saf.mcpserver.handlers.McpResource

class ToolContractResources {

    @McpResource(
        uri = "cameo://tool/diff",
        name = "diff tool deep contract",
        description = "Full contract behind the diff tool: row kinds and per-row fields (element rows vs REL_CHANGED endpoint-pair rows), result/error payload shapes, the complete error-code list, location-spec rules, and the scope iteration recipe. The inline tool card guarantees correct calls; read this when you need to interpret output rows or error codes.",
        mimeType = "text/markdown"
    )
    String diffContract() {
        return '''# diff - deep contract

## Location specs
- `open_model` - the currently active project (never written to).
- `file:<host absolute path to .mdzip>` - a snapshot on the HOST machine running Cameo. If not already open it is self-opened, compared, then closed; the previously active project is restored (facts.diffRestored).

Hard rules:
- Exactly one side must be `open_model`.
- Both sides `open_model` resolve to the same active project, so the identical-inputs fast-path applies (`rows: []`, facts.identicalInputs). The two-`file:` case does the same via canonical-path equality.
- `open_model` vs `open_model` with a different meaning (two distinct projects) is not representable: there is only ever one active project in this server.
- Identical inputs (from == to, or two file: sides resolving to the same canonical path) fast-path to `{ rows: [] }` with zero stats and facts.identicalInputs = true.
- The compare is READ-ONLY (CompareUtil.compareProjects + restore). Nothing is written to either model.

## Result shape
`{ rows, stats, facts }`
- facts: from, to, scope, active (project name), fromProj/toProj (name @ path), diffRestored, scopeSubtreeSize (when scope set), identicalInputs (fast path), selfOpenedClosed (how many file: sides we opened and closed).
- stats: { changeCount, symbolChanges, expandedMacros, byKind } where byKind counts the RETURNED rows per kind (REL_CHANGED, MODIFIED, DELETED, ADDED). Symbol/presentation-layer changes are counted, never listed as rows.
- rows: flattened array ordered REL_CHANGED, MODIFIED, DELETED, ADDED.

## Row kinds and fields
Element rows (ADDED / MODIFIED / DELETED):
`{ kind, element: { id, name, qualifiedName, metaclass, stereotypes[], safKind, safDomain, parentId }, ... }`
- MODIFIED adds: feature (changed property name), modificationType (reference | owner | multi | primitive | value | ...), before (raw string), after (raw string).
- ADDED / DELETED resolve identity against the to/from side respectively; identity is `(unresolved)` when the id cannot be found.

Relation rows (REL_CHANGED):
`{ kind: 'REL_CHANGED', status: 'ADDED'|'DELETED'|'MODIFIED', source: {id,name,...}, relation: human label (connects, derives from, ...), elementHint: {id,name,metaclass, via:'climb'} (owner connector, if climbed from a ConnectorEnd), before/after: [ endpoint... ] }`
- endpoint = `{ roleName, roleId, portKind, partWithPort, partTypeName, partId }`.
- A connector/connector-end change is resolved to its owning connector and its two ends; before/after hold the endpoint pools on each side.
- endError is set (e.g. "ReadOnlyElementException: ...") if an end could not be reconstructed.

Unlisted buckets (STEREOTYPE, TAGVALUE, DIAGRAM, DSL_CUSTOMIZATION, SHARE, MODULE_USAGE, PROJECT_OPTIONS, SYMBOL, OTHER) are never surfaced as rows.

## Error payload
`{ error: 'diff_error', code, message, facts }` with code in:
INVALID_LOCATION | TWC_NOT_YET | FILE_MISSING | AMBIGUOUS | RESOLVE_FAILED | OPEN_FAILED | SCOPE_NOT_FOUND | COMPARE_FAILED

## scope (decision A6)
Pass the element/package/subtree-root id that matches your concern; only rows whose referenced element/role/part id lies inside that subtree are kept. Unknown scope id -> SCOPE_NOT_FOUND. Recommended iteration: run the full diff once, inspect the rows, then tighten scope on a subtree and rerun.

## Worked scope recipe
1. `diff(from, to)` full.
2. Pick the subtree root from a row element's id/qualifiedName (or a REL_CHANGED source id).
3. `diff(from, to, scope=<that id>)` - stats.byKind now reflect the tightened set.

## Gotchas
- file: paths are HOST paths. The container /workspace maps to the host working dir; pass the host-form path (e.g. /home/mac/oc3/workspace/...).
- Reopening an already-loaded file path reuses the open (possibly degraded) instance; for repeatable comparisons use pristine per-scenario snapshot files.
- Large models produce many rows; prefer scope to tighten, and rely on stats for noise counts.'''
    }

    @McpResource(
        uri = "cameo://tool/stereotype-search",
        name = "Stereotype and element search guide",
        description = "Shared deep contract for the three search tools (find_elements, find_elements_by_type, saf_find_elements_by_type): SAF stereotype naming convention (SAF_<Domain><ViewpointCode>_<ConceptCode>), common SysML type-name filters, worked examples, and which of the three tools to reach for.",
        mimeType = "text/markdown"
    )
    String stereotypeSearchGuide() {
        return '''# Stereotype and element search guide

Shared contract for `find_elements`, `find_elements_by_type` and `saf_find_elements_by_type`. All three filter by CASE-INSENSITIVE substring on element name / stereotype name / type name; empty filters match everything (all filters optional).

## Stereotype naming
SAF stereotypes follow `SAF_<Domain><ViewpointCode>_<ConceptCode>` (e.g. SAF_C1_SCXD, SAF_O2_OPFR).
Common prefixes: SAF_ (all SAF stereotypes), SAF_C (conceptual domain), SAF_O (operational domain), SAF_P (physical domain).
Use full stereotype names with the SAF_ prefix (e.g. 'SAF_ConceptualSystem') - never bare concept kinds (e.g. 'conceptual_system').
Authoritative registry of available stereotypes: spec_list_stereotypes.

## Common SysML / UML type-name filters
Class, Package, Activity, ProxyPort, Interface, Connector, DataType, Constraint, ValueType, UseCase.

## Worked examples
- name='FFDS' -> "FFDS Context", "Fire Department FFDS"
- name='Fire' -> "Fire Department", "Fire Chief"
- stereotype='SAF_ConceptualSystem' -> all conceptual systems
- stereotype='SAF_C1_' -> every C1_OSTY viewpoint concept
- stereotype='SAF_' -> any element carrying a SAF stereotype
- type='Class', stereotype='SAF_' -> all SAF-stereotyped classes
- parentId='<pkg-id>', stereotype='SAF_SystemRequirement' -> requirements in that package

## Which tool to reach for
- `find_elements`: quick discovery over the whole model (incl. used projects/modules); plain fields (id, name, qualifiedName, type, stereotypes, owning project).
- `find_elements_by_type`: same engine plus scope='primary' (primary model only), owning-project and writability in results, specLanguage/specTextContains to find Constraint rule bodies.
- `saf_find_elements_by_type`: SAF-enriched results (safKind, safDomain) - prefer when querying SAF models or when you need element IDs for saf_get_element_details / saf_create_relationship.'''
    }
}