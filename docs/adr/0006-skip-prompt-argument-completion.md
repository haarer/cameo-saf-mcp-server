# ADR-0006: Skip Prompt Argument Completion

## Status

Accepted

## Context

The MCP protocol specification supports two features related to prompts:

1. **Prompt arguments** — typed parameters analogous to tool `inputSchema`, so MCP clients can display a form when a user selects a prompt template.
2. **`completion/complete`** — a JSON-RPC method that lets the MCP host request valid values for a prompt argument (e.g. if a prompt accepts an `elementId`, the host could ask for a list of valid element IDs).

The plan's Iteration 3 listed both of these alongside `@McpToolArgument` support, which was already implemented.

At the time of this decision, the project already supports:
- Fully typed tool arguments with JSON Schema (`@McpToolArgument`, `buildInputSchema()` in `GroovyScriptScanner`).
- The only prompt script (`hello_prompt.groovy`) is a static template with no arguments.

The primary interaction pattern for this project is **tool calls** from an AI agent, not prompt templates selected by a human in a UI.

## Decision

Do not implement `@McpPromptArgument`, prompt input schemas, or `completion/complete`.

## Consequences

1. **Lower spec compliance.** An MCP host that expects prompt argument metadata for a prompt-picker UI will see no arguments advertised. This does not affect any known client used with this project.

2. **Saved effort.** Implementing prompt arguments would require: a new annotation (`@McpPromptArgument`), an `inputSchema` field on `McpPromptDefinition`, scanner changes in `buildPrompt()`, and wiring `buildInputSchema()` for prompts. The `completion/complete` handler would require additional protocol routing in `McpProtocolHandler` and a way to resolve completions from the model. None of this provides value over the existing tool argument mechanism.

3. **Tool arguments remain the canonical interface.** Any interaction that could be a parameterized prompt is better expressed as a tool, since tools already have full argument schema, and agents invoke tools natively.
