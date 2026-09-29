package com.haarer.saf.mcpserver;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import com.haarer.saf.mcpserver.protocol.McpSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.logging.Level;
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
 * <p>{@code llm.log} (default {@code false}) appends a transcript of every
 * turn to {@value #DEFAULT_LOG_FILE} in the config directory — request bodies
 * with the presented tool array, the tool selection with its confidence, each
 * round's streamed text, and the response with {@code finish_reason},
 * reasoning, usage, and every tool call with its arguments and result.
 * {@code llm.log.path} overrides the destination. Both keys are re-read per
 * turn.
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
 *   <li>Streaming: the transcript records one entry per round with the number
 *       of chunks the endpoint happened to send and the text they carried,
 *       concatenated — not the raw deltas, whose split is a property of the
 *       transport and says nothing about the answer. Reasoning streamed
 *       separately as {@code delta.reasoning_content} is accumulated and
 *       recorded too, both in the round entry and in the response summary; it
 *       is never dropped.</li>
 *   <li>Tool selection: when the registered tool set is large (more than
 *       {@value #SELECTOR_THRESHOLD} tools and above the {@code llm.tool.max}
 *       cap), a BM25 match over tool names and descriptions picks the tools
 *       relevant to the user's text (minimum score {@code llm.tool.threshold},
 *       best first, capped at {@code llm.tool.max}). An empty selection or a
 *       failed lookup falls back to the full tool set. The index is rebuilt
 *       only when the registered tools change.</li>
 *   <li>Selection is re-decided after every tool round, because the useful
 *       query is not only the user's words: by round two the tool result and
 *       the model's own restatement are often the only text that shares
 *       vocabulary with a tool description. Two rules keep the re-evaluation
 *       from being a downgrade: a turn that fell back to the full tool set
 *       stays open for the rest of the turn, and a narrowed round reserves
 *       half of {@code llm.tool.max} for the tools the previous round already
 *       presented.</li>
 *   <li>Tool selection is reported, not applied silently: every round yields a
 *       {@link ToolSelection} carrying each presented tool's raw BM25 score, a
 *       confidence relative to the best match of that round, the tools that
 *       were dropped. When the set was not narrowed, the report says why
 *       instead of presenting an unexplained full set. It is fired via
 *       {@link StreamCallback#onToolSelection} and written to the
 *       transcript as {@code TOOL SELECTION}.</li>
 *   <li>Tools: when at least one {@link Tool} is registered, the request
 *       carries an OpenAI {@code tools} array. If the model answers with
 *       {@code tool_calls} (streamed as {@code delta.tool_calls} fragments),
 *       each tool is executed in-process, the result is appended to the
 *       conversation as a {@code tool} message, and the model is called again.
 *       The loop repeats until the model produces plain content, or the
 *       configured tool-round cap ({@code llm.tool.rounds}) is reached - in which
 *       case one final tools-free request asks the model to summarise what it
 *       built, so a long turn ends with a report rather than an error.</li>
 *   <li>Nothing about a turn is logged unless {@code llm.log} is enabled, and
 *       logging never affects the conversation: every write failure is
 *       swallowed.</li>
 * </ul>
 */
public class LlmChatClient {

    public static final String DEFAULT_URL = "http://host.containers.internal:1234";
    /** Default {@code llm.model} when the config file does not set one. */
    public static final String DEFAULT_MODEL = "default";
    private static final String PROP_URL = "cameo.mcp.console.llm.url";
    /** Default for {@code llm.context.turns}: conversation entries kept as context. */
    static final int DEFAULT_CONTEXT_TURNS = 30;
    /**
     * Default for {@code llm.context.window}: the assumed context window in
     * tokens. Deliberately conservative (128k) because a wrong value only
     * makes compaction run earlier or later than ideal, while compaction
     * itself is what keeps a long session from failing outright.
     */
    static final int DEFAULT_CONTEXT_WINDOW = 128000;
    /**
     * Default for {@code llm.context.compact}: compact once a request reaches
     * this fraction of {@code llm.context.window}. 0.8 leaves headroom for
     * the completion, which shares the window with the prompt.
     */
    static final double DEFAULT_COMPACT_FRACTION = 0.8;
    /** Default for {@code llm.context.keep}: recent turns kept verbatim. */
    static final int DEFAULT_KEEP_TURNS = 3;
    /**
     * Default for {@code llm.tool.rounds}: consecutive tool rounds per user turn.
     * Rounds, not tool calls - one round can carry several parallel calls, so the
     * effective budget is higher than this number. 8 was too small even for
     * ordinary chat; model-building tasks need far more, and the cap is a stop
     * that now ends the turn with a summary rather than an error.
     */
    static final int DEFAULT_TOOL_ROUNDS = 50;
    /** Tool results are truncated before being sent back to the model. */
    static final int MAX_TOOL_RESULT_CHARS = 4000;
    /** Default for {@code llm.tool.max}: max tools attached per request. */
    static final int DEFAULT_TOOL_MAX = 32;
    /** Above this many registered tools BM25 selection narrows the tool set. */
    static final int SELECTOR_THRESHOLD = 25;
    /**
     * Share of the per-round tool budget reserved for tools the previous round
     * already presented. A re-evaluated round may bring in new tools, but not
     * at the cost of everything the model was mid-way through using.
     */
    private static final double CARRY_SHARE = 0.5;
    /** Cap on each part of a later round's query; a tool result can be huge. */
    private static final int MAX_QUERY_PART = 500;
    /** Default for {@code llm.tool.threshold}: minimum BM25 score to be presented. */
    static final double DEFAULT_TOOL_THRESHOLD = 1.0;
    /** Default file name of the conversation log, inside the config dir. */
    static final String DEFAULT_LOG_FILE = "llm-conversation.log";

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
        /**
         * Fired once per user turn with the tool selection and the confidence
         * behind it: which tools were presented, which were dropped, and the
         * reason the set was not narrowed when it was not.
         */
        default void onToolSelection(ToolSelection selection) {
        }

        /**
         * A chunk of the model's reasoning arrived, streamed as it is
         * produced, before the reply text that follows it.
         *
         * <p>Not every provider streams reasoning separately: only the ones
         * that expose {@code delta.reasoning_content} ever call this, so an
         * implementation must not assume it fires. Implementations that
         * render the console can use it to show a live "thinking" block
         * instead of an apparently frozen pane during a long reasoning phase.
         */
        default void onReasoning(String delta) {
        }

    }

    /**
     * One tool of a turn's selection, with the evidence for the decision.
     *
     * @param name       tool name
     * @param score      raw BM25 score; unbounded and not a probability
     * @param confidence that score relative to the best-scoring tool of the
     *                   same turn, so {@code 1.0} is the top match
     * @param band       coarse read of the confidence: {@code high} (>= 0.66),
     *                   {@code medium} (>= 0.33), {@code low} below
     */
    public record ToolScore(String name, double score, double confidence, String band) {
    }

    /**
     * Outcome of one round's tool selection. {@code mode} is {@code bm25} when
     * the query narrowed the tool set, otherwise {@code all} with a
     * {@code reason} saying why every registered tool was presented instead.
     * {@code carried} counts how many of the presented tools were kept from
     * the previous round rather than re-matched, which is how a re-evaluated
     * round stays a superset of what the model was already working with.
     * @param round which round of the user turn this selection is for
     */
    public record ToolSelection(int round, String mode, String reason, int totalTools, int carried,
                                List<ToolScore> selected, List<ToolScore> rejected) {

        public boolean narrowed() {
            return !"all".equals(mode);
        }

        /** One-line summary for the console. */
        public String summary() {
            if (!narrowed()) {
                return "all " + totalTools + " tools presented (" + reason + ")";
            }
            var top = selected.isEmpty() ? "" : selected.get(0).name() + " " + selected.get(0).confidence();
            return selected.size() + " of " + totalTools + " tools presented ("
                + (carried > 0 ? carried + " carried, " : "") + "top: " + top + ")";
        }


        /** Names of the presented tools, for resolving back to the registry. */
        public Set<String> selectedNames() {
            Set<String> names = new LinkedHashSet<>();
            for (var s : selected) {
                names.add(s.name());
            }
            return names;
        }
    }

    /** One tool call requested by the model. */
    public record ToolCall(String id, String name, String arguments) {
    }

    /** Logger for the console; also used by the configuration UI. */
    static final Logger LOG = Logger.getLogger(LlmChatClient.class.getName());

    /**
     * Used only to test whether a tool call's arguments are well-formed. Parsing
     * is stateless, so a shared instance avoids threading the per-client mapper
     * into the static helpers that build history messages.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
    // Prompt size of the most recent round, i.e. the context as the endpoint
    // last saw it. This is the measured signal compaction triggers on: no
    // tokenizer is needed, and it is exact for the model that will be asked.
    private long lastPromptTokens;
    // BM25 selector over the tool registry: rebuilt lazily on the worker
    // thread whenever the registered tool set changes.
    private volatile boolean selectorDirty = true;
    private Bm25ToolSelector toolSelector;

    public LlmChatClient(ObjectMapper mapper) {
        this.mapper = mapper;
        // The trust store is built once, at construction: the certificate is
        // read here, so changing llm.ssl.ca needs a restart of MagicDraw. That
        // is deliberate - a trust store is not a per-turn concern, and
        // replacing the client mid-turn would drop its connection pool.
        HttpClient.Builder hb = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5));
        sslContext().ifPresent(hb::sslContext);
        this.http = hb.build();
        // Single worker: requests run FIFO, so a busy endpoint queues instead
        // of racing the conversation.
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "llm-console");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * TLS context that trusts {@code llm.ssl.ca} in addition to the JVM's
     * default trust store, or empty when the option is unset.
     *
     * <p>The configured certificate is <em>added to</em> the system trust store
     * rather than replacing it, so a public endpoint keeps working while a
     * private or self-signed one becomes reachable. A file that cannot be read
     * or holds no certificate is reported and ignored: the console must still
     * start, so the user sees a log warning and the ordinary error from the
     * endpoint, rather than a plugin that refuses to load.
     */
    private static Optional<SSLContext> sslContext() {
        File pem = sslCaFile();
        if (pem == null) {
            return Optional.empty();
        }
        if (!pem.isFile() || !pem.canRead()) {
            LOG.warning("llm.ssl.ca is set to " + pem + ", which is not a readable file; "
                + "using the default trust store.");
            return Optional.empty();
        }
        try {
            // Start from the default trust store so ordinary public endpoints
            // are unaffected, then add the configured certificate.
            KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
            trust.load(null, null);
            int added = 0;
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            try (InputStream in = Files.newInputStream(pem.toPath())) {
                for (Certificate c : cf.generateCertificates(in)) {
                    if (c instanceof X509Certificate) {
                        trust.setCertificateEntry("llm-ssl-ca-" + (++added), c);
                    }
                }
            }
            if (added == 0) {
                LOG.warning("llm.ssl.ca (" + pem + ") contains no X.509 certificate; "
                    + "using the default trust store.");
                return Optional.empty();
            }
            TrustManagerFactory tmf =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trust);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), null);
            LOG.info("Trusting " + added + " certificate(s) from " + pem);
            return Optional.of(ctx);
        } catch (Exception e) {
            // A broken certificate must not stop the console from starting.
            LOG.warning("Could not use llm.ssl.ca (" + pem + "): " + e.getMessage()
                + " - using the default trust store.");
            return Optional.empty();
        }
    }

    /**
     * The configured {@code llm.ssl.ca} file, or null when unset. A relative
     * path resolves against the config directory, so it travels with
     * {@code config.properties}.
     */
    static File sslCaFile() {
        String v = propertyFromFile("llm.ssl.ca");
        if (v == null || v.isBlank()) {
            return null;
        }
        File f = new File(v.trim());
        return f.isAbsolute() ? f
            : new File(TokenManager.getInstance().getConfigDir(), v.trim());
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
        selectorDirty = true;
    }

    /** Remove all registered tools; the next request then omits the tools field. */
    public void clearTools() {
        tools.clear();
        selectorDirty = true;
    }

    /** Unregister a tool by name; subsequent requests omit it. */
    public void unregisterTool(String name) {
        tools.removeIf(t -> t.name().equals(name));
        selectorDirty = true;
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
            ConversationLog log = ConversationLog.open();
            try {
                if (log != null) {
                    log.line("--- turn " + Instant.now() + " user: " + truncate(userText, 500));
                }
                // Before the new user message is added, so the kept tail
                // counts completed turns and the current one is never
                // summarised away from under the model.
                compactIfNeeded(log);
                // History is mutated here (at execution time) on the single
                // context snapshot for this request already contains the
                // assistant reply of every previously executed turn.
                history.add(Map.of("role", "user", "content", userText));
                trimHistory();
                // Per-turn tool-round cap; re-read so config changes apply to the next turn.
                int maxRounds = toolRounds();
                List<Map<String, Object>> messages = new ArrayList<>(history);
                ToolSelection selection = report(selectTools(0, userText), callback, log);
                List<Tool> selectedTools = toolsFor(selection);

                // Counts tool calls, not rounds: the two differ (a round may carry
                // several parallel calls), and only the call count reflects the work done.
                int toolCallsRun = 0;
                for (int round = 0; ; round++) {
                    // Per-round text, so a later round can query with what this
                    // one actually said rather than only what the user asked.
                    StringBuilder roundText = new StringBuilder();
                    RoundResult r = streamCompletions(messages, selectedTools, delta -> {
                        roundText.append(delta);
                        full.append(delta);
                        callback.onDelta(delta);
                    }, callback::onReasoning, log, round);
                    if (log != null) {
                        ObjectNode summary = mapper.createObjectNode();
                        summary.put("finish_reason", r.finishReason);
                        summary.put("content", full.toString());
                        summary.set("tool_calls", mapper.valueToTree(toolCallsToMaps(r.toolCalls)));
                        if (r.reasoning.length() > 0) {
                            summary.put("reasoning", r.reasoning.toString());
                        }
                        summary.put("prompt_tokens", r.usage[0]);
                        summary.put("completion_tokens", r.usage[1]);
                        log.json("RESPONSE round " + round, summary);
                    }
                    recordUsage(r.usage[0], r.usage[1]);
                    if (r.toolCalls.isEmpty()) {
                        history.add(Map.of("role", "assistant", "content", full.toString()));
                        trimHistory();
                        callback.onComplete(full.toString());
                        return;
                    }
                    if (round + 1 >= maxRounds) {
                        // Do not throw. The tools of this round have already run, and
                        // every earlier round's results are in the model and in the
                        // diagram, so throwing here discarded a turn's real work and
                        // left the user with an error and no summary. Instead make one
                        // final request with no tools offered, which forces a plain
                        // text answer: the model reports what it created and what is
                        // left. The turn still ends - the cap is a stop, not a pause.
                        finishWithSummary(messages, full, toolCallsRun, maxRounds,
                            callback, log);
                        return;
                    }
                    // Remember the assistant turn that asked for tools.
                    Map<String, Object> assistantMsg = new LinkedHashMap<>();
                    assistantMsg.put("role", "assistant");
                    assistantMsg.put("tool_calls", toolCallsToMaps(r.toolCalls));
                    messages.add(assistantMsg);
                    history.add(assistantMsg);
                    StringBuilder results = new StringBuilder();
                    for (ToolCall tc : r.toolCalls) {
                        toolCallsRun++;
                        if (log != null) {
                            log.line("--- tool call " + tc.id() + " " + tc.name()
                                + " args=" + tc.arguments());
                        }
                        callback.onToolCall(tc.name(), tc.arguments());
                        String result = executeTool(tc.name(), tc.arguments());
                        callback.onToolResult(tc.name(), result);
                        if (log != null) {
                            log.line("--- tool result " + tc.id() + " " + tc.name());
                            log.line(result);
                        }
                        messages.add(toolResultMessage(tc.id(), result));
                        history.add(toolResultMessage(tc.id(), result));
                        results.append(' ').append(result);
                    }
                    // The set is re-decided now that the round has produced its
                    // tool results: those, plus the model's own wording, can
                    // reach tools the user's message alone did not.
                    selection = report(reselect(userText, r, roundText.toString(),
                        results.toString(), selection), callback, log);
                    selectedTools = toolsFor(selection);
                }
            } catch (Exception e) {
                if (log != null) {
                    log.line("--- turn failed: " + e);
                }
                LOG.warning("LLM console request failed: " + e.getMessage());
                callback.onError(e.getMessage() == null ? e.toString() : e.getMessage());
            } finally {
                if (log != null) {
                    log.close();
                }
            }
        });
    }

    /**
     * End a turn that hit the tool-round cap without discarding its work.
     *
     * <p>One final request is made with no tools offered, so the model must answer
     * in plain text: what it created, and what is still missing. Everything the
     * turn built is already applied in the model, so this reports real state
     * rather than asking the model to remember.
     *
     * <p>If that request itself fails, the accumulated text plus an explicit
     * note is delivered instead - the user still learns what happened rather
     * than seeing the turn vanish.
     */
    private void finishWithSummary(List<Map<String, Object>> messages, StringBuilder full,
                                  int toolCallsRun, int maxRounds, StreamCallback callback,
                                  ConversationLog log) {
        // Appended after the model's own words, so it is a footer stating what
        // happened - not an instruction the model is expected to act on.
        String notice = "\n\n---\n_Stopped: tool-round limit of " + maxRounds
            + " reached after " + toolCallsRun + " tool calls. "
            + "Everything above is already applied to the model._";
        String text;
        try {
            List<Map<String, Object>> ask = new ArrayList<>(messages);
            ask.add(Map.of("role", "user", "content",
                "You have hit the tool-round limit. Do not call any more tools. "
                    + "Summarise what you have created so far and what is still missing."));
            // Empty active list => the request carries no "tools" field at all,
            // so the model cannot ask for another tool call.
            RoundResult r = streamCompletions(ask, List.of(), d -> {
                full.append(d);
                callback.onDelta(d);
            }, callback::onReasoning, log, -1);
            recordUsage(r.usage[0], r.usage[1]);
            text = full + notice;
        } catch (Exception e) {
            LOG.warning("Summary round after tool-round cap failed: " + e.getMessage());
            text = full + notice + "\n\n(Summary request failed: " + e.getMessage() + ")";
        }
        history.add(Map.of("role", "assistant", "content", text));
        trimHistory();
        callback.onComplete(text);
    }

    /** Drop the conversation history and usage counters (console's clear button). */
    public synchronized void reset() {
        history.clear();
        promptTokens = 0;
        completionTokens = 0;
        usageReported = false;
        lastPromptTokens = 0;
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

    /**
     * Drop the oldest history entries down to {@code llm.context.turns}, but
     * only on a whole-turn boundary.
     *
     * <p>A tool round appends one {@code assistant} message carrying
     * {@code tool_calls} followed by one {@code tool} result per call. Cutting
     * between them leaves a {@code tool} result whose call is gone, which an
     * OpenAI-compatible endpoint rejects with 400 - so the cut advances to the
     * next {@code user} message, which can only ever start a turn.
     */
    private void trimHistory() {
        int limit = contextTurns();
        if (history.size() <= limit) {
            return;
        }
        // Find the earliest turn boundary at or after the target cut, so the
        // smallest amount of history is dropped.
        int cut = -1;
        for (int i = Math.max(1, history.size() - limit); i < history.size(); i++) {
            if ("user".equals(history.get(i).get("role"))) {
                cut = i;
                break;
            }
        }
        if (cut < 0) {
            // No boundary left to cut on: a single turn longer than the limit.
            // Dropping part of it would orphan a tool result, so keep it whole.
            return;
        }
        history.subList(0, cut).clear();
    }

    /**
     * Summarise older turns when the conversation approaches the model's
     * context window, replacing them with a single stand-in entry.
     *
     * <p>Runs at the start of a turn, never mid-turn, so in-flight tool calls
     * and their results are never touched. The trigger is the prompt size the
     * endpoint reported for the previous round, which is the exact size the
     * next request would carry - no tokenizer and no character estimate.
     *
     * <p>The split is on a {@code user} boundary, so the retained tail is a
     * whole number of turns and the summary stands where those turns stood. If
     * the summary request fails the history is left untouched: a working
     * conversation that is slightly too long beats a compacted one that
     * silently lost the model's own account of what it built.
     */
    private void compactIfNeeded(ConversationLog log) {
        double fraction = compactFraction();
        if (fraction <= 0) {
            return;
        }
        long window = contextWindow();
        long threshold = (long) (window * fraction);
        long seen;
        synchronized (this) {
            seen = lastPromptTokens;
        }
        if (seen < threshold) {
            return;
        }

        // Keep the most recent N whole turns; summarise everything before them.
        int keep = keepTurns();
        int cut = -1;
        int turns = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            if ("user".equals(history.get(i).get("role")) && ++turns > keep) {
                cut = i;
                break;
            }
        }
        if (cut <= 0) {
            // Fewer than keep+1 turns, so there is nothing older to fold away.
            return;
        }

        List<Map<String, Object>> older = new ArrayList<>(history.subList(0, cut));
        if (log != null) {
            log.line("--- compacting: last prompt " + seen + " tokens, threshold " + threshold
                + " (" + fraction + " of " + window + "); summarising " + older.size()
                + " of " + history.size() + " entries");
        }

        String summary = summarize(older, log);
        if (summary == null || summary.isBlank()) {
            if (log != null) {
                log.line("--- compaction skipped: summary request produced nothing");
            }
            return;
        }

        history.subList(0, cut).clear();
        // Prepended as a user entry so the model reads it as context rather
        // than as its own prior words; the next kept turn is a user message too,
        // which keeps the assistant/user alternation valid.
        history.add(0, Map.of("role", "user",
            "content", "Summary of the earlier conversation, compacted to save context:\n\n" + summary));
        if (log != null) {
            log.line("--- compacted " + older.size() + " entries into a "
                + summary.length() + "-char summary; history now " + history.size() + " entries");
        }
    }

    /**
     * Ask the model to summarise {@code entries} into a standalone account of
     * what was established. Returns null when the request fails, so the caller
     * can leave the history alone.
     *
     * <p>No tools are offered: a summarising request that starts calling tools
     * would change the model, which is exactly what compaction must not do.
     */
    private String summarize(List<Map<String, Object>> entries, ConversationLog log) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Summarise the following conversation so it can replace the original as context.\n")
            .append("Keep: what the user asked for, the decisions taken, and concrete identifiers that\n")
            .append("were produced (element, diagram and package IDs and names, requirements). Drop the\n")
            .append("tool call mechanics, and do not invent anything not present below.\n")
            .append("Write plain prose, no preamble.\n\n--- conversation ---\n");
        for (Map<String, Object> m : entries) {
            prompt.append(roleOf(m)).append(": ").append(contentOf(m)).append("\n");
        }

        List<Map<String, Object>> ask = List.of(
            Map.of("role", "user", "content", prompt.toString()));
        StringBuilder out = new StringBuilder();
        try {
            // No reasoning consumer: compaction is internal housekeeping, and
            // its thinking is not part of the conversation the user is having.
            RoundResult r = streamCompletions(ask, List.of(), out::append, s -> { }, log, -1);
            // Usage of the summarising request is not part of the conversation
            // being summarised, so it is deliberately not recorded.
            if (r.toolCalls != null && !r.toolCalls.isEmpty()) {
                return null;
            }
            return out.toString().trim();
        } catch (Exception e) {
            LOG.warning("Compaction summary failed: " + e.getMessage());
            if (log != null) {
                log.line("--- compaction summary failed: " + e.getMessage());
            }
            return null;
        }
    }

    /** Role of a history entry, or {@code "?"} when it carries none. */
    private static String roleOf(Map<String, Object> m) {
        Object r = m.get("role");
        return r instanceof String s ? s : "?";
    }

    /** Printable text of a history entry, including tool results. */
    private static String contentOf(Map<String, Object> m) {
        Object c = m.get("content");
        if (c instanceof String s) {
            return s;
        }
        // A tool-call entry has no text; its calls are what matter, but the
        // summariser is told to drop mechanics anyway.
        return c != null ? String.valueOf(c) : "(requested tool calls)";
    }

    /**
     * Decide which tools to attach for this turn: the full set when
     * selection is unnecessary (few tools, or below the per-request cap);
     * otherwise the BM25-matched subset, falling back to the full set when
     * nothing matches or the lookup fails. Never returns null.
     */
    private ToolSelection selectTools(int round, String query) {
        List<Tool> all = new ArrayList<>(tools);
        if (!toolBm25Enabled()) {
            return allTools(round, all, "BM25 selection disabled by config ("
                + PluginConfig.TOOL_BM25 + "=false); all " + all.size() + " tools sent");
        }
        if (all.size() <= SELECTOR_THRESHOLD) {
            return allTools(round, all, "only " + all.size() + " tools registered, selection starts at "
                + (SELECTOR_THRESHOLD + 1));
        }
        if (all.size() <= toolMax()) {
            return allTools(round, all, all.size() + " tools already within llm.tool.max");
        }
        try {
            ensureToolSelector();
            var selection = toolSelector.select(query, toolThreshold(), toolMax());
            if (selection.selected().isEmpty()) {
                return allTools(round, all, selection.reason());
            }
            return new ToolSelection(round, selection.mode(), selection.reason(), selection.totalTools(), 0,
                rescored(selection.selected()), rescored(selection.rejected()));
        } catch (Exception e) {
            LOG.log(Level.FINE, "tool selection failed; sending all " + all.size() + " tools", e);
            return allTools(round, all, "selection failed: " + e);
        }
    }

    /** Tell the console and the transcript which tools this round presents. */
    private ToolSelection report(ToolSelection selection, StreamCallback callback, ConversationLog log) {
        callback.onToolSelection(selection);
        if (log != null) {
            logSelection(log, selection);
        }
        return selection;
    }

    /** The tools a selection resolves to; an empty selection means all of them. */
    private List<Tool> toolsFor(ToolSelection selection) {
        if (selection.selected().isEmpty()) {
            return new ArrayList<>(tools);
        }
        Set<String> names = selection.selectedNames();
        return tools.stream().filter(t -> names.contains(t.name())).toList();
    }

    /** Fallback: every registered tool is presented, and the report says why. */
    private ToolSelection allTools(int round, List<Tool> all, String reason) {
        return new ToolSelection(round, "all", reason, all.size(), 0, List.of(), List.of());
    }

    /**
     * Re-decide the tool set for the round after a tool call.
     *
     * <p>The query grows with what the round actually produced: the original
     * user message, the model's own words for the round, and the tool result.
     * Of those, the model's words are the only ones that speak the tool
     * vocabulary — a user's "why isn't it displayed" and a tool's
     * "PhysicalSystem" share no tokens with "saf_get_viewpoint_views", but the
     * model's restatement usually does.
     *
     * <p>Two guards keep the re-evaluation from being a downgrade. A round
     * that fell back to presenting everything keeps presenting everything:
     * a tool result's boilerplate words ("stereotype", "element") match
     * authoring tools and would otherwise crowd out the set the model was
     * already working with. And when the round was narrowed, the tools from
     * the previous round keep half the budget, so nothing the model is
     * mid-way through using disappears between rounds.
     */
    private ToolSelection reselect(String userText, RoundResult r, String roundText, String toolResult,
                                   ToolSelection previous) {
        if (!previous.narrowed()) {
            return previous;
        }
        ToolSelection fresh = selectTools(previous.round() + 1, roundQuery(userText, r, roundText, toolResult));
        if (!fresh.narrowed()) {
            return fresh;
        }
        int max = toolMax();
        int carryBudget = (int) Math.ceil(max * CARRY_SHARE);
        List<ToolScore> merged = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int carried = 0;
        for (ToolScore s : previous.selected()) {
            if (carried >= carryBudget) {
                break;
            }
            if (seen.add(s.name())) {
                merged.add(s);
                carried++;
            }
        }
        for (ToolScore s : fresh.selected()) {
            if (merged.size() >= max) {
                break;
            }
            if (seen.add(s.name())) {
                merged.add(s);
            }
        }
        return new ToolSelection(previous.round() + 1, "bm25", "", fresh.totalTools(), carried,
            List.copyOf(merged), fresh.rejected());
    }

    /**
     * The query for a later round. Parts are truncated: a tool result can be
     * a whole exported viewpoint, and past the first screenful it adds length
     * to the analysis without adding terms that match a tool.
     */
    private static String roundQuery(String userText, RoundResult r, String roundText, String toolResult) {
        StringBuilder q = new StringBuilder(userText == null ? "" : userText);
        addQueryPart(q, r.reasoning.toString());
        addQueryPart(q, roundText);
        addQueryPart(q, toolResult);
        return q.toString();
    }

    private static void addQueryPart(StringBuilder q, String part) {
        if (part == null || part.isBlank()) {
            return;
        }
        q.append(' ').append(part.length() > MAX_QUERY_PART ? part.substring(0, MAX_QUERY_PART) : part);
    }

    private static List<ToolScore> rescored(List<Bm25ToolSelector.Scored> scores) {
        return scores.stream()
            .map(s -> new ToolScore(s.name(), s.score(), s.confidence(), s.band()))
            .toList();
    }

    private void logSelection(ConversationLog log, ToolSelection selection) {
        ObjectNode node = mapper.createObjectNode();
        node.put("round", selection.round());
        node.put("mode", selection.mode());
        if (!selection.reason().isEmpty()) {
            node.put("reason", selection.reason());
        }
        node.put("totalTools", selection.totalTools());
        // An empty selection means the fallback presented every tool.
        node.put("presented", selection.selected().isEmpty() ? selection.totalTools() : selection.selected().size());
        node.put("carried", selection.carried());
        node.set("selected", scoreArray(selection.selected()));
        node.set("rejected", scoreArray(selection.rejected()));
        log.json("TOOL SELECTION", node);
    }

    private ArrayNode scoreArray(List<ToolScore> scores) {
        ArrayNode arr = mapper.createArrayNode();
        for (var s : scores) {
            ObjectNode n = arr.addObject();
            n.put("tool", s.name());
            n.put("score", s.score());
            n.put("confidence", s.confidence());
            n.put("band", s.band());
        }
        return arr;
    }


    /**
     * Lazily build (or reuse) the BM25 tool selector; rebuilt whenever the
     * registered tool set changed since the last build.
     */
    private synchronized void ensureToolSelector() throws IOException {
        if (selectorDirty || toolSelector == null) {
            if (toolSelector == null) {
                toolSelector = new Bm25ToolSelector();
            }
            toolSelector.rebuild(tools);
            selectorDirty = false;
        }
    }

    /** Result of one model round: requested tool calls plus reported usage. */
    private static final class RoundResult {
        final List<ToolCall> toolCalls = new ArrayList<>();
        final long[] usage = {0, 0};
        String finishReason = "";
        /** The model's reasoning, when the provider streams it separately. */
        final StringBuilder reasoning = new StringBuilder();
    }

    /**
     * POST {@code /v1/chat/completions} with {@code stream: true} and feed
     * reply chunks to {@code onDelta} as they arrive. Asks the server to
     * include token usage ({@code stream_options.include_usage}); tolerant of
     * servers that ignore the flag. Sends the tools selected for this turn,
     * if any, and parses {@code delta.tool_calls} fragments (streamed) or a
     * complete {@code message.tool_calls} (non-streaming fallback) into
     * {@link ToolCall}s.
     */
    RoundResult streamCompletions(List<Map<String, Object>> messages, List<Tool> active,
                                  Consumer<String> onDelta, Consumer<String> onReasoning,
                                  ConversationLog log, int round)
        throws Exception {
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
        if (log != null) {
            log.json("REQUEST round " + round + " (" + active.size() + " tools presented)", body);
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
        // The round's text, assembled as it streams. The raw chunk split is not
        // worth recording: it says nothing about the answer, only about how the
        // endpoint happened to frame it.
        final StringBuilder roundText = new StringBuilder();
        final int[] chunkCount = {0};
        final int[] unparsed = {0};
        try (Stream<String> lines = resp.body()) {
            lines.forEach(line -> {
                String l = line.trim();
                if (l.startsWith("data:")) {
                    sawData[0] = true;
                    String payload = l.substring(5).trim();
                    if (payload.isEmpty() || "[DONE]".equals(payload)) {
                        return;
                    }
                    chunkCount[0]++;
                    JsonNode node;
                    try {
                        node = mapper.readTree(payload);
                    } catch (IOException e) {
                        LOG.fine("Unparseable SSE payload: " + payload);
                        // Keep the unparseable payload in the transcript; it is
                        // exactly the kind of thing the log is needed for.
                        unparsed[0]++;
                        return;
                    }
                    captureUsage(node.path("usage"), result.usage);
                    JsonNode finish = node.path("choices").path(0).path("finish_reason");
                    if (finish.isTextual() && !finish.asText().isEmpty()) {
                        result.finishReason = finish.asText();
                    }
                    JsonNode delta = node.path("choices").path(0).path("delta");
                    JsonNode content = delta.path("content");
                    if (content.isTextual() && !content.asText().isEmpty()) {
                        roundText.append(content.asText());
                        onDelta.accept(content.asText());
                    }
                    // Some providers stream the thinking separately; it is part of
                    // what the model said, so it is kept, not dropped.
                    JsonNode reasoning = delta.path("reasoning_content");
                    if (reasoning.isTextual() && !reasoning.asText().isEmpty()) {
                        result.reasoning.append(reasoning.asText());
                        // Streamed as it arrives, not held back to the end of the
                        // round: a reasoning phase can run for many seconds, and a
                        // console that shows nothing until it finishes looks hung.
                        onReasoning.accept(reasoning.asText());
                    }
                    collectToolCalls(delta.path("tool_calls"), result);
                } else if (!l.isEmpty()) {
                    plain.append(l);
                }
            });
        }

        // One entry per round: how it was split over the wire, and the text.
        if (log != null && chunkCount[0] > 0) {
            String label = "stream round " + round + " (" + chunkCount[0] + " chunks";
            if (unparsed[0] > 0) {
                label += ", " + unparsed[0] + " unparseable";
            }
            log.line(">>> " + label + ")");
            if (result.reasoning.length() > 0) {
                log.line("--- reasoning");
                log.line(result.reasoning.toString());
            }
            if (roundText.length() > 0) {
                log.line("--- text");
                log.line(roundText.toString());
            }
        }

        // Fallback: the server ignored "stream" and returned a plain completion.
        if (!sawData[0]) {
            if (plain.length() == 0) {
                throw new IOException("Empty response from LLM endpoint");
            }
            JsonNode node = mapper.readTree(plain.toString());
            captureUsage(node.path("usage"), result.usage);
            if (log != null) {
                log.json("RESPONSE round " + round + " (non-streaming)", node);
            }
            JsonNode finish = node.path("choices").path(0).path("finish_reason");
            if (finish.isTextual() && !finish.asText().isEmpty()) {
                result.finishReason = finish.asText();
            }
            JsonNode message = node.path("choices").path(0).path("message");
            JsonNode content = message.path("content");
            // Reasoning first, matching the streaming order: the model thinks
            // before it answers, and a console that printed the answer before
            // the thinking that produced it would read backwards.
            if (message.path("reasoning_content").isTextual()) {
                String r = message.path("reasoning_content").asText();
                result.reasoning.append(r);
                onReasoning.accept(r);
            }
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
            // A truncated argument stream (the model hit a token limit mid-JSON)
            // must not be echoed back verbatim: the endpoint re-parses tool_calls
            // on the next request and rejects the whole request with HTTP 500,
            // which kills the turn. Substituting valid JSON keeps the history
            // parseable; the model is told what happened via the tool result.
            String args = tc.arguments();
            fn.put("arguments", argumentsWellFormed(args) ? args : "{}");
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", id);
            entry.put("type", "function");
            entry.put("function", fn);
            list.add(entry);
        }
        return list;
    }

    /**
     * Whether {@code args} is a JSON object the endpoint will accept back.
     *
     * <p>A tool call's arguments arrive as a string that is concatenated across
     * streamed fragments. If generation stops partway through - a token limit, a
     * dropped connection - the result is a prefix of valid JSON, and sending it
     * back makes the endpoint fail the next request outright.
     */
    private static boolean argumentsWellFormed(String args) {
        if (args == null || args.isBlank()) {
            return true;
        }
        try {
            return MAPPER.readTree(args) instanceof ObjectNode;
        } catch (Exception e) {
            return false;
        }
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
                if (!argumentsWellFormed(argsJson)) {
                    // Executing with {} produced misleading errors from the tool
                    // ("name is required" for a name that was sent but truncated).
                    // Report the real cause and let the model re-issue the call.
                    LOG.fine("Truncated tool arguments for " + name + ": " + argsJson);
                    return "Error: the arguments for " + name + " were cut off before the "
                        + "JSON was complete, so the tool was not run. Nothing was changed. "
                        + "Re-send the call, and keep it short if the arguments are long.";
                }
                Map<String, Object> args = parseArguments(argsJson);
                // The console runs the same tools as an MCP client but bypasses
                // the protocol handler, so without this the status line's
                // tool-call count stands still for the whole turn and only
                // moves when something external calls a tool.
                McpSession.incrementToolCallCount(name);
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
            if (prompt > 0) {
                lastPromptTokens = prompt;
            }
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
     * The model's context window in tokens; config key
     * {@code llm.context.window}, re-read per use, default
     * {@value #DEFAULT_CONTEXT_WINDOW}.
     */
    public static int contextWindow() {
        return positiveIntProperty("llm.context.window", DEFAULT_CONTEXT_WINDOW);
    }

    /**
     * Fraction of the context window at which older turns are summarised;
     * config key {@code llm.context.compact}, re-read per use, default
     * {@value #DEFAULT_COMPACT_FRACTION}. {@code <= 0} disables compaction, and
     * {@code >= 1} is clamped to 1, because a value above 1 would mean the
     * prompt may already exceed the window.
     */
    public static double compactFraction() {
        double v = positiveDoubleProperty("llm.context.compact", DEFAULT_COMPACT_FRACTION);
        return v >= 1.0 ? 1.0 : v;
    }

    /**
     * Recent turns kept verbatim when compaction runs; config key
     * {@code llm.context.keep}, re-read per use, default
     * {@value #DEFAULT_KEEP_TURNS}.
     */
    public static int keepTurns() {
        return positiveIntProperty("llm.context.keep", DEFAULT_KEEP_TURNS);
    }

    /**
     * Max consecutive tool-execution rounds per user turn; config key
     * {@code llm.tool.rounds}, re-read per turn, default
     * {@value #DEFAULT_TOOL_ROUNDS}.
     */
    public static int toolRounds() {
        return positiveIntProperty("llm.tool.rounds", DEFAULT_TOOL_ROUNDS);
    }

    /**
     * Max tools attached per request; config key {@code llm.tool.max},
     * re-read per turn, default {@value #DEFAULT_TOOL_MAX}.
     */
    public static int toolMax() {
        return positiveIntProperty("llm.tool.max", DEFAULT_TOOL_MAX);
    }

    /**
     * Minimum BM25 score for a tool to be presented; config key
     * {@code llm.tool.threshold}, re-read per turn, default
     * {@value #DEFAULT_TOOL_THRESHOLD}.
     */
    public static double toolThreshold() {
        return positiveDoubleProperty("llm.tool.threshold", DEFAULT_TOOL_THRESHOLD);
    }

    /**
     * Whether BM25 narrows the tool array; config key
     * {@code llm.tool.bm25}, re-read per turn, default {@code true}.
     *
     * <p>When off, every registered tool is sent on every round. That
     * deliberately ignores both {@link #toolMax()} and
     * {@link #toolThreshold()}: the point of the switch is to hand the model
     * the complete set, so honouring the cap would defeat it.
     */
    public static boolean toolBm25Enabled() {
        return booleanProperty(PluginConfig.TOOL_BM25, true);
    }

    /**
     * Read a boolean option, accepting the spellings people actually write in
     * a properties file. Anything unrecognised falls back to the default
     * rather than silently meaning {@code false}.
     */
    static boolean booleanProperty(String key, boolean def) {
        String v = propertyFromFile(key);
        if (v == null) {
            return def;
        }
        if (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("yes") || v.equalsIgnoreCase("on")) {
            return true;
        }
        if (v.equalsIgnoreCase("false") || v.equalsIgnoreCase("no") || v.equalsIgnoreCase("off")) {
            return false;
        }
        return def;
    }


    private static double positiveDoubleProperty(String key, double def) {
        String v = propertyFromFile(key);
        if (v != null) {
            try {
                double d = Double.parseDouble(v);
                if (d >= 0) {
                    return d;
                }
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return def;
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

    /**
     * Whether the full LLM conversation is appended to a log file; config key
     * {@code llm.log} (true/yes/on/1), re-read per turn so it can be toggled
     * without restarting MagicDraw. Default {@code false}.
     */
    public static boolean logEnabled() {
        String v = propertyFromFile("llm.log");
        if (v == null) {
            return false;
        }
        String s = v.toLowerCase();
        return s.equals("true") || s.equals("yes") || s.equals("on") || s.equals("1");
    }

    /**
     * Destination of the conversation log; config key {@code llm.log.path}
     * (absolute or relative path), defaulting to {@value #DEFAULT_LOG_FILE}
     * in the config directory next to {@code config.properties}.
     */
    public static File logFile() {
        String v = propertyFromFile("llm.log.path");
        if (v != null) {
            return new File(v);
        }
        return new File(TokenManager.getInstance().getConfigDir(), DEFAULT_LOG_FILE);
    }

    /**
     * Append-only transcript of the conversation: every request body (with the
     * tool array exactly as presented), every raw SSE chunk, the assembled
     * response of each round, and every tool call with its arguments and
     * result. For post-hoc analysis of what the model was actually sent and
     * what it answered. Every failure is swallowed — logging must never break
     * the console.
     */
    static final class ConversationLog implements Closeable {
        private static final ObjectWriter PRETTY =
            new ObjectMapper().writerWithDefaultPrettyPrinter();

        private final PrintWriter out;

        private ConversationLog(PrintWriter out) {
            this.out = out;
        }

        /** Open the log for appending, or return null when logging is off. */
        static ConversationLog open() {
            if (!logEnabled()) {
                return null;
            }
            File f = logFile();
            try {
                File parent = f.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                PrintWriter w = new PrintWriter(
                    new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8));
                ConversationLog log = new ConversationLog(w);
                log.line("=== session " + Instant.now()
                    + " model=" + configProperty("llm.model")
                    + " log=" + f.getAbsolutePath());
                return log;
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Cannot write LLM conversation log to " + f, e);
                return null;
            }
        }

        void line(String s) {
            if (out != null) {
                out.println(s);
            }
        }

        /** Append one labelled JSON document, pretty-printed. */
        void json(String label, JsonNode node) {
            if (out == null) {
                return;
            }
            line(">>> " + label);
            try {
                out.println(PRETTY.writeValueAsString(node));
            } catch (Exception e) {
                line("(unserializable " + label + ": " + e + ")");
            }
        }

        @Override
        public void close() {
            if (out != null) {
                out.flush();
                out.close();
            }
        }
    }
}
