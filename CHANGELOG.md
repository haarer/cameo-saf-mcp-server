# Changelog

All notable changes to this project are documented in this file, grouped by release.
Versions refer to git tags that define release packages.

## [Unreleased]

### Added

- Tool-surface administration: filtering which MCP tools are visible/callable (admin_set_enabled_tools, admin_get_enabled_tools, /admin web page, per-tool call telemetry) — iteration 7
- MCP resource cameo://saf-views listing SAF-viewpoint diagrams of the open model
- Resource endpoints for element and diagram (cameo://element/{id}, cameo://diagram/{id}), selection context resource
- LLM chat console in the dockable MCP status window: OpenAI-compatible chat client (LlmChatClient) with live SSE streaming, non-SSE fallback, single-worker FIFO queueing, and execution-time conversation context; MCP tools are exposed to the model and executed in-process (streaming tool-call loop), with the per-turn tool set pruned by BM25 selection when the registered tool count is high; "Hide Tool Calls" checkbox (default on) suppresses tool call/result lines; status line shows registered tool count, context size and cumulative token usage (via stream_options.include_usage). Endpoint and behavior configurable in config.properties (hot-reloadable): llm.url, llm.model, llm.key, llm.mcp.tools (comma-separated tool names exposed to the model), llm.tool.rounds (max tool-execution rounds per turn, default 8), llm.tool.threshold (minimum BM25 score for a tool to be presented, default 1.0), llm.tool.max (cap on presented tools, default 32; selection also engages above 25 registered tools), llm.context.turns (conversation entries kept as context, default 30); endpoint also via system property cameo.mcp.console.llm.url
- In-memory BM25 retrieval index (retrieval package): Lucene-backed Bm25Retriever resolved compileOnly from the MagicDraw classpath (Lucene 9.12), per-field query boosts, AND-across-query-terms / OR-across-fields semantics, add/upsert/remove/replace with in-place index updates, and thread-safe search over a volatile searcher snapshot — ranked text search over model elements (qualified names + documentation)
- BM25 tool selection for the LLM console (Bm25ToolSelector): per-turn retrieval over tool names and descriptions (name boost 3.0) prunes the tool array sent to the model; falls back to all tools when nothing matches the query, so selection can never starve the model of tools
- Optional LLM conversation log for the chat console: `llm.log=true` appends each turn to a file — every request body (with the tool array exactly as presented), the tool selection with its confidence, each round's streamed text with its chunk count, the response with `finish_reason`, reasoning and token usage, and every tool call with its raw arguments and result. Config keys `llm.log` (default false) and `llm.log.path` (default `llm-conversation.log` in the config dir), both re-read per turn. Logging failures are swallowed and never break the console
- Reasoning streamed separately as `delta.reasoning_content` (OpenRouter, DeepSeek, Qwen thinking models) is accumulated per round and recorded in both the stream entry and the response summary, instead of being lost among the raw deltas
- `gradle deploy` now ships the `scripts/` directory, so Groovy tool changes reach the deployed plugin

### Changed

- Tests run as separate agent jobs

### Fixed

- Tool selection never engaged: the selector's tool index was built only after the first turn, so the first (and in steady state every) turn sent all tools; the index is now rebuilt whenever the selector is created
- `saf_query_viewpoint` / `saf_export_viewpoint` returned wrong elements for a domain filter: the element match fell back to the generic SysML type of the SAF concept (usually "Class"), so every classifier in the model matched — operational elements and the whole UML metamodel came back for `domain=physical`. Matching now resolves each element's SAF kind from its applied stereotypes
- `saf_query_viewpoint` / `saf_export_viewpoint` / `saf_get_viewpoint_views` resolved domain and aspect against invented code tables (`am/ov/cv/pv`, `rq/st/pb/if/cx/tm`, "Operational View") that are not part of SAF. All three now resolve against the spec data itself — the domain and aspect vocabulary in `domains.json` / `aspects.json` (domain codes A, C, D, O, P; aspect ids 1-8) — and a viewpoint's grid cell is taken from its `VP_ID`, which encodes it (C1_SCXD is Conceptual x Context & Exchange)
- Domain/aspect input is a similarity search rather than an exact match, so a caller that does not know the exact SAF spelling still gets an answer: input is ranked against the vocabulary (code, name, snake_case, substring, token overlap, spelling distance) and the result reports the reading it took under `viewpoint.interpreted` with the ranked `domainCandidates` / `aspectCandidates`. Input matching no SAF value returns an error listing the available values and the grid cells in use
- `saf_query_viewpoint` now returns `{viewpoint, count, elements}` instead of a bare element list, so the resolved grid cell and any fuzzy reading travel with the result
- `saf_query_viewpoint` returned `[]` for valid domain/aspect pairs (e.g. `domain=physical, aspect=context`, the Physical Context viewpoint): the aspect filter was a hard-coded list of four operational/AM concept kinds, independent of the requested domain, so it intersected to nothing. The matching viewpoints' exposed concepts now decide the result
- `saf_export_viewpoint` returned every element in the model when the aspect filter produced no kinds (`if (relevantKinds.isEmpty()) return true`); it now shares the viewpoint resolution and reports the same error as `saf_query_viewpoint`
- A resolved cell whose viewpoints expose no usable concept kinds reported a blank label and the wrong reason: the message said concepts "map to no element kind" even when the viewpoint exposes none at all. The two cases are now distinguished and reported separately, the viewpoint is named when it has no `VP_ID` (one in the current SAF data does), and the resolved cell is attached to that error too
- The grid-cell listing in the "unknown domain/aspect" error mixed resolved cells (`C / Interface`) with the raw `Domain` slug of a viewpoint that has no `VP_ID` (`saf_development / Traceability & Mapping`); both now go through the same resolution

### Pending — iteration 8 (not yet implemented)

- SSE transport option (the SSE downstream notification channel on the existing transport is implemented — see ADR-0015; a standalone SSE transport remains an open decision), WebSocket transport option, notifications/initialized

## [v0.1.6] - 2026-09-01

### Added

- Admin model management tools: admin_load_model, admin_reset_model, admin_save_model, admin_create_model, admin_apply_profile

### Fixed

- Meta class resolution for SAF element derivation

## [v0.1.5] - 2026-09-01

### Added

- Diagram creation improvements (shape/symbol handling), configurable bind interface (cameo.mcp.server.bind.host), model save/open tools

## [v0.1.4] - 2026-08-23

### Changed

- Tool descriptions with expected value formats, case-insensitivity notes, constrained parameter values (iteration 5 P1)

### Added

- Structured error responses with hints, batch read tools (get_elements_details_batch, list_owned_elements, get_port_type_info)
- get_stereotype_tags, apply_stereotype, remove_stereotype; server-side spec filters (specLanguage, specTextContains)
- Admin surface for enabling and disabling tools

### Other

- MCP surface review document (docs/mcp-surface-review.md), several ADRs

## [v0.1.3] - 2026-06-10

### Added

- Iteration 5 Phase 3: stereotype catalog (list_model_stereotypes)

## [v0.1.2] - 2026-06-10

### Added

- Missing SAF spec data files (viewpoints, concepts, concerns, stakeholders, ...) and related ADRs

## [v0.1.1] - 2026-06-04

### Changed

- @McpToolArgument moved to method-level; tools expose typed inputSchema

## [v0.1.0] - 2026-06-02

### Added

- In-house MCP JSON-RPC 2.0 server plugin for Cameo Systems Modeler (no MCP SDK)
- Streamable HTTP transport on port 18750, session management via Mcp-Session-Id
- Groovy script scanner with ~2s hot-reload (@McpTool/@McpResource/@McpPrompt)
- Initial tools (echo, model info, logging demo) and Python integration tests
