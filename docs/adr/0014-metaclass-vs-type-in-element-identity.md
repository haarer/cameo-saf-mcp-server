# ADR-0014: Element Identity in Listings — `metaclass` (Structural) vs `type` (MagicDraw-Resolved Semantic Label)

## Status

Accepted

## Context

Earlier listings exposed a single `type` field populated from
`Element.getHumanType()`. For stereotyped elements this *sometimes* meant the
SAF stereotype (`SAF_ConceptualContext`), *sometimes* a formatted label
(`Part Property`), and *sometimes* the plain metaclass (`Property`,
`Association`) — i.e. undefined behavior from our side. An agent that reads a
listing and then mirrors the content via `create_*` tools cannot tell which
layer it is looking at, so read→mirror→create round-trips are ambiguous.

A first attempt replaced it with "first applied stereotype name". That is
deterministic but failed the semantic intent test: for `Meteorology System`
(SAF_ConceptualSystem) the head stereotype came out as `CustomImageHolder` —
a helper stereotype applied in parallel that does not characterize the element.
Ranking "the most important stereotype" by hand is hard: picking the *most
derived* stereotype (stereotype-generalization depth) is tractable, but
parallel helper stereotypes (`CustomImageHolder`, `HyperlinkOwner`) sit at the
same level and must be skipped — and the rule would be a hand-rolled priority
engine with a SAF-domain special case.

## Decision

Split structural from semantic identity, with *positively documented* semantics
for each:

1. **`metaclass` = structural identity, invariant.** The runtime Java class
   short name (Impl-stripped): equals the UML2 metaclass for standard kinds
   (`Class`, `Property`, `Connector`, `Interaction`, `Comment`). Known
   divergences accepted: `ElementTaggedValue`/`StringTaggedValue` are MagicDraw
   storage classes (UML2 has no `TaggedValue` metaclass); diagrams are
   presentation-layer (`DiagramPresentationElement`) and show `"Diagram"`;
   shared/proxy elements may expose proxy class names. This is what validation
   rule scoping and create factories key on.
2. **`type` = semantic intent.** `Element.getHumanType()` — the
   stereotype-resolved label MagicDraw already computes for the containment
   tree (`SAF_ConceptualSystem`, `Part Property`), equal to the metaclass name
   when unstereotyped. We do **not** hand-roll a stereotype-priority rule;
   instead we adopt and document MagicDraw's resolution. "Most derived
   stereotype" is the easy case; parallel helpers (`CustomImageHolder`,
   `HyperlinkOwner`) are not characterizing and are exactly what the profile
   mapping already handles.
3. **`stereotypes[]` remains the full applied set** everywhere; `type` alone is
   never a substitute for reading it.
4. **Guard:** `StereotypesHelper.getStereotypes` throws for
   `DiagramPresentationElement` — all stereotype reads are guarded; a
   diagram-id read on `element/{id}` yields `metaclass: "Diagram"`,
   `type` = diagram human type (`SysML Block Definition Diagram`), `name: ""`
   (not a `NamedElement`).

Reading contract: *metaclass is what it is; type is what it means.*

## Consequences

1. `type` labels match what human modelers see in the Cameo containment tree —
   agents share the human vocabulary.
2. `metaclass` gives the structural anchor for scoping and matching
   (`Class` vs `Property` no longer conflated with `SAF_ConceptualContext` vs
   `Part Property`).
3. `stereotypes[]` stays the authoritative full list; `type` is a convenience
   head, not a substitute.
4. **Open (revisit):** read→mirror→create coherence. An LLM mirroring a fact
   sheet via `create_*` needs the *create* side's `type` vocabulary to
   conceptually match the read side's `metaclass` vocabulary. They diverge
   today (`create_element` types are lowercase and lack e.g. `Interaction`,
   while `metaclass` yields `Interaction`). Revisit when the create tooling's
   type vocabulary is finalized — the read side stays clean regardless.

## Related

- ADR-0013 (resource navigation grammar, uniform listing shape).
- `docs/cameo-2026x-api-notes.md` § "MCP resources" (`metaclass` vs `type`).