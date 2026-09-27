package com.haarer.saf.mcpserver;

import com.fasterxml.jackson.core.type.TypeReference;
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
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 *   <li>Requests ask the server for token usage ({@code
 *       stream_options.include_usage}); when the server reports it, cumulative
 *       prompt and completion token counts are tracked and exposed via
 *       {@link #usageStats()} together with the current context size (message
 *       and character counts).</li>
 *   <li>Tools: when at least one {@link Tool} is registered, the request
 *       carries an OpenAI {@code tools} array. If the model answers with
 *       {@code tool_calls} (streamed as {@code delta.tool_calls} fragments),
 *       each tool is executed in-process, the result is appended to the
 *       conversation as a {@code tool} message, and the model is called again.
 *       The loop repeats until the model produces plain content or
 *       the configured tool-round cap ({@code llm.tool.rounds}) is reached.</li>
 * </ul>
 */
public class LlmChatClient {

    public static final String DEFAULT_URL = "http://host.containers.internal:1234";
    private static final String DEFAULT_MODEL = "default";
    private static final String PROP_URL = "cameo.mcp.console.llm.url";
    /** Default for {@code llm.context.turns}: conversation entries kept as context. */
    static final int DEFAULT_CONTEXT_TURNS = 30;
    /** Default for {@code llm.tool.rounds}: consecutive tool rounds per user turn. */
    static final int DEFAULT_TOOL_ROUNDS = 8;
    /** Tool results are truncated before being sent back to the model. */
    static final int MAX_TOOL_RESULT_CHARS = 4000;

    /**
     * A tool the LLM may call during a conversation. Tool execution happens
     * in-process on the single console worker thread.
     */
    public interface Tool {
        String name();

        String description();

        /** JSON Schema object describing the tool's arguments. */
        Map<String, Object> parametersSchema();

        /** Runs the tool; returns the text sent back to the model. */
        String execute(Map<String, Object> arguments) throws Exception;
    }

    public interface StreamCallback {
        /** A chunk of reply text arrived; may be called many times, in order. */
        void onDelta(String delta);

        /** The reply completed normally; {@code fullReply} is the whole text. */
        void onComplete(String fullReply);

        /** The request failed before any complete reply; the two callbacks are mutually exclusive. */
        void onError(String message);

        /** The model asked for a tool call, just before it is executed. */
        default void onToolCall(String name, String argumentsJson) {
        }

        /** A tool finished; {@code result} is what was sent back to the model. */
        default void onToolResult(String name, String result) {
        }
    }

    /** One tool call requested by the model. */
    public record ToolCall(String id, String name, String arguments) {
    }

    private static final Logger LOG = Logger.getLogger(LlmChatClient.class.getName());

    private final ObjectMapper mapper;
    private final HttpClient http;
    private final ExecutorService executor;
    private final List<Map<String, Object>> history = new CopyOnWriteArrayList<>();
    private final List<Tool> tools = new CopyOnWriteArrayList<>();
    // Token-usage counters, guarded by 'this' (written on the worker thread,
    // read from the EDT by the status window).
    private long promptTokens;
    private long completionTokens;
    private boolean usageReported;

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

    /** Register a tool for model invocation; re-registering a name replaces it. */
    public void registerTool(Tool tool) {
        tools.removeIf(t -> t.name().equals(tool.name()));
        tools.add(tool);
    }

    /** Remove all registered tools; the next request then omits the tools field. */
    public void clearTools() {
        tools.clear();
    }

    /** Unregister a tool by name; subsequent requests omit it. */
    public void unregisterTool(String name) {
        tools.removeIf(t -> t.name().equals(name));
    }

    /** Number of tools currently registered. */
    public int toolCount() {
        return tools.size();
    }

    /**
     * Queue one user turn for the LLM. The callback is invoked on a worker
     * thread (never the EDT); deltas arrive while the response is streaming.
     * Tool calls requested by the model are executed in-process and the model
     * is re-queried until it produces a plain reply (or the round cap hits).
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
                // Per-turn tool-round cap; re-read so config changes apply to the next turn.
                int maxRounds = toolRounds();
                List<Map<String, Object>> messages = new ArrayList<>(history);

                for (int round = 0; ; round++) {
                    RoundResult r = streamCompletions(messages, delta -> {
                        full.append(delta);
                        callback.onDelta(delta);
                    });
                    recordUsage(r.usage[0], r.usage[1]);
                    if (r.toolCalls.isEmpty()) {
                        history.add(Map.of("role", "assistant", "content", full.toString()));
                        trimHistory();
                        callback.onComplete(full.toString());
                        return;
                    }
                    if (round + 1 >= maxRounds) {
                        throw new IOException("Tool loop did not finish after "
                            + maxRounds + " rounds");
                    }
                    // Remember the assistant turn that asked for tools.
                    Map<String, Object> assistantMsg = new LinkedHashMap<>();
                    assistantMsg.put("role", "assistant");
                    assistantMsg.put("tool_calls", toolCallsToMaps(r.toolCalls));
                    messages.add(assistantMsg);
                    history.add(assistantMsg);
                    for (ToolCall tc : r.toolCalls) {
                        callback.onToolCall(tc.name(), tc.arguments());
                        String result = executeTool(tc.name(), tc.arguments());
                        callback.onToolResult(tc.name(), result);
                        messages.add(toolResultMessage(tc.id(), result));
                        history.add(toolResultMessage(tc.id(), result));
                    }
                }
            } catch (Exception e) {
                LOG.warning("LLM console request failed: " + e.getMessage());
                callback.onError(e.getMessage() == null ? e.toString() : e.getMessage());
            }
        });
    }

    /** Drop the conversation history and usage counters (console's clear button). */
    public synchronized void reset() {
        history.clear();
        promptTokens = 0;
        completionTokens = 0;
        usageReported = false;
    }

