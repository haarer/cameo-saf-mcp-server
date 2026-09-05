# Cameo 2026x API Notes (session-verified cheat-sheet)

>"Session-verifiziertes Cheat-Sheet" (M5 in `docs/mcp-surface-review.md`): what
>actually resolves and works in this installed Cameo 2026x instance, verified in
>the running JVM — not from an LLM prior. Ranked source of truth after the live
>JVM and the `cameo-api_*` Javadoc index. Add to this file only what has been
>verified in a session; cite the session date.

## Verifying a signature: always the safe order
1. Live JVM: `plugincode_introspect(cls, ...)` — what *is*.
2. `cameo-api_*` Javadoc index — what the installed version *should* be.
3. This cheat-sheet — what has already worked here.
4. LLM prior — hypothesis only, never the basis for committed code.

## FQN gotcha: `StereotypesHelper` package (`com.nomagic.**uml2**`)
- **Correct (exists):** `com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper`
  - `getStereotypes(Element)` → `List<Stereotype>` (static).
  - Declared in `lib/core-*.jar`, resolved from the application classloader.
- **Wrong (does NOT exist):** `com.nomagic.magicdraw.uml2.ext.jmi.helpers.StereotypesHelper`
  — there is no `magicdraw` segment. Verified `plugincode_introspect` returns
  "Class not found in any loader" (2026-09). Trusting the wrong FQN cost two
  restart cycles I blamed on a classloader bug that was never there.

**Rule of thumb for FQNs:** the `ext.jmi.helpers` / `ext.jmi` packages live
under `com.nomagic.uml2...` (no `magicdraw.`). Framework classes under
`com.nomagic.magicdraw.uml2...` are a distinct, non-helper namespace. When in
doubt, check package via `plugincode_introspect` or a jar scan, then use the
FQN literally.

## Element methods on live objects (no import needed from Groovy)
- `element.getAppliedStereotype()` — returns the list of applied `Stereotype`s
  directly (singular name, returns a collection). This is on
  `mdkernel.Element`. Good for Groovy bodies that must avoid FQN imports.
  Note: `getStereotypeApplications()` does **not** exist on `ClassImpl`.

## Selection: `SelectionProvider.getSelectedElements()` returns VOLATILE OBJECTS (verified 2026-09)
- FQN: `com.nomagic.magicdraw.ui.SelectionProvider`; get an instance with
  `getInstance(project)`, then `getSelectedElements()` / `getMainElement()`.
- **Gotcha:** for canvas (diagram) selections the returned items are **diagram
  views** (`com.nomagic.magicdraw.uml.symbols.shapes.ClassView` and siblings),
  NOT model elements. A model element selected in the browser comes back as an
  Element; a block selected/rubber-banded on a diagram comes back as its view.
- Effects of not unwrapping: `getQualifiedName()` / `getOwner()` raise
  `MissingMethodException` (views have no such methods), so names/qualified
  names silently come out empty and the reported `getID()` is the **view ID**,
  not the element ID.
- **Fix:** detect and unwrap via the `ModelElementProvider` contract
  (`com.nomagic.magicdraw.uml.core.ModelElementProvider#getElement()`):
  ```groovy
  if (e instanceof com.nomagic.magicdraw.uml.core.ModelElementProvider) {
      def el = e.getElement()
      if (el != null) e = el
  }
  ```
  `PresentationElement` implements it, so every diagram view unwraps. Reference:
  `cameo://selection` resource (`scripts/context_resources.groovy`).
- `getMainElement()` is useless as a "primary/anchor" concept: null on
  multi-select, duplicate of the sole element on single-select — dropped from
  `cameo://selection` in favor of the full `selected_elements` list.
- Active diagram: `project.getActiveDiagram()`.

## Diagram class: `com.nomagic.magicdraw.uml.symbols.DiagramPresentationElement` (verified 2026-09)
- `project.getActiveDiagram()` and the elements resolved by diagram element-ID are of type
  `com.nomagic.magicdraw.uml.symbols.DiagramPresentationElement`.
- **Wrong FQNs** (plugincode_introspect: "Class not found in any loader"):
  `com.nomagic.magicdraw.core.diagram.Diagram`, `com.nomagic.magicdraw.diagram.Diagram`,
  `com.nomagic.magicdraw.core.diagram.PresentationElement`.
- **Correct method for "what is on this diagram":** `getUsedModelElements()` → the model
  elements directly (no view unwrap needed). Confirmed on the FFDS Context Definition BDD:
  42 elements incl. `SAF_ConceptualSystem` parts and environments.
- Wrong attempts on this class: `getPresentations()` / `getPresentationElements()` return
  nothing useful on `DiagramPresentationElement` (this is not a `PresentationElement`).
- **Rendering / meta accessors:** `getDiagramType()`, `getDiagramTypeAsString()`,
  `getName()`, `getElement()`, `getHumanType()`. See `cameo://diagram/{id}`
  (`scripts/model_info.groovy`).

## MCP resources: URI templates pass `{param}` to handlers (verified 2026-09)
- Core (`McpProtocolHandler`): `resources/read` first exact-matches, then splits URIs on
  `/` and captures `{name}` segments into a `Map<String,String>` handed to the Groovy
  handler method's single `Map` parameter. Implemented in jar (requires Cameo restart).
- Resource handler convention (Groovy): no-arg method = static resource
  (`cameo://project`, `cameo://projects`, `cameo://selection`); one `Map` param =
  parametrized (`cameo://project/{id}`, `cameo://project/{id}/packages`,
  `cameo://element/{id}`, `cameo://element/{id}/children`,
  `cameo://element/{id}/relationships`, `cameo://diagram/{id}`).
