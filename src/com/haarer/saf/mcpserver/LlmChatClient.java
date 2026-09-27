package com.haarer.saf.mcpserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.logging.Logger;

/**
 * Minimal OpenAI-compatible chat client backing the status-window console.
 *
 * <p>Endpoint resolution (first match wins):
 * <ol>
 *   <li>system property {@code cameo.mcp.console.llm.url}</li>
 *   <li>{@code llm.url} in {@code config.properties} inside the plugin config
 *       directory (same directory as the MCP server token,
 *       {@code ~/.config/com.saf.mcpserver/})</li>
 *   <li>built-in default {@value #DEFAULT_URL}</li>
 * </ol>
 *
 * <p>Optional keys in the same {@code config.properties}: {@code llm.model}
 * (value of the request {@code model} field; default {@value #DEFAULT_MODEL})
 * and {@code llm.key} (sent as {@code Authorization: Bearer <key>}; the header
 * is omitted when the key is absent).
 *
 * <p>Behavior:
 * <ul>
 *   <li>Requests are sent with {@code stream: true}; SSE {@code data:} chunks
 *       are parsed and delivered via {@link StreamCallback#onDelta} while the
 *       response is still streaming. A non-streaming (plain JSON) response is
 *       accepted as a fallback.</li>
 *   <li>All requests run on a single worker thread, strictly FIFO. Text
 *       entered while a reply is still streaming is queued and only sent once
 *       the previous exchange has fully completed; the context for each
 *       request is built at execution time, so a queued message sees the
 *       reply to the message before it.</li>
 * </ul>
 */
public class LlmChatClient {

    public static final String DEFAULT_URL = "http://host.containers.internal:1234";
    private static final String DEFAULT_MODEL = "default";
    private static final String PROP_URL = "cameo.mcp.console.llm.url";
    private static final int MAX_HISTORY = 30;

    public interface StreamCallback {
        /** A chunk of reply text arrived; may be called many times, in order. */
        void onDelta(String delta);

        /** The reply completed normally; {@code fullReply} is the whole text. */
        void onComplete(String fullReply);

        /** The request failed before any complete reply; the two callbacks are mutually exclusive. */
        void onError(String message);
    }

    private static final Logger LOG = Logger.getLogger(LlmChatClient.class.getName());

    private final ObjectMapper mapper;
    private final HttpClient http;
    private final ExecutorService executor;
    private final List<Map<String, String>> history = new CopyOnWriteArrayList<>();

    public LlmChatClient(ObjectMapper mapper) {
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        // Single worker: requests run FIFO, so a busy endpoint queues instead
        // of racing the conversation.
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "llm-console");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Base URL of the OpenAI-compatible endpoint, resolved on every call so a
     * config change takes effect without restarting MagicDraw.
     */
    public String baseUrl() {
        String sys = System.getProperty(PROP_URL);
        if (sys != null && !sys.isBlank()) {
            return sys.trim();
        }
        String v = propertyFromFile("llm.url");
        return v != null ? v : DEFAULT_URL;
    }

    /**
     * Queue one user turn for the LLM. The callback is invoked on a worker
     * thread (never the EDT); deltas arrive while the response is streaming.
     */
    public void send(String userText, StreamCallback callback) {
        executor.execute(() -> {
            StringBuilder full = new StringBuilder();
            try {
                // History is mutated here (at execution time) on the single
                // worker thread: queued turns are appended in order, and the
                // context snapshot for this request already contains the
                // assistant reply of every previously executed turn.
                history.add(Map.of("role", "user", "content", userText));
                trimHistory();
                List<Map<String, String>> messages = List.copyOf(history);

                streamCompletions(messages, delta -> {
                    full.append(delta);
                    callback.onDelta(delta);
                });

                history.add(Map.of("role", "assistant", "content", full.toString()));
                trimHistory();
                callback.onComplete(full.toString());
            } catch (Exception e) {
                LOG.warning("LLM console request failed: " + e.getMessage());
                callback.onError(e.getMessage() == null ? e.toString() : e.getMessage());
            }
        });
    }