    /** Built-in tool: current date and time, optionally in an IANA time zone. */
    public static Tool currentTimeTool() {
        return new Tool() {
            @Override
            public String name() {
                return "get_current_time";
            }

            @Override
            public String description() {
                return "Get the current date and time. Optional argument 'timezone' "
                    + "(IANA id, e.g. Europe/Berlin); defaults to the system zone.";
            }

            @Override
            public Map<String, Object> parametersSchema() {
                Map<String, Object> tz = new LinkedHashMap<>();
                tz.put("type", "string");
                tz.put("description", "IANA time zone id, e.g. Europe/Berlin");
                Map<String, Object> props = new LinkedHashMap<>();
                props.put("timezone", tz);
                Map<String, Object> schema = new LinkedHashMap<>();
                schema.put("type", "object");
                schema.put("properties", props);
                return schema;
            }

            @Override
            public String execute(Map<String, Object> arguments) {
                String zone = arguments.get("timezone") instanceof String s && !s.isBlank()
                    ? s.trim()
                    : ZoneId.systemDefault().getId();
                return ZonedDateTime.now(ZoneId.of(zone))
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss zzzz"));
            }
        };
    }

    private String callModel() {
        String v = propertyFromFile("llm.model");
        return v != null ? v : DEFAULT_MODEL;
    }

    private void trimHistory() {
        while (history.size() > contextTurns()) {
            history.remove(0);
        }
    }

    /** Result of one model round: requested tool calls plus reported usage. */
    private static final class RoundResult {
        final List<ToolCall> toolCalls = new ArrayList<>();
        final long[] usage = {0, 0};
    }

