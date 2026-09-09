# Cameo 2026x API Notes (session-verified cheat-sheet)

>Origin: the 2026-08-23 MCP surface review session retrospective (measure M5,
>the session cheat sheet; M1–M5 implemented per `AGENTS.md` "Groovy code
>authoring (MCP surface navigation)" steps 1-5). What actually resolves and
>works in this installed Cameo 2026x instance, verified in the running JVM —
>not from an LLM prior. Ranked source of truth after the live JVM and the
>`cameo-api_*` Javadoc index. Add to this file only what has been verified in
>a session; cite the session date.

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

## Multi-valued EMF features have no generated setter — use `eSet` (verified 2026-09)
- `OpaqueExpression.setLanguage(List)` **does not exist**: `language` (and
  `body`) are multi-valued EMF features, and EMF generates no setters for
  those. Calling `spec.setLanguage([...])` fails.
- **Pattern (used by `modelcode_spec_update`, `scripts/modelcode.groovy`):**
  mutate through `eSet` with `UMLPackage` literals:
  ```groovy
  def LIT = com.nomagic.uml2.ext.magicdraw.metadata.UMLPackage.Literals
  spec.eSet(LIT.OPAQUE_EXPRESSION__LANGUAGE, [language])
  spec.eSet(LIT.OPAQUE_EXPRESSION__BODY, [body])
  // OpaqueBehavior holders:
  holder.eSet(LIT.OPAQUE_BEHAVIOR__LANGUAGE, [language])
  holder.eSet(LIT.OPAQUE_BEHAVIOR__BODY, [body])
  ```
- The `LIT` literal FQN above is session-verified; do not guess a
  `com.nomagic.magicdraw...` variant.

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

## Merge/diff engine: CompareUtil + diff taxonomy (verified 2026-09, M2 spike)

**Compare call (the pattern that works):** `compareProjects(project, baseProjectDescriptor,
ErrorHandler, Optimization)` — **one side must be an OPEN Project**, the other a
`ProjectDescriptor`. Build the descriptor from a file with
`ProjectDescriptorsFactory.createProjectDescriptor(URI)` (also `(String)`) or from the
open project via `getDescriptorForProject(project).getURI()`. `ErrorHandler` is the
SAM-compatible interface `com.nomagic.utils.ErrorHandler<T>` (contract: `error(T)`),
coercible in Groovy as `[error:{ Exception e -> throw e }] as com.nomagic.utils.ErrorHandler`.

- `Optimization.values()` = `[PERFORMANCE, MEMORY]` (enum constants NOT in the Javadoc
  index — enumerate at runtime).
- Result `ProjectDifference`: `getChanges()` (both contributors vs ancestor),
  `getSourceChanges()`, `getTargetChanges()`. In a two-way compare `sourceCount` is often
  0 and all real changes land in `target`/plain `changes`.
- **`Optimization.PERFORMANCE` wraps the whole result in a `MacroChange`**
  (`com.nomagic.magicdraw.merge.macro.MacroChange`, impl `merge.macro.impl.j`). You MUST
  recursively call `MacroChange.getChanges()` (returns grouped `Change`s) — expansion is
  mandatory before classification. Without it you see exactly 1 opaque change.
- Every leaf is a `com.nomagic.magicdraw.merge.Change` (impl `merge.impl.i`) whose
  `getDifference()` returns a `com.nomagic.magicdraw.diff.Difference` implementation.

**Classify via `instanceof` on the PUBLIC taxonomy, never impl class names.** Impl
classes are obfuscated in this install (`com.nomagic.magicdraw.diff.impl.a.a.b`,
`diff.impl.a.j`, `merge.impl.i`, `merge.macro.impl.j`) — their names must never be used
in code, only the public interfaces:

- `ElementAddition` / `ElementDeletion` / `ElementModification` (all extend
  `ElementDifference` → `getElementID()`). Resolve back: `project.getElementByID(id)`.
- `ElementModification.getChangedPropertyName()` (`feature`), `getModificationInfo()` →
  `ModificationInfo` hierarchy: `ReferenceModificationInfo` (`getValue()`,
  `getValueSpecificationID()`), `ValueModificationInfo` (`getModificationKind()`,
  `getValue()`), `PrimitiveMultiValueModificationInfo` (`getIndex()`), `ChangeOwnerInfo`
  (owner-change), `GenericOrderModificationInfo`, `PrimitiveValueModificationInfo`,
  `ValueOrderModificationInfo`, `OrderModificationInfo`.
- **`ModificationInfo` carries the NEW value only — there is NO `oldValue`.** Before/after
  must be reconstructed by resolving the `from` state's element (see plans/01 M3 rules).
  `ModificationKind` = `{ADDED, REMOVED, ...}` — ADDED/REMOVED valid only on multi-valued
  props.
- Other buckets that showed up on a real SAF_FFDS diff: `StereotypeModification`,
  `TagValueModification`, `DiagramDifference`, `DomainSpecificCustomizationDifference`,
  `ShareDifference`, `ModuleUsageDifference`, `ProjectOptionsDifference`.
- **Presentation/symbol layer:** `com.nomagic.magicdraw.diff.symbols.*`
  (`MultiplePersistentPropertyDifference` — the impl class was the biggest single bucket,
  3910/4404 on the real FFDS diff — plus `SymbolAddition/Deletion`, `SymbolDifference`,
  `SymbolPersistentPropertyDifference`, `PersistentPropertyOrderDifference`). These are
  diagram/shape snapshot diffs, NOT semantic model changes. The M3 extractor must filter
  or coalesce them, never emit them as first-class rows.
- `Change` also exposes `getConflicts()`, `getRequired()`, `getDependent()` (dependency
  sets — respected by the merge engine; MVP ignores them but they exist for cascade work).
- **Teardown:** after a compare, `CompareUtil.restore(projectDifference)` disposes it;
  close any temp-loaded project with `ProjectsManager.closeProject(project)`. Verified:
  `restore` + `closeProject` both succeed after the compare.
- **Negative self-diff is NOT guaranteed fully empty:** comparing the open model against
  a descriptor of the SAME file returned 1 `DomainSpecificCustomizationDifference` and 0
  element changes (in-memory DSL/project state vs saved file). Identical path comparison
  (file vs byte-identical file) is the reliable empty case.
- **Dead end (plan M2 step 4):** `DiffProvider.getInstance().getDiffForChangedElements(...)`
  and `MetaClassDiffProvider` DO NOT EXIST in this install. Semantic-diff must be built on
  the `ProjectDifference.getChanges()` → `Change.getDifference()` taxonomy; no shortcut.
- **File-to-file compare limitation:** temp-loading the `to` file via `loadProject(...)`
  while the base is another file NPEs inside the engine (`Project.getLoadedFrom()` null,
  `merge.aa.a:396`) when the temp project's modules are already attached to the open
  project. **Working pattern today: exactly one side open as a Project, other side as a
  `ProjectDescriptor`** (the engine loads the descriptor side internally). Both
  orientations (`from=open→to=file` and `from=file→to=open`) were verified. Notes for a
  file-to-file route in M4: compare after closing the unrelated open project, or accept
  the open-side-as-project constraint.
- **Reading arbitrary feature values for before/after (JMI reflection):** every model
  element is a `javax.jmi.reflect.RefObject` (verified: `ModelObject` interfaces =
  `[com.nomagic.magicdraw.foundation.MDObject, javax.jmi.reflect.RefObject,
  com.dassault_systemes.modeler.foundation.model.ModelElement]`). Feature access is on
  the parent `RefFeatured`: **`refGetValue(String featureName)`** (NOT
  `getValue(...)` — `getValue(String)` does not exist and throws
  `MissingMethodException`; hair was lost). Verified 2026-09 introspecting
  `javax.jmi.reflect.RefFeatured`: `refGetValue(String)`, `refGetValue(RefObject)`,
  `refSetValue(String,Object)`, `refSetValue(RefObject,Object)`, `refInvokeOperation`.
  This is the basis for M3's before/after reconstruction — always resolve by stable
  element ID (`project.getElementByID`) against BOTH sides, since element IDs are
  project-stable across snapshots.
- **Connector → ends → owning connector (M3):** `ConnectorEnd` lives at
  `com.nomagic.uml2.ext.magicdraw.compositestructures.mdinternalstructures.ConnectorEnd`
  (NOT `...classes.mdkernel` — FQN verification fails there). Declares `getRole()`,
  `getPartWithPort()`, `getDefiningEnd()` and `get_connectorOfEnd()` (owning Connector).
  `get_connectorOfEnd()` is the authoritative reverse link for reading a connector's
  endpoint-pair in REL_CHANGED reconstruction; fallback owner-walk retained for safety.
- **Self-opening a file: side (M3):** `pm.loadProject(ProjectDescriptor, boolean silent)`
  opens a project by file URI without disturbing the active project; `pm.closeProject(p)`
  tears it down. m3_extract uses this so only ONE side needs to be open in Cameo — the
  other `file:` side is opened for the duration of the run and closed in `finally`
  (verified `selfOpenedClosed:1`, active project untouched). Note the file-to-file NPE
  above still applies — this is single-file-side opening, not both-files.
