package com.haarer.saf.mcpserver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jidesoft.docking.DockContext;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.ui.ProjectWindow;
import com.haarer.saf.mcpserver.protocol.McpSession;
import com.haarer.saf.mcpserver.protocol.McpToolDefinition;
import com.nomagic.magicdraw.ui.ProjectWindowsConfigurator;
import com.nomagic.magicdraw.ui.ProjectWindowsManager;
import com.nomagic.magicdraw.ui.WindowComponentInfo;
import com.nomagic.magicdraw.ui.browser.WindowComponentContent;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.awt.Font;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * A dockable window inside the Cameo main frame showing MCP server status
 * plus a small LLM console.
 *
 * <p>Registered once via {@link ProjectWindowsManager.ConfiguratorRegistry};
 * MagicDraw calls {@link #configure} when a project is opened and the window
 * docks at the bottom of the main frame.
 *
 * <p>Layout (top to bottom):
 * <ol>
 *   <li>one status line: running/stopped, endpoint URL, tool count, active
 *       session count, total tool call count, LLM console context size
 *       (messages/chars) and cumulative token usage;</li>
 *   <li>a scrollable log: user turns are echoed, LLM replies stream in below
 *       in a distinct color, errors are red;</li>
 *   <li>a text entry that sends the typed line to the OpenAI-compatible LLM
 *       endpoint (see {@link LlmChatClient}) — turns entered while a reply is
 *       still streaming are queued — and a Clear button that resets the log
 *       and the conversation.</li>
 * </ol>
 */
public class McpStatusWindow implements ProjectWindowsConfigurator {

    public static final String WINDOW_ID = "com.haarer.saf.mcpserver.status";
    public static final String WINDOW_NAME = "MCP Server Status";

    private final Supplier<CameoMcpServer> serverSupplier;

    public McpStatusWindow(Supplier<CameoMcpServer> serverSupplier) {
        this.serverSupplier = serverSupplier;
    }

    @Override
    public void configure(Project project, ProjectWindowsManager manager) {
        var info = new WindowComponentInfo(WINDOW_ID, WINDOW_NAME, null,
            DockContext.DOCK_SIDE_SOUTH, 0, true);
        info.setTabTitle("MCP Status");
        info.setState(DockContext.STATE_FRAMEDOCKED);
        info.setDockable(true);
        info.setFloatable(true);
        info.setAutohidable(true);
        info.setRearrangable(true);
        info.setMaximizable(true);
        info.setRemoveOnHide(false);
        manager.addWindow(project, new ProjectWindow(info, new StatusContent(serverSupplier)));
    }

    private static final class StatusContent implements WindowComponentContent {
        private static final Color OK_COLOR = new Color(0, 128, 0);
        private static final Color STOP_COLOR = Color.RED;
        private static final Color USER_COLOR = new Color(70, 70, 70);
        private static final Color REPLY_COLOR = new Color(25, 110, 200);
        private static final Color ERROR_COLOR = new Color(190, 30, 30);
        private static final Color TOOL_COLOR = new Color(180, 100, 0);
        private static final Color INFO_COLOR = Color.GRAY;
        private final Supplier<CameoMcpServer> serverSupplier;
        private final LlmChatClient llm;
        private final JPanel panel;
        private final JLabel statusLine;
        private final JLabel detailLine;
        private final JTextPane logPane;
        private final JTextField input;
        private final Timer timer;
        private List<String> lastMcpToolNames = new ArrayList<>();

        StatusContent(Supplier<CameoMcpServer> serverSupplier) {
            this.serverSupplier = serverSupplier;
            this.llm = new LlmChatClient(new ObjectMapper());

            llm.registerTool(LlmChatClient.currentTimeTool());

            // -- status row: one line ------------------------------------
            statusLine = new JLabel("MCP Server: ...");
            statusLine.setFont(statusLine.getFont().deriveFont(Font.BOLD, 11f));
            detailLine = new JLabel(" ");
            detailLine.setFont(detailLine.getFont().deriveFont(Font.PLAIN, 11f));
            var statusRow = new JPanel(new BorderLayout(10, 0));
            statusRow.setOpaque(false);
            statusRow.add(statusLine, BorderLayout.WEST);
            statusRow.add(detailLine, BorderLayout.CENTER);

            // -- log area with scroll bar --------------------------------
            logPane = new JTextPane();
            logPane.setEditable(false);
            logPane.setBackground(Color.WHITE);
            logPane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
            var logScroll = new JScrollPane(logPane,
                JScrollPane.VERTICAL_SCROLLBAR_ALWAYS,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

            // -- text entry + menu + clear -------------------------------
            input = new JTextField();
            input.addActionListener(e -> submit());
            var menu = new JButton("≡");
            menu.setToolTipText("Configuration");
            menu.setMargin(new Insets(0, 8, 0, 8));
            menu.setFocusPainted(false);
            menu.addActionListener(e -> ConfigDialog.show(menu));
            var clear = new JButton("Clear");
            clear.addActionListener(e -> clearAll());
            var inputEast = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
            inputEast.setOpaque(false);
            inputEast.add(menu);
            inputEast.add(clear);
            var inputRow = new JPanel(new BorderLayout(6, 0));
            inputRow.setOpaque(false);
            inputRow.add(input, BorderLayout.CENTER);
            inputRow.add(inputEast, BorderLayout.EAST);

            panel = new JPanel(new BorderLayout(0, 0));
            panel.setOpaque(false);
            panel.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
            panel.add(statusRow, BorderLayout.NORTH);
            panel.add(logScroll, BorderLayout.CENTER);
            panel.add(inputRow, BorderLayout.SOUTH);
            panel.setMinimumSize(new Dimension(500, 220));

            appendLine("LLM endpoint: " + llm.baseUrl(), INFO_COLOR);
            appendLine("Type a message and press Enter to chat with the LLM.", INFO_COLOR);

            refresh();
            timer = new Timer(1000, e -> refresh());
            timer.start();
        }

        private void refresh() {
            syncMcpTools();
            String mcp;
            var server = serverSupplier.get();
            if (server == null) {
                statusLine.setText("MCP Server: not started");
                statusLine.setForeground(STOP_COLOR);
                mcp = " ";
            } else {
                boolean running = server.isRunning();
                statusLine.setText("MCP Server: " + (running ? "running" : "stopped"));
                statusLine.setForeground(running ? OK_COLOR : STOP_COLOR);
                mcp = running
                    ? "http://" + server.getHost() + ":" + server.getPort() + "/mcp"
                      + "   " + server.getToolCount() + " tools"
                      + "   " + server.getActiveSessions() + " sessions"
                      + "   " + McpSession.totalToolCalls() + " tool calls"
                    : " ";
            }
            detailLine.setText(mcp + llmStats());
        }

        /**
         * Keep the LLM console's MCP tools in sync with the server's latest
         * script scan (hot reload). The optional config key
         * {@code llm.mcp.tools} restricts the console to a comma-separated
         * list of tool names; absent or empty means all tools.
         */
        private void syncMcpTools() {
            var server = serverSupplier.get();
            if (server == null || !server.isRunning()) {
                return;
            }
            var filter = LlmChatClient.configProperty("llm.mcp.tools");
            Set<String> allowed = null;
            if (filter != null) {
                allowed = new TreeSet<>();
                for (String part : filter.split(",")) {
                    if (!part.trim().isEmpty()) {
                        allowed.add(part.trim());
                    }
                }
            }
            List<String> names = new ArrayList<>();
            for (McpToolDefinition def : server.getToolDefinitions()) {
                if (allowed == null || allowed.contains(def.name())) {
                    names.add(def.name());
                }
            }
            if (names.equals(lastMcpToolNames)) {
                return;
            }
            for (String old : lastMcpToolNames) {
                llm.unregisterTool(old);
            }
            for (McpToolDefinition def : server.getToolDefinitions()) {
                if (!names.contains(def.name())) {
                    continue;
                }
                final var toolDef = def;
                llm.registerTool(new LlmChatClient.Tool() {
                    @Override
                    public String name() {
                        return toolDef.name();
                    }

                    @Override
                    public String description() {
                        return toolDef.description() == null ? "" : toolDef.description();
                    }

                    @Override
                    public Map<String, Object> parametersSchema() {
                        return toolDef.inputSchema() == null
                            ? Map.of("type", "object") : toolDef.inputSchema();
                    }

                    @Override
                    public String execute(Map<String, Object> arguments) {
                        var result = toolDef.handler().call(arguments);
                        var sb = new StringBuilder();
                        for (var tc : result.content()) {
                            if (sb.length() > 0) {
                                sb.append('\n');
                            }
                            sb.append(tc.text());
                        }
                        return (result.isError() ? "Error: " : "") + sb;
                    }
                });
            }
            lastMcpToolNames = List.copyOf(names);
            appendLine(names.isEmpty()
                ? "no MCP tools registered for the LLM console"
                : names.size() + " MCP tools registered for the LLM console",
                INFO_COLOR);
        }

        private String llmStats() {
            LlmChatClient.UsageStats u = llm.usageStats();
            String s = "   LLM " + llm.toolCount() + " tools / ctx "
                + u.contextMessages + " msgs / " + human(u.contextChars) + " chars";
            return s + (u.usageReported
                ? "   " + u.promptTokens + " tok in / " + u.completionTokens + " tok out"
                : "   tokens -");
        }

        private static String human(long n) {
            if (n < 1000) {
                return Long.toString(n);
            }
            if (n < 1_000_000) {
                return String.format(Locale.ROOT, "%.1fk", n / 1000.0);
            }
            return String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0);
        }

        private void submit() {
            String text = input.getText().trim();
            if (text.isEmpty()) {
                return;
            }
            input.setText("");
            appendLine("> " + text, USER_COLOR);
            llm.send(text, new LlmChatClient.StreamCallback() {
                @Override
                public void onDelta(String delta) {
                    SwingUtilities.invokeLater(() -> appendText(delta, REPLY_COLOR));
                }

                @Override
                public void onComplete(String fullReply) {
                    SwingUtilities.invokeLater(() -> appendText("\n", REPLY_COLOR));
                }

                @Override
                public void onError(String message) {
                    SwingUtilities.invokeLater(() -> appendLine("error: " + message, ERROR_COLOR));
                }

                @Override
                public void onToolCall(String name, String argumentsJson) {
                    if (!showToolCalls()) {
                        return;
                    }
                    SwingUtilities.invokeLater(() -> appendLine(
                        "  [tool] " + name + " " + truncate(argumentsJson, 300), TOOL_COLOR));
                }

                @Override
                public void onToolResult(String name, String result) {
                    if (!showToolCalls()) {
                        return;
                    }
                    SwingUtilities.invokeLater(() -> appendLine(
                        "  [result] " + truncate(result, 300), TOOL_COLOR));
                }

            });
        }

        /**
         * Whether tool calls and their results are printed. Read per callback
         * rather than cached, so turning it on in the configuration dialog
         * takes effect on the next message. The per-round tool selection is
         * deliberately not shown here: it goes only to the conversation log
         * ({@code llm.log}), since it is diagnostic detail rather than part
         * of the conversation.
         */
        private boolean showToolCalls() {
            return PluginConfig.flag(PluginConfig.SHOW_TOOL_CALLS, false);
        }

        private void clearAll() {
            logPane.setText("");
            llm.reset();
            appendLine("console cleared", INFO_COLOR);
        }

        private void appendText(String text, Color color) {
            if (text == null || text.isEmpty()) {
                return;
            }
            try {
                StyledDocument doc = logPane.getStyledDocument();
                SimpleAttributeSet attrs = new SimpleAttributeSet();
                StyleConstants.setForeground(attrs, color);
                doc.insertString(doc.getLength(), text, attrs);
                logPane.setCaretPosition(doc.getLength());
            } catch (BadLocationException e) {
                // log is read-only; should not happen
            }
        }

        private void appendLine(String line, Color color) {
            appendText(line + "\n", color);
        }

        private static String truncate(String s, int max) {
            if (s == null) {
                return "";
            }
            return s.length() <= max ? s : s.substring(0, max) + "...";
        }

        @Override
        public Component getWindowComponent() {
            return panel;
        }

        @Override
        public Component getDefaultFocusComponent() {
            return input;
        }
    }
}