    /**
     * POST {@code /v1/chat/completions} with {@code stream: true} and feed
     * reply chunks to {@code onDelta} as they arrive. Asks the server to
     * include token usage ({@code stream_options.include_usage}); tolerant of
     * servers that ignore the flag. Sends the registered tools, if any, and
     * parses {@code delta.tool_calls} fragments (streamed) or a complete
     * {@code message.tool_calls} (non-streaming fallback) into
     * {@link ToolCall}s.
     */
    RoundResult streamCompletions(List<Map<String, Object>> messages, Consumer<String> onDelta) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", callModel());
        body.put("stream", true);
        body.putObject("stream_options").put("include_usage", true);
        ArrayNode msgArray = body.putArray("messages");
        for (Map<String, Object> m : messages) {
            ObjectNode n = msgArray.addObject();
            n.put("role", (String) m.get("role"));
            n.putPOJO("content", m.get("content"));
            if (m.get("tool_calls") != null) {
                n.set("tool_calls", mapper.valueToTree(m.get("tool_calls")));
            }
            if (m.get("tool_call_id") != null) {
                n.put("tool_call_id", (String) m.get("tool_call_id"));
            }
        }
        List<Tool> active = new ArrayList<>(tools);
        if (!active.isEmpty()) {
            ArrayNode toolArray = body.putArray("tools");
            for (Tool t : active) {
                ObjectNode entry = toolArray.addObject();
                entry.put("type", "function");
                ObjectNode fn = entry.putObject("function");
                fn.put("name", t.name());
                fn.put("description", t.description());
                fn.set("parameters", mapper.valueToTree(t.parametersSchema()));
            }
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
        final RoundResult result = new RoundResult();
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
                    captureUsage(node.path("usage"), result.usage);
                    JsonNode delta = node.path("choices").path(0).path("delta");
                    JsonNode content = delta.path("content");
                    if (content.isTextual() && !content.asText().isEmpty()) {
                        onDelta.accept(content.asText());
                    }
                    collectToolCalls(delta.path("tool_calls"), result);
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
            captureUsage(node.path("usage"), result.usage);
            JsonNode message = node.path("choices").path(0).path("message");
            JsonNode content = message.path("content");
            if (content.isTextual()) {
                onDelta.accept(content.asText());
            }
            collectToolCalls(message.path("tool_calls"), result);
            if (content.isMissingNode() && result.toolCalls.isEmpty()) {
                throw new IOException("No reply content in response: " + truncate(plain.toString(), 300));
            }
        }
        return result;
    }

    /** Merge one {@code tool_calls} array (streamed fragment or complete) into the result. */
    private static void collectToolCalls(JsonNode toolCallsNode, RoundResult result) {
        if (!toolCallsNode.isArray()) {
            return;
        }
        List<ToolCall> acc = result.toolCalls;
        for (JsonNode tc : toolCallsNode) {
            int idx = tc.path("index").asInt(0);
            while (acc.size() <= idx) {
                acc.add(new ToolCall(null, "", ""));
            }
            ToolCall a = acc.get(idx);
            String id = tc.path("id").isTextual() ? tc.path("id").asText() : a.id();
            JsonNode fn = tc.path("function");
            String name = a.name() + (fn.path("name").isTextual() ? fn.path("name").asText() : "");
            String args = a.arguments() + (fn.path("arguments").isTextual() ? fn.path("arguments").asText() : "");
            acc.set(idx, new ToolCall(id, name, args));
        }
    }

    /** Convert parsed tool calls into the OpenAI {@code assistant.tool_calls} message shape. */
    private static List<Map<String, Object>> toolCallsToMaps(List<ToolCall> calls) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            ToolCall tc = calls.get(i);
            String id = tc.id() == null || tc.id().isEmpty() ? "call_" + (i + 1) : tc.id();
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", tc.name());
            fn.put("arguments", tc.arguments());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", id);
            entry.put("type", "function");
            entry.put("function", fn);
            list.add(entry);
        }
        return list;
    }

    /** One {@code tool}-role message carrying a tool result. */
    private static Map<String, Object> toolResultMessage(String toolCallId, String result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "tool");
        m.put("tool_call_id", toolCallId == null || toolCallId.isEmpty() ? "call_1" : toolCallId);
        m.put("content", result);
        return m;
    }

    /** Execute a registered tool; errors are returned to the model as text. */
    private String executeTool(String name, String argsJson) {
        for (Tool t : tools) {
            if (t.name().equals(name)) {
                Map<String, Object> args = parseArguments(argsJson);
                try {
                    String r = t.execute(args);
                    return truncate(r == null ? "" : r, MAX_TOOL_RESULT_CHARS);
                } catch (Exception e) {
                    return "Error: " + e.getMessage();
                }
            }
        }
        return "Error: unknown tool '" + name + "'";
    }

    private Map<String, Object> parseArguments(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(argsJson, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            LOG.fine("Unparseable tool arguments: " + argsJson);
            return Map.of();
        }
    }

    /** Fill {@code out} with {prompt, completion} when {@code usage} carries token counts. */
    private static void captureUsage(JsonNode usage, long[] out) {
        if (!usage.isObject()) {
            return;
        }
        long p = usage.path("prompt_tokens").asLong(0);
        long c = usage.path("completion_tokens").asLong(0);
        if (p > 0 || c > 0) {
            out[0] = p;
            out[1] = c;
        }
    }

    private synchronized void recordUsage(long prompt, long completion) {
        if (prompt > 0 || completion > 0) {
            promptTokens += prompt;
            completionTokens += completion;
            usageReported = true;
        }
    }

    /** Snapshot of context size and token usage for the status line. */
    public synchronized UsageStats usageStats() {
        long chars = 0;
        for (Map<String, Object> m : history) {
            Object v = m.get("content");
            if (v instanceof String s) {
                chars += s.length();
            }
        }
        return new UsageStats(history.size(), chars, promptTokens, completionTokens, usageReported);
    }

    /** Immutable snapshot of console context size and token-usage counters. */
    public static final class UsageStats {
        /** Number of messages currently in the conversation context. */
        public final int contextMessages;
        /** Total characters across all context messages. */
        public final long contextChars;
        /** Cumulative prompt tokens reported by the endpoint since the last reset. */
        public final long promptTokens;
        /** Cumulative completion tokens reported by the endpoint since the last reset. */
        public final long completionTokens;
        /** Whether the endpoint has reported token usage at least once. */
        public final boolean usageReported;

        public UsageStats(int contextMessages, long contextChars, long promptTokens,
                          long completionTokens, boolean usageReported) {
            this.contextMessages = contextMessages;
            this.contextChars = contextChars;
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.usageReported = usageReported;
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

    /**
     * Read one optional key from the console's {@code config.properties}
     * (re-read on every call, so the file is hot-reloadable); null when
     * absent.
     */
    public static String configProperty(String key) {
        return propertyFromFile(key);
    }

    /**
     * Max conversation entries (user/assistant/tool messages) kept as context;
     * config key {@code llm.context.turns}, re-read per use, default
     * {@value #DEFAULT_CONTEXT_TURNS}.
     */
    public static int contextTurns() {
        return positiveIntProperty("llm.context.turns", DEFAULT_CONTEXT_TURNS);
    }

    /**
     * Max consecutive tool-execution rounds per user turn; config key
     * {@code llm.tool.rounds}, re-read per turn, default
     * {@value #DEFAULT_TOOL_ROUNDS}.
     */
    public static int toolRounds() {
        return positiveIntProperty("llm.tool.rounds", DEFAULT_TOOL_ROUNDS);
    }

    private static int positiveIntProperty(String key, int def) {
        String v = propertyFromFile(key);
        if (v != null) {
            try {
                int i = Integer.parseInt(v);
                if (i > 0) {
                    return i;
                }
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return def;
    }
}
