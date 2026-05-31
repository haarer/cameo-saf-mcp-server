package com.haarer.mcpserver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haarer.mcpserver.protocol.McpProtocolHandler;
import com.haarer.mcpserver.protocol.McpSession;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

public class StreamableMcpTransportProvider {

    private static final Logger LOG = Logger.getLogger(StreamableMcpTransportProvider.class.getName());
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private static String ts() { return LocalTime.now().format(TIME_FMT); }

    private static void trace(String msg) {
        System.err.println("[" + ts() + "] [McpTransport] " + msg);
        System.err.flush();
        LOG.fine(msg);
    }

    private static void info(String msg) {
        System.err.println("[" + ts() + "] [McpTransport] " + msg);
        System.err.flush();
        LOG.info(msg);
    }

    private static void warn(String msg) {
        System.err.println("[" + ts() + "] [McpTransport] WARN: " + msg);
        System.err.flush();
        LOG.warning(msg);
    }

    private final HttpServer httpServer;
    private final int port;
    private final McpProtocolHandler handler;
    private final McpSession.Manager sessionManager;
    private final ObjectMapper mapper = new ObjectMapper();

    public StreamableMcpTransportProvider(int port, McpProtocolHandler handler, McpSession.Manager sessionManager) throws IOException {
        this.port = port;
        this.handler = handler;
        this.sessionManager = sessionManager;
        this.httpServer = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        this.httpServer.setExecutor(Executors.newCachedThreadPool());
        httpServer.createContext("/", this::handleExchange);
        httpServer.start();
        info("HTTP server started on port " + port);
    }

    public int getPort() { return port; }

    public void stop() {
        httpServer.stop(2);
        info("HTTP server stopped");
    }

    private void setCorsHeaders(HttpExchange exchange) {
        var headers = exchange.getResponseHeaders();
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Methods", "POST, OPTIONS");
        headers.set("Access-Control-Allow-Headers", "Content-Type, Mcp-Session-Id");
    }

    private void handleExchange(HttpExchange exchange) throws IOException {
        var uri = exchange.getRequestURI().getPath();
        var method = exchange.getRequestMethod();
        trace(method + " " + uri);
        setCorsHeaders(exchange);

        if ("OPTIONS".equalsIgnoreCase(method)) {
            exchange.sendResponseHeaders(204, -1);
            exchange.getResponseBody().close();
            return;
        }

        if ("GET".equalsIgnoreCase(method) && (uri.equals("/") || uri.isEmpty())) {
            var info = "{\"status\":\"ok\",\"server\":\"cameo-mcp-server\",\"sessions\":" + sessionManager.getSessions().size() + "}";
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            var bytes = info.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.getResponseBody().close();
            return;
        }

        if (!"POST".equalsIgnoreCase(method)) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }

        if (!uri.equals("/mcp") && !uri.equals("/mcp/")) {
            sendError(exchange, 404, "Not found");
            return;
        }

        try {
            doPost(exchange);
        } catch (Exception e) {
            warn("doPost error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace(System.err);
            System.err.flush();
            if (!exchange.getResponseHeaders().containsKey("Content-Type")) {
                sendError(exchange, 500, "Internal error: " + e.getMessage());
            }
        }
    }

    private void doPost(HttpExchange exchange) throws IOException {
        byte[] bodyBytes;
        try {
            bodyBytes = exchange.getRequestBody().readAllBytes();
        } catch (IOException e) {
            sendError(exchange, 400, "Failed to read request body");
            return;
        }

        var bodyStr = new String(bodyBytes, StandardCharsets.UTF_8);
        trace("body=" + bodyStr);

        var tree = mapper.readTree(bodyStr);

        String sessionId = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
        McpSession session = (sessionId != null) ? sessionManager.get(sessionId) : null;

        McpProtocolHandler.HandleResult result;
        try {
            result = handler.handleRequest(sessionId, session, tree);
        } catch (Exception e) {
            warn("Protocol handler error: " + e.getMessage());
            sendError(exchange, 500, "Protocol error: " + e.getMessage());
            return;
        }

        var bytes = result.json().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");

        // If initialize created a new session, return the session ID
        if (result.sessionId() != null) {
            exchange.getResponseHeaders().set("Mcp-Session-Id", result.sessionId());
            sessionManager.create(result.sessionId());
        } else if (sessionId != null) {
            exchange.getResponseHeaders().set("Mcp-Session-Id", sessionId);
        }

        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().close();
        trace("response sent, " + bytes.length + " bytes");
    }

    private void sendError(HttpExchange exchange, int code, String message) throws IOException {
        var json = "{\"error\":\"" + message.replace("\"", "\\\"") + "\"}";
        var bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().close();
    }
}
