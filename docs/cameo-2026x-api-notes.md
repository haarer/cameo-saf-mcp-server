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
