# Changelog

All notable changes to this project are documented in this file, grouped by release.
Versions refer to git tags that define release packages.

## [Unreleased]

### Added

- Tool-surface administration: filtering which MCP tools are visible/callable (admin_set_enabled_tools, admin_get_enabled_tools, /admin web page, per-tool call telemetry) — iteration 7
- MCP resource cameo://saf-views listing SAF-viewpoint diagrams of the open model
- Resource endpoints for element and diagram (cameo://element/{id}, cameo://diagram/{id}), selection context resource

### Changed

- Tests run as separate agent jobs

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
