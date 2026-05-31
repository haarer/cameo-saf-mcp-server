package com.haarer.mcpserver.protocol;

import java.util.List;
import java.util.Map;

public record McpPromptDefinition(
    String name,
    String description,
    PromptHandler handler
) {
    @FunctionalInterface
    public interface PromptHandler {
        PromptResult call(Map<String, Object> arguments);
    }

    public record PromptResult(
        List<String> messages
    ) {}
}
