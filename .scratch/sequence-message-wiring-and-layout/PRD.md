# PRD — Sequence message wiring and diagram layout tools

Feature slug: `sequence-message-wiring-and-layout`

Status: proposed

## Problem

SAF defines no sequence diagram on the physical level, so behavior such as a
failed OpenVPN connection is documented as a plain SysML/UML Sequence Diagram
under a SAF physical context. The MCP surface can currently:

- create the Interaction, Lifeline, and Message elements in the model
  (`create_element` types `interaction`, `lifeline`, `message`);
- create the Sequence Diagram view with the interaction frame and lifeline
  shapes (`saf_create_diagram`).

It cannot:

1. **Wire the message arrows.** Messages exist only as bare model elements.
   `saf_create_diagram`'s shape-add loop treats every presentation as a
   `ShapeElement`, but a message renders as a
   `com.nomagic.magicdraw.uml.symbols.paths.SeqMessageView` — hence
   `java.lang.ClassCastException: ... SeqMessageView cannot be cast to ...
   ShapeElement`. Nothing connects a message's send/receive ends to the two
   lifelines or draws the arrow on the view.
2. **Layout diagrams.** Diagrams are hand-positioned; nothing triggers the
   MagicDraw layout engine.

Constraint: implementation must be based on the documented API served by the
cameo-api Javadoc index (source of truth). Live-JVM introspection and internal
knowledge are not authoritative.

## Documented API basis

Verified against the cameo-api Javadoc index.

### Wiring (model level, fallback)

- `com.nomagic.uml2.impl.ElementsFactory#createMessageInstance()`
- `com.nomagic.uml2.ext.magicdraw.metadata.UMLFactory#createMessageOccurrenceSpecification()`
- `com.nomagic.magicdraw.openapi.uml.ModelElementsManager#addElement(Element, Element)`
- `Message#setSendEvent(MessageEnd)`, `setReceiveEvent(MessageEnd)`, `setMessageSort(MessageSort)`
- `MessageEnd#setMessage(Message)`
- `MessageSortEnum` literals (verified): `ASYNCHSIGNAL`, `ASYNCHCALL`, `SYNCHCALL`, `REPLY`, `CREATEMESSAGE`, `DELETEMESSAGE`
- `InteractionFragment#setEnclosingInteraction(Interaction)`

Note: `OccurrenceSpecification` only documents a read accessor `getCovered()`;
there is no documented `setCovered`. Prefer the presentation-level path below,
which wires ends as part of view creation.

### Drawing the arrow (presentation level — canonical OpenAPI path)

- `PresentationElementsManager#createSequenceMessage(Message message, MessageSort sort, ShapeElement from, ShapeElement to, boolean recursive, int diagonal, Message insertAfter, int verticalGap)` — "Creates a PathElement for given message between given client (from) and supplier (to) PresentationElements." This is the documented call that creates the `SeqMessageView` arrow between two lifeline heads and wires the message ends.
- Lifeline heads: `AbstractDiagramPresentationElement#findPresentationElement(Element, Class)` with `com.nomagic.magicdraw.uml.symbols.shapes.SequenceLifelineView` (documented `@OpenApi` class, "Creates lifeline 'head' in sequence diagram"). Diagram presentation from `Project#getDiagram(Diagram)`.
- `insertAfter` + `verticalGap` stack messages along a lifeline (pass the previously wired message).

### Layout

- `com.nomagic.magicdraw.uml.symbols.layout.Layouting#layout(AbstractDiagramPresentationElement diagram, String layouterID, AbstractDiagramLayouterOptionsGroup optionsGroup)`; class Javadoc example: `diagram.open(); Layouting.layout(diagram);`.
- Layouter ID fields (documented): `HIERARCHIC_DIAGRAM_LAYOUTER`, `ORDERED_HIERARCHIC_DIAGRAM_LAYOUTER`, `CLASS_DIAGRAM_LAYOUTER`, `COMPOSITE_DIAGRAM_LAYOUTER`, `GRID_DIAGRAM_LAYOUTER`, `TREE_DIAGRAM_LAYOUTER`, `ORTHOGONAL_DIAGRAM_LAYOUTER`, `ORGANIC_DIAGRAM_LAYOUTER`, `CIRCULAR_DIAGRAM_LAYOUTER`, `ACTIVITY_DIAGRAM_LAYOUTER`, `STATE_DIAGRAM_LAYOUTER`, `BUSINESS_PROCESS_DIAGRAM_LAYOUTER`, plus routers `ORTHOGONAL_DIAGRAM_ROUTER`, `ORGANIC_DIAGRAM_ROUTER`.
- Options group recipe from the `Layouting` Javadoc: `Application.getInstance().getEnvironmentOptions().getGroup(HierarchicLayouterOptionsGroup.ID)` cast to `com.nomagic.magicdraw.core.options.HierarchicLayouterOptionsGroup`.

