package com.haarer.saf.mcpserver;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * The console's configuration: one catalog of every option the plugin reads,
 * its default, and a human description, plus load/save against
 * {@code config.properties}.
 *
 * <p>The catalog is the single source of truth. {@link ConfigDialog} renders it
 * and the runtime reads the same keys, so an option cannot exist in the file
 * without being editable, or be shown in the dialog without being read.
 *
 * <p>Values are stored as plain properties and re-read per turn, so editing
 * the file by hand and saving from the dialog are the same thing.
 */
public final class PluginConfig {

    /** How a value is entered and validated. */
    public enum Type {
        TEXT, SECRET, INT, DOUBLE, BOOL, MULTILINE
    }

    /**
     * One option.
     *
     * @param key   property key in {@code config.properties}
     * @param label short name shown in the dialog
     * @param type  how the value is entered
     * @param help  one line explaining what the value changes
     * @param def   value used when the key is absent; {@code null} for "no
     *              default", which is stored as an empty value
     */
    public record Option(String key, String label, Type type, String help, Object def) {
    }

    /** Console key controlling whether tool calls are printed. */
    public static final String SHOW_TOOL_CALLS = "console.showToolCalls";

    /** Key switching BM25 tool selection off. */
    public static final String TOOL_BM25 = "llm.tool.bm25";

    private PluginConfig() {
    }

    /**
     * Every option the plugin reads, in dialog order: endpoint and model, then
     * the limits, then logging, then what the console shows.
     */
    public static List<Option> options() {
        return List.of(
            new Option("llm.url", "Endpoint URL", Type.TEXT,
                "OpenAI-compatible completions base URL. /v1 and /chat/completions are added if missing.",
                "https://api.openai.com"),
            new Option("llm.model", "Model", Type.TEXT,
                "Model name sent with each request. Re-read per turn.",
                LlmChatClient.DEFAULT_MODEL),
            new Option("llm.key", "API key", Type.SECRET,
                "Sent as the Authorization bearer token. Stored in plain text in this file; keep the file readable only by you.",
                ""),
            new Option("llm.ssl.ca", "Server certificate", Type.TEXT,
                "Path to a PEM certificate (.pem/.crt) to trust in addition to the system store, for a "
                    + "private or self-signed https endpoint. Relative paths resolve against the config "
                    + "directory. Read once at startup, so changing it needs a restart of MagicDraw.",
                ""),
            new Option("llm.context.turns", "Context messages", Type.INT,
                "Hard cap on conversation entries (user/assistant/tool) kept as context; older ones are "
                    + "dropped whole turns at a time. Compaction normally runs first and replaces older "
                    + "turns with a summary, so this only bites in very long sessions.",
                LlmChatClient.DEFAULT_CONTEXT_TURNS),
            new Option("llm.context.window", "Context window", Type.INT,
                "The model's context window in tokens, used to decide when to compact. Set it to the "
                    + "figure your provider documents; there is no reliable way to detect it from the endpoint.",
                LlmChatClient.DEFAULT_CONTEXT_WINDOW),
            new Option("llm.context.compact", "Compact above", Type.DOUBLE,
                "Fraction of the context window at which older turns are summarised. 0.8 means compact once "
                    + "a request reaches 80% of the window. 0 disables compaction entirely. Compaction costs one "
                    + "extra model request, so it is not free.",
                LlmChatClient.DEFAULT_COMPACT_FRACTION),
            new Option("llm.context.keep", "Turns kept", Type.INT,
                "Recent turns kept verbatim when compaction runs. The summary covers everything older.",
                LlmChatClient.DEFAULT_KEEP_TURNS),
            new Option("llm.tool.rounds", "Tool rounds", Type.INT,
                "Maximum tool-execution rounds per message. One round can run several tools in parallel, so this is not a tool-call count. On reaching it the turn ends with a summary of what was built, not an error.",
                LlmChatClient.DEFAULT_TOOL_ROUNDS),
            new Option("llm.tool.max", "Max tools sent", Type.INT,
                "Most tools attached to one request. Above 25 registered tools, BM25 narrows the set to this many.",
                LlmChatClient.DEFAULT_TOOL_MAX),
            new Option(TOOL_BM25, "BM25 tool selection", Type.BOOL,
                "Narrow the tool array by relevance. Off means every registered tool is sent on every "
                    + "round, ignoring the max-tools limit and the score threshold.",
                true),
            new Option("llm.tool.threshold", "Tool score threshold", Type.DOUBLE,
                "Minimum BM25 score for a tool to be presented. Lower keeps more, higher keeps only strong matches.",
                LlmChatClient.DEFAULT_TOOL_THRESHOLD),
            new Option("llm.mcp.tools", "Allowed MCP tools", Type.MULTILINE,
                "Comma-separated MCP tool names the console may use. Empty means all of them.",
                ""),
            new Option("llm.log", "Conversation log", Type.BOOL,
                "Append every turn to a file: requests, tool selection with confidence, the reply, tool calls.",
                false),
            new Option("llm.log.path", "Log file", Type.TEXT,
                "Where the conversation log is written. Relative paths resolve against the config directory.",
                LlmChatClient.DEFAULT_LOG_FILE),
            new Option(SHOW_TOOL_CALLS, "Show tool calls", Type.BOOL,
                "Print tool calls and their results in the console. The per-round tool selection goes to the conversation log only.",
                false)
        );
    }