- Navigation grammar (agreed in grilling): fact → slice → deeper. Fact sheets carry
  counts/claims plus explicit slice URIs; `/children` is a compact id/name/kind/type
  list; `cameo://model/summary` and `cameo://requirements` were dropped.
- Element listings (uniform shape across `cameo://element/{id}/children`,
  `cameo://diagram/{id}`, `cameo://selection`): `{id, name, metaclass, type}` (+
  `qualifiedName`, `stereotypes` where relevant). Fact sheet (cameo://element/{id})
  adds taggedValues/documentation and `children`/`relationships` roll-ups
  (`count` + per-metaclass `byMetaclass` + slice `uri`). The faithful deep dump
  lives only in the `get_element_details` tool (still uses humanType).
- `metaclass` vs `type` (decided 2026-09, revisit later):
  - `metaclass` = structural identity, invariant: runtime Java class short name
    (Impl-stripped), equals the UML2 metaclass for standard kinds
    (`Class`/`Property`/`Connector`/`Interaction`/`Comment`), but `ElementTaggedValue`/
    `StringTaggedValue` are MagicDraw storage classes (UML2 has no `TaggedValue`
    metaclass), diagrams are `DiagramPresentationElement` (presentation layer —
    shows `"Diagram"`), shared/proxy elements may expose proxy class names.
  - `type` = semantic intent: **`elem.getHumanType()`** — MagicDraw's
    stereotype-resolved human label (the containment-tree label), equals the metaclass
    name when unstereotyped. Positively documented, NOT hand-rolled: ranking "the
    characterizing stereotype" among parallel helper stereotypes (e.g.
    `CustomImageHolder`, `HyperlinkOwner`) is hard — "most derived stereotype" is the
    easy case, parallel helpers are not. MagicDraw's profile mapping already resolves
    this (verified: `SAF_ConceptualSystem`, `Part Property`). `stereotypes[]` remains
    the full applied set. Reading contract: *"metaclass is what it is; type is what
    it means."*
  - Guard: `StereotypesHelper.getStereotypes` throws for `DiagramPresentationElement`
    — always guard it; diagram-id reads on `element/{id}` return `metaclass` `Diagram`,
    humanType like `SysML Block Definition Diagram`, name `""`, no stereotypes.
  - **Open concern (revisit)**: read→mirror→create coherence — an LLM that reads a
    fact sheet and then *mirrors* the content via `create_*` CRUD tools needs these
    to conceptually match the create API's `type` vocabulary. Today they diverge:
    `create_element` types are lowercase and lack e.g. `Interaction`, while `metaclass`
    yields `Interaction`. Align when the create tooling's type vocabulary is
    finalized.

## Validating rule authoring in-model (verified 2026-09)
A Constraint + `validationRule` stereotype body like this resolves and runs via
`modelcode_validation_eval` against 14 ports (13 pass, 1 fail — the untyped one):

```groovy
import com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper

def t = THIS.getType()
t != null && StereotypesHelper.getStereotypes(t).any {
    it.getName() == "SAF_PhysicalInterfaceDefinition"
}
```
`modelcode_validation_eval` bindings: `THIS` = target element, `project` =
active project, `result` = `groovy.lang.Reference` holder (`set`/`get`).
Boolean return = pass/fail.

## Recipe: authoring a Cameo validation rule (verified 2026-09)

A validation rule is a `Constraint` with the `validationRule` stereotype. Two
generic CRUD tools cover the metaclass-scope wiring (`constrainedElement`):
- `get_metaclass_by_name(name)` → resolves the UML2 metamodel metaclass
  (`Class`, `Property`, `Association`, …) via `StereotypesHelper.getUML2MetaClassByName`,
  returns its element `id` + qualified name.
- `set_constrained_element(constraintId, elementIds:[...])` → clears and sets the
  Constraint's `constrainedElement` reference list (multi-valued EMF feature; no
  generated setter — mutated in a session).

Workflow:

1. **Create the rule** (Constraint + `validationRule` stereotype + `abbreviation` /
   `errorMessage` / `severity` tags).
2. **Write the body** with `modelcode_spec_update(elementId, language='Groovy', body)`.
3. **Scope it** — the rule body runs on **every** element of the scoped metaclass, so:
   - `get_metaclass_by_name("Class"|"Property"|…)` → take `id`.
   - `set_constrained_element(constraintId, elementIds:[mcId])`.
4. **Gate the body on stereotypes** (mandatory): because the rule is called on every
   element of the metaclass, it must `return true` early for non-applicable elements.
   Detect applicability by stereotype on `THIS` (for Class-scoped rules) or on the
   owning classifier (`THIS.getOwner()` for Property-scoped rules). Example gates used
   for C1_SCXD:
   - Class-scoped subject (a `SAF_ConceptualContext` block): `if (!THIS.getAppliedStereotype()?.any{it.getName()=="SAF_ConceptualContext"}) return true`
   - Property-scoped subject (a context-element part):
     `def o=THIS.getOwner(); if(!(o?.getAppliedStereotype()?.any{it.getName()=="SAF_ConceptualContext"})) return true`
   - Per-part vs set-level decide the subject (see notes below).
5. **Debug** per-target with `modelcode_validation_eval` (green on applicable elements;
   green/`true` on non-applicable ones — the gate must prevent false positives).

Notes:
- Subject semantics: for per-part rules the offending element is the part (`Property`),
  for set-level rules (e.g. "exactly one SoI") the offending element is the whole block
  (`Class`). Pick the metaclass and the gate accordingly.
- `modelcode_validation_run` (the harness's real-engine route) still NPEs in
  `RuleSelector.getRelevantRules` (`filter` null) — a harness-side bug independent of
  scope. `modelcode_validation_eval` is the in-harness reference; the UI's native
  validation runs the scoped rule correctly.
