package com.haarer.saf.mcpserver;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.logging.Logger;

public class TokenManager {

    private static final Logger LOG = Logger.getLogger(TokenManager.class.getName());
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String TOKEN_FILE_NAME = ".token";

    private static final String AUTH_PREFIX = "Bearer ";

    private static TokenManager instance;
    private String token;
    private volatile boolean initialized = false;

    public static TokenManager getInstance() {
        if (instance == null) {
            synchronized (TokenManager.class) {
                if (instance == null) {
                    instance = new TokenManager();
                }
            }
        }
        return instance;
    }

    private TokenManager() {
    }

    /**
     * Loads the persisted token or generates a new one on first startup.
     * Never returns null/empty once initialized: a token always exists.
     */
    public String getToken() {
        ensureInitialized();
        return token;
    }

    /**
     * True iff the request carried a valid {@code Authorization: Bearer <token>} header.
     */
    public boolean isAuthorized(String authorizationHeader) {
        ensureInitialized();
        if (!isAuthEnabled()) {
            return true;
        }
        return authorizationHeader != null
            && authorizationHeader.startsWith(AUTH_PREFIX)
            && token.equals(authorizationHeader.substring(AUTH_PREFIX.length()).trim());
    }

    private boolean isAuthEnabled() {
        return !"false".equalsIgnoreCase(System.getProperty("cameo.mcp.server.auth", "true"));
    }

    private void ensureInitialized() {
        if (initialized) return;
        synchronized (this) {
            if (initialized) return;
            File configDir = getOrCreateConfigDir();
            File tokenFile = new File(configDir, TOKEN_FILE_NAME);
            try {
                if (tokenFile.exists() && tokenFile.canRead()) {
                    String existing = Files.readString(tokenFile.toPath()).trim();
                    if (!existing.isEmpty()) {
                        token = existing;
                        initialized = true;
                        info("Token loaded from " + tokenFile.getAbsolutePath());
                        return;
                    }
                }
                token = generateToken();
                writeToken(tokenFile);
                initialized = true;
                notifyGui("MCP server token generated (first start), copy it into your client config:");
                notifyGui("    " + token);
                notifyGui("Saved to " + tokenFile.getAbsolutePath());
            } catch (IOException e) {
                token = generateToken();
                initialized = true;
                warn("Could not persist token to " + tokenFile.getAbsolutePath()
                    + " (" + e.getMessage() + "); using in-memory token for this session.");
                notifyGui("MCP server token (NOT persisted), copy it into your client config:");
                notifyGui("    " + token);
            }
        }
    }

    private String generateToken() {
        byte[] randomBytes = new byte[16];
        RANDOM.nextBytes(randomBytes);
        return String.format("%032x", new java.math.BigInteger(1, randomBytes));
    }

    private void writeToken(File tokenFile) throws IOException {
        Files.writeString(tokenFile.toPath(), token + System.lineSeparator());
    }

    private File getOrCreateConfigDir() {
        String userHome = System.getProperty("user.home");
        File configDir = new File(userHome, ".config" + File.separator + "com.saf.mcpserver");
        if (!configDir.exists()) {
            try {
                configDir.mkdirs();
            } catch (SecurityException e) {
                warn("Cannot create config dir " + configDir.getAbsolutePath() + ": " + e.getMessage());
            }
        }
        return configDir;
    }

    private void notifyGui(String message) {
        try {
            com.nomagic.magicdraw.core.Application.getInstance().getGUILog().log("[MCP Server] " + message);
        } catch (Exception e) {
            // Best-effort; GUILog is not available headless
            info("[MCP Server] " + message);
        }
    }

    private void info(String msg) {
        System.err.println("[CameoMcpServer] " + msg);
        LOG.info(msg);
    }

    private void warn(String msg) {
        System.err.println("[CameoMcpServer] WARN: " + msg);
        LOG.warning(msg);
    }
}
