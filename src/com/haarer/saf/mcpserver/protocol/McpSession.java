package com.haarer.saf.mcpserver.protocol;

import com.haarer.saf.mcpserver.handlers.GroovyScriptScanner;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class McpSession {
    private final String id;
    private final List<McpToolDefinition> tools = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<McpResourceDefinition> resources = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<McpPromptDefinition> prompts = new java.util.concurrent.CopyOnWriteArrayList<>();

    public McpSession(String id) {
        this.id = id;
    }

    public String getId() { return id; }

    public void syncFromScan(GroovyScriptScanner.ScanResult result) {
        tools.clear();
        tools.addAll(result.tools());
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
