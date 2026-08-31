package com.haarer.saf.mcpserver;

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
            String host = System.getProperty("cameo.mcp.server.bind.host", "0.0.0.0");
            int port = Integer.parseInt(System.getProperty("cameo.mcp.server.port", "18750"));
            log("Cameo SAF MCP Server: Starting on " + host + ":" + port + " ...");
            server = new CameoMcpServer(host, port);
            log("Cameo SAF MCP Server: Started on " + host + ":" + port);
        } catch (Exception e) {
            logError("Failed to start: " + e.getMessage());
            e.printStackTrace(System.err);
        }
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
