# Expose the viewpoint diagram recipe (Presentation) on the SAF tool path

Status: needs-triage

## Problem

Every SAF viewpoint carries a `Presentation` field that states what its
diagram should look like — diagram type plus what it features. It is the
authoritative recipe for drawing that diagram.

- 52 of 57 viewpoints have a non-empty `Presentation`
  (`_data/viewpoints.json`; the remaining 5 are genuinely empty, not missing data)

```
A2_ARAS  -> "A block definition diagram (BDD) featuring a
             claim-argument-evidence pattern (CAE)."
A2_TRMD  -> "A table format listing terms included in glossaries…"
```

It is reachable on the **spec** path only:

- `spec_get_viewpoint` returns `presentation` (scripts/saf_spec_tools.groovy:164) ✅

It is **not** reachable on the **SAF** path, which is the path diagram
authoring actually uses:

- `saf_get_element_semantics` returns
  `viewpoints: [{id, vpId, name}]` (scripts/saf_tools.groovy:1416) ❌
  — identity only, no recipe

## Impact

A model authoring a diagram through the `saf_*` tools can see *which*
viewpoints apply to an element but not *what those diagrams look like*. To
learn the recipe it must cross over to the spec tools, and it has to infer
that `spec_get_viewpoint` is the place to look.

This is the fallback path: build a base diagram from the recipe and place
elements on it. It is strictly weaker than passing `viewpoint=` to
`saf_create_diagram`, which acts on viewpoint semantics directly
(`exposes->concept->realizes->stereotype`) rather than on prose. So the
weak path is under-signposted, and the strong path is not always usable —
in which case the recipe is the only guide available.

The coaching prose in `saf_create_diagram` used to compensate for this by
spelling out the recipe inline. That prose has been removed (see the
tool-surface review), because it duplicated authoritative spec data and
polluted BM25 matching. That removal makes this gap matter more, not less.

## Proposal

Add the recipe to the `viewpoints[]` entries returned by
`saf_get_element_semantics`, so the SAF path carries it:

```
viewpoints: [{id, vpId, name, presentation}]
```

Open questions for triage:

1. **Cost.** `saf_get_element_semantics` accepts a batch of element IDs.
   Adding a prose field per viewpoint inflates a call that is already the
   widest read in the surface. May warrant a separate
   `spec`-side lookup rather than inline inclusion.
2. **Duplication.** `presentation` is a `List<String>`; some viewpoints
   list several presentation forms (e.g. a BDD *and* a table). Whether to
   return all or just the first needs a decision.
3. **Alternative.** Leave `saf_get_element_semantics` lean and instead
   point at `spec_get_viewpoint` from the viewpoint-taking tool
   descriptions. Cheaper, but leaves the model to make the cross-tool hop.

## References

- `scripts/saf_tools.groovy:1416` — the lean `viewpoints[]` return
- `scripts/saf_spec_tools.groovy:164` — where `presentation` is exposed today
- `_data/viewpoints.json` — 52/57 populated
