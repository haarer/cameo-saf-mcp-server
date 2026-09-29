# Changelog

All notable changes to this project are documented in this file, grouped by release.
Versions refer to git tags that define release packages.

## [Unreleased]

### Added

- Tool-surface administration: filtering which MCP tools are visible/callable (admin_set_enabled_tools, admin_get_enabled_tools, /admin web page, per-tool call telemetry) — iteration 7
- `llm.ssl.ca` (configuration dialog: "Server certificate") points the console's HTTPS requests at a private or self-signed endpoint by trusting a PEM certificate (.pem/.crt) that the JVM does not already trust. The certificate is **added to** the system trust store rather than replacing it, so a public endpoint keeps working alongside a private one. A relative path resolves against the config directory, so the certificate can travel with `config.properties`. Unlike every other option, it is read once when the client is built, so changing it needs a restart of MagicDraw — a trust store is not a per-turn concern, and swapping the client mid-turn would drop its connection pool. A path that is missing, unreadable, or holds no X.509 certificate is logged and ignored, falling back to the default trust store: a bad certificate must not stop the console from starting
- MCP resource cameo://saf-views listing SAF-viewpoint diagrams of the open model
- Resource endpoints for element and diagram (cameo://element/{id}, cameo://diagram/{id}), selection context resource
- LLM chat console in the dockable MCP status window: OpenAI-compatible chat client (LlmChatClient) with live SSE streaming, non-SSE fallback, single-worker FIFO queueing, and execution-time conversation context; MCP tools are exposed to the model and executed in-process (streaming tool-call loop), with the per-turn tool set pruned by BM25 selection when the registered tool count is high; status line shows registered tool count, context size and cumulative token usage (via stream_options.include_usage). Endpoint and behavior configurable in config.properties (hot-reloadable): llm.url, llm.model, llm.key, llm.mcp.tools (comma-separated tool names exposed to the model), llm.tool.rounds (max tool-execution rounds per turn, default 50), llm.tool.threshold (minimum BM25 score for a tool to be presented, default 1.0), llm.tool.max (cap on presented tools, default 32; selection also engages above 25 registered tools), llm.context.turns (conversation entries kept as context, default 30); endpoint also via system property cameo.mcp.console.llm.url
- In-memory BM25 retrieval index (retrieval package): Lucene-backed Bm25Retriever resolved compileOnly from the MagicDraw classpath (Lucene 9.12), per-field query boosts, AND-across-query-terms / OR-across-fields semantics, add/upsert/remove/replace with in-place index updates, and thread-safe search over a volatile searcher snapshot — ranked text search over model elements (qualified names + documentation)
- BM25 tool selection for the LLM console (Bm25ToolSelector): per-turn retrieval over tool names and descriptions (name boost 3.0) prunes the tool array sent to the model; falls back to all tools when nothing matches the query, so selection can never starve the model of tools
- Optional LLM conversation log for the chat console: `llm.log=true` appends each turn to a file — every request body (with the tool array exactly as presented), the tool selection with its confidence, each round's streamed text with its chunk count, the response with `finish_reason`, reasoning and token usage, and every tool call with its raw arguments and result. Config keys `llm.log` (default false) and `llm.log.path` (default `llm-conversation.log` in the config dir), both re-read per turn. Logging failures are swallowed and never break the console
- Reasoning streamed separately as `delta.reasoning_content` (OpenRouter, DeepSeek, Qwen thinking models) is accumulated per round and recorded in both the stream entry and the response summary, instead of being lost among the raw deltas
- Tool selection is re-evaluated on every tool round, not once per user turn: the query grows with the original message, the model's own reasoning and text for the round, and the round's tool results. Two guards keep this from being a downgrade — a turn that fell back to presenting every tool stays open (a tool result's boilerplate, e.g. "has stereotype", otherwise pulls in stereotype-*editing* tools), and a narrowed round keeps half of the `llm.tool.max` budget for tools the previous round already presented, so nothing the model is mid-way through using disappears. Each round is reported separately in the transcript, with its carried count
- `gradle deploy` now ships the `scripts/` directory, so Groovy tool changes reach the deployed plugin
- Configuration dialog behind a hamburger button in the console's input row: one labelled field with a help line for every option the plugin reads — endpoint, model, API key (masked), the four limits, the MCP tool allow-list, the conversation log, and show tool calls. The dialog is built from a single catalog (`PluginConfig.options`) that the runtime reads its keys from, so an option cannot be editable without being read or readable without being editable. Saving validates per type and writes back to `config.properties`, keeping keys the catalog does not manage, so hand-written settings survive an edit in the dialog. Values are re-read per turn, so a change takes effect on the next message

### Changed

- The status line's tool-call count stood still for the whole LLM turn and only moved once something external called a tool. The console runs the same tools as an MCP client but invokes their handlers directly, bypassing `McpProtocolHandler`, which was the only place the counter was incremented; console tool calls are now counted as they execute. The token counters in the same line still advance only at the end of a round, because `stream_options.include_usage` reports usage when the response stream closes
- `llm.tool.bm25` (default true, in the configuration dialog as "BM25 tool selection") switches relevance-based tool narrowing off. Off means every registered tool is sent on every round, deliberately ignoring `llm.tool.max` and `llm.tool.threshold` — honouring either would defeat the point, since BM25 is the only thing that reads them. Useful to see what the model does with the full set, and to check whether narrowing is costing the right tool. The round is never narrowed, so the per-round re-evaluation keeps presenting everything too. Because the two dependent options stop applying, the dialog greys out their fields, labels and help text while the switch is off, live as you toggle it
- The configuration dialog's grey help text sat in its own panel below the form, so its lines were laid out on independent row heights and lined up with no field in particular. Because the MCP tool allow-list is a three-row text area, that one row was three times taller than the rest and every help line below it was offset by a growing amount. Each option is now one cell holding its field, its help line and its error line stacked together, which makes the alignment structural; the help is HTML so it wraps to its field's width
- The console no longer prints the per-round tool selection or its per-tool confidence: that is diagnostic detail and it now goes only to the conversation log (`llm.log`). The `[tool]` and `[result]` lines are unchanged and still follow the "Show tool calls" option
- Tests run as separate agent jobs
- `LlmChatClient.ToolSelection` carries the round it belongs to and how many of its tools were carried from the previous round
- The "Hide Tool Calls" checkbox in the console input row is gone, replaced by the "Show tool calls" option in the configuration dialog. It is now the `console.showToolCalls` key rather than an in-memory flag, so the setting survives a restart and can be set in the file, and the console picks up a change on the next message

### Fixed

- A tool call whose arguments were cut off mid-JSON ended the whole turn with `HTTP 500: Failed to parse tool call arguments as JSON`. A tool call's arguments arrive as a string assembled from streamed fragments, and when generation stops partway through — a token limit, a dropped connection — what is left is a prefix of valid JSON. Three things then went wrong at once: `parseArguments` swallowed the parse failure and passed the tool an empty map, so the tool complained about an argument that had in fact been sent (`{"error":"name is required"}` for a name that was right there in the truncated text); the malformed string was stored in the conversation history and sent back on the next request; and the endpoint, which re-parses the `tool_calls` it is given, rejected the whole request with a 500 — so one truncated argument killed the turn and everything after it. Well-formedness is now checked when the arguments are assembled. The tool is not run, and is told plainly that its arguments were cut off, nothing was changed, and to re-send more concisely; and the history entry carries valid JSON, so the next request is accepted and the model can recover on its own
- A turn that hit the tool-round cap ended in an error, discarding everything it had already done. The cap is checked *after* that round's tools have run and their results are in the model, so a long model-building turn could create dozens of elements and then report nothing but a failure: the work was in the diagram, the summary was not. Reaching the cap now makes one final request with no tools offered, so the model reports what it built and what is still missing, and the turn ends with that. If even that request fails, the text streamed so far is still delivered, with the reason. The message also never made clear that the cap counts *rounds* and not tool calls - one round can run several tools in parallel - so the footer now reports both ("reached after 76 tool calls"), and the default rises from 8, too small even for ordinary chat, to 50. The configuration dialog's help line no longer says the turn is abandoned
- BM25 retrieval matched stopwords: `Bm25Retriever` used `StandardAnalyzer`'s no-arg constructor, which in Lucene 9 uses an *empty* stop set, so "the", "of" and "a" were indexed and matched like any other term. Every English query therefore hit nearly every document on common words alone, putting a constant noise floor under all scores that a score threshold cannot remove. The index and query side now drop English **and** German function words — the element index covers model documentation, where a query of `"es"` or `"der die das und ist nicht"` otherwise matched German elements on grammar alone. StandardAnalyzer's tokenization is kept rather than `GermanAnalyzer`'s, so camelCase tool names and element identifiers are not stemmed
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