    /** The {@code config.properties} backing this catalog. */
    public static File file() {
        return new File(TokenManager.getInstance().getConfigDir(), "config.properties");
    }

    /** All properties currently in the file; empty when it is absent or unreadable. */
    public static Properties load() {
        Properties props = new Properties();
        File f = file();
        if (!f.exists() || !f.canRead()) {
            return props;
        }
        try (Reader in = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
            props.load(in);
        } catch (IOException e) {
            LlmChatClient.LOG.warning("Could not read " + f + ": " + e.getMessage());
        }
        return props;
    }

    /**
     * Write {@code values} back, leaving every key not listed untouched so a
     * hand-written key the catalog does not know about survives a save.
     */
    public static void save(Map<String, String> values) throws IOException {
        Properties props = load();
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) {
                props.remove(e.getKey());
            } else {
                props.setProperty(e.getKey(), e.getValue());
            }
        }
        File f = file();
        File dir = f.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            throw new IOException("Could not create " + dir);
        }
        try (Writer out = Files.newBufferedWriter(f.toPath(), StandardCharsets.UTF_8)) {
            props.store(out, "MCP server plugin configuration. Values are re-read per turn.");
        }
    }

    /** The value of {@code option} as stored, falling back to its default. */
    public static String current(Option option, Properties stored) {
        String v = stored.getProperty(option.key());
        if (v == null || v.isBlank()) {
            return option.def() == null ? "" : String.valueOf(option.def());
        }
        return v.trim();
    }

    /**
     * The current value of a boolean option, with the given default when the
     * key is absent or not a recognisable boolean.
     */
    public static boolean flag(String key, boolean def) {
        return LlmChatClient.booleanProperty(key, def);
    }

    /**
     * Options that only take effect while {@link #TOOL_BM25} is on. The
     * dialog disables them when selection is off, because BM25 is the only
     * thing that reads them: with selection off neither the cap nor the
     * score can apply, and leaving them editable would promise otherwise.
     */
    public static boolean dependsOnToolSelection(String key) {
        return "llm.tool.max".equals(key) || "llm.tool.threshold".equals(key);
    }

    /**
     * Validate a field's text against the option's type and return the value to
     * store, or an error message describing what is wrong. A number must be in
     * range: a count of 0 or a negative score would silently break selection.
     */
    public static String validate(Option option, String text) {
        String v = text == null ? "" : text.trim();
        switch (option.type()) {
            case INT:
                if (v.isEmpty()) {
                    return "";
                }
                try {
                    if (Integer.parseInt(v) <= 0) {
                        return "must be greater than 0";
                    }
                } catch (NumberFormatException e) {
                    return "must be a whole number";
                }
                return v;
            case DOUBLE:
                if (v.isEmpty()) {
                    return "";
                }
                try {
                    if (Double.parseDouble(v) < 0) {
                        return "must not be negative";
                    }
                } catch (NumberFormatException e) {
                    return "must be a number";
                }
                return v;
            case BOOL:
                if (v.isEmpty()) {
                    return "";
                }
                if (!v.equalsIgnoreCase("true") && !v.equalsIgnoreCase("false")) {
                    return "must be true or false";
                }
                return v.toLowerCase(java.util.Locale.ROOT);
            default:
                return v;
        }
    }

    /** Key order for a stable dialog layout. */
    public static List<String> keys() {
        List<String> keys = new ArrayList<>();
        for (Option o : options()) {
            keys.add(o.key());
        }
        return keys;
    }

    /** Lookup by key, for callers that only have the key at hand. */
    public static Option byKey(String key) {
        for (Option o : options()) {
            if (o.key().equals(key)) {
                return o;
            }
        }
        return null;
    }
}
