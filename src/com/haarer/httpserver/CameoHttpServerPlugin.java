package com.haarer.httpserver;

import com.nomagic.magicdraw.plugins.Plugin;
import com.nomagic.magicdraw.core.Application;
import java.util.logging.Logger;

public class CameoHttpServerPlugin extends Plugin {
    private static final Logger LOG = Logger.getLogger(CameoHttpServerPlugin.class.getName());
    private CameoHttpServer server;

    @Override
    public void init() {
        int port = Integer.parseInt(System.getProperty("cameo.http.server.port", "18741"));
        try {
            server = new CameoHttpServer(port);
            server.start();
            Application.getInstance().getGUILog().log(
                "Cameo HTTP Server: Started on port " + port);
            LOG.info("Cameo HTTP Server: Started on port " + port);
        } catch (Exception e) {
            LOG.severe("Cameo HTTP Server: Failed to start: " + e.getMessage());
            Application.getInstance().getGUILog().showError(
                "Cameo HTTP Server: Failed to start: " + e.getMessage());
        }
    }

    @Override
    public boolean close() {
        if (server != null) {
            server.stop();
            LOG.info("Cameo HTTP Server: Stopped");
        }
        return true;
    }

    @Override
    public boolean isSupported() {
        return true;
    }
}
