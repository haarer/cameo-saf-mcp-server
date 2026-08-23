package com.haarer.saf.mcpserver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haarer.saf.mcpserver.protocol.McpProtocolHandler;
import com.haarer.saf.mcpserver.protocol.McpSession;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
    private final Map<String, SseClient> sseClients = new ConcurrentHashMap<>();
    private final ScheduledExecutorService keepaliveExecutor;

    public StreamableMcpTransportProvider(int port, McpProtocolHandler handler, McpSession.Manager sessionManager) throws IOException {
        this.port = port;
        this.handler = handler;
        this.sessionManager = sessionManager;
        this.httpServer = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        this.httpServer.setExecutor(Executors.newCachedThreadPool());
        httpServer.createContext("/", this::handleExchange);
        this.keepaliveExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "mcp-sse-keepalive");
            t.setDaemon(true);
            return t;
        });
        keepaliveExecutor.scheduleWithFixedDelay(this::sendKeepalives, 15, 15, TimeUnit.SECONDS);
        httpServer.start();
        info("HTTP server started on port " + port);
    }

    public int getPort() { return port; }

    public void stop() {
        keepaliveExecutor.shutdownNow();
        for (var client : List.copyOf(sseClients.values())) {
            removeClient(client);
        }
        httpServer.stop(2);
        info("HTTP server stopped");
    }

    /**
     * Sends a JSON-RPC notification to every connected SSE stream.
     * Returns the number of streams the frame was written to.
     */
    public int broadcastNotification(String method) {
        String json = "{\"jsonrpc\":\"2.0\",\"method\":\"" + method + "\"}";
        String frame = "event: message\ndata: " + json + "\n\n";
        int sent = 0;
        for (var client : sseClients.values()) {
            try {
                writeRaw(client, frame);
                sent++;
            } catch (Exception e) {
                warn("SSE write failed for " + client.key + ": " + e.getMessage());
                removeClient(client);
            }
        }
        return sent;
    }

    private void sendKeepalives() {
        for (var client : sseClients.values()) {
            try {
                writeRaw(client, ": ka " + ts() + "\n\n");
            } catch (Exception e) {
                removeClient(client);
            }
        }
    }

    private void writeRaw(SseClient client, String frame) throws IOException {
        synchronized (client) {
            client.out.write(frame.getBytes(StandardCharsets.UTF_8));
            client.out.flush();
        }
    }

    private void removeClient(SseClient client) {
        if (sseClients.remove(client.key) != null) {
            info("SSE stream closed: key=" + client.key + " (" + sseClients.size() + " still open)");
        }
        client.closed.countDown();
        try { client.exchange.close(); } catch (Exception ignored) {}
    }

    private void handleSse(HttpExchange exchange) throws IOException {
        String sessionId = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
        if (sessionId != null && sessionManager.get(sessionId) == null) {
            sendError(exchange, 404, "Session not found");
            return;
        }
        var headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/event-stream");
        headers.set("Cache-Control", "no-cache");
        headers.set("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, 0);
        var out = exchange.getResponseBody();
        var client = new SseClient(UUID.randomUUID().toString(), sessionId, exchange, out);
        sseClients.put(client.key, client);
        info("SSE stream opened: key=" + client.key + " session=" + sessionId
            + " (" + sseClients.size() + " open)");
        try {
            writeRaw(client, ": connected " + ts() + "\n\n");
        } catch (IOException e) {
            removeClient(client);
            return;
        }
        // Block this handler thread until the client is removed by another
        // thread (keepalive write failure, DELETE, or stop). GET request
        // bodies hit EOF immediately, so they are NOT a disconnect signal.
        try {
            client.closed.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void handleDelete(HttpExchange exchange) throws IOException {
        String sessionId = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
        if (sessionId != null) {
            sessionManager.remove(sessionId);
            for (var client : List.copyOf(sseClients.values())) {
                if (sessionId.equals(client.sessionId)) {
                    removeClient(client);
                }
            }
        }
        exchange.sendResponseHeaders(200, -1);
    }

    private void setCorsHeaders(HttpExchange exchange) {
        var headers = exchange.getResponseHeaders();
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
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
            var info = "{\"status\":\"ok\",\"server\":\"cameo-saf-mcp-server\",\"sessions\":"
                + sessionManager.getSessions().size()
                + ",\"sseStreams\":" + sseClients.size() + "}";
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            var bytes = info.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.getResponseBody().close();
            return;
        }

        boolean isMcpEndpoint = uri.equals("/mcp") || uri.equals("/mcp/");
        if (!isMcpEndpoint) {
            sendError(exchange, 404, "Not found");
            return;
        }

        if ("GET".equalsIgnoreCase(method)) {
            handleSse(exchange);
            return;
        }

        if ("DELETE".equalsIgnoreCase(method)) {
            handleDelete(exchange);
            return;
        }

        if (!"POST".equalsIgnoreCase(method)) {
            sendError(exchange, 405, "Method not allowed");
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

    private static final class SseClient {
        final String key;
        final String sessionId;
        final HttpExchange exchange;
        final OutputStream out;
        final CountDownLatch closed = new CountDownLatch(1);

        SseClient(String key, String sessionId, HttpExchange exchange, OutputStream out) {
            this.key = key;
            this.sessionId = sessionId;
            this.exchange = exchange;
            this.out = out;
        }
    }
}
