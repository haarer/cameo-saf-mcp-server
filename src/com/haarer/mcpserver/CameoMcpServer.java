package com.haarer.mcpserver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haarer.mcpserver.handlers.GroovyScriptScanner;
import com.haarer.mcpserver.protocol.McpProtocolHandler;
import com.haarer.mcpserver.protocol.McpSession;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

public class CameoMcpServer {

    private static final Logger LOG = Logger.getLogger(CameoMcpServer.class.getName());
    private static final long HOT_RELOAD_INTERVAL_MS = 2000;

    private final McpSession.Manager sessionManager;
    private final McpProtocolHandler protocolHandler;
    private final StreamableMcpTransportProvider transportProvider;
    private final GroovyScriptScanner scanner;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread hotReloadThread;

    public CameoMcpServer(int port) throws IOException {
        var scriptsDir = determineDefaultScriptsDir();
        info("scripts dir: " + scriptsDir);

        sessionManager = new McpSession.Manager();

        var mapper = new ObjectMapper();
        protocolHandler = new McpProtocolHandler(mapper, sessionManager);

        transportProvider = new StreamableMcpTransportProvider(port, protocolHandler, sessionManager);

        scanner = new GroovyScriptScanner(scriptsDir);

        hotReloadThread = new Thread(this::hotReloadLoop, "mcp-hot-reload");
        hotReloadThread.setDaemon(true);
        hotReloadThread.start();

        reloadScripts();

        info("Cameo MCP Server started on port " + port);
    }

    public int getPort() {
        return transportProvider.getPort();
    }

    private String determineDefaultScriptsDir() {
        var propertyDir = System.getProperty("cameo.mcp.server.scripts.dir");
        if (propertyDir != null) return propertyDir;
        try {
            var jarPath = CameoMcpServerPlugin.class.getProtectionDomain()
                .getCodeSource().getLocation().getPath();
            var lastSlash = jarPath.lastIndexOf(File.separator);
            if (lastSlash != -1) {
                return jarPath.substring(0, lastSlash) + File.separator + "scripts";
            }
        } catch (Exception ignored) {}
        return "/workspace/cameo-mcp-server/scripts";
    }

    private static void info(String msg) {
        System.err.println("[CameoMcpServer] " + msg);
        LOG.info(msg);
    }

    private static void warn(String msg) {
        System.err.println("[CameoMcpServer] WARN: " + msg);
        LOG.warning(msg);
    }

    private void hotReloadLoop() {
        while (running.get()) {
            try {
                if (scanner.hasChanges()) {
                    reloadScripts();
                }
                Thread.sleep(HOT_RELOAD_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                warn("Hot-reload error: " + e.getMessage());
            }
        }
    }

    private synchronized void reloadScripts() {
        var result = scanner.scan();
        sessionManager.setLatestScan(result);
        for (var session : sessionManager.getSessions()) {
            session.syncFromScan(result);
        }
    }

    public void stop() {
        running.set(false);
        if (hotReloadThread.isAlive()) {
            hotReloadThread.interrupt();
            try {
                hotReloadThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        transportProvider.stop();
        LOG.info("Cameo MCP Server stopped");
    }
}