## Tools

### 1. `diagram_add_sequence_message`

Wires one or more existing model `Message` elements onto a Sequence Diagram and
draws the arrows.

Arguments:

| name | type | required | description |
|---|---|---|---|
| `diagramId` | string | yes | ID of the Sequence Diagram view |
| `wiring` | array of object | yes | `{ messageId, from, to, sort?, insertAfter? }`; `sort` ∈ {asynchcall, synchcall, asynchsignal, reply, createmessage, deletemessage} (default asynchcall); `insertAfter` = messageId to stack this arrow below |
| `layout` | boolean | no | also run diagram layout after wiring (default true) |

Steps:

1. `project.getDiagram(diagramElement)`.
2. For each entry resolve lifeline head shapes via
   `diagram.findPresentationElement(lifelineElement, SequenceLifelineView.class)`.
3. Map `sort` → `MessageSortEnum` literal (documented `getByName`).
4. `PresentationElementsManager.createSequenceMessage(message, sort, from, to, false, 0, insertAfter, 0)`.
5. If `layout`, run `Layouting.layout(...)` (tool 2).

Fallback (only if a test shows `createSequenceMessage` leaves the ends unwired):
create two `MessageOccurrenceSpecification` via `UMLFactory`, add them into the
interaction with `ModelElementsManager.addElement`, then
`message.setSendEvent(end1)` / `setReceiveEvent(end2)` with
`end.setMessage(message)`.

### 2. `diagram_auto_layout`

Triggers the layout engine on any diagram.

Arguments:

| name | type | required | description |
|---|---|---|---|
| `diagramId` | string | yes | ID of any diagram view |
| `layouter` | string | no | documented ID; default by diagram type: BDD → HIERARCHIC_DIAGRAM_LAYOUTER, IBD → COMPOSITE_DIAGRAM_LAYOUTER, activity → ACTIVITY_DIAGRAM_LAYOUTER, state → STATE_DIAGRAM_LAYOUTER, else GRID_DIAGRAM_LAYOUTER |
| `orientation` | string | no | optional, applied via the options group |

Steps:

1. `diagram.open(false)`.
2. Resolve options group (`getEnvironmentOptions().getGroup(HierarchicLayouterOptionsGroup.ID)`); apply orientation when requested.
3. `Layouting.layout(diagram, layouterID, optionsGroup)`.
4. `diagram.close()`.

Sequence diagrams keep their own vertical-stack layout behavior; the layout
tool targets BDD/IBD/activity/state diagrams primarily.

### 3. `saf_create_diagram` fix (incidental)

Special-case the presentation-add loop: when the added element is a `Message`
inside a Sequence Diagram, do not attempt `createShapeElement` (the cause of the
`SeqMessageView` → `ShapeElement` cast error). Skip it (reporting that wires are
best added via `diagram_add_sequence_message`) or route through that tool.

## Validation

Re-run on the existing `Failed OpenVPN Connection` model in `NewSAFModel`
(interaction `_2026x_1_26f0132_1789802104272_530078_4322`; lifelines `..._548672_4323`,
`..._920944_4324`; 7 messages `..._395881_4325` … `..._387809_4340`): wire all 7
messages in order, auto-layout the BDD and the diagram, export PNGs, and inspect.

## Delivery order

1. `diagram_auto_layout` (tools 2)
2. `diagram_add_sequence_message` (tool 1)
3. `saf_create_diagram` cast fix