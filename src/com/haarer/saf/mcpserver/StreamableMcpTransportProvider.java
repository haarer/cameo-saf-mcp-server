package com.haarer.saf.mcpserver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haarer.saf.mcpserver.handlers.GroovyScriptScanner;
import com.haarer.saf.mcpserver.protocol.McpProtocolHandler;
import com.haarer.saf.mcpserver.protocol.McpSession;
import com.haarer.saf.mcpserver.protocol.McpToolDefinition;

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
    private final String host;
    private final int port;
    private final McpProtocolHandler handler;
    private final McpSession.Manager sessionManager;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, SseClient> sseClients = new ConcurrentHashMap<>();
    private final ScheduledExecutorService keepaliveExecutor;

    public StreamableMcpTransportProvider(String host, int port, McpProtocolHandler handler, McpSession.Manager sessionManager) throws IOException {
        this.host = host;
        this.port = port;
        this.handler = handler;
        this.sessionManager = sessionManager;
        this.httpServer = HttpServer.create(new InetSocketAddress(host, port), 0);
        this.httpServer.setExecutor(Executors.newCachedThreadPool());
        httpServer.createContext("/", this::handleExchange);
        this.keepaliveExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "mcp-sse-keepalive");
            t.setDaemon(true);
            return t;
        });
        keepaliveExecutor.scheduleWithFixedDelay(this::sendKeepalives, 15, 15, TimeUnit.SECONDS);
        httpServer.start();
        info("HTTP server started on " + host + ":" + port);
    }

    public int getPort() { return port; }

    public String getHost() { return host; }

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

        if ("GET".equalsIgnoreCase(method) && uri.equals("/admin")) {
            handleAdminPage(exchange);
            return;
        }

        if ("GET".equalsIgnoreCase(method) && uri.equals("/admin/api")) {
            handleAdminApi(exchange);
            return;
        }

        if ("POST".equalsIgnoreCase(method) && uri.equals("/admin/api/enable")) {
            handleAdminApiEnable(exchange);
            return;
        }

        if ("POST".equalsIgnoreCase(method) && uri.equals("/admin/api/disable")) {
            handleAdminApiDisable(exchange);
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

    private void handleAdminPage(HttpExchange exchange) throws IOException {
        var html = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>SAF MCP Server Admin</title>
<style>
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #0d1117; color: #c9d1d9; padding: 24px; }
  h1 { font-size: 20px; margin-bottom: 8px; color: #f0f6fc; }
  .subtitle { color: #8b949e; font-size: 13px; margin-bottom: 20px; }
  .tool-grid { display: flex; flex-direction: column; gap: 6px; }
  .tool-row { display: flex; align-items: center; gap: 12px; padding: 10px 14px; background: #161b22; border: 1px solid #30363d; border-radius: 6px; }
  .tool-row.enabled { border-color: #238636; }
  .tool-name { font-family: 'SFMono-Regular', Consolas, monospace; font-size: 13px; min-width: 260px; color: #f0f6fc; }
  .tool-desc { flex: 1; font-size: 12px; color: #8b949e; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .tool-stats { font-size: 12px; color: #8b949e; min-width: 80px; text-align: right; }
  .toggle { position: relative; width: 36px; height: 20px; flex-shrink: 0; }
  .toggle input { opacity: 0; width: 0; height: 0; }
  .toggle .slider { position: absolute; inset: 0; background: #30363d; border-radius: 20px; cursor: pointer; transition: .2s; }
  .toggle .slider::before { content: ''; position: absolute; width: 14px; height: 14px; left: 3px; bottom: 3px; background: #8b949e; border-radius: 50%; transition: .2s; }
  .toggle input:checked + .slider { background: #238636; }
  .toggle input:checked + .slider::before { background: #fff; transform: translateX(16px); }
  .actions { margin-top: 16px; display: flex; gap: 8px; }
  .btn { padding: 6px 16px; font-size: 13px; border: 1px solid #30363d; border-radius: 6px; cursor: pointer; background: #21262d; color: #c9d1d9; }
  .btn:hover { background: #30363d; }
  .btn.primary { background: #238636; border-color: #238636; color: #fff; }
  .btn.primary:hover { background: #2ea043; }
  .btn.danger { background: #da3633; border-color: #da3633; color: #fff; }
  .btn.danger:hover { background: #f85149; }
  #msg { margin-top: 8px; font-size: 12px; color: #8b949e; }
  .badge { font-size: 11px; padding: 2px 8px; border-radius: 10px; background: #21262d; }
  .badge.enabled { background: #238636; color: #fff; }
  .badge.disabled { background: #30363d; color: #8b949e; }
</style>
</head>
<body>
  <h1>SAF MCP Server — Tool Manager</h1>
  <p class="subtitle">Enable or disable individual MCP tools. Changes take effect immediately.</p>
  <div class="actions">
    <button class="btn primary" onclick="enableAll()">Enable All</button>
    <button class="btn danger" onclick="disableAll()">Disable All</button>
    <button class="btn" onclick="loadTools()">Refresh</button>
  </div>
  <div class="tool-grid" id="toolGrid"></div>
  <div id="msg"></div>
  <script>
    async function loadTools() {
      var r = await fetch('/admin/api');
      var data = await r.json();
      var grid = document.getElementById('toolGrid');
      grid.innerHTML = '';
      for (var t of data.tools) {
        var row = document.createElement('div');
        row.className = 'tool-row' + (t.enabled ? ' enabled' : '');
        row.innerHTML = `
          <label class="toggle">
            <input type="checkbox" ` + (t.enabled ? 'checked' : '') + ` onchange="toggleTool('` + t.name + `', this.checked)">
            <span class="slider"></span>
          </label>
          <span class="tool-name">` + t.name + `</span>
          <span class="badge ` + (t.enabled ? 'enabled' : 'disabled') + `">` + (t.enabled ? 'ON' : 'OFF') + `</span>
          <span class="tool-desc" title="` + (t.description || '').replace(/"/g, '&quot;') + `">` + (t.description || '') + `</span>
          <span class="tool-stats">` + t.calls + ` calls</span>
        `;
        grid.appendChild(row);
      }
    }

    async function toggleTool(name, enabled) {
      if (enabled) {
        await fetch('/admin/api/enable', { method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify({tools:[name]}) });
      } else {
        var r = await fetch('/admin/api', { method: 'GET' });
        var data = await r.json();
        var allEnabled = data.tools.filter(t => t.enabled && t.name !== name).map(t => t.name);
        await fetch('/admin/api/enable', { method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify({tools:allEnabled}) });
      }
      loadTools();
      msg('Updated');
    }

    async function enableAll() {
      await fetch('/admin/api/enable', { method: 'POST', headers: {'Content-Type':'application/json'}, body: '{}' });
      loadTools();
      msg('All tools enabled');
    }

    async function disableAll() {
      await fetch('/admin/api/enable', { method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify({tools:[]}) });
      loadTools();
      msg('All tools disabled');
    }

    function msg(text) { document.getElementById('msg').textContent = text; }
    loadTools();
  </script>
</body>
</html>
""";
        var bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().close();
    }

    private void handleAdminApi(HttpExchange exchange) throws IOException {
        var scanResult = sessionManager.getLatestScan();
        var allTools = scanResult != null ? scanResult.tools() : List.<McpToolDefinition>of();
        var enabledTools = McpSession.getEnabledTools();

        var toolsArray = mapper.createArrayNode();
        for (var tool : allTools) {
            var node = mapper.createObjectNode();
            node.put("name", tool.name());
            node.put("description", tool.description());
            node.put("enabled", enabledTools == null || enabledTools.contains(tool.name()));
            node.put("calls", McpSession.getToolCallCount(tool.name()));
            toolsArray.add(node);
        }

        var result = mapper.createObjectNode();
        result.set("tools", toolsArray);
        result.put("filterActive", enabledTools != null);
        if (enabledTools != null) {
            var enabledArray = mapper.createArrayNode();
            for (var t : enabledTools) enabledArray.add(t);
            result.set("enabledTools", enabledArray);
        }

        var json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        var bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().close();
    }

    private void handleAdminApiEnable(HttpExchange exchange) throws IOException {
        var body = exchange.getRequestBody().readAllBytes();
        var bodyStr = new String(body, StandardCharsets.UTF_8);
        var tree = mapper.readTree(bodyStr);

        if (tree.has("tools") && tree.get("tools").isArray() && tree.get("tools").size() > 0) {
            var set = new java.util.LinkedHashSet<String>();
            for (var t : tree.get("tools")) set.add(t.asText());
            McpSession.setEnabledTools(set);
        } else {
            McpSession.clearEnabledTools();
        }

        var result = mapper.createObjectNode();
        result.put("status", "ok");
        result.put("message", "Tools updated");
        var json = mapper.writeValueAsString(result);
        var bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private void handleAdminApiDisable(HttpExchange exchange) throws IOException {
        McpSession.clearEnabledTools();
        var result = mapper.createObjectNode();
        result.put("status", "ok");
        result.put("message", "All tools re-enabled");
        var json = mapper.writeValueAsString(result);
        var bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
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
