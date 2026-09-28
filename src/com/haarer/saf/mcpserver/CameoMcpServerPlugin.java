package com.haarer.saf.mcpserver;

import com.nomagic.magicdraw.ui.ProjectWindowsManager;
import com.nomagic.magicdraw.plugins.Plugin;
import com.nomagic.magicdraw.core.Application;

import java.util.logging.Logger;

public class CameoMcpServerPlugin extends Plugin {

    private static final Logger LOG = Logger.getLogger(CameoMcpServerPlugin.class.getName());
    private CameoMcpServer server;

    private void log(String msg) {
        System.err.println("[CameoMcpServer] " + msg);
        LOG.info(msg);
        try {
            Application.getInstance().getGUILog().log(msg);
        } catch (Exception ignored) {}
    }

    private void logError(String msg) {
        System.err.println("[CameoMcpServer] ERROR: " + msg);
        LOG.severe(msg);
        try {
            Application.getInstance().getGUILog().showError(msg);
        } catch (Exception ignored) {}
    }

    @Override
    public void init() {
        try {
            String host = System.getProperty("cameo.mcp.server.bind.host", "127.0.0.1");
            int port = Integer.parseInt(System.getProperty("cameo.mcp.server.port", "18750"));
            log("Cameo SAF MCP Server: Starting on " + host + ":" + port + " ...");
            server = new CameoMcpServer(host, port);
            log("Cameo SAF MCP Server: Started on " + host + ":" + port);
        } catch (Exception e) {
            logError("Failed to start: " + e.getMessage());
            e.printStackTrace(System.err);
        }

        // The docked window is a nice-to-have: Cameo only calls configure()
        // when a project is opened *after* this registration, so a project
        // that was already open at startup would never get it and nothing
        // would be logged. The menu item is the reliable path.
        var configurator = new McpStatusWindow(() -> server);
        try {
            ProjectWindowsManager.ConfiguratorRegistry.addConfigurator(configurator);
            log("Registered MCP status window configurator");
        } catch (Throwable t) {
            // NoClassDefFoundError is an Error, not an Exception: a product
            // without this API must not be able to abort plugin startup.
            logError("Could not register the docked status window ("
                + t + "). Use Window > MCP Server Status to open it.");
        }

        installMenuItemWithRetry();
    }

    /**
     * Cameo builds its menu bar during startup, after plugins initialise, so
     * the menu may not exist on the first attempt. Poll briefly, then give up
     * rather than retrying forever.
     */
    private void installMenuItemWithRetry() {
        for (int attempt = 1; attempt <= 30; attempt++) {
            if (McpStatusWindow.installMenuItem(() -> server)) {
                log("Added Window > " + McpStatusWindow.WINDOW_NAME);
                return;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        logError("Could not add the Window > " + McpStatusWindow.WINDOW_NAME
            + " menu item; the menu bar was never available.");
    }

    @Override
    public boolean close() {
        if (server != null) {
            server.stop();
            LOG.info("Cameo SAF MCP Server: Stopped");
        }
        return true;
    }

    @Override
    public boolean isSupported() {
        return true;
    }
}
