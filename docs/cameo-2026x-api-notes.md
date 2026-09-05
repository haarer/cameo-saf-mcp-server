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
