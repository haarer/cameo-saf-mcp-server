package com.haarer.saf.mcpserver.protocol;

import java.util.List;
import java.util.Map;

public record McpToolDefinition(
    String name,
    String description,
    Map<String, Object> inputSchema,
    ToolHandler handler
) {
    @FunctionalInterface
    public interface ToolHandler {
        ToolResult call(Map<String, Object> arguments);
    }

    public record ToolResult(
        List<TextContent> content,
        boolean isError
    ) {}

    public record TextContent(
        String text
    ) {}
}
