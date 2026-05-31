package com.haarer.mcpserver.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class McpProtocolHandler {

    private static final String SERVER_NAME = "cameo-mcp-server";
    private static final String SERVER_VERSION = "1.0.0";
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final ObjectMapper mapper;
    private final McpSession.Manager sessionManager;

    public McpProtocolHandler(ObjectMapper mapper, McpSession.Manager sessionManager) {
        this.mapper = mapper;
        this.sessionManager = sessionManager;
    }

    public HandleResult handleRequest(String sessionId, McpSession session, JsonNode request) throws Exception {
        String method = request.has("method") ? request.get("method").asText() : null;
        Object id = nodeToValue(request.has("id") ? request.get("id") : null);

        if ("initialize".equals(method)) {
            return handleInitialize(request, id);
        }

        if (sessionId == null || session == null) {
            return HandleResult.error(this, id, -32001, "Session not found");
        }

        return switch (method) {
            case "tools/list" -> HandleResult.ok(handleToolsList(session, id));
            case "tools/call" -> handleToolsCall(session, request, id);
            case "resources/list" -> HandleResult.ok(handleResourcesList(session, id));
            case "resources/read" -> handleResourcesRead(session, request, id);
            case "prompts/list" -> HandleResult.ok(handlePromptsList(session, id));
            case "prompts/get" -> handlePromptsGet(session, request, id);
            case "ping" -> HandleResult.ok(buildSuccessResponse(id, mapper.createObjectNode()));
            default -> HandleResult.error(this, id, -32601, "Method not found: " + method);
        };
    }

    public HandleResult handleInitialize(JsonNode request, Object id) throws Exception {
        var sessionId = UUID.randomUUID().toString();
        sessionManager.create(sessionId);

        var initResult = mapper.createObjectNode();
        initResult.put("protocolVersion", PROTOCOL_VERSION);

        var serverNode = mapper.createObjectNode();
        serverNode.put("name", SERVER_NAME);
        serverNode.put("version", SERVER_VERSION);
        initResult.set("serverInfo", serverNode);

        var caps = mapper.createObjectNode();
        var toolsCaps = mapper.createObjectNode();
        toolsCaps.put("listChanged", false);
        caps.set("tools", toolsCaps);

        var resourcesCaps = mapper.createObjectNode();
        resourcesCaps.put("listChanged", false);
        resourcesCaps.put("subscribe", false);
        caps.set("resources", resourcesCaps);

        var promptsCaps = mapper.createObjectNode();
        promptsCaps.put("listChanged", false);
        caps.set("prompts", promptsCaps);

        initResult.set("capabilities", caps);

        var json = buildSuccessResponse(id, initResult);
        return HandleResult.ok(json, sessionId);
    }

    private String handleToolsList(McpSession session, Object id) throws Exception {
        var toolsArray = mapper.createArrayNode();
        for (var tool : session.getTools()) {
            var node = mapper.createObjectNode();
            node.put("name", tool.name());
            node.put("description", tool.description());
            var schema = mapper.createObjectNode();
            schema.put("type", "object");
            node.set("inputSchema", schema);
            toolsArray.add(node);
        }
        var result = mapper.createObjectNode();
        result.set("tools", toolsArray);
        return buildSuccessResponse(id, result);
    }

    private HandleResult handleToolsCall(McpSession session, JsonNode request, Object id) throws Exception {
        var params = request.has("params") ? request.get("params") : null;
        if (params == null) return HandleResult.error(this, id, -32602, "Missing params");

        String toolName = params.has("name") ? params.get("name").asText() : null;
        if (toolName == null) return HandleResult.error(this, id, -32602, "Missing tool name");

        var arguments = nodeToMap(params.has("arguments") ? params.get("arguments") : null);

        for (var tool : session.getTools()) {
            if (tool.name().equals(toolName)) {
                try {
                    var toolResult = tool.handler().call(arguments);
                    var contentArray = mapper.createArrayNode();
                    for (var tc : toolResult.content()) {
                        var textNode = mapper.createObjectNode();
                        textNode.put("type", "text");
                        textNode.put("text", tc.text());
                        contentArray.add(textNode);
                    }
                    var result = mapper.createObjectNode();
                    result.set("content", contentArray);
                    if (toolResult.isError()) result.put("isError", true);
                    return HandleResult.ok(buildSuccessResponse(id, result));
                } catch (Exception e) {
                    var contentArray = mapper.createArrayNode();
                    var textNode = mapper.createObjectNode();
                    textNode.put("type", "text");
                    textNode.put("text", "Error: " + e.getMessage());
                    contentArray.add(textNode);
                    var result = mapper.createObjectNode();
                    result.set("content", contentArray);
                    result.put("isError", true);
                    return HandleResult.ok(buildSuccessResponse(id, result));
                }
            }
        }
        return HandleResult.error(this, id, -32602, "Tool not found: " + toolName);
    }

    private String handleResourcesList(McpSession session, Object id) throws Exception {
        var resourcesArray = mapper.createArrayNode();
        for (var res : session.getResources()) {
            var node = mapper.createObjectNode();
            node.put("uri", res.uri());
            node.put("name", res.name());
            node.put("description", res.description());
            if (res.mimeType() != null) node.put("mimeType", res.mimeType());
            resourcesArray.add(node);
        }
        var result = mapper.createObjectNode();
        result.set("resources", resourcesArray);
        return buildSuccessResponse(id, result);
    }

    private HandleResult handleResourcesRead(McpSession session, JsonNode request, Object id) throws Exception {
        var params = request.has("params") ? request.get("params") : null;
        if (params == null) return HandleResult.error(this, id, -32602, "Missing params");
        String uri = params.has("uri") ? params.get("uri").asText() : null;
        if (uri == null) return HandleResult.error(this, id, -32602, "Missing uri");

        for (var res : session.getResources()) {
            if (res.uri().equals(uri)) {
                try {
                    var resultText = res.handler().read().text();
                    var contentsArray = mapper.createArrayNode();
                    var contentNode = mapper.createObjectNode();
                    contentNode.put("uri", uri);
                    contentNode.put("mimeType", res.mimeType());
                    contentNode.put("text", resultText);
                    contentsArray.add(contentNode);
                    var result = mapper.createObjectNode();
                    result.set("contents", contentsArray);
                    return HandleResult.ok(buildSuccessResponse(id, result));
                } catch (Exception e) {
                    return HandleResult.error(this, id, -32603, "Resource read error: " + e.getMessage());
                }
            }
        }
        return HandleResult.error(this, id, -32602, "Resource not found: " + uri);
    }

    private String handlePromptsList(McpSession session, Object id) throws Exception {
        var promptsArray = mapper.createArrayNode();
        for (var prompt : session.getPrompts()) {
            var node = mapper.createObjectNode();
            node.put("name", prompt.name());
            node.put("description", prompt.description());
            node.set("arguments", mapper.createArrayNode());
            promptsArray.add(node);
        }
        var result = mapper.createObjectNode();
        result.set("prompts", promptsArray);
        return buildSuccessResponse(id, result);
    }

    private HandleResult handlePromptsGet(McpSession session, JsonNode request, Object id) throws Exception {
        var params = request.has("params") ? request.get("params") : null;
        if (params == null) return HandleResult.error(this, id, -32602, "Missing params");
        String promptName = params.has("name") ? params.get("name").asText() : null;
        if (promptName == null) return HandleResult.error(this, id, -32602, "Missing prompt name");

        var arguments = nodeToMap(params.has("arguments") ? params.get("arguments") : null);

        for (var prompt : session.getPrompts()) {
            if (prompt.name().equals(promptName)) {
                try {
                    var promptResult = prompt.handler().call(arguments);
                    var messagesArray = mapper.createArrayNode();
                    for (var text : promptResult.messages()) {
                        var msgNode = mapper.createObjectNode();
                        msgNode.put("role", "user");
                        var contentNode = mapper.createObjectNode();
                        contentNode.put("type", "text");
                        contentNode.put("text", text);
                        msgNode.set("content", contentNode);
                        messagesArray.add(msgNode);
                    }
                    var result = mapper.createObjectNode();
                    result.set("messages", messagesArray);
                    return HandleResult.ok(buildSuccessResponse(id, result));
                } catch (Exception e) {
                    return HandleResult.error(this, id, -32603, "Prompt error: " + e.getMessage());
                }
            }
        }
        return HandleResult.error(this, id, -32602, "Prompt not found: " + promptName);
    }

    String buildSuccessResponse(Object id, ObjectNode result) throws Exception {
        var response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", valueToNode(id));
        response.set("result", result);
        return mapper.writeValueAsString(response);
    }

    String buildErrorResponse(Object id, int code, String message) throws Exception {
        var error = mapper.createObjectNode();
        error.put("code", code);
        error.put("message", message);
        var response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", valueToNode(id));
        response.set("error", error);
        return mapper.writeValueAsString(response);
    }

    private JsonNode valueToNode(Object value) {
        if (value == null) return mapper.getNodeFactory().nullNode();
        if (value instanceof Integer i) return mapper.getNodeFactory().numberNode(i);
        if (value instanceof Long l) return mapper.getNodeFactory().numberNode(l);
        if (value instanceof Number n) return mapper.getNodeFactory().numberNode(n.doubleValue());
        return mapper.getNodeFactory().textNode(value.toString());
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> nodeToMap(JsonNode node) {
        if (node == null || !node.isObject()) return Map.of();
        var map = new LinkedHashMap<String, Object>();
        node.fields().forEachRemaining(entry -> map.put(entry.getKey(), nodeToValue(entry.getValue())));
        return map;
    }

    static Object nodeToValue(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isTextual()) return node.asText();
        if (node.isInt()) return node.asInt();
        if (node.isLong()) return node.asLong();
        if (node.isDouble() || node.isFloat()) return node.asDouble();
        if (node.isBoolean()) return node.booleanValue();
        if (node.isArray()) {
            var list = new ArrayList<Object>();
            node.forEach(child -> list.add(nodeToValue(child)));
            return list;
        }
        if (node.isObject()) return nodeToMap(node);
        return node.asText();
    }

    public record HandleResult(
        boolean success,
        String json,
        String sessionId
    ) {
        public static HandleResult ok(String json) { return new HandleResult(true, json, null); }
        public static HandleResult ok(String json, String sessionId) { return new HandleResult(true, json, sessionId); }
        public static HandleResult error(McpProtocolHandler handler, Object id, int code, String message) {
            try {
                var json = handler.buildErrorResponse(id, code, message);
                return new HandleResult(false, json, null);
            } catch (Exception e) {
                return new HandleResult(false, "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":" + code + ",\"message\":\"error building response\"}}", null);
            }
        }
    }
}
