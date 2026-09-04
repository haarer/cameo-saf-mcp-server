# ADR-0012: `modelcode_validation_eval` — Standalone Debug Harness for Validation Rules

## Status

Accepted

## Context

Authoring a validation rule stored as a Constraint body in the model needs a
fast iterate loop: write the Groovy body, run it against candidate elements,
see pass/fail per element, fix, repeat — all without opening the heavy
validation UI.

Two execution paths exist for such rules:

1. **The real validation engine** (`DefaultValidationRuleImpl`) — authoritative,
   produces violations with messages, but only when driven by MagicDraw's
   validation framework. Invoked standalone, it fails with an NPE
   (`RuleSelector.getRelevantRules` — `this.filter` is null) because the
   rule's `constrainedElementsFilter`/parent rule registration is absent
   outside the UI context.
2. **A lightweight GroovyShell** that executes the Constraint's spec body
   directly against target elements.

The two paths need distinct tools: one for authoritative verification, one for
fast authoring/debugging.

## Decision

Split the two concerns into two `modelcode_*` tools:

- **`modelcode_validation_run`** — the authoritative path through the real
  validation engine (returns violations + messages). Known limitation: can
  currently fail with the `RuleSelector.filter` NPE when a rule is not wired
  into a validation suite/profile; documented in `docs/mcp-surface-review.md`.
- **`modelcode_validation_eval`** — the debug path. Pre-compiles the rule's
  Groovy spec once, then evaluates it per target with validation-engine-style
  bindings:
  - `THIS` — the target element,
  - `project` — the active project,
  - `result` — a `groovy.lang.Reference` holder that supports `set`/`get`.
  The rule's Boolean return value is the pass/fail. Returns per-target
  pass/fail plus raw values and an error count. Supports explicit `targetIds`
  or scanning the primary model for a type-name substring.

### Classloader note (verified)

`modelcode_validation_eval` builds its shell as:

```groovy
def cc = new CompilerConfiguration()
def shell = new GroovyShell(new GroovyClassLoader(this.getClass().getClassLoader(), cc), new Binding())
```

This **resolves the full Cameo Open API** (`com.nomagic.*`, including
`com.nomagic.uml2.ext.jmi.helpers.StereotypesHelper`) because the script's own
`GroovyClassLoader` ultimately delegates to the Cameo application classloader.
A `getPluginClassLoader()` Java change was implemented to "fix" class loading,
then reverted as unnecessary — the original code works as-is. See ADR-0002's
correction.

## Consequences

1. Fast authoring loop: `modelcode_spec_update` to write → `modelcode_validation_eval`
   to iterate → `modelcode_validation_run` for authoritative sign-off.
2. The debug harness executes rules that make real `com.nomagic.*` API calls
   (e.g. `StereotypesHelper.getStereotypes(t)`), the same pattern used by
   shipped rules like `OpCapabilityComposition`.
3. The eval path does **not** require the validation engine's rule registration,
   so standalone rules can be iterated without a suite/profile.
4. `modelcode_validation_run` remains authoritative but is subject to the
   known engine NPE for unregistered rules.

## Related

- ADR-0011 (modelcode/plugincode namespacing).
- ADR-0002 (classloader correction).
- `docs/mcp-surface-review.md` § "Validierungs-Engine-Interna".
