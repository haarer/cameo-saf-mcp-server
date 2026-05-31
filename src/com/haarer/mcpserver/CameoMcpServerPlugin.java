package com.haarer.mcpserver;

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
            int port = Integer.parseInt(System.getProperty("cameo.mcp.server.port", "18750"));
            log("Cameo MCP Server: Starting on port " + port + " ...");
            server = new CameoMcpServer(port);
            log("Cameo MCP Server: Started on port " + port);
        } catch (Exception e) {
            logError("Failed to start: " + e.getMessage());
            e.printStackTrace(System.err);
        }
    }

    @Override
    public boolean close() {
        if (server != null) {
            server.stop();
            LOG.info("Cameo MCP Server: Stopped");
        }
        return true;
    }

    @Override
    public boolean isSupported() {
        return true;
    }
}
