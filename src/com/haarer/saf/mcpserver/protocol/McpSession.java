package com.haarer.saf.mcpserver.protocol;

import com.haarer.saf.mcpserver.handlers.GroovyScriptScanner;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class McpSession {
    private final String id;
    private final List<McpToolDefinition> tools = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<McpResourceDefinition> resources = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<McpPromptDefinition> prompts = new java.util.concurrent.CopyOnWriteArrayList<>();

    private static volatile Set<String> enabledTools = null;
    private static final ConcurrentHashMap<String, AtomicLong> toolCallCounts = new ConcurrentHashMap<>();

    public static void setEnabledTools(Set<String> toolNames) {
        enabledTools = toolNames == null || toolNames.isEmpty() ? null : Set.copyOf(toolNames);
    }

    public static void clearEnabledTools() {
        enabledTools = null;
    }

    public static Set<String> getEnabledTools() {
        return enabledTools;
    }

    public static boolean isToolEnabled(String toolName) {
        if (enabledTools == null) return true;
        return enabledTools.contains(toolName);
    }

    public static void incrementToolCallCount(String toolName) {
        toolCallCounts.computeIfAbsent(toolName, k -> new AtomicLong(0)).incrementAndGet();
    }

    public static long getToolCallCount(String toolName) {
        var c = toolCallCounts.get(toolName);
        return c != null ? c.get() : 0;
    }

    public static Map<String, Long> getToolCallCounts() {
        var map = new java.util.LinkedHashMap<String, Long>();
        for (var entry : toolCallCounts.entrySet()) {
            map.put(entry.getKey(), entry.getValue().get());
        }
        return map;
    }

    public static void resetToolCallCounts() {
        toolCallCounts.clear();
    }

    public McpSession(String id) {
        this.id = id;
    }

    public String getId() { return id; }

    public void syncFromScan(GroovyScriptScanner.ScanResult result) {
        tools.clear();
        var allTools = result.tools();
        if (enabledTools != null) {
            for (var tool : allTools) {
                if (enabledTools.contains(tool.name())) {
                    tools.add(tool);
                }
            }
        } else {
            tools.addAll(allTools);
        }
        resources.clear();
        resources.addAll(result.resources());
        prompts.clear();
        prompts.addAll(result.prompts());
    }

    public List<McpToolDefinition> getTools() { return tools; }
    public List<McpResourceDefinition> getResources() { return resources; }
    public List<McpPromptDefinition> getPrompts() { return prompts; }

    public static class Manager {
        private final Map<String, McpSession> sessions = new ConcurrentHashMap<>();
        private volatile GroovyScriptScanner.ScanResult latestScan = new GroovyScriptScanner.ScanResult(List.of(), List.of(), List.of(), Map.of());

        public void setLatestScan(GroovyScriptScanner.ScanResult result) { this.latestScan = result; }
        public GroovyScriptScanner.ScanResult getLatestScan() { return latestScan; }

        public McpSession create(String id) {
            var session = new McpSession(id);
            session.syncFromScan(latestScan);
            sessions.put(id, session);
            return session;
        }
        public McpSession get(String id) { return sessions.get(id); }
        public void remove(String id) { sessions.remove(id); }
        public List<McpSession> getSessions() { return List.copyOf(sessions.values()); }
    }
}
