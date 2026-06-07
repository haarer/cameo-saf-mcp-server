package com.haarer.saf.mcpserver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haarer.saf.mcpserver.data.SafDataStore;
import com.haarer.saf.mcpserver.handlers.GroovyScriptScanner;
import com.haarer.saf.mcpserver.protocol.McpProtocolHandler;
import com.haarer.saf.mcpserver.protocol.McpSession;

import java.io.File;
import java.io.IOException;
import java.util.List;
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

        var dataDir = determineDefaultDataDir();
        info("SAF data dir: " + dataDir);
        if (dataDir != null) {
            SafDataStore.init(dataDir);
        } else {
            warn("No SAF data directory found - spec tools will be unavailable");
        }

        sessionManager = new McpSession.Manager();

        var mapper = new ObjectMapper();
        protocolHandler = new McpProtocolHandler(mapper, sessionManager);

        transportProvider = new StreamableMcpTransportProvider(port, protocolHandler, sessionManager);

        scanner = new GroovyScriptScanner(scriptsDir, mapper);

        hotReloadThread = new Thread(this::hotReloadLoop, "mcp-hot-reload");
        hotReloadThread.setDaemon(true);
        hotReloadThread.start();

        reloadScripts();
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
        return "/workspace/cameo-saf-mcp-server/scripts";
    }

    private String determineDefaultDataDir() {
        var sysProp = System.getProperty("cameo.mcp.server.data.dir");
        if (sysProp != null) {
            var f = new File(sysProp);
            if (f.exists()) return f.getAbsolutePath();
        }
        try {
            var jarPath = CameoMcpServerPlugin.class.getProtectionDomain()
                .getCodeSource().getLocation().getPath();
            var pluginDir = new File(jarPath).getParent();
            var dataDir = new File(pluginDir, "_data").getAbsolutePath();
            if (new File(dataDir).exists()) return dataDir;
        } catch (Exception ignored) {}
        for (var root : List.of("plugins/com.haarer.saf.mcpserver/_data",
                "/workspace/cameo-saf-mcp-server/_data",
                "../_data", "./_data", "_data", "scripts/../_data")) {
            var f = new File(root);
            if (f.exists()) return f.getAbsolutePath();
        }
        return null;
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
        SafDataStore.shutdown();
        transportProvider.stop();
        LOG.info("Cameo SAF MCP Server stopped");
    }
}
