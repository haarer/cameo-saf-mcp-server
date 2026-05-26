package com.haarer.httpserver;

import com.haarer.httpserver.handlers.LogRequestHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

public class CameoHttpServer {
    private final HttpServer server;

    public CameoHttpServer(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        registerHandlers();
    }

    private void registerHandlers() {
        // Initial iteration: all requests are logged
        server.createContext("/", new LogRequestHandler());
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(2);
    }

    public int getPort() {
        return server.getAddress().getPort();
    }
}
