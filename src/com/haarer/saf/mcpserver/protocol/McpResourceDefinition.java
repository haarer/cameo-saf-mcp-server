package com.haarer.saf.mcpserver.protocol;

import java.util.List;
import java.util.Map;

public record McpResourceDefinition(
    String uri,
    String name,
    String description,
    String mimeType,
    ResourceHandler handler
) {
    @FunctionalInterface
    public interface ResourceHandler {
        ResourceResult read();
    }

    public record ResourceResult(
        String text
    ) {}
}
