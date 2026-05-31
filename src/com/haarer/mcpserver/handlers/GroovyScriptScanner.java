package com.haarer.mcpserver.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haarer.mcpserver.protocol.McpPromptDefinition;
import com.haarer.mcpserver.protocol.McpResourceDefinition;
import com.haarer.mcpserver.protocol.McpToolDefinition;

import groovy.lang.GroovyClassLoader;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class GroovyScriptScanner {

    private static final Logger LOG = Logger.getLogger(GroovyScriptScanner.class.getName());

    private final String scriptsDirPath;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, Long> fileCache = new ConcurrentHashMap<>();

    public GroovyScriptScanner(String scriptsDirPath, ObjectMapper mapper) {
        this.scriptsDirPath = scriptsDirPath;
        this.mapper = mapper;
    }

    public boolean hasChanges() {
        var dir = new File(scriptsDirPath);
        if (!dir.isDirectory()) return false;
        var files = dir.listFiles((d, n) -> n.endsWith(".groovy"));
        if (files == null) return false;
        for (var f : files) {
            var cached = fileCache.get(f.getAbsolutePath());
            if (cached == null || cached != f.lastModified()) {
                return true;
            }
        }
        return false;
    }

    public ScanResult scan() {
        var dir = new File(scriptsDirPath);
        if (!dir.isDirectory()) {
            LOG.warning("Scripts directory not found: " + scriptsDirPath);
            return new ScanResult(List.of(), List.of(), List.of(), Map.copyOf(fileCache));
        }

        var tools = new ArrayList<McpToolDefinition>();
        var resources = new ArrayList<McpResourceDefinition>();
        var prompts = new ArrayList<McpPromptDefinition>();
        var newCache = new ConcurrentHashMap<String, Long>();

        var files = dir.listFiles((d, n) -> n.endsWith(".groovy"));
        if (files == null) {
            return new ScanResult(List.of(), List.of(), List.of(), Map.copyOf(fileCache));
        }

        var gcl = new GroovyClassLoader(getClass().getClassLoader());
        for (var file : files) {
            newCache.put(file.getAbsolutePath(), file.lastModified());
            try {
                var clazz = gcl.parseClass(file);
                var instance = clazz.getDeclaredConstructor().newInstance();

                for (var method : clazz.getMethods()) {
                    buildTool(tools, instance, method);
                    buildResource(resources, instance, method);
                    buildPrompt(prompts, instance, method);
                }
            } catch (Exception e) {
                LOG.warning("Failed to load " + file.getName() + ": " + e.getMessage());
            }
        }

        fileCache.clear();
        fileCache.putAll(newCache);

        return new ScanResult(tools, resources, prompts, Map.copyOf(fileCache));
    }

    private void buildTool(List<McpToolDefinition> tools, Object instance, Method method) {
        var ann = method.getAnnotation(McpTool.class);
        if (ann == null) return;

        var handler = new McpToolDefinition.ToolHandler() {
            @Override
            public McpToolDefinition.ToolResult call(Map<String, Object> arguments) {
                try {
                    Object result;
                    if (method.getParameterCount() == 0) {
                        result = method.invoke(instance);
                    } else {
                        result = method.invoke(instance, arguments != null ? arguments : Map.of());
                    }
                    var text = serialize(result);
                    return new McpToolDefinition.ToolResult(
                        List.of(new McpToolDefinition.TextContent(text)),
                        false
                    );
                } catch (Exception e) {
                    var cause = e.getCause() != null ? e.getCause() : e;
                    return new McpToolDefinition.ToolResult(
                        List.of(new McpToolDefinition.TextContent("Error: " + cause.getMessage())),
                        true
                    );
                }
            }
        };

        tools.add(new McpToolDefinition(ann.name(), ann.description(), handler));
        LOG.info("Registered tool: " + ann.name());
    }

    private void buildResource(List<McpResourceDefinition> resources, Object instance, Method method) {
        var ann = method.getAnnotation(McpResource.class);
        if (ann == null) return;

        var handler = new McpResourceDefinition.ResourceHandler() {
            @Override
            public McpResourceDefinition.ResourceResult read() {
                try {
                    Object result;
                    if (method.getParameterCount() == 0) {
                        result = method.invoke(instance);
                    } else {
                        result = method.invoke(instance, Map.of());
                    }
                    var text = serialize(result);
                    return new McpResourceDefinition.ResourceResult(text);
                } catch (Exception e) {
                    var cause = e.getCause() != null ? e.getCause() : e;
                    throw new RuntimeException("Resource error: " + cause.getMessage(), cause);
                }
            }
        };

        resources.add(new McpResourceDefinition(ann.uri(), ann.name(), ann.description(), ann.mimeType(), handler));
        LOG.info("Registered resource: " + ann.name() + " [" + ann.uri() + "]");
    }

    private void buildPrompt(List<McpPromptDefinition> prompts, Object instance, Method method) {
        var ann = method.getAnnotation(McpPrompt.class);
        if (ann == null) return;

        var handler = new McpPromptDefinition.PromptHandler() {
            @Override
            public McpPromptDefinition.PromptResult call(Map<String, Object> arguments) {
                try {
                    Object result;
                    if (method.getParameterCount() == 0) {
                        result = method.invoke(instance);
                    } else {
                        result = method.invoke(instance, arguments != null ? arguments : Map.of());
                    }
                    var text = serialize(result);
                    return new McpPromptDefinition.PromptResult(List.of(text));
                } catch (Exception e) {
                    var cause = e.getCause() != null ? e.getCause() : e;
                    throw new RuntimeException("Prompt error: " + cause.getMessage(), cause);
                }
            }
        };

        prompts.add(new McpPromptDefinition(ann.name(), ann.description(), handler));
        LOG.info("Registered prompt: " + ann.name());
    }

    private String serialize(Object result) {
        if (result == null) return "";
        if (result instanceof Map || result instanceof Collection || result instanceof Object[]) {
            try {
                return mapper.writeValueAsString(result);
            } catch (Exception e) {
                LOG.warning("Failed to serialize result with Jackson: " + e.getMessage());
                return result.toString();
            }
        }
        return result.toString();
    }

    public record ScanResult(
        List<McpToolDefinition> tools,
        List<McpResourceDefinition> resources,
        List<McpPromptDefinition> prompts,
        Map<String, Long> fileCache
    ) {}
}