    /** Drop the conversation history (used by the console's clear button). */
    public void reset() {
        history.clear();
    }

    private String callModel() {
        String v = propertyFromFile("llm.model");
        return v != null ? v : DEFAULT_MODEL;
    }

    private void trimHistory() {
        while (history.size() > MAX_HISTORY) {
            history.remove(0);
        }
    }

    /**
     * POST {@code /v1/chat/completions} with {@code stream: true} and feed
     * reply chunks to {@code onDelta} as they arrive.
     */
    private void streamCompletions(List<Map<String, String>> messages, Consumer<String> onDelta) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", callModel());
        body.put("stream", true);
        ArrayNode msgArray = body.putArray("messages");
        for (Map<String, String> m : messages) {
            ObjectNode n = msgArray.addObject();
            n.put("role", m.get("role"));
            n.put("content", m.get("content"));
        }

        HttpRequest.Builder req = HttpRequest.newBuilder()
            .uri(URI.create(completionsUrl(baseUrl())))
            .timeout(Duration.ofSeconds(300))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));

        String key = propertyFromFile("llm.key");
        if (key != null) {
            req.header("Authorization", "Bearer " + key);
        }

        // ofLines() iterates the body lazily as it arrives, so SSE chunks are
        // parsed and delivered while the response is still streaming.
        HttpResponse<Stream<String>> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofLines());
        if (resp.statusCode() / 100 != 2) {
            String err;
            try (Stream<String> lines = resp.body()) {
                err = String.join(" ", lines.limit(10).toArray(String[]::new));
            }
            throw new IOException("HTTP " + resp.statusCode() + ": " + truncate(err, 300));
        }

        final boolean[] sawData = {false};
        final StringBuilder plain = new StringBuilder();
        try (Stream<String> lines = resp.body()) {
            lines.forEach(line -> {
                String l = line.trim();
                if (l.startsWith("data:")) {
                    sawData[0] = true;
                    String payload = l.substring(5).trim();
                    if (payload.isEmpty() || "[DONE]".equals(payload)) {
                        return;
                    }
                    JsonNode node;
                    try {
                        node = mapper.readTree(payload);
                    } catch (IOException e) {
                        LOG.fine("Unparseable SSE payload: " + payload);
                        return;
                    }
                    JsonNode content = node.path("choices").path(0).path("delta").path("content");
                    if (content.isTextual() && !content.asText().isEmpty()) {
                        onDelta.accept(content.asText());
                    }
                } else if (!l.isEmpty()) {
                    plain.append(l);
                }
            });
        }

        // Fallback: the server ignored "stream" and returned a plain completion.
        if (!sawData[0]) {
            if (plain.length() == 0) {
                throw new IOException("Empty response from LLM endpoint");
            }
            JsonNode node = mapper.readTree(plain.toString());
            JsonNode content = node.path("choices").path(0).path("message").path("content");
            if (!content.isTextual()) {
                throw new IOException("No reply content in response: " + truncate(plain.toString(), 300));
            }
            onDelta.accept(content.asText());
        }
    }

    /**
     * Append {@code /v1/chat/completions} to the configured base URL unless it
     * already points at the completions endpoint.
     */
    static String completionsUrl(String base) {
        String b = base.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        if (b.endsWith("/chat/completions")) {
            return b;
        }
        if (!b.endsWith("/v1")) {
            b = b + "/v1";
        }
        return b + "/chat/completions";
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /**
     * Read one property from {@code config.properties} next to the MCP server
     * token, or null when absent/unreadable.
     */
    private static String propertyFromFile(String key) {
        File dir = TokenManager.getInstance().getConfigDir();
        File file = new File(dir, "config.properties");
        if (!file.exists() || !file.canRead()) {
            return null;
        }
        try {
            Properties p = new Properties();
            try (var in = Files.newBufferedReader(file.toPath())) {
                p.load(in);
            }
            String v = p.getProperty(key);
            return (v == null || v.isBlank()) ? null : v.trim();
        } catch (IOException e) {
            LOG.fine("Could not read " + file + ": " + e.getMessage());
            return null;
        }
    }
}
