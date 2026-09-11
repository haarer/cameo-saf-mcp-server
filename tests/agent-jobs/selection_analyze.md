# Job: analyze the current Cameo selection

Use the cameo MCP server. Steps:

1. Read the resource `cameo://selection`.
2. If nothing is selected, say so and stop.
3. For each element in `selected_elements`, read its facts (name, type,
   qualifiedName) from the `cameo://element/{id}` resource. To interpret the
   selection in SAF terms, call `saf_get_element_semantics` once with
   `elementIds` set to all the ids (batch, one call) for kind/domain/viewpoints.
4. Produce a compact markdown report: one line per selected element with name,
   type, and qualifiedName. End with one sentence on what kind of SAF context
   the selection forms (conceptual context, environment, system, ...).

Report which MCP tools you used. Keep the whole reply under 400 words.